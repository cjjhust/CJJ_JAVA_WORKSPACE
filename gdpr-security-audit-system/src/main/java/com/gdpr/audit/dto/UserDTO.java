package com.gdpr.audit.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户请求/响应 DTO — 包含 PII 字段，用于演示审计切面的脱敏效果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {

    private Long id;
    private String firstName;
    private String lastName;
    /** PII：邮箱 */
    private String email;
    /** PII：电话 */
    private String phone;
    /** PII：IBAN 银行账号 */
    private String iban;
    private String department;
    private LocalDateTime createdAt;
}
