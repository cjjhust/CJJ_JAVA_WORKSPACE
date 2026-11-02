package com.aslp.order.statemachine;

public enum OrderStates {
    CREATED,          // 订单创建
    PAID,             // 已支付
    PICKED,           // 已拣货（Bruchsal / Mönchengladbach 仓）
    SHIPPED,          // 已发货（DHL/DPD）
    FBA_RETURN_LABEL, // FBA 退货换标（状态机核心状态）
    FBA_RELABELED,    // 换标完成
    DELIVERED,        // 已送达
    COMPLETED,        // 完成
    CANCELLED         // 已取消（P1-4 Saga 补偿终点：锁库存失败等前端步骤失败时回滚到此）
}
