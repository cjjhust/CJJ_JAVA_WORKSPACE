package com.aslp.inventory.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * M2 补货邮件组装测试：断言「发什么内容」而不是「怎么发」。
 *
 * <p>收件人固定为脱敏地址 {@code warehouse-manager@aslp.internal}（保留域 .internal 不可投递），
 * 详见 readme §12 命名与脱敏约定。
 */
@ExtendWith(MockitoExtension.class)
class ReplenishmentMailServiceTest {

    /** 脱敏收件人（采购主管）。 */
    private static final String EXPECTED_RECIPIENT = "warehouse-manager@aslp.internal";

    @Mock
    private JavaMailSender mailSender;

    private ReplenishmentMailService service;

    private SimpleMailMessage captureSentMessage() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("邮件主题包含 SKU 与仓库，正文包含当前库存数量")
    void buildsSubjectAndBodyFromArguments() {
        service = new ReplenishmentMailService(mailSender);

        service.sendReplenishmentSuggestion("AMZ-9999", "Mönchengladbach", 5);

        SimpleMailMessage message = captureSentMessage();
        assertArrayEquals(new String[]{EXPECTED_RECIPIENT}, message.getTo());
        assertTrue(message.getSubject().contains("AMZ-9999"), "主题需带 SKU 便于检索");
        assertTrue(message.getSubject().contains("Mönchengladbach"), "主题需带仓库便于分派");
        assertTrue(message.getText().contains("当前库存：5"), "正文需带当前库存作为补货依据");
    }

    @Test
    @DisplayName("不同 SKU 的邮件内容互不串味（无共享可变状态）")
    void messagesAreIndependentPerCall() {
        service = new ReplenishmentMailService(mailSender);

        service.sendReplenishmentSuggestion("AMZ-1002", "Bruchsal", 28);
        service.sendReplenishmentSuggestion("EBAY-2001", "Mönchengladbach", 64);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, org.mockito.Mockito.times(2)).send(captor.capture());
        assertTrue(captor.getAllValues().get(0).getSubject().contains("AMZ-1002"));
        assertTrue(captor.getAllValues().get(1).getSubject().contains("EBAY-2001"));
    }
}
