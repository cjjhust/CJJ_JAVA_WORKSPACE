package com.aslp.inventory.service;

import com.aslp.inventory.config.WarningProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 节流实现的测试（M2 / P1-10）。
 *
 * <p>这一类代码要验证的不是"逻辑对不对"（逻辑就是几句话），而是
 * <b>"我们向 Redis 发的到底是哪条命令"</b>：
 * 必须是 {@code SET NX + TTL}（原子占位 + 自动过期），而不是"先查再写"。
 * 后者在多实例下就是竞态 —— 两个实例同时读到"没有窗口"，然后都发信。
 *
 * <p>失败降级同样必须钉死：Redis 抖动时不能变成邮件轰炸，也不能彻底不发。
 */
class RedisMailThrottleTest {

    private RBucket<String> bucket;
    private WarningProperties properties;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        bucket = Mockito.mock(RBucket.class);

        properties = new WarningProperties();
        properties.setMailInterval(Duration.ofMinutes(30));
    }

    private RedisMailThrottle throttle() {
        RedissonClient redisson = Mockito.mock(RedissonClient.class);
        when(redisson.<String>getBucket(RedisMailThrottle.KEY)).thenReturn(bucket);
        return new RedisMailThrottle(redisson, properties);
    }

    @Test
    @DisplayName("占位用 SET NX + TTL（原子 + 自动过期），而不是「先查再写」")
    void acquireUsesSetNxWithTtl() {
        when(bucket.trySet(any(String.class), anyLong(), any(TimeUnit.class))).thenReturn(true);

        assertTrue(throttle().tryAcquire());

        // 30 分钟 = 1800000 ms；TTL 由 Redis 自己管理，因此不需要清理任务，
        // 也不会出现"实例崩溃导致窗口永远打不开"
        verify(bucket).trySet(any(String.class), eq(1_800_000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("占位失败 = 仍在窗口内（这正是多实例下「只有一个能发」的机制）")
    void acquireReturnsFalseWhenWindowAlreadyOpen() {
        when(bucket.trySet(any(String.class), anyLong(), any(TimeUnit.class))).thenReturn(false);

        assertFalse(throttle().tryAcquire());
    }

    @Test
    void releaseDeletesWindowSoNextRoundCanRetryImmediately() {
        RedisMailThrottle throttle = throttle();

        throttle.release();

        verify(bucket).delete();
        assertEquals(0, throttle.secondsUntilAllowed());
    }

    @Test
    void markSentRefreshesWindow() {
        throttle().markSent();

        verify(bucket).set(any(String.class), eq(1_800_000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("剩余时间来自 Redis TTL；-1/-2（无过期时间/键不存在）都表示「现在可以发」")
    void secondsUntilAllowedComesFromRedisTtl() {
        RedisMailThrottle throttle = throttle();

        when(bucket.remainTimeToLive()).thenReturn(900_000L);
        assertEquals(900, throttle.secondsUntilAllowed());

        when(bucket.remainTimeToLive()).thenReturn(-2L);   // 键不存在
        assertEquals(0, throttle.secondsUntilAllowed());

        when(bucket.remainTimeToLive()).thenReturn(-1L);   // 存在但无 TTL（异常状态，不能当成"被节流"）
        assertEquals(0, throttle.secondsUntilAllowed());
    }

    @Test
    @DisplayName("Redis 挂掉时降级为进程内节流：不轰炸（同一实例仍被拦住），也不失联")
    void fallsBackToInProcessThrottleWhenRedisFails() {
        doThrow(new RuntimeException("Redis connection refused"))
                .when(bucket).trySet(any(String.class), anyLong(), any(TimeUnit.class));

        RedisMailThrottle throttle = throttle();

        // 第一次：Redis 失败 → 降级后放行（不能因为节流组件故障就不发告警邮件）
        assertTrue(throttle.tryAcquire(), "降级后第一次应放行");
        // 第二次：降级窗口已打开 → 拦住（避免退化成"每轮都发"）
        assertFalse(throttle.tryAcquire(), "降级后仍必须节流，否则就是邮件轰炸");
        assertTrue(throttle.secondsUntilAllowed() > 0);
    }

    @Test
    void redisFailureOnReleaseAndMarkSentIsSwallowed() {
        doThrow(new RuntimeException("boom")).when(bucket).delete();
        doThrow(new RuntimeException("boom")).when(bucket).set(any(String.class), anyLong(), any(TimeUnit.class));

        RedisMailThrottle throttle = throttle();

        // 释放/记窗口失败不该把异常抛给巡检任务（否则一轮库存巡检会因 Redis 抖动而中断）
        throttle.release();
        throttle.markSent();
    }
}
