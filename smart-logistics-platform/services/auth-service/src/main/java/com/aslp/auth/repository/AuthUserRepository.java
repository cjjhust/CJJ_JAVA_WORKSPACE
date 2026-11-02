package com.aslp.auth.repository;

import com.aslp.auth.entity.AuthUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * P2-6 — 用户仓储（登录认证用）。
 */
public interface AuthUserRepository extends JpaRepository<AuthUser, String> {

    Optional<AuthUser> findByUsername(String username);
}
