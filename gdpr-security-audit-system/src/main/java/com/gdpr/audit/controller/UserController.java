package com.gdpr.audit.controller;

import com.gdpr.audit.annotation.Auditable;
import com.gdpr.audit.dto.UserDTO;
import com.gdpr.audit.enums.AuditAction;
import com.gdpr.audit.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * 示例业务 Controller — User CRUD，全部端点打 @Auditable。
 *
 * 审计效果验证方式：
 * 1. POST /api/users（body 含 email/phone/iban）→ audit_logs.request_body 中 PII 已脱敏
 * 2. DELETE /api/users/1 → audit_logs 记录操作人与被删资源 ID
 *
 * 注意：POST/PUT 属于写操作，需要认证（SecurityConfig 中 anyRequest().authenticated()）；
 * 读取端点同样需要认证，可通过 Keycloak 获取 Token 后携带访问。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    @Auditable(action = AuditAction.VIEW, resourceType = "User", description = "查询用户列表")
    public ResponseEntity<List<UserDTO>> list() {
        return ResponseEntity.ok(userService.findAll());
    }

    @GetMapping("/{id}")
    @Auditable(action = AuditAction.VIEW, resourceType = "User", description = "查看用户详情")
    public ResponseEntity<UserDTO> get(@PathVariable Long id) {
        return ResponseEntity.ok(userService.findById(id));
    }

    @PostMapping
    @Auditable(action = AuditAction.CREATE, resourceType = "User", description = "创建用户")
    public ResponseEntity<UserDTO> create(@RequestBody UserDTO dto) {
        UserDTO created = userService.create(dto);
        return ResponseEntity.created(URI.create("/api/users/" + created.getId())).body(created);
    }

    @PutMapping("/{id}")
    @Auditable(action = AuditAction.MODIFY, resourceType = "User", description = "更新用户")
    public ResponseEntity<UserDTO> update(@PathVariable Long id, @RequestBody UserDTO dto) {
        return ResponseEntity.ok(userService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @Auditable(action = AuditAction.DELETE, resourceType = "User", description = "删除用户")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
