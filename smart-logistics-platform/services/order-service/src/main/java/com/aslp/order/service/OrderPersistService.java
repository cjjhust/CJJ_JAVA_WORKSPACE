package com.aslp.order.service;

import com.aslp.order.entity.OrderRecord;
import com.aslp.order.repository.OrderRepository;
import com.aslp.order.strategy.OrderDto;
import com.aslp.order.strategy.OrderPullResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 订单幂等落库（P2-1：从 {@link OrderPullService} 拆出，独立事务边界）。
 *
 * <p><b>为什么要单独成 bean</b>：拉取链路里既有远程调用（平台拉取，最坏
 * 重试 3 次 × 读超时 5s ≈ 数十秒）又有写库。原实现把整条链路标 {@code @Transactional}，
 * 远程调用期间会一直占着一个 DB 连接 —— 高并发下连接池被「什么都没写的连接」耗尽。
 * 拆成两段后：远程调用在事务外执行，落库走本 bean 的 {@code @Transactional}。
 * 必须跨 bean 才能生效（Spring AOP 代理，同类自调用会绕过代理 ——
 * 与 {@code PlatformPullGateway} 单独抽 bean 是同一个陷阱）。
 *
 * <p><b>幂等的三层保障</b>（应用层判断 + 数据库约束闭环）：
 * <ol>
 *   <li><b>批内去重</b>：分页边界重叠时，平台可能把同一订单返回两次 —— 先按 orderId 去重（保序）；</li>
 *   <li><b>批量查存量</b>：一次 {@code IN} 查询替代逐单 SELECT（N 次往返 → 1 次）；</li>
 *   <li><b>唯一约束兜底</b>：并发拉取（5 分钟定时任务与手动补拉同时跑）都判定「不存在」而都走新增时，
 *       {@code UNIQUE(order_id)} 让后到者抛 {@link DataIntegrityViolationException}，
 *       捕获后转「更新」—— check-then-act 的竞态最终由数据库兜底，而不是只靠应用层的 if。</li>
 * </ol>
 *
 * <p>已存在订单只同步状态与异常标签，<b>不覆盖仓库编码</b>（保留客服手工修正，
 * 与 M1 原有语义一致；「人工修正会被平台同步覆盖」的已知限制见 readme §10）。
 */
@Service
public class OrderPersistService {

    private static final Logger log = LoggerFactory.getLogger(OrderPersistService.class);

    /** 落库结果：计数（供指标/回执）+ 本次新建的订单（P2-3 事件发布用）。 */
    public record PersistOutcome(PersistCounts counts, java.util.List<OrderDto> createdDtos) {
    }

    /**
     * 幂等落库计数（供调用方打指标与回执）。
     */
    public record PersistCounts(int created, int updated, int flagged) {
    }

    /** 仓库编码归一化：兼容 API 返回的无变音符写法，统一为业务展示口径。 */
    private static final Map<String, String> WAREHOUSE_ALIASES = Map.of(
            "bruchsal", "Bruchsal",
            "moenchengladbach", "Mönchengladbach",
            "monchengladbach", "Mönchengladbach",
            "mönchengladbach", "Mönchengladbach"
    );

    private final OrderRepository repository;

    public OrderPersistService(OrderRepository repository) {
        this.repository = repository;
    }

    /**
     * 幂等落库一批订单（事务内）。
     *
     * @param result 拉取成功（{@code success=true}）的结果；失败结果不应进入本方法
     * @return 落库计数 + 本次真正新建的订单（P2-3：事件发布只针对 created，
     *         updated 表示库存侧早已见过该订单，重发是噪音）
     */
    @Transactional
    public PersistOutcome persist(OrderPullResult result) {
        // 1) 批内去重（保序）：分页重叠时同一 orderId 可能出现两次
        Map<String, OrderDto> deduped = new LinkedHashMap<>();
        for (OrderDto dto : result.orders()) {
            deduped.putIfAbsent(dto.orderId(), dto);
        }

        // 2) 批量查存量：1 次 IN 查询替代 N 次 findByOrderId
        Map<String, OrderRecord> existing = new HashMap<>();
        if (!deduped.isEmpty()) {
            for (OrderRecord record : repository.findByOrderIdIn(deduped.keySet())) {
                existing.put(record.getOrderId(), record);
            }
        }

        int created = 0;
        int updated = 0;
        int flagged = 0;
        List<OrderDto> createdDtos = new ArrayList<>();

        for (OrderDto dto : deduped.values()) {
            OrderRecord record = existing.get(dto.orderId());
            if (record != null) {
                // 已存在：仅同步状态与异常标签（保留客服手工修正过的仓库编码）
                record.setStatus(dto.status());
                record.setErrorTag(dto.errorTag());
                repository.save(record);
                updated++;
            } else {
                try {
                    OrderRecord fresh = new OrderRecord(
                            dto.orderId(), result.platform(), dto.product(),
                            normalizeWarehouse(dto.warehouse()), dto.status());
                    fresh.setErrorTag(dto.errorTag());
                    repository.save(fresh);
                    created++;
                    createdDtos.add(dto);   // P2-3：只有真正新建的订单才进事件发布清单
                } catch (DataIntegrityViolationException e) {
                    // 3) 并发竞态兜底：另一路拉取抢先插入了同一订单 —— 转更新而不是让整批失败
                    log.info("[订单接入] {} 并发插入冲突（UNIQUE(order_id)），转为更新", dto.orderId());
                    Optional<OrderRecord> raced = repository.findByOrderId(dto.orderId());
                    if (raced.isPresent()) {
                        OrderRecord r = raced.get();
                        r.setStatus(dto.status());
                        r.setErrorTag(dto.errorTag());
                        repository.save(r);
                        updated++;
                    }
                }
            }

            if (!dto.isNormal()) {
                flagged++;
            }
        }

        return new PersistOutcome(new PersistCounts(created, updated, flagged), createdDtos);
    }

    /** 仓库编码归一化（{@link OrderPullService#correctOrder} 复用同一口径）。 */
    public static String normalizeWarehouse(String raw) {
        if (raw == null) {
            return null;
        }
        return WAREHOUSE_ALIASES.getOrDefault(raw.trim().toLowerCase(), raw.trim());
    }
}
