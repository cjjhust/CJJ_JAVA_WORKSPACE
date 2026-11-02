package com.aslp.monolith;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 单体拓扑的唯一入口（P2-7）。
 *
 * <p>{@code scanBasePackages = "com.aslp"} 把 5 个业务模块的全部组件
 * （controller/service/repository/…）拉进同一个上下文；业务代码零改动。
 *
 * <p>装配由 profile 切换：
 * <ul>
 *   <li>{@code monolith}（本应用默认）：report 走进程内 {@code LocalXxxSource}，
 *       HTTP 客户端（{@code @Profile("!monolith")}）不装配；</li>
 *   <li>微服务形态：各服务用自己的 main class 启动（不设 monolith profile），行为不变。</li>
 * </ul>
 *
 * <p> exclude MinioHealthIndicator 相关自动配置？——不需要：order 模块的
 * MinIO 客户端是普通 @Bean，单体进程内照常工作；本机没起 MinIO 时
 * 健康检查降级为 DOWN，与 order-service 单独运行时的行为一致。
 */
@SpringBootApplication(scanBasePackages = "com.aslp")
public class MonolithApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonolithApplication.class, args);
    }
}
