package com.aslp.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * P2-6 — 平台用户（登录认证的唯一数据源）。
 *
 * <p>密码只存 BCrypt 哈希（{@code $2a$10$...}，含盐，不可逆）。
 * 角色直接落在用户行上 —— 这是 RBAC 的最小闭环：
 * 「谁能以什么角色登录」由数据决定，而不是由代码里的 if 决定。
 */
@Entity
@Table(name = "auth_user")
public class AuthUser {

    @Id
    @Column(name = "username", length = 64)
    private String username;

    /** BCrypt 哈希（含盐）。 */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /** 角色（不含 ROLE_ 前缀，与 JWT roles claim 同口径）。 */
    @Column(name = "role", nullable = false, length = 32)
    private String role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuthUser() {
        // JPA
    }

    public AuthUser(String username, String passwordHash, String role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
