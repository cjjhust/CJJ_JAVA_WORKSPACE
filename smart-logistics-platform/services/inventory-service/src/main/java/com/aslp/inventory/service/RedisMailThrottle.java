package com.aslp.inventory.service;

import com.aslp.inventory.config.WarningProperties;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的跨实例节流（M2）。
 *
 * <h3>为什么是 Redis 而不是进程内变量</h3>
 * 节流语义是"全局唯一"的 —— 补货邮件 30 分钟内只该有一封，与副本数无关。
 * 原实现放在进程内的 {@code AtomicReference} 里，多实例部署时每个副本各记一份，
 * 邮件量随副本数线性放大（而且"看起来是好的"，因为单实例下完全正常）。
 *
 * <h3>为什么用 {@code SET NX + TTL} 而不是"查一下时间戳再写"</h3>
 * 后者是典型的 check-then-act 竞态：两个实例可能同时读到"没有窗口"然后都发信。
 * {@code trySet}（Redisson 的 {@code SET key value NX PX ttl}）由 Redis 单线程保证原子性，
 * 因此**只有第一个实例能拿到资格**，且窗口到期由 Redis 自动清理 —— 不需要清理任务，
 * 也不会出现"实例崩溃后窗口永远打不开"。
 *
 * <h3>Redis 不可用时怎么办：降级为进程内节流（fail-degraded，不是 fail-open）</h3>
 * 三种选择都有代价：
 * <ul>
 *   <li><b>fail-open</b>（Redis 挂了就允许发）：会退化成"每个实例每轮都发" = 邮件轰炸；</li>
 *   <li><b>fail-closed</b>（Redis 挂了就永不发）：邮件是**运维告警通道**，静默失联比多发几封更糟；</li>
 *   <li><b>降级为进程内节流</b>：单实例仍然严格 30 分钟一封，多实例会放宽到"每实例一封" ——
 *       既不会轰炸，也不会失联。本项目选这个，并记 WARN 让降级**可见**。</li>
 * </ul>
 */
@Component
public class RedisMailThrottle implements MailThrottle {

    private static final Logger log = LoggerFactory.getLogger(RedisMailThrottle.class);

    /** 节流键：`inventory:warning:last-mail-at`（与库存锁 `inventory:lock:*` 同一命名空间前缀）。 */
    public static final String KEY = "inventory:warning:last-mail-at";

    private final RBucket<String> bucket;
    private final Duration interval;
    private final InMemoryMailThrottle fallback;

    public RedisMailThrottle(RedissonClient redissonClient, WarningProperties properties) {
        this.bucket = redissonClient.getBucket(KEY);
        this.interval = properties.getMailInterval();
        this.fallback = new InMemoryMailThrottle(interval);
    }

    @Override
    public boolean tryAcquire() {
        try {
            // SET NX PX：原子占位 + 自动过期。返回值即"我是不是第一个"。
            boolean acquired = bucket.trySet(Instant.now().toString(), interval.toMillis(), TimeUnit.MILLISECONDS);
            if (acquired) {
                // 拿到 Redis 资格时把进程内的降级窗口清掉，避免下次降级时被一个陈旧的窗口误拦
                fallback.release();
            }
            return acquired;
        } catch (RuntimeException e) {
            log.warn("[库存预警] Redis 节流不可用，降级为进程内节流（多实例下会放宽为每实例一封）：{}", e.toString());
            return fallback.tryAcquire();
        }
    }

    @Override
    public void markSent() {
        fallback.markSent();
        try {
            bucket.set(Instant.now().toString(), interval.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.warn("[库存预警] 写入 Redis 节流窗口失败（已降级为进程内）：{}", e.toString());
        }
    }

    @Override
    public void release() {
        fallback.release();
        try {
            bucket.delete();
        } catch (RuntimeException e) {
            log.warn("[库存预警] 释放 Redis 节流资格失败（下一轮会自动过期，不影响正确性）：{}", e.toString());
        }
    }

    @Override
    public long secondsUntilAllowed() {
        long redisSeconds = 0;
        try {
            long millis = bucket.remainTimeToLive();
            // Redisson 约定：-1 = 存在但无过期时间；-2 = 键不存在 → 都表示"现在可以发"
            redisSeconds = millis > 0 ? millis / 1000 : 0;
        } catch (RuntimeException e) {
            // 诊断接口会被频繁调用，这里用 debug（WARN 留给真正的发送路径，避免刷屏）
            log.debug("[库存预警] 读取 Redis 节流窗口失败，改用进程内窗口：{}", e.toString());
        }
        // 取两者较大值：**实际生效的节流是"两个窗口里更严的那个"**。
        // 只报 Redis 的 TTL 会在降级期间骗人（Redis 说 0，但进程内窗口其实还在拦）。
        return Math.max(redisSeconds, fallback.secondsUntilAllowed());
    }
}
