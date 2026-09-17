package com.aslp.order.document;

/**
 * 对象存储不可用（MinIO 连不上 / 桶不存在 / 认证失败）。
 *
 * <p>控制器把它映射为 <b>503</b> 而不是 500：这是<b>依赖不可用</b>（可重试、可等恢复），
 * 不是本服务的代码错误。状态码选对了，上游的告警与重试策略才有意义。
 */
public class DocumentStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
