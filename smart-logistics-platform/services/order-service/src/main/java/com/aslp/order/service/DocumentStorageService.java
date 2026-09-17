package com.aslp.order.service;

import com.aslp.order.config.MinioProperties;
import com.aslp.order.document.DocumentNotFoundException;
import com.aslp.order.document.DocumentStorageException;
import com.aslp.order.document.DocumentType;
import com.aslp.order.document.PdfDocumentWriter;
import com.aslp.order.entity.OrderRecord;
import com.aslp.order.repository.OrderRepository;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.http.Method;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * P1-6 单据存储服务：生成 PDF → 上传 MinIO → 支持重打（下载）与预签名外链。
 *
 * <p><b>对象键设计</b>：{@code orders/{orderId}/{kind}-{yyyyMMdd'T'HHmmss'Z'}.pdf}
 * <ul>
 *   <li>按订单号分组：既能按前缀高效列举，也便于按订单整体归档/删除（欧盟 GDPR
 *       的删除请求可以按前缀一次性清理）；</li>
 *   <li>带时间戳：面单可能因为改地址/换承运商而重打，<b>历史版本必须留痕</b>
 *       （打印次数与内容在物流纠纷里是常见举证材料）；</li>
 *   <li>「最新一版」= 前缀下键最大的那个（ISO-8601 UTC 时间戳按字典序 == 按时间序，
 *       所以不需要额外查元数据排序）。</li>
 * </ul>
 *
 * <p><b>为什么把 sha256 存进对象元数据</b>：单据是「对外凭证」，需要能证明「平台上
 * 这份 PDF 和当时生成的一致」。哈希随对象一起存，事后可直接校验，成本几乎为零。
 */
@Service
public class DocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorageService.class);

    /** 对象键根前缀。 */
    static final String ORDER_PREFIX = "orders/";

    static final String CONTENT_TYPE = "application/pdf";

    /** 对象键里的时间戳（UTC，ISO-8601 基本格式：字典序 == 时间序）。 */
    private static final DateTimeFormatter KEY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final MinioClient minioClient;
    private final MinioClient presignClient;
    private final MinioProperties minioProperties;
    private final PdfDocumentWriter writer;
    private final OrderRepository orderRepository;

    public DocumentStorageService(MinioClient minioClient,
                                  @Qualifier("presignMinioClient") MinioClient presignClient,
                                  MinioProperties minioProperties,
                                  PdfDocumentWriter writer,
                                  OrderRepository orderRepository) {
        this.minioClient = minioClient;
        this.presignClient = presignClient;
        this.minioProperties = minioProperties;
        this.writer = writer;
        this.orderRepository = orderRepository;
    }

    /** 已存入的单据元数据。 */
    public record StoredDocument(String orderId, DocumentType type, String objectKey, String contentType,
                                 long size, String sha256, Instant storedAt, String presignedUrl) {
    }

    /** 取回的单据内容。 */
    public record DownloadedDocument(String objectKey, String contentType, byte[] content) {
    }

    /**
     * 生成并存入一份单据（同一订单同一类型可多次调用 —— 每次生成新版本）。
     *
     * @throws DocumentNotFoundException 订单不存在
     * @throws DocumentStorageException  对象存储不可用
     */
    public StoredDocument store(String orderId, DocumentType type) {
        OrderRecord order = orderRepository.findByOrderId(orderId)
                .orElseThrow(() -> new DocumentNotFoundException("订单不存在：" + orderId));

        byte[] pdf = writer.write(order, type);
        String objectKey = buildObjectKey(orderId, type);
        String sha256 = sha256(pdf);

        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(pdf), pdf.length, -1)
                    .contentType(CONTENT_TYPE)
                    // 元数据只放 ASCII：MinIO/S3 的用户元数据头要求 ASCII，中文会直接报错
                    .userMetadata(Map.of(
                            "order-id", orderId,
                            "document-type", type.slug(),
                            "sha256", sha256))
                    .build());
        } catch (Exception e) {
            throw new DocumentStorageException("单据上传失败（桶 " + minioProperties.getBucket() + "）："
                    + e.getMessage(), e);
        }

        log.info("[单据存储] 已生成 {}（订单 {}，{} 字节，sha256={}）",
                type.slug(), orderId, pdf.length, sha256.substring(0, 12));
        return new StoredDocument(orderId, type, objectKey, CONTENT_TYPE, pdf.length, sha256,
                Instant.now(), presignedUrl(objectKey));
    }

    /** 下载「最新一版」单据。 */
    public DownloadedDocument load(String orderId, DocumentType type) {
        String objectKey = latestObjectKey(orderId, type);
        try (GetObjectResponse response = minioClient.getObject(GetObjectArgs.builder()
                .bucket(minioProperties.getBucket())
                .object(objectKey)
                .build())) {
            return new DownloadedDocument(objectKey, CONTENT_TYPE, response.readAllBytes());
        } catch (Exception e) {
            throw new DocumentStorageException("单据下载失败（" + objectKey + "）：" + e.getMessage(), e);
        }
    }

    /** 列出某订单已生成的全部单据（含历史版本，按对象键升序 = 按时间升序）。 */
    public List<StoredDocument> list(String orderId) {
        if (orderRepository.findByOrderId(orderId).isEmpty()) {
            throw new DocumentNotFoundException("订单不存在：" + orderId);
        }
        List<StoredDocument> documents = new ArrayList<>();
        try {
            for (Result<Item> result : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .prefix(ORDER_PREFIX + orderId + "/")
                    .recursive(true)
                    .build())) {
                Item item = result.get();
                documents.add(new StoredDocument(orderId, typeOf(item.objectName()), item.objectName(),
                        CONTENT_TYPE, item.size(), null, item.lastModified().toInstant(),
                        presignedUrl(item.objectName())));
            }
        } catch (Exception e) {
            throw new DocumentStorageException("单据列举失败（订单 " + orderId + "）：" + e.getMessage(), e);
        }
        return documents;
    }

    /** 生成预签名下载 URL（用对外端点签，见 {@link MinioProperties#getPublicEndpoint()}）。 */
    public String presignedUrl(String objectKey) {
        try {
            return presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(minioProperties.getBucket())
                    .object(objectKey)
                    .expiry((int) minioProperties.getPresignExpiry().toSeconds())
                    .build());
        } catch (Exception e) {
            throw new DocumentStorageException("预签名 URL 生成失败（" + objectKey + "）：" + e.getMessage(), e);
        }
    }

    /**
     * 桶可达性探测结果。
     *
     * <p>带上错误原因而不是只返回布尔：健康检查里至少能直接看到
     * {@code InvalidAccessKeyId} / {@code Connection refused} 这类根因，
     * 而不用再去翻日志（本轮就是这么定位到占位符写法的，见 readme §9 #45）。
     */
    public record BucketProbe(boolean available, String detail) {
    }

    /** 桶是否可用（健康检查用）。 */
    public BucketProbe probeBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .build());
            return new BucketProbe(exists, exists ? "bucket reachable" : "bucket missing");
        } catch (Exception e) {
            log.debug("[单据存储] 桶可用性探测失败：{}", e.toString());
            return new BucketProbe(false, e.getMessage());
        }
    }

    /** 对象键：orders/{orderId}/{kind}-{UTC 时间戳}.pdf */
    static String buildObjectKey(String orderId, DocumentType type) {
        return ORDER_PREFIX + orderId + "/" + type.slug() + "-" + KEY_TIMESTAMP.format(Instant.now()) + ".pdf";
    }

    /** 从对象键反推单据类型（列举时用；未知键归到面单以避免 NPE，但会记日志）。 */
    private static DocumentType typeOf(String objectKey) {
        for (DocumentType type : DocumentType.values()) {
            if (objectKey.contains("/" + type.slug() + "-")) {
                return type;
            }
        }
        log.warn("[单据存储] 无法识别对象键的类型：{}", objectKey);
        return DocumentType.SHIPPING_LABEL;
    }

    /** 最新版本 = 前缀下对象键最大者（ISO-8601 UTC 时间戳的字典序即时间序）。 */
    private String latestObjectKey(String orderId, DocumentType type) {
        String prefix = ORDER_PREFIX + orderId + "/" + type.slug() + "-";
        String latest = null;
        try {
            for (Result<Item> result : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .prefix(prefix)
                    .recursive(true)
                    .build())) {
                String candidate = result.get().objectName();
                if (latest == null || candidate.compareTo(latest) > 0) {
                    latest = candidate;
                }
            }
        } catch (Exception e) {
            throw new DocumentStorageException("单据查询失败（订单 " + orderId + "）：" + e.getMessage(), e);
        }
        if (latest == null) {
            throw new DocumentNotFoundException("该订单还没有生成「" + type.label() + "」："
                    + orderId + "（先调用 POST /api/orders/" + orderId + "/documents/" + type.slug() + " 生成）");
        }
        return latest;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256（不应发生）", e);
        }
    }
}
