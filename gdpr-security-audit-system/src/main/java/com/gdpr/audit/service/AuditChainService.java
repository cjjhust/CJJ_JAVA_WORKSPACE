package com.gdpr.audit.service;

import com.gdpr.audit.entity.AuditChainHead;
import com.gdpr.audit.entity.AuditLog;
import com.gdpr.audit.repository.AuditChainHeadRepository;
import com.gdpr.audit.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

@Service
public class AuditChainService {

    public static final String STREAM_ID = "global";
    public static final String GENESIS_HASH = "0".repeat(64);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final AuditChainHeadRepository headRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditChainStartupValidator startupValidator;
    private final SecretKeySpec signingKey;
    private final String signingKeyId;

    public AuditChainService(
            AuditChainHeadRepository headRepository,
            AuditLogRepository auditLogRepository,
            AuditChainStartupValidator startupValidator,
            @Value("${audit.chain.hmac-key}") String hmacKey,
            @Value("${audit.chain.key-id}") String signingKeyId) {
        if (hmacKey == null || hmacKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("audit.chain.hmac-key must contain at least 32 UTF-8 bytes");
        }
        if (signingKeyId == null || signingKeyId.isBlank()) {
            throw new IllegalArgumentException("audit.chain.key-id must not be blank");
        }
        this.headRepository = headRepository;
        this.auditLogRepository = auditLogRepository;
        this.startupValidator = startupValidator;
        this.signingKey = new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        this.signingKeyId = signingKeyId;
    }

    @Transactional
    public AuditLog append(AuditLog event) {
        if (startupValidator.isSafeMode()) {
            throw new IllegalStateException("Audit chain in SAFE MODE — appends rejected: "
                    + startupValidator.getInconsistencyReason());
        }
        AuditChainHead head = headRepository.findLockedByStreamId(STREAM_ID)
                .orElseThrow(() -> new IllegalStateException("Audit chain head is not initialized"));

        if (event.getTimestamp() == null) {
            event.setTimestamp(LocalDateTime.now(ZoneOffset.UTC));
        }
        event.setTimestamp(event.getTimestamp().truncatedTo(ChronoUnit.MICROS));
        event.setChainSequence(head.getNextSequence());
        event.setPreviousHash(head.getLastHash());
        event.setSigningKeyId(signingKeyId);
        event.setRecordHash(calculateHash(event));

        AuditLog saved = auditLogRepository.save(event);
        head.setNextSequence(head.getNextSequence() + 1);
        head.setLastHash(saved.getRecordHash());
        head.setUpdatedAt(Instant.now());
        headRepository.save(head);
        return saved;
    }

    String calculateHash(AuditLog event) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(signingKey);
            return HexFormat.of().formatHex(mac.doFinal(canonicalBytes(event)));
        } catch (GeneralSecurityException | IOException ex) {
            throw new IllegalStateException("Unable to calculate audit chain HMAC", ex);
        }
    }

    public boolean verifyRecordHash(AuditLog event) {
        if (event.getSigningKeyId() == null || !signingKeyId.equals(event.getSigningKeyId())
                || event.getRecordHash() == null) {
            return false;
        }
        byte[] expected = HexFormat.of().parseHex(calculateHash(event));
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(event.getRecordHash());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] canonicalBytes(AuditLog event) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(1);
            output.writeLong(event.getChainSequence());
            writeString(output, event.getPreviousHash());
            writeString(output, event.getSigningKeyId());
            writeString(output, event.getTimestamp().toString());
            writeString(output, event.getUserId());
            writeString(output, event.getUsername());
            writeString(output, event.getUserRoles());
            writeString(output, event.getAction() == null ? null : event.getAction().name());
            writeString(output, event.getResourceType());
            writeString(output, event.getResourceId());
            writeString(output, event.getRequestMethod());
            writeString(output, event.getRequestUri());
            writeString(output, event.getRequestBody());
            writeString(output, event.getResponseStatus() == null ? null : event.getResponseStatus().toString());
            writeString(output, event.getClientIp());
            writeString(output, event.getUserAgent());
            writeString(output, event.getDuration() == null ? null : event.getDuration().toString());
            writeString(output, event.getSuccess() == null ? null : event.getSuccess().toString());
            writeString(output, event.getErrorMessage());
        }
        return bytes.toByteArray();
    }

    private void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
