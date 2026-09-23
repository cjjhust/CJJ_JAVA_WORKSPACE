package com.aslp.route.controller;

import com.aslp.route.engine.VrpProblemFactory;
import com.aslp.route.service.TrackingService;
import com.aslp.route.service.VrpRouteService;
import com.aslp.route.tracking.Carrier;
import com.aslp.route.tracking.ShipmentState;
import com.aslp.route.tracking.TrackingClientException;
import com.aslp.route.tracking.TrackingNotFoundException;
import com.aslp.route.tracking.TrackingUnavailableException;
import com.aslp.route.tracking.TrackingView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 追踪接口的 HTTP 语义测试（M3）。
 *
 * <p><b>本类只验证一件事：失败类型 → HTTP 状态码 的映射。</b>
 * 这是契约里最容易被写错、也最影响调用方决策的部分：
 * <ul>
 *   <li>查无此单 → 404（前端提示"核对单号"）</li>
 *   <li>承运商不可用 → 503（前端提示"稍后重试"）</li>
 *   <li>承运商拒绝 → 502（我们自己看日志）</li>
 *   <li>入参问题 → 400（前端提示"输入有误"）</li>
 * </ul>
 * 若把这些都压成 500，前端就只能写一句"操作失败"。
 */
@WebMvcTest(RouteController.class)
class TrackingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VrpRouteService vrpService;

    @MockBean
    private VrpProblemFactory problemFactory;

    @MockBean
    private TrackingService trackingService;

    private static TrackingView view(Carrier carrier) {
        return new TrackingView("00340434161094000000", carrier, ShipmentState.DELIVERED, "delivered",
                "The shipment has been successfully delivered", "Mönchengladbach, DE",
                Instant.parse("2026-09-22T12:20:00Z"), null, List.of(), false);
    }

    @Test
    void returnsTrackingViewOnSuccess() throws Exception {
        when(trackingService.lookup(eq("00340434161094000000"), any(), eq(false)))
                .thenReturn(view(Carrier.DHL));

        mockMvc.perform(get("/api/routes/tracking/00340434161094000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carrier").value("DHL"))
                .andExpect(jsonPath("$.state").value("DELIVERED"))
                .andExpect(jsonPath("$.stale").value(false));
    }

    @Test
    void passesCarrierAndRefreshThrough() throws Exception {
        when(trackingService.lookup(eq("01234567890123"), eq(Carrier.DPD), eq(true)))
                .thenReturn(view(Carrier.DPD));

        mockMvc.perform(get("/api/routes/tracking/01234567890123")
                        .param("carrier", "dpd")     // 大小写不敏感
                        .param("refresh", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carrier").value("DPD"));
    }

    @Test
    void notFoundMapsTo404() throws Exception {
        when(trackingService.lookup(any(), any(), eq(false)))
                .thenThrow(new TrackingNotFoundException(Carrier.DHL, "00340434161094000002"));

        mockMvc.perform(get("/api/routes/tracking/00340434161094000002"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TRACKING_NOT_FOUND"))
                .andExpect(jsonPath("$.carrier").value("DHL"));
    }

    @Test
    void rateLimitedMapsTo503WithRetryAfter() throws Exception {
        when(trackingService.lookup(any(), any(), eq(false)))
                .thenThrow(new TrackingUnavailableException(Carrier.DHL, "DHL 限流（429）", null, true, 7));

        mockMvc.perform(get("/api/routes/tracking/00340434161094000429"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("TRACKING_RATE_LIMITED"))
                .andExpect(jsonPath("$.rateLimited").value(true))
                .andExpect(jsonPath("$.retryAfterSeconds").value(7));
    }

    @Test
    void upstreamErrorMapsTo503() throws Exception {
        when(trackingService.lookup(any(), any(), eq(false)))
                .thenThrow(new TrackingUnavailableException(Carrier.DPD, "DPD 服务端错误", null, false, null));

        mockMvc.perform(get("/api/routes/tracking/01234567890123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("TRACKING_UNAVAILABLE"))
                .andExpect(jsonPath("$.rateLimited").value(false));
    }

    @Test
    @DisplayName("承运商拒绝 → 502（不是 400：可能是我方请求/凭据的问题，别让用户去改正确单号）")
    void upstreamRejectionMapsTo502() throws Exception {
        when(trackingService.lookup(any(), any(), eq(false)))
                .thenThrow(new TrackingClientException(Carrier.DHL, 403, "forbidden"));

        mockMvc.perform(get("/api/routes/tracking/00340434161094000000"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("TRACKING_UPSTREAM_REJECTED"))
                .andExpect(jsonPath("$.upstreamStatus").value(403));
    }

    @Test
    void unknownCarrierParamMapsTo400() throws Exception {
        mockMvc.perform(get("/api/routes/tracking/01234567890123").param("carrier", "GLS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        Mockito.verifyNoInteractions(trackingService);
    }

    @Test
    void undetectableNumberMapsTo400() throws Exception {
        when(trackingService.lookup(any(), any(), eq(false)))
                .thenThrow(new IllegalArgumentException("无法根据单号识别承运商"));

        mockMvc.perform(get("/api/routes/tracking/ABC-123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("无法根据单号识别承运商"));
    }
}
