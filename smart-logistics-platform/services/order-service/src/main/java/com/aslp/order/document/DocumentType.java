package com.aslp.order.document;

import java.util.Arrays;
import java.util.Locale;

/**
 * P1-6 单据类型。
 *
 * <p>两类单据对应海外仓最常见的两个「必须留档、必须可重打」的场景：
 * <ul>
 *   <li>{@link #SHIPPING_LABEL} 面单（仓内作业联）：随货走，打印后贴在包裹上；</li>
 *   <li>{@link #CUSTOMS_DECLARATION} 报关单（CN22 摘要）：跨境件必需，随附报关。</li>
 * </ul>
 *
 * <p>用枚举而不是裸字符串：路径参数会被直接拼进对象键，
 * 白名单化可以避免「任意 type 都写一个对象」造成桶里出现无法识别的前缀。
 */
public enum DocumentType {

    /** 面单 / 仓内作业联。 */
    SHIPPING_LABEL("shipping-label", "面单（仓内作业联）"),

    /** 报关单（CN22 摘要）。 */
    CUSTOMS_DECLARATION("customs-declaration", "报关单（CN22 摘要）");

    private final String slug;
    private final String label;

    DocumentType(String slug, String label) {
        this.slug = slug;
        this.label = label;
    }

    /** 对象键里的短名（小写连字符，避免大小写敏感文件系统差异）。 */
    public String slug() {
        return slug;
    }

    /** 中文展示名（仅用于日志/响应，不进入对象键 —— MinIO 元数据要求 ASCII）。 */
    public String label() {
        return label;
    }

    /**
     * 解析路径/查询参数里的类型名（大小写无关，同时接受 slug 与枚举名）。
     *
     * @throws IllegalArgumentException 无法识别时抛出（控制器转 400）
     */
    public static DocumentType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("单据类型不能为空，可选：" + names());
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values())
                .filter(type -> type.slug.equals(normalized) || type.name().toLowerCase(Locale.ROOT).replace('_', '-').equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "不支持的单据类型：" + raw + "，可选：" + names()));
    }

    /** 支持的类型清单（错误信息用）。 */
    public static String names() {
        return Arrays.stream(values()).map(DocumentType::slug).toList().toString();
    }
}
