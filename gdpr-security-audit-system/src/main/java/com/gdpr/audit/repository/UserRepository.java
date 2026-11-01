package com.gdpr.audit.repository;

import com.gdpr.audit.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 示例业务 Repository — 标准 CRUD 由 JpaRepository 提供。
 */
public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);
}
