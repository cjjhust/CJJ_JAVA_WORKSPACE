package com.gdpr.audit.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdpr.audit.annotation.Auditable;
import com.gdpr.audit.dto.UserDTO;
import com.gdpr.audit.entity.AuditLog;
import com.gdpr.audit.gdpr.GdprDataMasker;
import com.gdpr.audit.service.AuditChainService;
import com.gdpr.audit.service.AuditOutboxPublisher;
import com.gdpr.audit.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * 审计切面 — 拦截所有标注 {@link Auditable} 的方法，自动写入审计日志。
 *
 * 流程：记录开始时间 → 执行目标方法 → 提取 HTTP 上下文与用户信息（JWT）→
 * 对 requestBody / errorMessage 做 GDPR 脱敏 → 追加到 PostgreSQL HMAC 审计链。
 *
 * 当前演示模式下审计持久化失败只记录 WARN；生产应根据威胁模型配置 fail-closed。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    /** 审计专用 Logger — 经由 logback-spring.xml 的 AUDIT logger 异步输出 JSON 到 Logstash */
    private static final org.slf4j.Logger AUDIT_LOGGER = org.slf4j.LoggerFactory.getLogger("AUDIT");

    /** 请求体记录的最大长度，超出截断（脱敏前先截断，控制正则开销） */
    private static final int MAX_BODY_LENGTH = 10_000;

    private final AuditChainService auditChainService;
    private final AuditOutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    @Around("@annotation(com.gdpr.audit.annotation.Auditable)")
    public Object audit(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();

        Auditable auditable = getAuditable(joinPoint);
        Throwable businessError = null;
        Object result = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable ex) {
            businessError = ex;
            throw ex;
        } finally {
            saveAuditLog(joinPoint, auditable, result, businessError, System.currentTimeMillis() - start);
        }
    }

    /** 构建并持久化审计日志；异常被吞掉，不影响业务响应 */
    private void saveAuditLog(ProceedingJoinPoint joinPoint, Auditable auditable,
                              Object result, Throwable error, long durationMs) {
        try {
            HttpServletRequest request = currentRequest();

            AuditLog.AuditLogBuilder builder = AuditLog.builder()
                    .userId(SecurityUtils.getCurrentUserId())
                    .username(SecurityUtils.getCurrentUsername())
                    .userRoles(SecurityUtils.getCurrentUserRoles())
                    .action(auditable.action())
                    .resourceType(auditable.resourceType())
                    .resourceId(extractResourceId(joinPoint.getArgs(), result))
                    .duration(durationMs)
                    .success(error == null)
                    .errorMessage(error != null ? GdprDataMasker.maskPii(error.getMessage()) : null);

            if (request != null) {
                builder.requestMethod(request.getMethod())
                        .requestUri(request.getRequestURI())
                        .clientIp(resolveClientIp(request))
                        .userAgent(request.getHeader("User-Agent"))
                        .responseStatus(resolveStatus(result, error));
            } else {
                builder.responseStatus(error != null ? 500 : 200);
            }

            builder.requestBody(maskArgs(joinPoint));

            AuditLog auditLog = auditChainService.append(builder.build());

            // Outbox：同事务记录待投递事件，保证 DB→ELK 至少一次投递
            outboxPublisher.enqueue(auditLog.getId(), Map.ofEntries(
                    Map.entry("auditLogId", auditLog.getId()),
                    Map.entry("chainSequence", auditLog.getChainSequence()),
                    Map.entry("recordHash", auditLog.getRecordHash()),
                    Map.entry("signingKeyId", auditLog.getSigningKeyId())
            ));

            // 双写 ELK：异步经 AUDIT logger → Logstash → Elasticsearch（失败不阻塞业务）
            AUDIT_LOGGER.info("Audit event: {} {} {} {} {} {} {} {} {}",
                    kv("action", auditLog.getAction()),
                    kv("resourceType", auditLog.getResourceType()),
                    kv("requestMethod", auditLog.getRequestMethod()),
                    kv("responseStatus", auditLog.getResponseStatus()),
                    kv("userId", auditLog.getUserId()),
                    kv("username", auditLog.getUsername()),
                    kv("userRoles", auditLog.getUserRoles()),
                    kv("resourceId", auditLog.getResourceId()),
                    kv("chainSequence", auditLog.getChainSequence()),
                    kv("recordHash", auditLog.getRecordHash()),
                    kv("signingKeyId", auditLog.getSigningKeyId()),
                    kv("clientIp", auditLog.getClientIp()),
                    kv("duration", auditLog.getDuration()),
                    kv("success", auditLog.getSuccess()),
                    kv("requestUri", auditLog.getRequestUri())
            );
        } catch (Exception ex) {
            log.warn("Failed to persist audit log for {}#{}: {}",
                    joinPoint.getSignature().getDeclaringTypeName(),
                    joinPoint.getSignature().getName(), ex.getMessage());
        }
    }

    /** 方法参数序列化为 JSON 后脱敏，作为请求体记录；超长截断 */
    private String maskArgs(ProceedingJoinPoint joinPoint) {
        try {
            Object[] args = joinPoint.getArgs();
            if (args == null || args.length == 0) {
                return null;
            }
            String json = objectMapper.writeValueAsString(args);
            if (json.length() > MAX_BODY_LENGTH) {
                json = json.substring(0, MAX_BODY_LENGTH);
            }
            return GdprDataMasker.maskPii(json);
        } catch (Exception ex) {
            // 不可序列化的参数退化为类名列表
            StringBuilder sb = new StringBuilder("[unserializable: ");
            Object[] args = joinPoint.getArgs();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
            }
            return sb.append("]").toString();
        }
    }

    static String extractResourceId(Object[] args, Object result) {
        if (args == null) {
            if (result instanceof ResponseEntity<?> response && response.getBody() instanceof UserDTO user) {
                return user.getId() == null ? null : user.getId().toString();
            }
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Number || arg instanceof CharSequence) {
                return arg.toString();
            }
        }
        if (result instanceof ResponseEntity<?> response && response.getBody() instanceof UserDTO user) {
            return user.getId() == null ? null : user.getId().toString();
        }
        return null;
    }

    private Auditable getAuditable(ProceedingJoinPoint joinPoint) {
        for (var m : joinPoint.getTarget().getClass().getMethods()) {
            if (m.getName().equals(joinPoint.getSignature().getName())) {
                Auditable a = m.getAnnotation(Auditable.class);
                if (a != null) {
                    return a;
                }
            }
        }
        return null;
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    /** 支持 X-Forwarded-For（反向代理场景），取第一个 IP */
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private int resolveStatus(Object result, Throwable error) {
        if (error != null) {
            return 500;
        }
        if (result instanceof ResponseEntity<?> response) {
            HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
            return status != null ? status.value() : response.getStatusCode().value();
        }
        return 200;
    }
}
