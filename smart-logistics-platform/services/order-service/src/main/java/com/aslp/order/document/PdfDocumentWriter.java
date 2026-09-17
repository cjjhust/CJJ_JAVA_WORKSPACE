package com.aslp.order.document;

import com.aslp.order.config.DocumentProperties;
import com.aslp.order.entity.OrderRecord;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * P1-6 单据 PDF 生成器（面单 / 报关单）。
 *
 * <p><b>三条刻意的实现选择</b>：
 * <ol>
 *   <li><b>用 OpenPDF（LGPL）而不是 iText 7（AGPL）</b>：AGPL 用在「随货单据」这种
 *       被外部（货代、海关）接触的产物上，会给公司带来不必要的许可证义务；</li>
 *   <li><b>正文只用 ASCII（英文/德文）</b>：Base14 内置字体（Helvetica）没有中文字形，
 *       直接写中文会变成空白/方块。要出中文单据必须内嵌 CJK 字体子集，属后续项；</li>
 *   <li><b>压缩级别设为 0（不压缩内容流）</b>：单据体积本就只有几十 KB，
 *       牺牲一点体积换来「排障时可以直接在文件里搜单号」——出问题时能一眼看出
 *       生成的内容对不对，比省几 KB 重要得多。</li>
 * </ol>
 */
@Component
public class PdfDocumentWriter {

    /** 单据生成时区：仓库在德国，单据时间必须是作业员看到的当地时间。 */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/Berlin");

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ENGLISH);

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
    private static final Font SECTION = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10);
    private static final Font MONO = FontFactory.getFont(FontFactory.COURIER, 10);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 8);
    private static final Font BARCODE = FontFactory.getFont(FontFactory.COURIER_BOLD, 18);

    private final DocumentProperties properties;

    public PdfDocumentWriter(DocumentProperties properties) {
        this.properties = properties;
    }

    /**
     * 生成单据 PDF。
     *
     * @param order 订单（数据来源，必须已落库）
     * @param type  单据类型
     * @return PDF 字节（调用方负责上传）
     */
    public byte[] write(OrderRecord order, DocumentType type) {
        ZonedDateTime now = ZonedDateTime.now(BUSINESS_ZONE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 40, 40, 40, 40);
        try {
            PdfWriter writer = PdfWriter.getInstance(pdf, out);
            // 见类注释第 3 条：不压缩内容流，便于排障时直接搜单号
            writer.setCompressionLevel(0);
            pdf.open();
            switch (type) {
                case SHIPPING_LABEL -> writeShippingLabel(pdf, order, now);
                case CUSTOMS_DECLARATION -> writeCustomsDeclaration(pdf, order, now);
            }
            pdf.add(issuedFooter(type, now));
        } catch (DocumentException e) {
            // PDF 组装失败属程序性问题（不是基础设施问题），直接上抛
            throw new IllegalStateException("单据 PDF 生成失败：" + type + " / " + order.getOrderId(), e);
        } finally {
            pdf.close();
        }
        return out.toByteArray();
    }

    /** 面单（仓内作业联）：随货走，重点是单号与拣货信息必须一眼可见。 */
    private void writeShippingLabel(Document pdf, OrderRecord order, ZonedDateTime now)
            throws DocumentException {

        pdf.add(title("ASLP SHIPPING LABEL (WAREHOUSE COPY)"));
        pdf.add(spacer());

        // 单号区：大号等宽字体 + 框，作业员扫一眼就能核对
        PdfPTable orderBox = new PdfPTable(1);
        orderBox.setWidthPercentage(100);
        PdfPCell orderCell = new PdfPCell(new Phrase("ORDER  " + order.getOrderId(), BARCODE));
        orderCell.setPadding(12);
        orderCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        orderCell.setBackgroundColor(new Color(240, 244, 248));
        orderBox.addCell(orderCell);
        pdf.add(orderBox);
        pdf.add(spacer());

        PdfPTable from = new PdfPTable(2);
        from.setWidthPercentage(100);
        from.setWidths(new float[]{1, 2.4f});
        addRow(from, "SHIP FROM", properties.getShipperName() + "\n" + properties.getShipperAddress(), true);
        addRow(from, "WAREHOUSE", order.getWarehouseCode(), true);
        addRow(from, "DESTINATION", properties.getDestinationCountry()
                + "   (consignee address is on the carrier label)", true);
        pdf.add(from);
        pdf.add(spacer());

        pdf.add(sectionTitle("PICKING DETAILS"));
        PdfPTable detail = new PdfPTable(2);
        detail.setWidthPercentage(100);
        detail.setWidths(new float[]{1, 2.4f});
        addRow(detail, "PRODUCT", safe(order.getProduct()), false);
        addRow(detail, "PLATFORM", safe(order.getPlatform()), false);
        addRow(detail, "STATUS", safe(order.getStatus()), false);
        addRow(detail, "EXCEPTION TAG", order.getErrorTag() == null ? "-" : order.getErrorTag(), false);
        pdf.add(detail);
        pdf.add(spacer());

        Paragraph note = new Paragraph(
                "Handling: place this copy inside the parcel. "
                        + "Carrier label (with consignee address) is printed separately by the carrier integration.",
                SMALL);
        note.setSpacingBefore(4);
        pdf.add(note);

        Paragraph signature = new Paragraph("\nPicked by: ______________________     Date: "
                + now.toLocalDate() + "     Checked by: ______________________", BODY);
        pdf.add(signature);
    }

    /** 报关单（CN22 摘要）：发货方/申报信息来自配置，商品来自订单。 */
    private void writeCustomsDeclaration(Document pdf, OrderRecord order, ZonedDateTime now)
            throws DocumentException {

        pdf.add(title("CUSTOMS DECLARATION / CN22 (SUMMARY)"));
        pdf.add(spacer());

        PdfPTable from = new PdfPTable(2);
        from.setWidthPercentage(100);
        from.setWidths(new float[]{1, 2.4f});
        addRow(from, "SENDER", properties.getShipperName() + "\n" + properties.getShipperAddress(), true);
        addRow(from, "VAT / TAX ID", properties.getShipperTaxId(), true);
        addRow(from, "DESTINATION", properties.getDestinationCountry(), true);
        addRow(from, "REFERENCE", order.getOrderId(), true);
        pdf.add(from);
        pdf.add(spacer());

        pdf.add(sectionTitle("CONTENTS"));
        PdfPTable contents = new PdfPTable(4);
        contents.setWidthPercentage(100);
        contents.setWidths(new float[]{2.6f, 2f, 1f, 1f});
        headerRow(contents, "DESCRIPTION", "HS CODE", "QTY", "VALUE (" + properties.getCurrency() + ")");
        contents.addCell(bodyCell(safe(order.getProduct())));
        contents.addCell(bodyCell(properties.getHsCode()));
        contents.addCell(bodyCell("1"));
        contents.addCell(bodyCell(properties.getDeclaredValueEur()));
        pdf.add(contents);
        pdf.add(spacer());

        Paragraph declaration = new Paragraph(
                "I hereby certify that the information above is correct and complete, "
                        + "and that the contents of this consignment are as stated. "
                        + "Goods are of German origin, intended for commercial resale.",
                BODY);
        pdf.add(declaration);

        Paragraph signature = new Paragraph("\n\nPlace and date: Bruchsal, " + now.toLocalDate()
                + "\n\nSignature: ______________________________", BODY);
        pdf.add(signature);
    }

    private Paragraph title(String text) {
        Paragraph paragraph = new Paragraph(text, TITLE);
        paragraph.setAlignment(Element.ALIGN_CENTER);
        paragraph.setSpacingAfter(10);
        return paragraph;
    }

    private Paragraph sectionTitle(String text) {
        Paragraph paragraph = new Paragraph(text, SECTION);
        paragraph.setSpacingAfter(6);
        return paragraph;
    }

    private Paragraph issuedFooter(DocumentType type, ZonedDateTime now) {
        Paragraph footer = new Paragraph(
                "\nGenerated by ASLP (" + type.slug() + ") at " + now.format(TIMESTAMP)
                        + " - demo document, not a legally valid transport document.", SMALL);
        footer.setAlignment(Element.ALIGN_CENTER);
        return footer;
    }

    private static Paragraph spacer() {
        return new Paragraph(" ", SMALL);
    }

    private static void headerRow(PdfPTable table, String... headers) {
        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(header, SECTION));
            cell.setPadding(6);
            cell.setBackgroundColor(new Color(238, 243, 248));
            table.addCell(cell);
        }
    }

    private static PdfPCell bodyCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, BODY));
        cell.setPadding(6);
        return cell;
    }

    private static void addRow(PdfPTable table, String label, String value, boolean bold) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, SECTION));
        labelCell.setPadding(6);
        labelCell.setBackgroundColor(new Color(246, 248, 250));
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, bold ? MONO : BODY));
        valueCell.setPadding(6);
        table.addCell(valueCell);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
