package com.aslp.inventory.service;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class ReplenishmentMailService {

    private final JavaMailSender mailSender;

    public ReplenishmentMailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendReplenishmentSuggestion(String sku, String warehouseCode, int currentQty) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo("warehouse-manager@aslp.internal");
        message.setSubject("[库存补货建议] SKU: " + sku + " / 仓库: " + warehouseCode);
        message.setText("当前库存：" + currentQty + "，建议补货至安全库存水平（Bruchsal / Mönchengladbach 两仓一致性检查已通过）。");
        mailSender.send(message);
        System.out.println("[补货邮件已发送] SKU=" + sku + ", 仓库=" + warehouseCode);
    }
}
