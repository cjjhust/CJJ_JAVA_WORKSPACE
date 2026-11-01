package com.gdpr.audit.gdpr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GdprDataMasker 单元测试 — 覆盖邮箱/电话/IBAN 的各类格式与边界情况。
 */
@DisplayName("GdprDataMasker PII 脱敏")
class GdprDataMaskerTest {

    // ========== 邮箱 ==========

    @ParameterizedTest(name = "[{index}] {0} → 邮箱被脱敏")
    @ValueSource(strings = {
            "user@example.com",
            "first.last@company.de",
            "user@mail.company.co.uk"
    })
    void maskEmails_masksStandardEmails(String input) {
        String result = GdprDataMasker.maskPii(input);
        assertThat(result).doesNotContain(input.split("@")[0].substring(1));
        assertThat(result).containsPattern("@\\S*\\*\\S*\\.(com|de|uk)");
        assertThat(result).doesNotContain("@example.com").doesNotContain("@company.de");
    }

    @Test
    void maskEmails_masksLocalPartAndDomain() {
        String result = GdprDataMasker.maskPii("john.doe@example.com");
        // 首尾字符保留，中间掩码；原邮箱完整字符串不得出现
        assertThat(result).startsWith("j").endsWith(".com");
        assertThat(result).doesNotContain("john.doe@example.com");
        assertThat(result).contains("****");
    }

    @Test
    void maskEmails_leavesInvalidEmailUnchanged() {
        String input = "not-an-email";
        assertThat(GdprDataMasker.maskPii(input)).isEqualTo(input);
    }

    // ========== 电话 ==========

    @ParameterizedTest(name = "[{index}] {0} → 电话被脱敏")
    @ValueSource(strings = {
            "+49 170 1234567",
            "+49-170-1234567",
            "0170 1234567"
    })
    void maskPhoneNumbers_masksGermanPhoneFormats(String input) {
        String result = GdprDataMasker.maskPii(input);
        // 掩码后不再保留完整数字串，仅保留末 4 位
        assertThat(result).contains("***").doesNotContain("1234567");
        assertThat(result).endsWith("4567");
    }

    @Test
    void maskPhoneNumbers_leavesShortNumberUnchanged() {
        String input = "12345";
        assertThat(GdprDataMasker.maskPii(input)).isEqualTo("12345");
    }

    @Test
    void maskPhoneNumbers_masksInternationalPrefix() {
        String result = GdprDataMasker.maskPii("+49 170 1234567");
        assertThat(result).startsWith("+49");
    }

    // ========== IBAN ==========

    @ParameterizedTest(name = "[{index}] {0} → IBAN 被脱敏")
    @ValueSource(strings = {
            "DE89370400440532013000",
            "FR7630006000011234567890189"
    })
    void maskIbans_masksIbans(String input) {
        String result = GdprDataMasker.maskPii(input);
        assertThat(result).doesNotContain(input);
        assertThat(result).hasSizeLessThan(input.length());
        assertThat(result).contains("****");
    }

    @Test
    void maskIbans_masksGermanIbanKeepingCountryAndLast4() {
        assertThat(GdprDataMasker.maskPii("DE89370400440532013000"))
                .isEqualTo("DE89****3000");
    }

    @Test
    void maskIbans_masksSpacedIban() {
        // 带空格的 IBAN：国家码+校验位连续，尾部连续 4 位仍命中掩码规则
        String result = GdprDataMasker.maskPii("DE89 3704 0044 0532 0130 00");
        assertThat(result).contains("****").doesNotContain("0044");
    }

    // ========== 边界情况 ==========

    @ParameterizedTest(name = "[{index}] 输入 {0}")
    @NullAndEmptySource
    void maskPii_handlesNullAndEmpty(String input) {
        assertThat(GdprDataMasker.maskPii(input)).isEqualTo(input);
    }

    @Test
    void maskPii_masksMixedContent() {
        String input = "用户 john@mail.com 的 IBAN 是 DE89370400440532013000";
        String result = GdprDataMasker.maskPii(input);
        assertThat(result).doesNotContain("john@mail.com");
        assertThat(result).doesNotContain("DE89370400440532013000");
        assertThat(result).contains("用户 ").contains(" 的 IBAN 是 ");
    }

    @Test
    void maskPii_masksPiiInsideJsonString() {
        String input = "{\"name\":\"Max\",\"email\":\"max@web.de\",\"iban\":\"DE89370400440532013000\"}";
        String result = GdprDataMasker.maskPii(input);
        assertThat(result).doesNotContain("max@web.de");
        assertThat(result).doesNotContain("DE89370400440532013000");
        assertThat(result).startsWith("{").endsWith("}");
    }

    @ParameterizedTest(name = "[{index}] 多重 PII：{0}")
    @CsvSource({
            "'Contact: anna@gmx.de, +49 30 901820'",
            "'pay to DE12345678901234 or call 030 12345678'"
    })
    void maskPii_handlesMultiplePiiInOneString(String input) {
        String result = GdprDataMasker.maskPii(input);
        assertThat(result).doesNotContain(input);
        assertThat(result).contains("***");
    }

    @Test
    void maskPii_leavesPlainTextChangedOnlyInPiiParts() {
        String input = "order #4711 shipped";
        assertThat(GdprDataMasker.maskPii(input)).isEqualTo(input);
    }

    @Test
    void maskPii_truncatesOversizedInput() {
        String huge = "a".repeat(200_000);
        assertThat(GdprDataMasker.maskPii(huge).length()).isLessThanOrEqualTo(100_000);
    }
}
