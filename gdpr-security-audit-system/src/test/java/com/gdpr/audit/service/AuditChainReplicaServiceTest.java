package com.gdpr.audit.service;

import com.gdpr.audit.entity.AuditChainHead;
import com.gdpr.audit.repository.AuditChainHeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 本机 append-only 副本单测：一致放行、库被回滚/截断可检出、空副本放行。
 */
class AuditChainReplicaServiceTest {

    @TempDir
    Path tempDir;

    private AuditChainHeadRepository headRepository;
    private AuditChainReplicaService service;
    private Path replicaFile;

    @BeforeEach
    void setUp() {
        headRepository = mock(AuditChainHeadRepository.class);
        replicaFile = tempDir.resolve("replica/audit-chain-replica.log");
        service = new AuditChainReplicaService(headRepository, replicaFile.toString());
    }

    private void headAt(long nextSequence, String lastHash) {
        when(headRepository.findById(AuditChainService.STREAM_ID)).thenReturn(Optional.of(
                new AuditChainHead(AuditChainService.STREAM_ID, nextSequence, lastHash, Instant.now(), 0)));
    }

    @Test
    void consistentReplicaPasses() {
        headAt(6, "abc123");
        service.recordCheckpoint(5, "abc123");

        assertThat(service.verifyReplica()).isTrue();
    }

    @Test
    void detectsDatabaseRollback() {
        // 副本记录到 7，但库链头只到 4 —— 库疑似被回滚/截断
        service.recordCheckpoint(7, "deadbeef");
        headAt(5, "deadbeef");

        assertThat(service.verifyReplica()).isFalse();
    }

    @Test
    void detectsHashChangeAtSameSequence() {
        service.recordCheckpoint(5, "original-hash");
        headAt(6, "rewritten-hash");

        assertThat(service.verifyReplica()).isFalse();
    }

    @Test
    void emptyReplicaOrMissingFilePasses() {
        headAt(6, "abc123");

        assertThat(service.verifyReplica()).isTrue();

        service.recordCheckpoint(5, "abc123");
        assertThat(service.verifyReplica()).isTrue();
    }

    @Test
    void replicaFileIsAppendOnlyAcrossCheckpoints() throws Exception {
        service.recordCheckpoint(1, "hash-1");
        service.recordCheckpoint(2, "hash-2");
        service.recordCheckpoint(3, "hash-3");

        var lines = Files.readAllLines(replicaFile);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("1:hash-1");
        assertThat(lines.get(2)).isEqualTo("3:hash-3");
    }

    @Test
    void duplicateCheckpointIsNotAppendedAgain() throws Exception {
        service.recordCheckpoint(5, "same-hash");
        service.recordCheckpoint(5, "same-hash");
        service.recordCheckpoint(5, "same-hash");

        assertThat(Files.readAllLines(replicaFile)).hasSize(1);
    }

    @Test
    void corruptedReplicaReportsMismatchInsteadOfPassing() throws Exception {
        Files.createDirectories(replicaFile.getParent());
        Files.writeString(replicaFile, "5:abc123\nnot-a-checkpoint\n");
        headAt(6, "abc123");

        assertThat(service.verifyReplica()).isFalse();
    }

    @Test
    void regressedSequenceInsideReplicaIsDetected() throws Exception {
        Files.createDirectories(replicaFile.getParent());
        Files.writeString(replicaFile, "5:hash-5\n3:hash-3\n");
        headAt(6, "hash-5");

        assertThat(service.verifyReplica()).isFalse();
    }
}
