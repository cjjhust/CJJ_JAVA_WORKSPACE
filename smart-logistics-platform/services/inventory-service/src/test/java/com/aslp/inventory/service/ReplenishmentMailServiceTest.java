package com.aslp.inventory.service;

import com.aslp.inventory.config.MailTemplateConfig;
import com.aslp.inventory.config.WarningProperties;
import com.aslp.inventory.entity.InventoryItem;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P1-5 补货邮件测试：用<b>真实 Thymeleaf 引擎</b>渲染模板，断言「邮件里到底有什么」。
 *
 * <p>刻意不用 Mock 的模板引擎：模板（HTML 结构 + 变量名）正是本次要交付的东西，
 * 把它 Mock 掉就只测试了「我们调用了 process()」，等于没测。这里从 classpath 加载
 * {@code templates/email/replenishment.html}，任何拼错的变量名都会让渲染抛异常。
 *
 * <p><b>断言前必须往返一次序列化</b>（{@code writeTo()} → 重新解析）：拿未发送的
 * {@code MimeMessage} 对象去读 {@code getContentType()} / {@code getContent()} 会得到
 * 误导结果（实测：正文其实是 multipart/alternative 两份，但未序列化时读到的是
 * {@code text/plain} 且 count=1）—— 因为 MIME 头的真实形态在写信时（writeTo）才确定。
 * SMTP 服务器与收件人看到的是序列化后的字节，测试就必须按这个口径断言。
 *
 * <p>收件人固定为脱敏地址 {@code warehouse-manager@aslp.internal}（保留域 .internal
 * 不可投递），详见 readme §12 命名与脱敏约定。
 */
@ExtendWith(MockitoExtension.class)
class ReplenishmentMailServiceTest {

    /** 脱敏收件人（采购主管）。 */
    private static final String EXPECTED_RECIPIENT = "warehouse-manager@aslp.internal";

    @Mock
    private JavaMailSender mailSender;

    private WarningProperties properties;
    private ReplenishmentMailService service;

    @BeforeEach
    void setUp() {
        properties = new WarningProperties();
        properties.setThreshold(10);
        properties.setTargetMultiplier(2);
        service = new ReplenishmentMailService(mailSender, new MailTemplateConfig().mailTemplateEngine(),
                properties);
    }

    private static InventoryItem lowStock(String sku, String warehouse, int available, int locked) {
        InventoryItem item = new InventoryItem();
        item.setSku(sku);
        item.setWarehouseCode(warehouse);
        item.setAvailableQty(available);
        item.setLockedQty(locked);
        return item;
    }

    private List<InventoryItem> twoLowStockItems() {
        return List.of(
                lowStock("AMZ-9999", "Mönchengladbach", 3, 1),
                lowStock("EBAY-2001", "Bruchsal", 9, 0));
    }

    // ------------------------------------------------------------ 模板渲染

    @Test
    @DisplayName("HTML 正文：表格承载 SKU/仓库/库存/建议补货量，且样式行内化")
    void rendersHtmlWithItemTable() {
        String html = service.renderHtml(service.toItems(twoLowStockItems()));

        assertTrue(html.contains("<table"), "邮件正文必须是表格布局（邮件客户端对现代 CSS 支持极差）");
        assertTrue(html.contains("AMZ-9999") && html.contains("EBAY-2001"), "两个 SKU 都要出现");
        assertTrue(html.contains("Mönchengladbach") && html.contains("Bruchsal"), "仓库要出现");
        assertTrue(html.contains("style=\""), "样式必须行内化，否则会被邮件客户端剥掉");
        assertTrue(html.contains(EXPECTED_RECIPIENT), "页脚要带收件人，便于转派时核对");
        assertTrue(html.contains(">17<"), "AMZ-9999 建议补货 20-3=17 应出现在单元格里");
        assertTrue(html.contains(">11<"), "EBAY-2001 建议补货 20-9=11");
    }

    @Test
    @DisplayName("纯文本正文：与 HTML 同源同事实（老客户端兜底不丢信息）")
    void rendersPlainTextWithSameFacts() {
        String text = service.buildPlainText(service.toItems(twoLowStockItems()));

        assertTrue(text.contains("ASLP 库存补货建议"));
        assertTrue(text.contains("AMZ-9999@Mönchengladbach"));
        assertTrue(text.contains("[告急]"), "告急行要有醒目标记");
        assertTrue(text.contains("建议补货 17"));
        assertFalse(text.contains("<table"), "纯文本里不能混入 HTML 标签");
    }

    @Test
    @DisplayName("主题：单个 SKU 带单号与仓库（可直接分派），多个给数量与告急数")
    void buildsSubjectForSingleAndMultipleItems() {
        List<ReplenishmentMailService.ReplenishmentItem> single =
                service.toItems(List.of(lowStock("AMZ-9999", "Mönchengladbach", 5, 0)));
        String singleSubject = service.buildSubject(single);
        assertTrue(singleSubject.contains("AMZ-9999"), "单条时主题必须带单号，便于直接分派");
        assertTrue(singleSubject.contains("Mönchengladbach"));
        assertTrue(singleSubject.startsWith("[库存补货建议]"));

        String multiSubject = service.buildSubject(service.toItems(twoLowStockItems()));
        assertTrue(multiSubject.contains("2 个 SKU"));
        assertTrue(multiSubject.contains("告急 1 个"), "只有 AMZ-9999 低于阈值一半");
    }

    @Test
    @DisplayName("告急标记：低于阈值一半（5）才算告急，等于一半不算")
    void marksCriticalOnlyBelowHalfOfThreshold() {
        List<ReplenishmentMailService.ReplenishmentItem> items =
                service.toItems(List.of(lowStock("A", "Bruchsal", 4, 0), lowStock("B", "Bruchsal", 5, 0)));

        assertEquals(20 - 4, items.get(0).suggestedQty());
        assertTrue(items.get(0).critical(), "4 < 10/2=5 → 告急");
        assertEquals(20 - 5, items.get(1).suggestedQty());
        assertFalse(items.get(1).critical(), "5 不在「低于 5」的范围内");
    }

    // ------------------------------------------------------------ 真实发信

    @Test
    @DisplayName("发送：正文同时含纯文本与 HTML 两份（multipart/alternative）")
    void sendsMultipartAlternativeMessage() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);

        boolean sent = service.sendReplenishmentDigest(twoLowStockItems());

        assertTrue(sent);
        verify(mailSender).send(message);
        assertTrue(message.getAllRecipients()[0].toString().contains(EXPECTED_RECIPIENT));
        assertTrue(message.getSubject().contains("2 个 SKU"));

        // 按「收件人会收到什么」断言：序列化 → 重新解析（见类注释）
        byte[] raw = serialize(message);
        String mimeHeaders = contentTypeHeaders(raw);
        assertTrue(mimeHeaders.contains("multipart/alternative"),
                "必须声明 alternative（客户端在纯文本/HTML 中二选一），实际 MIME：\n" + mimeHeaders);
        assertTrue(mimeHeaders.contains("text/html"), "HTML 正文要作为独立 part 存在");
        assertTrue(mimeHeaders.contains("text/plain"), "纯文本兜底（老客户端/网关只认它）");

        List<String> bodies = textBodies(new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(raw)).getContent());
        assertEquals(2, bodies.size(), "应该正好两份正文（纯文本 + HTML），实际 " + bodies.size() + " 份");
        assertTrue(bodies.stream().anyMatch(body -> body.contains("AMZ-9999@Mönchengladbach")),
                "纯文本里要有可读的明细");
        assertTrue(bodies.stream().anyMatch(body -> body.contains("<table")),
                "HTML 里要有表格布局");
    }

    /** 序列化为字节 = SMTP 服务器与收件人看到的形态。 */
    private static byte[] serialize(MimeMessage message) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return out.toByteArray();
    }

    /** 只取各层 Content-Type 头（断言失败时一眼看出结构）。 */
    private static String contentTypeHeaders(byte[] raw) {
        return new String(raw, StandardCharsets.UTF_8).lines()
                .filter(line -> line.toLowerCase().startsWith("content-type"))
                .reduce("", (a, b) -> a + b + "\n");
    }

    /** 递归收集 MIME 树里的文本正文（与具体嵌套层数无关）。 */
    private static List<String> textBodies(Object content) throws Exception {
        List<String> bodies = new ArrayList<>();
        if (content instanceof MimeMultipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                bodies.addAll(textBodies(multipart.getBodyPart(i).getContent()));
            }
        } else if (content instanceof String text) {
            bodies.add(text);
        }
        return bodies;
    }

    @Test
    @DisplayName("SMTP 故障：吞掉异常并返回 false（邮件发不出去不能带崩预警链路）")
    void swallowsSmtpFailure() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("SMTP 未就绪")).when(mailSender).send(any(MimeMessage.class));

        assertFalse(service.sendReplenishmentDigest(twoLowStockItems()));
    }

    @Test
    @DisplayName("空清单：不发信也不碰 SMTP（避免发一封空邮件）")
    void skipsEmptyDigest() {
        assertFalse(service.sendReplenishmentDigest(List.of()));

        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("收件人未配置：不发信（不把邮件发到空地址）")
    void skipsWhenRecipientMissing() {
        properties.setRecipient(" ");

        assertFalse(service.sendReplenishmentDigest(twoLowStockItems()));

        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
