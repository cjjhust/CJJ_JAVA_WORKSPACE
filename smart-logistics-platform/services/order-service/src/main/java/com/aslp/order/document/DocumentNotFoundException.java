package com.aslp.order.document;

/**
 * 单据不存在（该订单还没生成过这类单据）。
 *
 * <p>与 {@link DocumentStorageException} 分开：前者是「正常业务状态」（还没打印），
 * 后者是「基础设施故障」（对象存储挂了）。混在一起会让运维看不出该重试还是该等。
 */
public class DocumentNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentNotFoundException(String message) {
        super(message);
    }
}
