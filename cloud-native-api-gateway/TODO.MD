```
云原生 API 网关与服务治理组件 (Cloud-Native API Gateway)
适合展现：网络编程、高并发性能优化、微服务底层原理
为什么适合德国/欧洲市场？ 欧洲企业正在全面从传统的集中式 SOA 架构向 Kubernetes + Service Mesh / Microservices 转型。理解网关底层机制（如 Envoy、Spring Cloud Gateway）的工程师在企业中非常吃香。
业务场景： 作为一个统一的微服务入口，处理流量路由、动态限流、JWT 鉴权透传、请求头改写，并收集全链路 Trace ID。
硬核技术栈：
Core: Java 21, Spring Cloud Gateway / Netty (响应式非阻塞网络编程)
Resilience: Resilience4j (基于令牌桶/漏桶算法实现动态限流)
Auth & Security: OAuth2 Resource Server / Keycloak (解析 JWT 并透传用户上下文)
Observability: OpenTelemetry (OTel) + Jaeger (分布式链路追踪)
面试谈资与亮点：
“我基于 Netty/WebFlux 实现了非阻塞的 API 路由与动态插件链，相比传统 Blocking I/O 降低了 60% 的内存占用。”
“通过整合 OpenTelemetry，在网关层自动注入 traceparent 头，实现了跨微服务的全链路可观测性。”

```