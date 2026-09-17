package com.aslp.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * P1-6：MinIO 对象存储参数（前缀 {@code aslp.minio}）。
 *
 * <p>本文档库存的是**面单与报关单 PDF**：体积小（几十 KB）、数量大（每单每类一份）、
 * 需要长期留档并支持重打 —— 正是对象存储的典型场景，没必要塞进关系库
 * （BLOB 会让备份/迁移成本成倍上升）。
 */
@ConfigurationProperties(prefix = "aslp.minio")
public class MinioProperties {

    /** 服务端端点（容器内是服务别名，如 {@code http://aslp-minio:9000}）。 */
    private String endpoint = "http://localhost:9000";

    /**
     * 对外端点（可选）：用于生成**预签名 URL**。
     *
     * <p>为什么要单独配：预签名 URL 的签名覆盖 Host 头，
     * 拿容器内端点（`aslp-minio:9000`）签出来的 URL 在宿主浏览器里既解析不了主机名、
     * 也不能靠改字符串修好（改了就签名不匹配）。所以用「对外端点」再建一个客户端专门签名。
     * 留空则复用 {@link #endpoint}（本地开发即此形态）。
     */
    private String publicEndpoint;

    /** 访问密钥。 */
    private String accessKey = "aslp-minio-admin";

    /** 密钥。 */
    private String secretKey = "aslp-minio-secret";

    /** 单据桶名。 */
    private String bucket = "aslp-documents";

    /**
     * 客户端使用的区域（MinIO 默认 {@code us-east-1}）。
     *
     * <p><b>必须显式配置</b>：SDK 在签名前若不知道区域，会先发一个
     * {@code GET /{bucket}?location=} 去问服务端。而预签名客户端用的是**对外端点**
     * （容器内是 {@code localhost:9000}，根本不可达）→ 预签名会直接失败，
     * 连带把上传成功的结果也变成 503（实测踩到，见 readme §9 #46）。
     * 把区域写死就能跳过这次查询。
     */
    private String region = "us-east-1";

    /** 预签名 URL 有效期（分钟级即可：给作业员点开下载/打印用）。 */
    private Duration presignExpiry = Duration.ofMinutes(15);

    /** 启动时自动建桶（桶不存在时创建；建不了只告警，不阻塞服务启动）。 */
    private boolean autoCreateBucket = true;

    /** 对外端点是否单独配置。 */
    public boolean hasPublicEndpoint() {
        return StringUtils.hasText(publicEndpoint);
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getPublicEndpoint() {
        return publicEndpoint;
    }

    public void setPublicEndpoint(String publicEndpoint) {
        this.publicEndpoint = publicEndpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Duration getPresignExpiry() {
        return presignExpiry;
    }

    public void setPresignExpiry(Duration presignExpiry) {
        this.presignExpiry = presignExpiry;
    }

    public boolean isAutoCreateBucket() {
        return autoCreateBucket;
    }

    public void setAutoCreateBucket(boolean autoCreateBucket) {
        this.autoCreateBucket = autoCreateBucket;
    }
}
