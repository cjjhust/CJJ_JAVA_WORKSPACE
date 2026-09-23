package com.aslp.order.statemachine;

import com.aslp.order.entity.OrderStateEventRecord;
import com.aslp.order.entity.OrderStateRecord;
import com.aslp.order.repository.OrderStateEventRepository;
import com.aslp.order.repository.OrderStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 订单状态机服务（M4 技术亮点：FBA 退货换标）。
 *
 * <h3>两次演进（都写在这里，面试时是完整的一条线）</h3>
 * <ol>
 *   <li><b>缺陷 #16「状态被跨订单污染」</b>：原实现持有单个 {@code SimpleOrderStateMachine}
 *       实例，所有订单共享同一状态。修复方式是按订单号隔离实例。</li>
 *   <li><b>本轮（P1-8）「隔离了但没持久化」</b>：隔离后的实例仍全在进程内的
 *       {@code ConcurrentHashMap} 里 —— 服务一重启状态归零，多实例部署时各实例看到的还不同
 *       （都是"分布式单体的内存状态"）。现改为 <b>DB 为唯一真相源</b>：
 *       每个请求都「读库 → 纯逻辑判定 → 事务写回 + 追加事件」，
 *       进程内不再保存任何状态（因此也没有缓存不一致问题）。</li>
 * </ol>
 *
 * <h3>为什么写回与事件在同一个事务里</h3>
 * 状态与轨迹必须同时成功或同时失败。若分成两个事务，"状态已变但轨迹没记"会永久留下
 * 一段无法解释的历史（审计的完整性比多写一条日志更值钱）。
 *
 * <h3>并发</h3>
 * {@link OrderStateRecord} 带 {@code @Version}：两个请求同时推进同一订单时，后写者抛
 * {@code ObjectOptimisticLockingFailureException}（调用方返回 409/500 由上层决定），
 * 而不是把两次迁移"合并"成一个谁也说不清的结果。
 */
@Service
public class OrderStateMachineService {

    /** 默认订单号，便于无参调用（演示 / 冒烟测试）。 */
    public static final String DEFAULT_ORDER_ID = "DEMO-001";

    private final OrderStateRepository stateRepository;
    private final OrderStateEventRepository eventRepository;

    public OrderStateMachineService(OrderStateRepository stateRepository,
                                    OrderStateEventRepository eventRepository) {
        this.stateRepository = stateRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * 触发状态迁移（读库 → 判定 → 事务写回 + 追加事件）。
     *
     * <p>返回 {@link StateTransition} 而不是 boolean：调用方需要知道「从哪来、到哪去」，
     * 才能把"被拒绝"与"已在该状态"区分开（只返回 false 的话，前端提示只能写"操作失败"）。
     */
    @Transactional
    public StateTransition triggerEvent(String orderId, OrderEvents event) {
        String id = normalize(orderId);
        Instant now = Instant.now();

        OrderStateRecord record = stateRepository.findById(id)
                .orElseGet(() -> new OrderStateRecord(id, OrderStates.CREATED, now));

        OrderStates from = record.getCurrentState();
        // 用持久化状态"恢复"出一个状态机实例来判定本次迁移是否合法；
        // 这个实例用完即弃（不进任何缓存）—— 状态只有一个来源，就是这行 DB 记录。
        SimpleOrderStateMachine machine = new SimpleOrderStateMachine(from);
        boolean accepted = machine.sendEvent(event);
        OrderStates to = machine.getCurrentState();

        if (accepted) {
            record.setCurrentState(to);
            record.setUpdatedAt(now);
            stateRepository.save(record);
        }

        // 被拒绝的事件同样落库：它回答"客户说点过按钮，为什么没生效"
        eventRepository.save(new OrderStateEventRecord(id, event.name(), from, to, accepted, now));

        return new StateTransition(id, event, from, to, accepted);
    }

    /** 便捷重载：使用默认订单号。 */
    public StateTransition triggerEvent(OrderEvents event) {
        return triggerEvent(DEFAULT_ORDER_ID, event);
    }

    /**
     * 当前状态。
     *
     * <p>库里没有该订单的记录 = 从未发生过任何迁移，语义上等同 {@link OrderStates#CREATED}。
     * 这里**不**顺手写一行 CREATED 记录：读操作不应产生写入（否则一个查询接口会把库写胖，
     * 也会让"从未推进过"与"推进过又重置"变得无法区分）。
     */
    @Transactional(readOnly = true)
    public OrderStates getCurrentState(String orderId) {
        return stateRepository.findById(normalize(orderId))
                .map(OrderStateRecord::getCurrentState)
                .orElse(OrderStates.CREATED);
    }

    public OrderStates getCurrentState() {
        return getCurrentState(DEFAULT_ORDER_ID);
    }

    /**
     * 重置：删除当前状态行（回到"从未推进"），并留一条审计。
     *
     * <p>为什么不把状态写回 CREATED：那会与「新订单」无法区分，而且事件轨迹里会凭空多出
     * 一条"迁移到 CREATED"。删除行 + 记 RESET 审计，语义上是"夹具复位"，且历史仍然完整。
     *
     * <p>用 {@code findById(...).ifPresent(delete)} 而不是 {@code deleteById(id)}：
     * Spring Data JPA 3.x 的 {@code deleteById} 在目标不存在时抛
     * {@code EmptyResultDataAccessException}（→ HTTP 500）。而"重置一个从未推进过的订单"
     * 是完全正常的调用（夹具复位、重复运维操作），必须幂等。
     */
    @Transactional
    public StateTransition reset(String orderId) {
        String id = normalize(orderId);
        Instant now = Instant.now();
        OrderStates from = getCurrentState(id);
        stateRepository.findById(id).ifPresent(stateRepository::delete);
        eventRepository.save(new OrderStateEventRecord(id, "RESET", from, OrderStates.CREATED, true, now));
        return new StateTransition(id, null, from, OrderStates.CREATED, true);
    }

    /** 事件轨迹（只读，按发生顺序）。 */
    @Transactional(readOnly = true)
    public List<OrderStateEventRecord> history(String orderId) {
        return eventRepository.findByOrderIdOrderByIdAsc(normalize(orderId));
    }

    /** 已跟踪的订单数（即库中有状态行的订单数）。 */
    @Transactional(readOnly = true)
    public long trackedOrders() {
        return stateRepository.count();
    }

    private static String normalize(String orderId) {
        return (orderId == null || orderId.isBlank()) ? DEFAULT_ORDER_ID : orderId.trim();
    }

    /**
     * 一次迁移的结果。
     *
     * @param orderId  订单号（已归一化）
     * @param event    本次请求的事件；{@code null} 表示 RESET
     * @param fromState 迁移前状态
     * @param toState  迁移后状态（被拒绝时等于 {@code fromState}）
     * @param accepted 是否发生了合法迁移
     */
    public record StateTransition(String orderId, OrderEvents event, OrderStates fromState,
                                  OrderStates toState, boolean accepted) {
    }
}
