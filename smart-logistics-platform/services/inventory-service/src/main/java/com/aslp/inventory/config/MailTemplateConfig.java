package com.aslp.inventory.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * P1-5：邮件模板引擎装配。
 *
 * <p><b>为什么手装配而不用 {@code spring-boot-starter-thymeleaf}</b>：那个 starter
 * 会顺带注册 Web 视图解析器（ThymeleafViewResolver），把「本服务只返回 JSON」这件事
 * 变得不再显然 —— 错误页、String 返回值语义都会被卷进视图解析；而本项目只需要
 * 「把 {@code classpath:templates/} 下的模板渲染成字符串」。所以这里只装配一个
 * 用途单一的引擎 bean，作用域清晰、对 Web 层零影响。
 *
 * <p><b>为什么用 {@code ClassLoaderTemplateResolver} 而不是 Spring 版本</b>：
 * Spring 版解析器需要 {@code ApplicationContext} 才能把 {@code classpath:} 前缀
 * 解析成 Resource，也就是说「渲染一封邮件」会隐含「必须有一个 Spring 上下文」。
 * 邮件模板本来就在 classpath 里（与 bean 无关），用类加载器直接取更直接，
 * 也让渲染逻辑可以在纯单元测试里跑（不必启动上下文）。
 *
 * <p>模板编码固定 UTF-8：邮件正文含中文（商品名、仓库名），编码不一致会出现乱码。
 */
@Configuration
@EnableConfigurationProperties(WarningProperties.class)
public class MailTemplateConfig {

    /** 邮件模板根目录（classpath 相对路径）。 */
    public static final String TEMPLATE_PREFIX = "templates/";

    @Bean
    public SpringTemplateEngine mailTemplateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(TEMPLATE_PREFIX);
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        resolver.setTemplateMode(TemplateMode.HTML);
        // 模板在打包后不会变，缓存可以避免每次发信都解析一遍
        resolver.setCacheable(true);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
