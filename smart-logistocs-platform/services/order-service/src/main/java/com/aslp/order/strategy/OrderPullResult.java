package com.aslp.order.strategy;

import java.util.List;

public record OrderPullResult(String platform, List<OrderDto> orders, boolean success, String errorMsg) {}
