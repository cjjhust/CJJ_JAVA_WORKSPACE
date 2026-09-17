package com.aslp.order.document;

import com.aslp.order.config.DocumentProperties;
import com.aslp.order.entity.OrderRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-6 单据 PDF 生成测试。
 *
 * <p>校验的是「PDF 真的是 PDF 且内容真的是我们要的」，而不是「字节数等于 1234」：
 * <ol>
 *   <li>PDF 文件头/尾（{@code %PDF-} / {@code %%EOF}）—— 前者错了任何阅读器都打不开；</li>
 *   <li>单据内容里的关键字段（单号/HS 编码）—— 因为生成器把压缩级别设为 0
 *       （见 {@link PdfDocumentWriter} 类注释），内容流是明文，可以直接在字节里搜，
 *       不需要额外引入 PDF 解析库；</li>
 *   <li>两类单据内容必须不同（防止模板分支写反）。</li>
 * </ol>
 */
@DisplayName("P1-6 单据 PDF 生成")
class PdfDocumentWriterTest {

    private PdfDocumentWriter writer;
    private OrderRecord order;

    @BeforeEach
    void setUp() {
        writer = new PdfDocumentWriter(new DocumentProperties());
        order = new OrderRecord("AMZ-DE-1001", "Amazon-Mock", "AeroSleep 婴儿床 6 件套",
                "Bruchsal", "PAID");
        order.setErrorTag(null);
    }

    private static String raw(byte[] pdf) {
        // 内容流未压缩，直接按 ISO-8859-1 读字节即可搜 ASCII 文本
        return new String(pdf, StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("面单：是合法 PDF 且含单号、仓库与作业交接栏")
    void shippingLabelContainsOrderFacts() {
        byte[] pdf = writer.write(order, DocumentType.SHIPPING_LABEL);
        String text = raw(pdf);

        assertTrue(text.startsWith("%PDF-"), "PDF 文件头必须正确，否则阅读器直接报损坏");
        assertTrue(text.trim().endsWith("%%EOF"), "PDF 必须以 %%EOF 结束");
        assertTrue(pdf.length > 800, "单据不该是空壳，实测 " + pdf.length + " 字节");
        assertTrue(text.contains("SHIPPING LABEL"), "面单要有明确抬头");
        assertTrue(text.contains("AMZ-DE-1001"), "单号是面单的第一识别信息");
        assertTrue(text.contains("Bruchsal"), "发货仓必须打印（拣货员按仓分单）");
        assertTrue(text.contains("ASLP Fulfillment GmbH"), "发货方信息来自配置");
        assertTrue(text.contains("Picked by"), "作业联要有交接签字栏");
    }

    @Test
    @DisplayName("报关单：含发货方税号、HS 编码与申报价值（缺一项海关就退件）")
    void customsDeclarationContainsDeclarationFacts() {
        byte[] pdf = writer.write(order, DocumentType.CUSTOMS_DECLARATION);
        String text = raw(pdf);

        assertTrue(text.startsWith("%PDF-"));
        assertTrue(text.contains("CUSTOMS DECLARATION"), "报关单要有明确抬头");
        assertTrue(text.contains("DE000000000"), "发货方税号必须打印");
        assertTrue(text.contains("9503.00.99"), "HS 编码必须打印（决定关税归类）");
        assertTrue(text.contains("29.90"), "申报价值必须打印");
        assertTrue(text.contains("AMZ-DE-1001"), "参考号 = 平台单号，便于对账");
    }

    @Test
    @DisplayName("两类单据内容不同（模板分支没写反），且同一订单可重复生成")
    void documentTypesProduceDifferentDocuments() {
        byte[] label = writer.write(order, DocumentType.SHIPPING_LABEL);
        byte[] declaration = writer.write(order, DocumentType.CUSTOMS_DECLARATION);

        assertNotEquals(raw(label).contains("CUSTOMS DECLARATION"),
                raw(declaration).contains("CUSTOMS DECLARATION"), "两类单据必须走不同分支");
        assertTrue(raw(label).length() > 0 && raw(declaration).length() > 0);
    }

    @Test
    @DisplayName("空字段不炸：商品/异常标签为 null 时用占位符渲染")
    void toleratesNullFields() {
        OrderRecord sparse = new OrderRecord("AMZ-EMPTY", "eBay", null, "Bruchsal", "CREATED");
        sparse.setErrorTag(null);

        byte[] pdf = writer.write(sparse, DocumentType.SHIPPING_LABEL);

        assertTrue(raw(pdf).startsWith("%PDF-"));
        assertTrue(raw(pdf).contains("AMZ-EMPTY"));
    }
}
