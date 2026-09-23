package com.aslp.route.tracking;

/**
 * 承运商拒绝了我们的请求（除 404 之外的 4xx）。
 *
 * <p><b>为什么对外映射成 502 而不是 400</b>：这一类的成因有两面 ——
 * 可能是单号格式确实非法（用户的问题），也可能是我们自己把请求拼错了/凭据过期了（我们的问题）。
 * 贸然返回 400 会误导用户去改一个本来就正确的单号；
 * 返回 502 则如实表达"网关背后的这次上游调用没成"，并把上游原文带出来供排查。
 *
 * <p>不重试：重试一个被拒绝的请求只会浪费配额。
 */
public class TrackingClientException extends RuntimeException {

    private final Carrier carrier;
    private final int upstreamStatus;

    public TrackingClientException(Carrier carrier, int upstreamStatus, String upstreamBody) {
        super("承运商 " + carrier + " 拒绝请求（HTTP " + upstreamStatus + "）：" + upstreamBody);
        this.carrier = carrier;
        this.upstreamStatus = upstreamStatus;
    }

    public Carrier getCarrier() {
        return carrier;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }
}
