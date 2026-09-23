package com.aslp.route.tracking;

/**
 * 承运商明确回了"查无此单"（HTTP 404，或 200 但结果为空）。
 *
 * <p>这是**正常业务状态**而不是错误：客户把单号打错了、或者包裹还没被承运商录系统。
 * 因此对外映射为 HTTP <b>404</b>，前端可以引导"请核对单号"，
 * 而不是像依赖故障那样提示"稍后重试"（那会让用户一直重试一个永远不存在的单号）。
 *
 * <p>不缓存这个结果：包裹可能几分钟后就被揽收录入，"查无此单"是个会过期的事实。
 */
public class TrackingNotFoundException extends RuntimeException {

    private final Carrier carrier;
    private final String trackingNumber;

    public TrackingNotFoundException(Carrier carrier, String trackingNumber) {
        super("承运商 " + carrier + " 查询不到该单号：" + trackingNumber);
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
    }

    public Carrier getCarrier() {
        return carrier;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }
}
