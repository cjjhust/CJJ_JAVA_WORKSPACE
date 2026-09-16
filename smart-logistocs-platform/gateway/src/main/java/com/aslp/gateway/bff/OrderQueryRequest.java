package com.aslp.gateway.bff;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public class OrderQueryRequest {

    @NotBlank(message = "状态不能为空")
    private String status;

    @Min(value = 1, message = "页码最小为 1")
    private int page = 1;

    @Min(value = 1, message = "每页最小为 1")
    private int size = 10;

    private String warehouseCode;

    // getters / setters
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
    public String getWarehouseCode() { return warehouseCode; }
    public void setWarehouseCode(String warehouseCode) { this.warehouseCode = warehouseCode; }
}
