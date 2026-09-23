package com.aslp.route;

import com.aslp.route.config.TrackingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * M3 路由与尾程服务入口。
 *
 * <p><b>为什么显式写 {@code @EnableConfigurationProperties}</b>：
 * 只标 {@code @ConfigurationProperties} 的类若没被注册，**不会报任何错** —— 它只是个普通类：
 * 编译期无错、单元测试也全绿（单测通常直接 new 对象），只有真的启动 Spring 上下文才会炸。
 * 本项目 order-service 的 {@code DocumentProperties} 就因此让容器反复重启（readme §9 #43），
 * 所以「新增配置类」与「注册配置类」必须同一步完成。
 */
@SpringBootApplication
@EnableConfigurationProperties(TrackingProperties.class)
public class RouteServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RouteServiceApplication.class, args);
    }
}
