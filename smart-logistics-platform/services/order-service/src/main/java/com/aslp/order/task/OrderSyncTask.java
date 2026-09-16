package com.aslp.order.task;

import com.aslp.order.service.OrderPullService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 订单定时同步任务（M1）——「流水线式的自动导入」。
 *
 * <p>默认关闭，容器环境通过 {@code aslp.order.sync.enabled=true} 开启。
 * 异常隔离：拉取失败只记日志，不影响下一次调度（配合平台限流退避策略演进）。
 */
@Component
@ConditionalOnProperty(name = "aslp.order.sync.enabled", havingValue = "true")
public class OrderSyncTask {

    private static final Logger log = LoggerFactory.getLogger(OrderSyncTask.class);

    private final OrderPullService pullService;

    public OrderSyncTask(OrderPullService pullService) {
        this.pullService = pullService;
    }

    /** 默认每 5 分钟增量拉取一次。 */
    @Scheduled(cron = "${aslp.order.sync.cron:0 */5 * * * *}")
    public void syncOrders() {
        try {
            OrderPullService.PullSummary summary = pullService.pullAndPersist();
            log.info("[订单定时同步] {} 完成：新增 {}，更新 {}，异常 {}",
                    summary.platform(), summary.created(), summary.updated(), summary.flagged());
        } catch (Exception e) {
            log.error("[订单定时同步] 执行失败，将在下个周期重试：{}", e.getMessage(), e);
        }
    }
}
