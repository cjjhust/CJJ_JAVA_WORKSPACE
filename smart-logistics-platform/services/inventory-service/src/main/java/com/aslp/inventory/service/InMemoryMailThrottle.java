package com.aslp.inventory.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内节流实现（M2）—— 单实例够用，也是 Redis 不可用时的降级方案。
 *
 * <p><b>它不能解决多实例问题</b>：每个实例各存一份"上次发信时间"，
 * N 个副本就是 N 倍邮件量。真正的多实例节流见 {@link RedisMailThrottle}。
 * 保留它是因为：Redis 抖动时"退化成单实例节流"远好于"完全失去节流"（那会变成邮件轰炸）。
 *
 * <p>用 {@code compareAndSet} 而非"读-判断-写"：同一实例内可能有两个线程同时触发
 * （定时任务 + 运维手动调用），CAS 才能保证只有一个拿到资格。
 */
public class InMemoryMailThrottle implements MailThrottle {

    private final Duration interval;

    /** 本次窗口的起点；null = 无窗口（可以立即发信）。 */
    private final AtomicReference<Instant> windowStart = new AtomicReference<>();

    public InMemoryMailThrottle(Duration interval) {
        this.interval = interval;
    }

    @Override
    public boolean tryAcquire() {
        Instant now = Instant.now();
        while (true) {
            Instant current = windowStart.get();
            if (current != null && now.isBefore(current.plus(interval))) {
                return false;   // 仍在窗口内
            }
            if (windowStart.compareAndSet(current, now)) {
                return true;    // 抢到资格
            }
            // 被其它线程抢先，重试
        }
    }

    @Override
    public void markSent() {
        windowStart.set(Instant.now());
    }

    @Override
    public void release() {
        windowStart.set(null);
    }

    @Override
    public long secondsUntilAllowed() {
        Instant start = windowStart.get();
        if (start == null) {
            return 0;
        }
        long remain = start.plus(interval).getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(0, remain);
    }
}
