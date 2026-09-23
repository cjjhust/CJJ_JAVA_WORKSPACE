package com.aslp.route.tracking;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 承运商时间戳解析测试（M3 的隐藏坑点）。
 *
 * <p>核心命题：**不带时区的时间戳是承运商本地时间（Europe/Berlin），不是 UTC**。
 * 当成 UTC 处理会让每个事件偏移 1~2 小时（夏令时/冬令时不同），而"包裹几点到"
 * 恰恰是客服与客户最爱引用的字段。
 */
class TrackingTimesTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Test
    void parsesOffsetTimestampsAsInstant() {
        assertEquals(Instant.parse("2026-09-22T12:20:00Z"), TrackingTimes.parse("2026-09-22T12:20:00Z"));
        assertEquals(Instant.parse("2026-09-22T12:20:00Z"), TrackingTimes.parse("2026-09-22T14:20:00+02:00"));
    }

    @Test
    void parsesZonelessTimestampsAsCarrierLocalTime() {
        // 夏令时：柏林 = UTC+2 → 14:20 本地 == 12:20Z
        Instant summer = TrackingTimes.parse("2026-09-22T14:20:00");
        assertEquals(ZonedDateTime.of(2026, 9, 22, 14, 20, 0, 0, BERLIN).toInstant(), summer);
        assertEquals(Instant.parse("2026-09-22T12:20:00Z"), summer);

        // 冬令时：柏林 = UTC+1 → 14:20 本地 == 13:20Z（同一段代码，偏移不同）
        Instant winter = TrackingTimes.parse("2026-01-14T14:20:00");
        assertEquals(Instant.parse("2026-01-14T13:20:00Z"), winter);
    }

    @Test
    void acceptsSpaceSeparatedAndMillisPrecision() {
        // 毫秒精度要保留（同一秒内的多个节点靠它定序）
        assertEquals(Instant.parse("2026-09-22T12:20:00.123Z"), TrackingTimes.parse("2026-09-22T12:20:00.123Z"));
        // 空格分隔的变体（部分老接口）要能解析成与 T 分隔完全相同的结果
        assertEquals(TrackingTimes.parse("2026-09-22T14:20:00"),
                TrackingTimes.parse("2026-09-22 14:20:00"));
    }

    @Test
    void returnsNullInsteadOfGuessingOnUnparseableInput() {
        // 宁可让该节点没有时间，也不要写一个看起来合理但错的时间
        assertNull(TrackingTimes.parse("gestern"));
        assertNull(TrackingTimes.parse(""));
        assertNull(TrackingTimes.parse(null));
    }
}
