package com.aslp.order.strategy;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * P1-2 故障演练开关（仅作用于 Mock 策略）。
 *
 * <p><b>为什么需要它</b>：熔断 / 降级这类"只在故障时才出现"的行为，
 * 没有可控的故障源就无法验证 —— 单元测试能覆盖回退逻辑，但"连续失败后熔断真的打开、
 * 接口真的返回降级响应"必须在运行时证明。本开关提供这个故障注入点。
 *
 * <p><b>边界</b>：真实策略（{@link AmazonSpApiStrategy}）不读取该开关；
 * 生产环境也不应暴露 {@code POST /api/orders/mock/failure-mode}
 * （网关 docker 链路已限制仅 ADMIN 可调用）。
 */
@Component
public class PlatformFailureSwitch {

    /** NONE = 正常返回 Mock 数据；ERROR = 抛出 {@link PlatformUnavailableException}。 */
    public enum Mode { NONE, ERROR }

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.NONE);

    public Mode getMode() {
        return mode.get();
    }

    public void setMode(Mode mode) {
        this.mode.set(mode == null ? Mode.NONE : mode);
    }

    public boolean isFailing() {
        return mode.get() == Mode.ERROR;
    }
}
