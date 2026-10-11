```
工业 4.0 实时设备监控与可观测平台 (IoT & Observability)
优先级：P1 —— 完美契合北威州强项（工业制造、汽车供应链、物流）。
1. 所需核心组件
后端：Java 21, Spring Boot 3.x, Spring WebFlux (响应式编程处理高频数据)
消息中间件：Apache Kafka (欧洲主流，取代 RocketMQ)
时序数据库：TimescaleDB (基于 PostgreSQL 的时序插件) 或 InfluxDB
实时通信：Spring WebSocket / STOMP
可观测性 (Observability)：Prometheus + Grafana
前端 (可选/简单呈现)：React / Vue + Chart.js (实时绘制传感器折线图)
2. 项目里程碑与开发顺序
Milestone 2.1：模拟传感器数据源与 Kafka 流量注入
[ ] 编写 Python/Java 脚本或 Telegraf，模拟 500+ 工厂传感器每秒上报 JSON 数据（温度、压力、振动）。
[ ] 搭建 Kafka Cluster，配置 Topic、分区与 ACK 策略，保证高吞吐与消息不丢失。
Milestone 2.2：WebFlux 响应式消费与时序数据库写入
[ ] Spring Boot 采用 Kafka Listener 批量消费传感器数据。
[ ] 整合 TimescaleDB，设计超表（Hypertable）与自动数据保留策略（Retention Policy），高效存储海量数据。
Milestone 2.3：WebSocket 全双工推拉与前端实时看板
[ ] 搭建 Spring WebSocket 服务端，将 Kafka 实时过滤出的异常指标推送至前端。
[ ] 前端通过 WebSocket 接收数据并在 Dashboard 上实时动态刷屏。
Milestone 2.4：Full Observability (全栈可观测性)
[ ] 引入 Micrometer，向 Prometheus 暴露 JVM、Kafka 消费延迟、WebFlux 吞吐量指标。
[ ] 在 Grafana 中配置专业的 Monitoring Dashboard（德企 Tech Lead 极度青睐这个指标屏）。

```