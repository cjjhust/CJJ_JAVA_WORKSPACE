```
高可用分布式事件驱动订单系统 (Event-Driven Order System)
优先级：P2 —— 将原“秒杀系统”改造成标准的“分布式弹性架构”，摆脱 Demo 感。
1. 所需核心组件
后端：Java 21, Spring Boot 3.x, Spring Cloud / Resilience4j
缓存与分布式锁：Redis (Redisson)
消息中间件：Apache Kafka
数据库：PostgreSQL 16
系统容错：Resilience4j (熔断、限流、重试，替代 Sentinel/Hystrix)
2. 项目里程碑与开发顺序
Milestone 3.1：防重提交与分布式锁（幂等性设计）
[ ] 使用 Redis + Token 机制实现接口幂等性（Idempotency），防止重复下单。
[ ] 使用 Redisson 分布式锁控制并发库存扣减，防止 Over-selling（超卖）。
Milestone 3.2：Kafka 事件驱动与最终一致性 (Saga Pattern)
[ ] 将“创建订单”与“扣减库存/发送通知”解耦，采用 Kafka 发送订单事件。
[ ] 实现死信队列（DLQ）与本地消息表（Transactional Outbox Pattern），保证分布式数据最终一致性。
Milestone 3.3：服务韧性与熔断限流 (Resilience)
[ ] 集成 Resilience4j，针对高并发场景配置 RateLimiter（限流）和 CircuitBreaker（熔断）。
[ ] 编写压测脚本（JMeter 或 Locust），模拟高并发流量，验证系统的优雅降级（Graceful Degradation）。

```