package com.aslp.order.spapi;

/**
 * SP-API 拒绝请求（非 429 的 4xx：400 参数错 / 403 无权限 / 404 资源不存在）。
 *
 * <p>与 {@link com.aslp.order.strategy.PlatformUnavailableException} 的区别是本类的
 * <b>重试语义</b>：请求本身或凭据/授权有问题，重试 3 次只会得到同样的 4xx，
 * 白白消耗平台配额并把熔断器推向打开 —— 所以它是<b>不可重试</b>的，
 * 由 {@code AmazonSpApiStrategy} 捕获后转成 {@code success=false} 的业务失败结果。
 *
 * <p>「可重试」与「不可重试」这条分界线是契约测试的重点覆盖对象：
 * 分错了，要么该重试的没重试（平台抖动变成业务失败），
 * 要么不该重试的疯狂重试（把配额打光）。
 */
public class SpApiClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SpApiClientException(String message) {
        super(message);
    }

    public SpApiClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
