package com.aslp.order.controller;

import com.aslp.order.document.DocumentNotFoundException;
import com.aslp.order.document.DocumentStorageException;
import com.aslp.order.document.DocumentType;
import com.aslp.order.service.DocumentStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1-6 单据接口：面单 / 报关单的生成、下载与列举。
 *
 * <p>路径挂在 {@code /api/orders/{orderId}/documents} 下（跟随既有订单路由，
 * 网关无需新增规则）。
 *
 * <p><b>状态码约定</b>（比「一律 200 + success=false」更适合文件类接口）：
 * <ul>
 *   <li>200 生成/下载/列举成功；</li>
 *   <li>404 订单不存在，或该订单还没生成过这类单据（
 *       见 {@link DocumentNotFoundException} —— 这是正常业务状态，要能被前端区分）；</li>
 *   <li>400 单据类型写错（白名单外的 type 直接拒绝，不往桶里写垃圾前缀）；</li>
 *   <li>503 对象存储不可用（依赖故障，可重试）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/orders/{orderId}/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentStorageService storage;

    public DocumentController(DocumentStorageService storage) {
        this.storage = storage;
    }

    /** 生成并存档一份单据（可重复调用：每次生成一个新版本并留痕）。 */
    @PostMapping("/{type}")
    public ResponseEntity<Map<String, Object>> generate(@PathVariable String orderId,
                                                        @PathVariable String type) {
        DocumentType documentType = DocumentType.parse(type);
        DocumentStorageService.StoredDocument document = storage.store(orderId, documentType);
        return ResponseEntity.ok(toBody(document));
    }

    /** 下载最新一版单据（直接给 PDF 字节流，便于浏览器/作业终端直接打印）。 */
    @GetMapping("/{type}")
    public ResponseEntity<byte[]> download(@PathVariable String orderId, @PathVariable String type) {
        DocumentType documentType = DocumentType.parse(type);
        DocumentStorageService.DownloadedDocument document = storage.load(orderId, documentType);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + fileName(document.objectKey()) + "\"")
                .body(document.content());
    }

    /** 列出该订单的全部单据（含历史版本）。 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@PathVariable String orderId) {
        List<DocumentStorageService.StoredDocument> documents = storage.list(orderId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("count", documents.size());
        body.put("types", List.of(DocumentType.names()));
        body.put("documents", documents.stream().map(DocumentController::toBody).toList());
        return ResponseEntity.ok(body);
    }

    /** 400：单据类型不在白名单内。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        log.warn("[单据] 入参被拒：{}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "BAD_REQUEST",
                "reason", e.getMessage(),
                "supportedTypes", DocumentType.names()));
    }

    /** 404：订单不存在或尚未生成该类单据。 */
    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(DocumentNotFoundException e) {
        return ResponseEntity.status(404).body(Map.of(
                "error", "DOCUMENT_NOT_FOUND",
                "reason", e.getMessage()));
    }

    /** 503：对象存储不可用（可重试）。 */
    @ExceptionHandler(DocumentStorageException.class)
    public ResponseEntity<Map<String, Object>> storageUnavailable(DocumentStorageException e) {
        log.error("[单据] 对象存储不可用：{}", e.getMessage());
        return ResponseEntity.status(503).body(Map.of(
                "error", "STORAGE_UNAVAILABLE",
                "reason", e.getMessage()));
    }

    private static Map<String, Object> toBody(DocumentStorageService.StoredDocument document) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", document.orderId());
        body.put("type", document.type().slug());
        body.put("typeLabel", document.type().label());
        body.put("objectKey", document.objectKey());
        body.put("contentType", document.contentType());
        body.put("size", document.size());
        body.put("sha256", document.sha256());
        body.put("storedAt", document.storedAt().toString());
        body.put("downloadUrl", "/api/orders/" + document.orderId() + "/documents/" + document.type().slug());
        body.put("presignedUrl", document.presignedUrl());
        return body;
    }

    private static String fileName(String objectKey) {
        int slash = objectKey.lastIndexOf('/');
        return slash < 0 ? objectKey : objectKey.substring(slash + 1);
    }
}
