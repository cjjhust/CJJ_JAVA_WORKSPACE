package com.aslp.order.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * P1-6：MinIO 客户端与单据桶自举。
 *
 * <p><b>启动不依赖 MinIO</b>：{@link MinioClient} 是惰性的（构造时不建连），
 * 建桶动作放在 {@link ApplicationRunner} 里且失败只告警 —— 否则对象存储一抖，
 * 整个订单服务就起不来（P1-2 的教训：可选依赖不能变成启动硬约束）。
 *
 * <p><b>两个客户端</b>：上传/下载走容器内端点，预签名走对外端点。原因见
 * {@link MinioProperties#getPublicEndpoint()}：签名覆盖 Host，必须用同一个 Host 签。
 *
 * <p><b>注意</b>：{@link DocumentProperties} 一并在这里注册 ——
 * {@code @ConfigurationProperties} 类不写进 {@code @EnableConfigurationProperties}
 * 就只是个普通类，拿不到 {@code aslp.document.*} 的值；而这种缺失<b>编译期不报错、
 * 单元测试也不报错</b>（测试是直接 new 出来的），只有真正启动上下文才会炸
 * （见 readme §9 #43）。
 */
@Configuration
@EnableConfigurationProperties({MinioProperties.class, DocumentProperties.class})
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    /** 业务用客户端（上传 / 下载 / 列举）。 */
    @Bean
    @Primary
    public MinioClient minioClient(MinioProperties properties) {
        return build(properties.getEndpoint(), properties);
    }

    /** 预签名专用客户端（对外端点；未配置时与控制面共用同一实例）。 */
    @Bean
    public MinioClient presignMinioClient(MinioProperties properties) {
        if (!properties.hasPublicEndpoint()) {
            return minioClient(properties);
        }
        return build(properties.getPublicEndpoint(), properties);
    }

    /** 启动时确保单据桶存在（幂等；失败不阻塞启动）。 */
    @Bean
    public ApplicationRunner documentBucketBootstrap(MinioClient minioClient, MinioProperties properties) {
        return args -> {
            if (!properties.isAutoCreateBucket()) {
                return;
            }
            String bucket = properties.getBucket();
            try {
                if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("[单据存储] 已创建桶 {}（端点 {}）", bucket, properties.getEndpoint());
                } else {
                    log.info("[单据存储] 桶 {} 就绪（端点 {}）", bucket, properties.getEndpoint());
                }
            } catch (Exception e) {
                // 只告警：单据功能会返回 503，但订单主链路（拉取/状态机）不受影响
                log.warn("[单据存储] 桶 {} 不可用（MinIO 未就绪？端点 {}）：{}",
                        bucket, properties.getEndpoint(), e.toString());
            }
        };
    }

    private static MinioClient build(String endpoint, MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(endpoint)
                // 显式给区域：避免 SDK 为了签名去问服务端「这个桶在哪个区域」
                // （预签名客户端用的是对外端点，容器内不可达 —— 见 MinioProperties#getRegion）
                .region(properties.getRegion())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}
