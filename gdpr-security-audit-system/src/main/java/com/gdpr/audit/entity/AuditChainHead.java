package com.gdpr.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "audit_chain_heads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuditChainHead {

    @Id
    @Column(name = "stream_id", length = 100)
    private String streamId;

    @Column(name = "next_sequence", nullable = false)
    private long nextSequence;

    @Column(name = "last_hash", nullable = false, length = 64)
    private String lastHash;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;
}
