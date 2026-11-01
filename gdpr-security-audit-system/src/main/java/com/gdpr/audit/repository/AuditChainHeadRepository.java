package com.gdpr.audit.repository;

import com.gdpr.audit.entity.AuditChainHead;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AuditChainHeadRepository extends JpaRepository<AuditChainHead, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM AuditChainHead h WHERE h.streamId = :streamId")
    Optional<AuditChainHead> findLockedByStreamId(@Param("streamId") String streamId);
}
