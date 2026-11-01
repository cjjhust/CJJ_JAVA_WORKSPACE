package com.gdpr.audit.security;

import com.gdpr.audit.entity.AuditLog;
import com.gdpr.audit.enums.AuditAction;
import com.gdpr.audit.service.AuditChainService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 安全事件审计过滤器单测：401/403 响应写入审计链，200 不写。
 */
@ExtendWith(MockitoExtension.class)
class AuditSecurityEventFilterTest {

    @Mock
    private AuditChainService auditChainService;

    @Mock
    private FilterChain filterChain;

    private AuditSecurityEventFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuditSecurityEventFilter(auditChainService);
    }

    private void downstreamReturns(MockHttpServletResponse res, int status)
            throws ServletException, IOException {
        doAnswer(inv -> {
            ((MockHttpServletResponse) inv.getArgument(1)).setStatus(status);
            return null;
        }).when(filterChain).doFilter(any(), any());
    }

    @Test
    void recordsAuditEventOn401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/users");
        req.setRemoteAddr("192.0.2.7");
        MockHttpServletResponse res = new MockHttpServletResponse();
        downstreamReturns(res, 401);
        whenAppendReturnsSame();

        filter.doFilter(req, res, filterChain);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditChainService).append(captor.capture());
        AuditLog event = captor.getValue();
        assertThat(event.getAction()).isEqualTo(AuditAction.LOGIN);
        assertThat(event.getResourceType()).isEqualTo("SecurityEvent");
        assertThat(event.getResourceId()).isEqualTo("401");
        assertThat(event.getResponseStatus()).isEqualTo(401);
        assertThat(event.getSuccess()).isFalse();
        assertThat(event.getUserId()).isEqualTo(SecurityUtils.ANONYMOUS);
        assertThat(event.getClientIp()).isEqualTo("192.0.2.7");
    }

    @Test
    void recordsAuditEventOn403() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/admin/dashboard");
        req.setRemoteAddr("203.0.113.9");
        MockHttpServletResponse res = new MockHttpServletResponse();
        downstreamReturns(res, 403);
        whenAppendReturnsSame();

        filter.doFilter(req, res, filterChain);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditChainService).append(captor.capture());
        AuditLog event = captor.getValue();
        assertThat(event.getAction()).isEqualTo(AuditAction.VIEW);
        assertThat(event.getResourceId()).isEqualTo("403");
    }

    @Test
    void noAuditEventOn200() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/public/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        downstreamReturns(res, 200);

        filter.doFilter(req, res, filterChain);

        verify(auditChainService, never()).append(any(AuditLog.class));
    }

    private void whenAppendReturnsSame() {
        doAnswer(inv -> inv.getArgument(0))
                .when(auditChainService).append(any(AuditLog.class));
    }
}
