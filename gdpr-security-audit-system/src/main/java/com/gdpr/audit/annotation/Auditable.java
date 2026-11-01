package com.gdpr.audit.annotation;

import com.gdpr.audit.enums.AuditAction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级审计注解 — 标记需要被 AuditAspect 拦截记录的敏感操作。
 *
 * 用法示例：
 * <pre>
 * &#64;Auditable(action = AuditAction.VIEW, resourceType = "User")
 * &#64;GetMapping("/&#123;id&#125;")
 * public ResponseEntity&lt;UserDTO&gt; getUser(@PathVariable Long id) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /** 操作类型 */
    AuditAction action();

    /** 资源类型（如 "User"、"Document"） */
    String resourceType();

    /** 描述（可选） */
    String description() default "";
}
