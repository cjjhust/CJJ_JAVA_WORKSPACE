package com.aslp.order.spapi;

import java.util.List;

/**
 * 一次 SP-API 订单拉取的完整结果（P1-4）。
 *
 * @param orders    跨页汇总后的订单（已按页序拼接）
 * @param pages     实际发起的列表请求页数（= 1 表示没有分页）
 * @param truncated 是否因 {@code max-pages} 上限而提前停止 —— 为 true 意味着
 *                  平台上还有未拉取的订单，属于「数据不完整」而不是「失败」
 */
public record SpApiFetchResult(List<SpApiOrder> orders, int pages, boolean truncated) {
}
