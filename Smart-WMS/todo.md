# Smart-WMS 智慧仓储系统 - 综合开发任务清单 (TODO.md)

## 项目背景与核心解决的问题

在传统仓储管理（WMS）与工业物联网（IoT）落地过程中，系统常面临以下核心痛点：
1. **硬件通信不确定性**：硬件设备（RFID、电子价签、AGV、分拣闸机）易受网络抖动、电池耗尽、信号遮挡等影响，导致指令丢失或无响应。
2. **高并发硬件上报撑爆数据库**：自动化流水线高频上报（如毫秒级 RFID 连续扫码）若直接同步写库，极易造成数据库死锁或连接池耗尽。
3. **软硬件强耦合与进程风险**：原生硬件驱动（C/C++ DLL/SO 库、串口）不稳定，一旦驱动崩溃会导致整个 Java 进程挂掉。
4. **货位利用率低与行走浪费**：缺乏数据分析，高频商品与低频商品乱堆，导致捡货路径冗长、仓储效率低下。

**Smart-WMS 解决方案**：
本项目通过 **单体多模块（Monolith Multi-Module）** 架构，构建一套高可用、高并发、集“硬件物联网接入 + 业务自动化 + 智能数据分析”于一体的智慧仓储系统。
* **零硬件门槛**：项目内自带轻量级 Mock 模拟器，无需购买任何物理硬件与基站，即可在单机上完美模拟完整物联网链路。
* **高可靠硬件状态机**：设计 **指令状态机 + 异步 ACK + 定时补偿重试** 机制，确保软硬件状态终态一致。
* **高并发吞吐**：采用 **Netty 长连接 + MQ 异步削峰 + 批量入库** 架构。
* **软硬件隔离**：通过 **Edge Agent（边缘代理）模式**，将硬件驱动隔离在主系统之外。
* **智能数据决策**：引入 **ABC 库位推荐、库龄分析与看板**，展示综合仓储 KPI 与算法优化能力。

---

## 项目工程结构说明

smart-wms
├── wms-common      // 工具类、通用常量、错误码、全局异常处理、BaseEntity
├── wms-domain      // 数据库 Entity、VO/DTO、状态机枚举、算法数据模型
├── wms-hardware    // Netty Server、MQTT 客户端、硬件协议编解码器、Channel 管理
├── wms-service     // 入库/出库/库存/盘点核心逻辑、状态机驱动、ABC分析算法、MQ 消费者
├── wms-analytics   // 仓储 KPI 统计、库龄分析、实时数据大屏聚合接口
├── wms-admin       // Spring Boot 启动类、REST API、Web 拦截器
└── wms-mock        // 纯 Mock 工具（MQTT 设备模拟器、TCP 报文发包器、Edge 代理脚本）

---

## 阶段一：M1 - 基础骨架与核心业务 CRUD (第 1 - 2 周)

> **目标**：完成基础仓储模型搭建，实现通过 HTTP 与标准扫码枪（HID 虚拟键盘模式）交互的无感扫码入出库闭环。

- [ ] **M1.1 工程初始化与多模块配置**
  - [ ] 创建 Maven 多模块工程结构，配置 `Java 17 / 21` 与 `Spring Boot 3.x`。
  - [ ] 引入 `MyBatis-Plus` (或 `MyBatis-Flex`)、`PostgreSQL` / `MySQL` 驱动、`Lombok`。
  - [ ] 配置 `wms-common` 的统一 API 返回体 `Result<T>` 与全局异常拦截器 `GlobalExceptionHandler`。

- [ ] **M1.2 仓储核心领域建模 (DDL & Entity)**
  - [ ] 设计并创建商品表 `goods`（`id`, `sku_code`, `name`, `spec`, `unit`, `abc_type`）。
  - [ ] 设计并创建货位表 `location`（`id`, `location_code`, `zone`, `shelf_row`, `shelf_col`, `distance_score`）。
  - [ ] 设计并创建库存表 `inventory`（`id`, `goods_id`, `location_id`, `quantity`, `lock_quantity`, `inbound_time`）。
  - [ ] 设计并创建入库/出库单据表 `inbound_order` / `outbound_order` 及明细表。

- [ ] **M1.3 ZXing 条码与二维码渲染服务**
  - [ ] 在 `wms-service` 中引入 `com.google.zxing:core` 和 `javase` 依赖。
  - [ ] 实现 `BarcodeService.generateBarcodeStream(String code)`，支持生成 CODE128 一维码输出为 PNG 流。
  - [ ] 实现 REST 接口 `/api/v1/barcode/render`，可通过浏览器直观查看渲染好的商品/货位条码。

- [ ] **M1.4 扫码枪 (HID 模式) 交互与无感操作**
  - [ ] 编写前端极简 HTML 页面（`scan_inbound.html`），页面放置无边框 Input 并保持自动聚焦。
  - [ ] 编写 JS 监听 `Enter` 事件：扫码枪扫入 SKU 码后自动带有回车，触发异步 POST 请求。
  - [ ] 实现后端 `/api/v1/wms/scan-inbound` 接口，接收 SKU 码与货位码，校验并完成库存 +1 操作。

---

## 阶段二：M2 - 设备指令状态机与异步 MQTT 通信 (第 3 - 4 周)

> **目标**：引入 MQTT 消息队列与设备指令状态机，使用 Python/Node.js 模拟 100 个电子价签（ESL），打通“指令下发-异步ACK-重试补偿”闭环。

- [ ] **M2.1 环境搭建与 MQTT 整合**
  - [ ] 使用 Docker Compose 启动 **EMQX Broker**（映射 1883 端口与 18083 管理后台）。
  - [ ] 在 `wms-hardware` 中引入 `spring-boot-starter-integration-mqtt`。
  - [ ] 配置 MQTT Outbound 通道（用于发指令）与 Inbound 通道（用于接收 ACK）。

- [ ] **M2.2 硬件指令状态机设计**
  - [ ] 创建指令任务表 `hardware_command_task`（包含 `task_id`, `device_id`, `command_type`, `payload`, `status`, `retry_count`, `created_at`, `updated_at`）。
  - [ ] 定义状态机枚举 `CommandStatusEnum`：`CREATED`、`PENDING`、`ACKED`、`SUCCESS`、`FAILED`、`TIMEOUT`。

- [ ] **M2.3 硬件 Mock 模拟器编写 (`wms-mock`)**
  - [ ] 在 `wms-mock` 中编写 Python 脚本 `mock_esl_gateway.py`（使用 `paho-mqtt` 库）。
  - [ ] 模拟 100 个货位电子价签，订阅 Topic `wms/esl/cmd/+`。
  - [ ] 收到刷屏指令后，模拟硬件处理延迟（1 - 3秒），向 `wms/esl/ack` 回传 JSON：`{"taskId": "xxx", "code": 200, "msg": "SUCCESS"}`。
  - [ ] 脚本中加入 10% 的随机“丢包/异常”概率，用于测试系统的容错能力。

- [ ] **M2.4 定时补偿与幂等重试机制**
  - [ ] 实现 `EslCommandPublisher`：发送指令前插入 `hardware_command_task` 记录（状态为 `PENDING`），随后向 MQTT 发消息。
  - [ ] 实现 `EslAckListener`：收到 ACK 后更新对应 `taskId` 的状态为 `SUCCESS`。
  - [ ] 开启 `@EnableScheduling`，编写定时任务 `CommandRetryScheduler`：每 5 秒扫描一次 `PENDING` 且超过 5 秒未更新的任务。
  - [ ] 重试逻辑：若 `retry_count < 3` 则重新发消息并 `retry_count + 1`；若达到 3 次，将状态置为 `TIMEOUT` 并触发告警日志。

---

## 阶段三：M3 - Netty 工业级 TCP 长连接与自定义二进制协议 (第 5 - 6 周)

> **目标**：在 `wms-hardware` 中使用 Netty 启动 TCP Server，设计十六进制通讯协议，用网络调试助手模拟硬件设备连接、心跳及高频报文解析。

- [ ] **M3.1 自定义二进制十六进制协议设计**
  - [ ] 制定通信协议格式（Total 12+ Bytes）：
    * 帧头：`0xAA 0x55` (2 Bytes)
    * 长度：`Length` (2 Bytes)
    * 设备 ID：`DeviceID` (4 Bytes, 如 `0x00000001`)
    * 指令类型：`CmdType` (1 Byte: `0x01` 心跳, `0x02` 扫码/过闸, `0x03` 控制ACK)
    * 数据体：`Payload` (N Bytes)
    * CRC8 校验：`CRC` (1 Byte)
    * 帧尾：`0x0D 0x0A` (2 Bytes)

- [ ] **M3.2 Netty TCP 服务端整合 (`wms-hardware`)**
  - [ ] 引入 `io.netty:netty-all` 依赖。
  - [ ] 实现 Spring Boot 启动时同步/异步拉起 Netty Server（监听 `8989` 端口）。
  - [ ] 建立 `ChannelRepository` 管理在线设备 Channel（映射关系：`DeviceID` <-> `Channel`）。

- [ ] **M3.3 粘包拆包与编解码器实现**
  - [ ] 配置 Netty ChannelPipeline，添加 `LengthFieldBasedFrameDecoder` 解决 TCP 粘包拆包问题。
  - [ ] 编写自定义解码器 `WMSPacketDecoder`：校验帧头帧尾、计算 CRC8 校验码，将 ByteBuf 解析为 Java 对象 `WMSMessage`。
  - [ ] 编写自定义编码器 `WMSPacketEncoder`：将 Java 对象序列化为二进制十六进制流发送给硬件。

- [ ] **M3.4 心跳检测与死连接剔除**
  - [ ] Pipeline 中加入 `IdleStateHandler(30, 0, 0, TimeUnit.SECONDS)`。
  - [ ] 编写 `HeartbeatHandler`：30 秒未收到硬件 `0x01` 心跳包时，打印日志并主动调用 `ctx.close()` 释放 Channel。

- [ ] **M3.5 纯软件模拟发包测试**
  - [ ] 使用 NetAssistant（TCP 调试助手）或 Python 脚本 `mock_tcp_rfid.py` 充当硬件客户端。
  - [ ] 建立 TCP 连接连入 `8989` 端口，按照协议发送心跳与扫码十六进制报文（如：`AA 55 00 0D 00 00 00 01 02 41 42 43 B8 0D 0A`）。
  - [ ] 验证 Netty 是否成功解析出字符串 `"ABC"` 并触发后端的入库业务逻辑。

---

## 阶段四：M4 - 高并发削峰、边缘代理与高级仓储业务 (第 7 - 8 周)

> **目标**：引入 RabbitMQ/Kafka 削峰，加入盘点与动盘锁库业务，实现 Python 边缘代理隔离模拟。

- [ ] **M4.1 消息队列 (RabbitMQ / Kafka) 削峰**
  - [ ] Docker 启动 RabbitMQ 并配置 Exchange 与 Queue。
  - [ ] 改造 Netty `WMSPacketDecoder`：Netty 收到高频 RFID 报文后，**不做任何数据库读写**，直接投递到 RabbitMQ `rfid.scan.queue`。
  - [ ] 在 `wms-service` 中实现 `@RabbitListener` 消费者：开启多线程异步处理，使用 List 缓存报文，实现每 500ms 或攒满 100 条进行**批量 `saveBatch()` 入库**。

- [ ] **M4.2 动盘与盲盘管理 (Stock Counting)**
  - [ ] 创建盘点单表 `inventory_check_order` 与明细表。
  - [ ] **盘点锁库**：发起盘点时锁定指定货位（`lock_quantity`），禁止出库操作。
  - [ ] **账实差异核对**：接收扫码上报的实际盘点数，对比系统账面数，自动生成盘盈（Gain）/ 盘亏（Loss）调账单并记录日志。

- [ ] **M4.3 边缘代理 (Edge Agent) 模式模拟 (`wms-mock`)**
  - [ ] 编写 Python 脚本 `edge_agent.py`，模拟本地串口设备读取（使用 `pyserial` 或模拟数据生成）。
  - [ ] 边缘代理通过 WebSocket 或 HTTP 与 Spring `wms-hardware` 交互。
  - [ ] 模拟 Edge 脚本异常崩溃，验证主 Spring Boot 进程完全不受影响，体现架构的隔离容错能力。

---

## 阶段五：M5 - 仓储算法、统计分析与 KPI 数据大屏 (第 9 - 10 周)

> **目标**：打造系统数据闭环与高价值点，实现 ABC 货位自动推荐、库龄滞销预警算法以及实时 KPI 看板。

- [ ] **M5.1 ABC 库位分类与智能推荐算法**
  - [ ] 编写定时任务 `AbcAnalysisScheduler`：统计过去 30 天 SKU 的出库频次（Pick Frequency）。
  - [ ] 按出库量累计占比划分为：A 类（前 80% 爆款）、B 类（15% 次常用）、C 类（5% 冷门）。
  - [ ] **上架推荐算法**：当新商品入库时，根据其 ABC 属性，优先匹配并推荐 `distance_score` 离出口最近的空闲货位。

- [ ] **M5.2 库龄分析与滞销预警 (Stock Ageing Analysis)**
  - [ ] 编写 SQL / 内存算法：按 `NOW() - inbound_time` 计算库存商品的停留天数（0-30天、31-90天、90天+）。
  - [ ] 编写预警接口 `/api/v1/analytics/slow-moving`：查询超过 90 天未发生出库变动的滞销 SKU，输出资金占用评估。

- [ ] **M5.3 实时仓储 KPI 统计大屏接口 (`wms-analytics`)**
  - [ ] **吞吐量统计**：当日/当周入库量、出库量趋势折线图数据接口。
  - [ ] **货位利用率**：已用货位 / 总货位百分比饼图接口。
  - [ ] **硬件健康度**：设备在线数、过去 24 小时状态机重试成功率与失败率指标。
  - [ ] 编写极简 Vue / HTML 页面或集成 ECharts 展现 KPI 可视化面板。

- [ ] **M5.4 端到端全流程闭环联调测试**
  - [ ] **步骤 1**：发布出库单，ABC 算法自动指定最优近出口货位。
  - [ ] **步骤 2**：系统触发 MQTT 指令，通知对应货位电子价签亮灯，状态机记录为 `PENDING` -> 收到 Mock 价签 ACK 后变为 `SUCCESS`。
  - [ ] **步骤 3**：用 Python Mock 脚本向 Netty 8989 端口发送 RFID 扫描过闸报文（模拟拿取商品通过通道门）。
  - [ ] **步骤 4**：MQ 消费者收到过闸报文，异步批量扣减库存，更新出库单状态。
  - [ ] **步骤 5**：刷新 KPI 统计面板，验证当日出库吞吐量 +1，货位利用率实时更新。