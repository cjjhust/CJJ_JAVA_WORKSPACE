package com.aslp.order.statemachine;

public enum OrderStates {
    CREATED,          // 订单创建
    PAID,             // 已支付
    PICKED,           // 已拣货（Bruchsal / Mönchengladbach 仓）
    SHIPPED,          // 已发货（DHL/DPD）
    FBA_RETURN_LABEL, // FBA 退货换标（状态机核心状态）
    FBA_RELABELED,    // 换标完成
    DELIVERED,        // 已送达
    COMPLETED         // 完成
}
