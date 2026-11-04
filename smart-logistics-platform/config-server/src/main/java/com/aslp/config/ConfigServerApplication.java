package com.aslp.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * P2-2 — 统一配置中心（端口 8888）。
 *
 * <p><b>backend 选型：native（classpath 配置库）而非 Git</b>：
 * 规划原文是 "Git 后端"，但 Git 后端要求配置仓与运行环境分离——
 * 本项目配置目前只有 6 个服务 × 2 profile 的环境差异，全部版本化在
 * {@code config-repo/} 里随代码仓一起演进（同一次提交里代码与配置一致），
 * 引入独立配置仓只会增加一次"改代码还要记得改配置仓"的同步成本。
 * 升级路径明确：配置需要跨环境独立演进时，把 {@code config-repo/} 拆成独立 Git 仓，
 * {@code spring.profiles.active} 从 native 换成 git 即可，客户端零改动。
 *
 * <p>客户端接入约定（各服务 {@code application.yml}）：
 * {@code spring.config.import=optional:configserver:}（optional 保证注册中心/配置中心
 * 未启动时服务仍能以本地配置起跑 —— 与"配置中心不在，服务不能死"的可用性要求一致）。
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
