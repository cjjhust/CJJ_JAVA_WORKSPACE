package com.aslp.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P1-6：单据抬头与申报参数（前缀 {@code aslp.document}）。
 *
 * <p>面单/报关单上的「发货方、申报价值、HS 编码」是<b>公司口径</b>，
 * 不是代码逻辑 —— 换抬头、调申报价值不应该重新构建镜像。默认值全部是可公开的演示值。
 *
 * <p>注意：单据正文用<b>英文/德文</b>。原因不是偏好，而是 PDF 内置字体（Helvetica 等
 * Base14 字体）不含中文字形 —— 直接写中文会渲染成空白或方块。
 * 要出中文单据必须内嵌 CJK 字体（Noto Sans CJK 子集），属后续项（见 readme §10 限制）。
 */
@ConfigurationProperties(prefix = "aslp.document")
public class DocumentProperties {

    /** 发货方名称（报关单必需）。 */
    private String shipperName = "ASLP Fulfillment GmbH (DEMO)";

    /** 发货方地址。 */
    private String shipperAddress = "Industriestrasse 12, 76646 Bruchsal, Germany";

    /** 发货方税号 / VAT（演示值，非真实主体）。 */
    private String shipperTaxId = "DE000000000";

    /** 默认目的国（真实业务应来自订单收货地址；当前项目未持久化该字段，见 readme §10）。 */
    private String destinationCountry = "DE";

    /** 单件申报价值（EUR，演示值）。 */
    private String declaredValueEur = "29.90";

    /** 默认 HS 编码（演示值：其他玩具类）。 */
    private String hsCode = "9503.00.99";

    /** 币种。 */
    private String currency = "EUR";

    public String getShipperName() {
        return shipperName;
    }

    public void setShipperName(String shipperName) {
        this.shipperName = shipperName;
    }

    public String getShipperAddress() {
        return shipperAddress;
    }

    public void setShipperAddress(String shipperAddress) {
        this.shipperAddress = shipperAddress;
    }

    public String getShipperTaxId() {
        return shipperTaxId;
    }

    public void setShipperTaxId(String shipperTaxId) {
        this.shipperTaxId = shipperTaxId;
    }

    public String getDestinationCountry() {
        return destinationCountry;
    }

    public void setDestinationCountry(String destinationCountry) {
        this.destinationCountry = destinationCountry;
    }

    public String getDeclaredValueEur() {
        return declaredValueEur;
    }

    public void setDeclaredValueEur(String declaredValueEur) {
        this.declaredValueEur = declaredValueEur;
    }

    public String getHsCode() {
        return hsCode;
    }

    public void setHsCode(String hsCode) {
        this.hsCode = hsCode;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
