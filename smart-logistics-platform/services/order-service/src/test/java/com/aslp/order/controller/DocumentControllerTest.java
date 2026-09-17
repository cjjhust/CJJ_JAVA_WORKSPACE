package com.aslp.order.controller;

import com.aslp.order.document.DocumentNotFoundException;
import com.aslp.order.document.DocumentStorageException;
import com.aslp.order.document.DocumentType;
import com.aslp.order.service.DocumentStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1-6 单据接口的<b>状态码契约</b>测试。
 *
 * <p>文件类接口最容易做错的就是状态码：一律 200 + success=false 会让前端无法区分
 * 「还没生成」（可引导用户点击生成）与「存储挂了」（该提示稍后重试）。
 * 本测试把这四种语义钉死：200 / 400 / 404 / 503。
 */
@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 fake".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentStorageService storage;

    private static DocumentStorageService.StoredDocument stored() {
        return new DocumentStorageService.StoredDocument("AMZ-DE-1001", DocumentType.SHIPPING_LABEL,
                "orders/AMZ-DE-1001/shipping-label-20260917T101500Z.pdf", "application/pdf",
                1234L, "a".repeat(64), Instant.parse("2026-09-17T10:15:00Z"),
                "http://localhost:9000/aslp-documents/x?signature=y");
    }

    @Test
    @DisplayName("生成：返回对象键、sha256 与预签名 URL（200）")
    void generateReturnsStoredMetadata() throws Exception {
        when(storage.store("AMZ-DE-1001", DocumentType.SHIPPING_LABEL)).thenReturn(stored());

        mockMvc.perform(post("/api/orders/AMZ-DE-1001/documents/shipping-label"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectKey").value("orders/AMZ-DE-1001/shipping-label-20260917T101500Z.pdf"))
                .andExpect(jsonPath("$.type").value("shipping-label"))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.presignedUrl").value("http://localhost:9000/aslp-documents/x?signature=y"))
                .andExpect(jsonPath("$.downloadUrl").value("/api/orders/AMZ-DE-1001/documents/shipping-label"));
    }

    @Test
    @DisplayName("类型可大小写混写：CUSTOMS_DECLARATION 与 customs-declaration 等价")
    void acceptsBothTypeSpellings() throws Exception {
        when(storage.store(eq("AMZ-DE-1001"), eq(DocumentType.CUSTOMS_DECLARATION))).thenReturn(
                new DocumentStorageService.StoredDocument("AMZ-DE-1001", DocumentType.CUSTOMS_DECLARATION,
                        "orders/AMZ-DE-1001/customs-declaration-x.pdf", "application/pdf", 900L,
                        "b".repeat(64), Instant.now(), "http://localhost:9000/signed"));

        mockMvc.perform(post("/api/orders/AMZ-DE-1001/documents/CUSTOMS_DECLARATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("customs-declaration"));
    }

    @Test
    @DisplayName("下载：Content-Type 为 application/pdf 且正文是 PDF 字节")
    void downloadReturnsPdfBytes() throws Exception {
        when(storage.load("AMZ-DE-1001", DocumentType.SHIPPING_LABEL)).thenReturn(
                new DocumentStorageService.DownloadedDocument(
                        "orders/AMZ-DE-1001/shipping-label-20260917T101500Z.pdf", "application/pdf", PDF_BYTES));

        mockMvc.perform(get("/api/orders/AMZ-DE-1001/documents/shipping-label"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(PDF_BYTES))
                .andExpect(header().string("Content-Disposition",
                        "inline; filename=\"shipping-label-20260917T101500Z.pdf\""));
    }

    @Test
    @DisplayName("列举：返回该订单的全部单据（含历史版本）")
    void listReturnsDocuments() throws Exception {
        when(storage.list("AMZ-DE-1001")).thenReturn(List.of(stored()));

        mockMvc.perform(get("/api/orders/AMZ-DE-1001/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.documents[0].typeLabel").value("面单（仓内作业联）"));
    }

    @Test
    @DisplayName("类型不在白名单：400 并回显支持的类型（不让垃圾前缀写进桶里）")
    void rejectsUnknownType() throws Exception {
        mockMvc.perform(post("/api/orders/AMZ-DE-1001/documents/packing-slip"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.supportedTypes").exists());
    }

    @Test
    @DisplayName("单据未生成：404（正常业务状态，前端据此引导用户点击生成）")
    void returnsNotFoundWhenDocumentMissing() throws Exception {
        when(storage.load(any(), any())).thenThrow(new DocumentNotFoundException("该订单还没有生成「面单」"));

        mockMvc.perform(get("/api/orders/AMZ-DE-1001/documents/shipping-label"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("对象存储不可用：503（依赖故障可重试，不是 500）")
    void returnsServiceUnavailableWhenStorageDown() throws Exception {
        when(storage.store(any(), any())).thenThrow(
                new DocumentStorageException("单据上传失败（桶 aslp-documents）：connection refused", null));

        mockMvc.perform(post("/api/orders/AMZ-DE-1001/documents/shipping-label"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("aslp-documents")));
    }
}
