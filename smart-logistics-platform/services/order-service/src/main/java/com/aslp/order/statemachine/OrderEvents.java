package com.aslp.order.statemachine;

public enum OrderEvents {
    PAY,              // 支付
    PICK,             // 拣货
    SHIP,             // 发货
    FBA_RETURN,       // FBA 退货触发
    RELABEL,          // 换标完成
    DELIVER,          // 送达
    COMPLETE,         // 完成
    COMPENSATE        // P1-4 Saga 补偿：前端本地步骤失败（如锁库存失败）时撤销订单
}
