package com.aslp.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * P2-1 — 服务注册中心（Eureka Server，端口 8761）。
 *
 * <p>单节点注册中心（演示/本地形态）：
 * <ul>
 *   <li>{@code register-with-eureka=false} / {@code fetch-registry=false}：
 *       自己不注册自己、不拉注册表 —— 单节点下这两个自指行为只会产生无害噪音；</li>
 *   <li>{@code enable-self-preservation=false}：演示环境关掉自我保护 ——
 *       单实例场景下保护模式会让已死实例长时间挂着（开发期频繁重启服务，表脏了很迷惑）；
 *       生产多节点部署时必须重新打开（网络分区时宁可不摘实例）。</li>
 * </ul>
 */
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
