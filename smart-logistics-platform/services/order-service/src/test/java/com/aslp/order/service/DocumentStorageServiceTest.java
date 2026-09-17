package com.aslp.order.service;

import com.aslp.order.config.DocumentProperties;
import com.aslp.order.config.MinioProperties;
import com.aslp.order.document.DocumentNotFoundException;
import com.aslp.order.document.DocumentStorageException;
import com.aslp.order.document.DocumentType;
import com.aslp.order.document.PdfDocumentWriter;
import com.aslp.order.entity.OrderRecord;
import com.aslp.order.repository.OrderRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-6 单据存储服务测试（Mock 掉 MinIO 客户端，只锁「我们的契约」）。
 *
 * <p>覆盖三件事：
 * <ol>
 *   <li><b>对象键命名</b>：{@code orders/{orderId}/{kind}-{时间戳}.pdf} —— 前缀决定
 *       能否按订单整体归档；</li>
 *   <li><b>异常分类</b>：订单不存在 → 404 语义（{@link DocumentNotFoundException}）；
 *       存储不可用 → 503 语义（{@link DocumentStorageException}）。两者混在一起，
 *       运维就分不清「该等恢复」还是「该去建单」；</li>
 *   <li><b>元数据</b>：sha256 随对象一起存（单据是对外凭证，要能事后校验一致性），
 *       且预签名 URL 必须用<b>对外端点</b>的客户端生成。</li>
 * </ol>
 *
 * <p>下载成功路径（getObject 的字节流）不在单测里造 —— {@code GetObjectResponse} 只能
 * 由 SDK 内部构造，硬造等于测 Mockito。该路径由容器端到端断言覆盖
 * （{@code GET .../documents/shipping-label} 的正文以 {@code %PDF-} 开头）。
 */
@ExtendWith(MockitoExtension.class)
class DocumentStorageServiceTest {

    @Mock
    private MinioClient minioClient;

    @Mock
    private MinioClient presignClient;

    @Mock
    private OrderRepository orderRepository;

    private MinioProperties minioProperties;
    private DocumentStorageService service;

    @BeforeEach
    void setUp() {
        minioProperties = new MinioProperties();
        minioProperties.setBucket("aslp-documents");
        service = new DocumentStorageService(minioClient, presignClient, minioProperties,
                new PdfDocumentWriter(new DocumentProperties()), orderRepository);
    }

    private OrderRecord persistedOrder() {
        return new OrderRecord("AMZ-DE-1001", "Amazon-Mock", "AeroSleep 婴儿床",
                "Bruchsal", "PAID");
    }

    @Test
    @DisplayName("生成并上传：对象键按订单分组并带时间戳，元数据含 sha256")
    void storesDocumentWithHashedMetadata() throws Exception {
        when(orderRepository.findByOrderId("AMZ-DE-1001")).thenReturn(Optional.of(persistedOrder()));
        when(presignClient.getPresignedObjectUrl(any())).thenReturn("http://localhost:9000/aslp-documents/x?signature=y");

        DocumentStorageService.StoredDocument stored =
                service.store("AMZ-DE-1001", DocumentType.SHIPPING_LABEL);

        assertEquals("AMZ-DE-1001", stored.orderId());
        assertEquals(DocumentType.SHIPPING_LABEL, stored.type());
        assertTrue(stored.objectKey().startsWith("orders/AMZ-DE-1001/shipping-label-"),
                "对象键必须按订单分组 + 带类型与时间戳，实际：" + stored.objectKey());
        assertTrue(stored.objectKey().endsWith(".pdf"));
        assertEquals("application/pdf", stored.contentType());
        assertTrue(stored.size() > 800, "上传的是真实 PDF 字节，不是空壳");
        assertEquals(64, stored.sha256().length(), "sha256 十六进制串长度固定 64");
        assertEquals("http://localhost:9000/aslp-documents/x?signature=y", stored.presignedUrl());
        assertNotNull(stored.storedAt());

        // 关键参数：桶名 / 内容类型 / 元数据
        org.mockito.ArgumentCaptor<PutObjectArgs> captor =
                org.mockito.ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());
        PutObjectArgs args = captor.getValue();
        assertEquals("aslp-documents", args.bucket());
        assertEquals(stored.objectKey(), args.object());
        assertEquals("application/pdf", args.contentType());
        // 注意：userMetadata() 的键已带 x-amz-meta- 前缀（MinIO SDK 在 build 时加上的，
        // 实测：{x-amz-meta-sha256=[...]}），写断言时别再手动拼一次
        assertTrue(args.userMetadata().get("x-amz-meta-sha256").contains(stored.sha256()),
                "sha256 必须随对象一起存（单据是对外凭证，事后要能校验一致性），实际=" + args.userMetadata());
        assertTrue(args.userMetadata().get("x-amz-meta-order-id").contains("AMZ-DE-1001"));
        assertTrue(args.userMetadata().get("x-amz-meta-document-type").contains("shipping-label"));
    }

    @Test
    @DisplayName("订单不存在：抛「单据未找到」（404 语义），不加存储调用")
    void rejectsUnknownOrder() {
        when(orderRepository.findByOrderId("NOPE")).thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class,
                () -> service.store("NOPE", DocumentType.SHIPPING_LABEL));
    }

    @Test
    @DisplayName("对象存储不可用：抛「存储不可用」（503 语义），与业务性 404 分开")
    void mapsStorageFailureToStorageException() throws Exception {
        when(orderRepository.findByOrderId("AMZ-DE-1001")).thenReturn(Optional.of(persistedOrder()));
        when(minioClient.putObject(any(PutObjectArgs.class)))
                .thenThrow(new java.io.IOException("connection refused"));

        DocumentStorageException exception = assertThrows(DocumentStorageException.class,
                () -> service.store("AMZ-DE-1001", DocumentType.SHIPPING_LABEL));

        assertTrue(exception.getMessage().contains("aslp-documents"), "错误信息要带桶名，便于定位配置串了");
    }

    @Test
    @DisplayName("下载未生成过的单据：抛 404 语义并在提示里给出生成方式")
    void reportsMissingDocumentOnDownload() {
        when(minioClient.listObjects(any(io.minio.ListObjectsArgs.class))).thenReturn(List.of());

        DocumentNotFoundException exception = assertThrows(DocumentNotFoundException.class,
                () -> service.load("AMZ-DE-1002", DocumentType.CUSTOMS_DECLARATION));

        assertTrue(exception.getMessage().contains("报关单"));
    }

    @Test
    @DisplayName("列举：先校验订单存在，再按订单前缀查询")
    void listsDocumentsForExistingOrder() throws Exception {
        when(orderRepository.findByOrderId("AMZ-DE-1001")).thenReturn(Optional.of(persistedOrder()));
        when(minioClient.listObjects(any(io.minio.ListObjectsArgs.class))).thenReturn(List.of());

        List<DocumentStorageService.StoredDocument> documents = service.list("AMZ-DE-1001");

        assertTrue(documents.isEmpty(), "还没生成过就是空列表（不是 404）");
    }

    @Test
    @DisplayName("对象键：ISO-8601 基本格式时间戳 → 字典序即时间序（「最新一版」可直接取键最大者）")
    void objectKeyTimestampIsLexicographicallyOrdered() {
        String first = DocumentStorageService.buildObjectKey("AMZ-1", DocumentType.SHIPPING_LABEL);
        String second = DocumentStorageService.buildObjectKey("AMZ-1", DocumentType.SHIPPING_LABEL);

        assertTrue(first.endsWith("Z.pdf"), "实际：" + first);
        assertTrue(first.compareTo(second) <= 0, "同一毫秒内生成的键也必须保持可比（字典序 == 时间序）");
        assertTrue(first.startsWith("orders/AMZ-1/shipping-label-"));
    }

    @Test
    @DisplayName("预签名 URL 用「对外端点」的客户端生成（签名覆盖 Host，不能拿内网端点签）")
    void presignUsesPublicEndpointClient() throws Exception {
        when(presignClient.getPresignedObjectUrl(any())).thenReturn("http://localhost:9000/signed");

        service.presignedUrl("orders/AMZ-1/shipping-label-x.pdf");

        org.mockito.ArgumentCaptor<io.minio.GetPresignedObjectUrlArgs> captor =
                org.mockito.ArgumentCaptor.forClass(io.minio.GetPresignedObjectUrlArgs.class);
        verify(presignClient).getPresignedObjectUrl(captor.capture());
        verify(minioClient, org.mockito.Mockito.never()).getPresignedObjectUrl(any());
        assertEquals(Method.GET, captor.getValue().method());
        assertEquals("aslp-documents", captor.getValue().bucket());
        assertEquals(900, captor.getValue().expiry(), "15 分钟 = 900 秒");
    }
}
