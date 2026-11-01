package com.gdpr.audit.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdpr.audit.annotation.Auditable;
import com.gdpr.audit.dto.UserDTO;
import com.gdpr.audit.entity.AuditLog;
import com.gdpr.audit.gdpr.GdprDataMasker;
import com.gdpr.audit.service.AuditEventRecorder;
import com.gdpr.audit.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计切面 — 拦截所有标注 {@link Auditable} 的方法，自动写入审计日志。
 *
 * 流程：记录开始时间 → 执行目标方法 → 提取 HTTP 上下文与用户信息（JWT）→
 * 对 requestBody / errorMessage 做 GDPR 脱敏 → 追加到 PostgreSQL HMAC 审计链。
 *
 * 成功路径采用 fail-closed：业务事务与审计链/Outbox 同事务提交，审计失败则业务回滚。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuditAspect {

    /** 请求体记录的最大长度，超出截断（脱敏前先截断，控制正则开销） */
    private static final int MAX_BODY_LENGTH = 10_000;

    private final AuditEventRecorder auditEventRecorder;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public AuditAspect(AuditEventRecorder auditEventRecorder, ObjectMapper objectMapper,
                       @NonNull PlatformTransactionManager transactionManager) {
        this.auditEventRecorder = auditEventRecorder;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Around("@annotation(com.gdpr.audit.annotation.Auditable)")
    public Object audit(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        Auditable auditable = getAuditable(joinPoint);
        try {
            return transactionTemplate.execute(status -> {
                final Object result;
                try {
                    result = joinPoint.proceed();
                } catch (Throwable businessError) {
                    status.setRollbackOnly();
                    throw new BusinessInvocationFailure(businessError);
                }
                auditEventRecorder.record(buildAuditLog(joinPoint, auditable, result, null,
                        System.currentTimeMillis() - start));
                return result;
            });
        } catch (BusinessInvocationFailure failure) {
            recordBusinessFailure(joinPoint, auditable, failure.getCause(),
                    System.currentTimeMillis() - start);
            throw failure.getCause();
        }
    }

    private void recordBusinessFailure(ProceedingJoinPoint joinPoint, Auditable auditable,
                                       Throwable businessError, long durationMs) {
        try {
            auditEventRecorder.record(buildAuditLog(joinPoint, auditable, null, businessError, durationMs));
        } catch (RuntimeException auditError) {
            businessError.addSuppressed(auditError);
            log.error("Failed to persist audit record for failed operation {}#{}",
                    joinPoint.getSignature().getDeclaringTypeName(),
                    joinPoint.getSignature().getName(), auditError);
        }
    }

    private AuditLog buildAuditLog(ProceedingJoinPoint joinPoint, Auditable auditable,
                                   Object result, Throwable error, long durationMs) {
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
                .errorMessage(error != null ? GdprDataMasker.maskPii(error.getMessage()) : null)
                .requestBody(maskArgs(joinPoint));

        if (request != null) {
            builder.requestMethod(request.getMethod())
                    .requestUri(request.getRequestURI())
                    .clientIp(resolveClientIp(request))
                    .userAgent(request.getHeader("User-Agent"))
                    .responseStatus(resolveStatus(result, error));
        } else {
            builder.responseStatus(error != null ? 500 : 200);
        }
        return builder.build();
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
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Auditable auditable = signature.getMethod().getAnnotation(Auditable.class);
        if (auditable == null) {
            throw new IllegalStateException("Audit advice invoked without @Auditable metadata");
        }
        return auditable;
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

    private static final class BusinessInvocationFailure extends RuntimeException {
        private BusinessInvocationFailure(Throwable cause) {
            super(cause);
        }
    }
}
