package com.aslp.order.service;

import com.aslp.order.entity.OrderRecord;
import com.aslp.order.repository.OrderRepository;
import com.aslp.order.strategy.OrderDto;
import com.aslp.order.strategy.OrderPullResult;
import com.aslp.order.strategy.OrderPullStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M1 订单统一接入服务单元测试（不依赖数据库）。
 *
 * <p>覆盖三个关键行为：
 * <ol>
 *   <li>首次拉取：新增落库 + 异常打标计数 + 仓库编码归一化</li>
 *   <li>重复拉取：以 orderId 幂等，只更新不新增（防漏单同时防重复）</li>
 *   <li>平台拉取失败：整体标记失败且不写库</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class OrderPullServiceTest {

    @Mock
    private OrderRepository repository;

    private OrderPullService service;

    /** 可替换返回值的测试用策略。 */
    private static class StubStrategy implements OrderPullStrategy {
        private final OrderPullResult result;

        StubStrategy(OrderPullResult result) {
            this.result = result;
        }

        @Override
        public String getPlatformName() {
            return result.platform();
        }

        @Override
        public OrderPullResult pullOrders() {
            return result;
        }
    }

    private static final OrderPullResult MOCK_RESULT = new OrderPullResult(
            "Amazon-Mock",
            List.of(
                    new OrderDto("AMZ-1001", "奶粉一件代发", "Bruchsal", "PAID", null),
                    new OrderDto("AMZ-1002", "大件中转-婴儿推车", "Moenchengladbach", "PAID", null),
                    new OrderDto("AMZ-1003", "逆向退货换标", "Bruchsal", "CREATED", "ADDRESS_INVALID")
            ),
            true,
            null);

    @BeforeEach
    void setUp() {
        service = new OrderPullService(new StubStrategy(MOCK_RESULT), repository);
    }

    @Test
    void firstPullCreatesRecordsAndFlagsAbnormalOnes() {
        // 全部订单都尚不存在
        lenient().when(repository.findByOrderId(anyString())).thenReturn(Optional.empty());
        lenient().when(repository.save(any(OrderRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderPullService.PullSummary summary = service.pullAndPersist();

        assertTrue(summary.success());
        assertEquals(3, summary.fetched());
        assertEquals(3, summary.created(), "首次拉取应全部新增");
        assertEquals(0, summary.updated());
        assertEquals(1, summary.flagged(), "AMZ-1003 带 ADDRESS_INVALID，应计入异常打标");
        verify(repository, times(3)).save(any(OrderRecord.class));
    }

    @Test
    void warehouseAliasIsNormalizedToBusinessName() {
        when(repository.findByOrderId("AMZ-1002")).thenReturn(Optional.empty());
        when(repository.findByOrderId("AMZ-1001")).thenReturn(Optional.empty());
        when(repository.findByOrderId("AMZ-1003")).thenReturn(Optional.empty());
        when(repository.save(any(OrderRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.pullAndPersist();

        OrderRecord saved = captureSaved();
        // Moenchengladbach（API 无变音符写法）必须归一化为 Mönchengladbach
        assertEquals("Mönchengladbach", saved.getWarehouseCode());
    }

    @Test
    void repeatedPullIsIdempotentByOrderId() {
        // 已存在：直接返回既有实体，模拟数据库命中
        when(repository.findByOrderId(anyString())).thenAnswer(inv -> Optional.of(
                new OrderRecord(inv.getArgument(0), "Amazon-Mock", "奶粉一件代发", "Bruchsal", "PAID")));
        when(repository.save(any(OrderRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderPullService.PullSummary summary = service.pullAndPersist();

        assertEquals(0, summary.created(), "重复订单不应新增");
        assertEquals(3, summary.updated(), "重复订单应走更新分支");
    }

    @Test
    void platformFailureDoesNotWriteToDatabase() {
        OrderPullService failing = new OrderPullService(
                new StubStrategy(new OrderPullResult("Amazon", List.of(), false, "401 Unauthorized")),
                repository);

        OrderPullService.PullSummary summary = failing.pullAndPersist();

        assertFalse(summary.success());
        assertEquals(0, summary.created());
        assertEquals("401 Unauthorized", summary.errorMsg());
        verify(repository, never()).save(any(OrderRecord.class));
    }

    @Test
    void correctOrderClearsErrorTag() {
        OrderRecord record = new OrderRecord("AMZ-1003", "Amazon-Mock", "逆向退货换标", "Bruchsal", "CREATED");
        record.setErrorTag(OrderRecord.TAG_ADDRESS_INVALID);
        when(repository.findByOrderId("AMZ-1003")).thenReturn(Optional.of(record));
        when(repository.save(any(OrderRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean ok = service.correctOrder("AMZ-1003", "Mönchengladbach", "PAID");

        assertTrue(ok);
        assertNull(record.getErrorTag(), "客服修正后异常标签应被清除");
        assertEquals("Mönchengladbach", record.getWarehouseCode());
        assertEquals("PAID", record.getStatus());
    }

    @Test
    void correctOrderReturnsFalseWhenMissing() {
        when(repository.findByOrderId("NOPE")).thenReturn(Optional.empty());
        assertFalse(service.correctOrder("NOPE", "Bruchsal", "PAID"));
    }

    /** 捕获最后一次 save 的实体。 */
    private OrderRecord captureSaved() {
        org.mockito.ArgumentCaptor<OrderRecord> captor =
                org.mockito.ArgumentCaptor.forClass(OrderRecord.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream()
                .filter(r -> "AMZ-1002".equals(r.getOrderId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未捕获到 AMZ-1002 的保存调用"));
    }
}
