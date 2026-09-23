package com.aslp.report.client;

/**
 * 报表数据源不可用（M5）。
 *
 * <p>承载「哪个上游挂了 + 原因」，供报表服务做<b>部分降级</b>：
 * 订单服务挂了不该导致整个看板白屏，库存部分仍应正常显示，
 * 同时在响应里明确标注是哪个数据源缺失（否则用户会以为「今天真的没有低库存」）。
 */
public class ReportSourceUnavailableException extends RuntimeException {

    private final String source;

    public ReportSourceUnavailableException(String source, Throwable cause) {
        super("报表数据源不可用：" + source + "（" + cause.getClass().getSimpleName() + ": " + cause.getMessage() + "）", cause);
        this.source = source;
    }

    /** 数据源标识（如 {@code order-service}），会原样出现在响应的 unavailable 列表里。 */
    public String getSource() {
        return source;
    }
}
