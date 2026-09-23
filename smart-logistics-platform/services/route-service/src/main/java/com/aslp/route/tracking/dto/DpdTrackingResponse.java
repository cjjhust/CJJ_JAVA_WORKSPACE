package com.aslp.route.tracking.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * DPD 追踪响应报文（{@code GET /tracking/v1/parcels/{parcelNumber}}）。
 *
 * <p><b>关于契约的诚实说明</b>：DHL 用的是公开文档里的 Unified Tracking 结构；
 * DPD 的正式接入走 DPD Web API（需商户凭据与 OAuth），本项目按公开的
 * {@code parcelLifeCycle} 结构建模 —— 与 P1-4 对 SP-API 的处理一样：
 * **契约桩锁住"我们理解的契约"**，真接生产凭据时以实际报文为准并同步更新本类与桩。
 * 这类不确定性写进代码注释和 readme §10，比事后说"我以为它是这样"要便宜。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DpdTrackingResponse(
        String parcelNumber,
        /** 当前状态码（如 DELIVERED / IN_TRANSIT）。 */
        String status,
        String statusDescription,
        String lastUpdate,
        String estimatedDelivery,
        /** 事件轨迹（DPD 侧命名）。 */
        List<LifeCycleEntry> parcelLifeCycle) {

    /** 单个节点。{@code label} 是文案，{@code status} 是码；两者都可能缺失其一。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LifeCycleEntry(
            String status,
            String label,
            String dateTime,
            String location) {
    }
}
