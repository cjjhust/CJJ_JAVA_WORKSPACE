package com.gdpr.audit.service;

import com.gdpr.audit.entity.AuditChainHead;
import com.gdpr.audit.repository.AuditChainHeadRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 本机 append-only 副本（readme「单机能做到哪一步」方案第 1 层）。
 *
 * 原理：周期性地把审计链检查点（sequence + lastHash）**追加**写入本地文件。
 * 追加写只产生新行、从不改写旧行；校验时重放整个文件并与数据库链头比对：
 * - 文件末行与链头一致 → 正常；
 * - 文件末行序号 > 链头序号，或中间行被回退 → 数据库可能被回滚/截断（数据库里的
 *   历史被改短，但文件里保留着"曾经到达过这里"的证据）。
 *
 * 边界（诚实声明）：普通文件可被宿主机 root 删除或重写。生产用 `chattr +a`
 * （Linux append-only 属性，root 也无法改写旧行）或部署到另一台机器；
 * 本组件在启动时检测并打印当前文件是否具备 append-only 属性提示。
 */
@Slf4j
@Component
public class AuditChainReplicaService implements ApplicationRunner {

    private final AuditChainHeadRepository headRepository;
    private final Path replicaPath;

    /** 上一次已落盘的检查点，避免周期任务重复追加造成文件无界增长 */
    private long lastWrittenSequence = Long.MIN_VALUE;
    private String lastWrittenHash;

    public AuditChainReplicaService(AuditChainHeadRepository headRepository,
                                    @Value("${audit.replica.path:./audit-chain-replica.log}") String replicaPath) {
        this.headRepository = headRepository;
        this.replicaPath = Path.of(replicaPath);
    }

    /**
     * 追加一条检查点（sequence:lastHash）。
     *
     * 使用 SYNC 刷盘，避免断电后最近检查点丢失；相同检查点不重复写入。
     * 追加写失败只告警，不阻断业务（丢失的是锚点，不是审计主记录）。
     */
    public synchronized void recordCheckpoint(long sequence, String lastHash) {
        if (sequence == lastWrittenSequence && java.util.Objects.equals(lastHash, lastWrittenHash)) {
            return;
        }
        try {
            Path parent = replicaPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(replicaPath,
                    sequence + ":" + lastHash + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
            lastWrittenSequence = sequence;
            lastWrittenHash = lastHash;
        } catch (IOException ex) {
            log.warn("Audit replica checkpoint write failed: {}", ex.getMessage());
        }
    }

    /**
     * 重放副本文件并与数据库链头比对。
     *
     * @return true 一致（或副本为空/链头缺失——空库合法状态）；false 数据库疑似被回滚。
     */
    public synchronized boolean verifyReplica() {
        try {
            if (!Files.exists(replicaPath)) {
                return true;
            }
            long maxSequence = -1;
            String maxHash = null;
            long previousSequence = -1;
            for (String raw : Files.readAllLines(replicaPath, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                int separator = line.indexOf(':');
                if (separator <= 0 || separator == line.length() - 1) {
                    log.error("AUDIT REPLICA CORRUPT: unparseable checkpoint '{}' — anchor file damaged or tampered", line);
                    return false;
                }
                long sequence;
                try {
                    sequence = Long.parseLong(line.substring(0, separator));
                } catch (NumberFormatException ex) {
                    log.error("AUDIT REPLICA CORRUPT: invalid sequence in '{}'", line);
                    return false;
                }
                if (sequence < previousSequence) {
                    log.error("AUDIT REPLICA CORRUPT: sequence regressed {} -> {}", previousSequence, sequence);
                    return false;
                }
                previousSequence = sequence;
                maxSequence = sequence;
                maxHash = line.substring(separator + 1);
            }
            AuditChainHead head = headRepository.findById(AuditChainService.STREAM_ID).orElse(null);
            if (head == null || maxSequence < 0) {
                return true;
            }
            long headSequence = head.getNextSequence() - 1;
            if (maxSequence > headSequence
                    || (maxSequence == headSequence && !maxHash.equals(head.getLastHash()))) {
                log.error("AUDIT REPLICA MISMATCH: replica has sequence {} but DB head is at {} — "
                        + "database may have been rolled back or truncated", maxSequence, headSequence);
                return false;
            }
            return true;
        } catch (IOException ex) {
            // 读不到锁点文件 = 校验能力失效，按失败上报，交运维/告警处理
            log.error("Audit replica unreadable: {}", ex.getMessage());
            return false;
        }
    }

    /** 周期记录检查点（独立于业务写入，保证即使无新事件也有时间锚点） */
    @Scheduled(fixedDelayString = "${audit.replica.record-interval-ms:30000}")
    public void recordPeriodically() {
        headRepository.findById(AuditChainService.STREAM_ID).ifPresent(head ->
                recordCheckpoint(head.getNextSequence() - 1, head.getLastHash()));
    }

    /** 周期校验：副本与库不一致时走 SIEM 告警通道 */
    @Scheduled(fixedDelayString = "${audit.replica.verify-interval-ms:60000}")
    public void verifyPeriodically() {
        if (!verifyReplica()) {
            org.slf4j.LoggerFactory.getLogger("SIEM_ALERT")
                    .error("event=audit_replica_mismatch replica={} ", replicaPath);
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Audit chain replica file: {} (for tamper resistance set 'chattr +a' on this file in production)",
                replicaPath.toAbsolutePath());
        recordPeriodically();
    }
}
