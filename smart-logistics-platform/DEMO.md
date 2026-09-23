# ASLP 演示手册（面试 / 评审现场用）

> **目标**：15 分钟内让面试官相信「这是一个真的跑起来、并且有工程纪律的系统」。
> **原则**：每一条演示都配一条**可复制的命令**，用输出说话，而不是用嘴说"我做了限流/熔断/持久化"。
>
> 配套文档：`readme.md`（技术细节与踩坑全记录）、`todo.md`（轮次日志：每轮做了什么、修了什么缺陷）。

---

## 0. 一句话开场（背下来）

> "这是给一家德国海外仓公司做的**智能物流平台**：Bruchsal 总仓 + Mönchengladbach 分仓，
> 对接 Amazon/eBay 订单、Redis 分布式锁防超卖、jsprit 算配送路径、DHL/DPD 做一单到底追踪，
> 并且是**微服务 + 完整可观测性**（Prometheus / Grafana / Zipkin / Loki）。
> 6 个服务 + 12 个基础设施容器，一条命令冷启动，**124 项端到端断言全绿**。"

一句话里给三个"钩子"（业务、算法、工程），面试官会顺着问下去。

---

## 1. 启动（演示前 5 分钟做好）

```bash
cd smart-logistics-platform

# 方式 A（推荐，最稳）：容器全量启动 + 全量验证
docker compose down -v          # 清空数据卷，保证从干净状态开始
bash scripts/container-verify.sh
```

期望结尾看到：

```
========================================
  通过: 124   失败: 0
========================================
=== 6/6 外部化状态验证（内存 → DB / Redis）：重启容器后状态必须还在 ===
  ✅ 状态已持久化（FBA_RELABELED 渡过了服务重启；内存实现会回到 CREATED）
  ✅ 邮件节流窗口已外部化（重启后仍 mailSkipped=true，Redis TTL=17xx s）
```

**这段输出本身就是最好的开场白**：一句话不用说，把 124/124 贴出来，
然后说"这条命令冷启动 18 个容器、跑完 124 项断言，还会重启容器验证『状态与节流窗口都不在进程内』"。

本地开发（不想等镜像构建时）：

```bash
mvn clean package -T 1C                       # 256 个单测
docker compose up -d aslp_postgres aslp_redis # 只起基础设施
bash scripts/smoke-test.sh                    # 启 6 个服务 → 断言 → 自动清理
```

---

## 2. 演示动线（8 站，每站 1-2 分钟）

> 全程用一个终端贴命令。**下面是按"讲故事"顺序排的**，不是按模块号。

### 第 1 站 · 系统全景（30 秒）

```bash
docker compose ps --format "table {{.Name}}\t{{.Status}}" | head -20
```

**台词**："6 个 Java 服务 + 12 个基础设施（PG/Redis/Kafka/ZooKeeper/Prometheus/Grafana/Zipkin/Loki/Promtail/WireMock/MailHog/MinIO），全部容器化，一条命令冷启动。"

**追问预案**：为什么 6 个服务而不是单体？→ 见 §4-Q1。

---

### 第 2 站 · 网关与鉴权（1 分钟）★ 亮点：401 vs 200

```bash
# ① 匿名访问 BFF → 401（docker profile 强制 JWT）
curl -s -o /dev/null -w "匿名: %{http_code}\n" "http://localhost:8080/bff/orders/search?status=PAID"

# ② 登录拿 JWT，再访问 → 200
TOKEN=$(curl -s -X POST "http://localhost:8080/api/auth/login?username=admin" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -s -o /dev/null -w "带 JWT: %{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/bff/orders/search?status=PAID"
```

**台词**："网关是 WebFlux 的 `ServerHttpSecurity`；开发 profile 放行、容器 profile 强制 JWT + 角色（`/bff/**` 要 USER，`POST /api/inventory/**` 要 ADMIN）。"
**顺口说的坑**（加分项）："这里踩过一个坑——原来注入的是 Servlet 的 `HttpSecurity` 且漏了 `@Bean`，配置**从未生效**，表现为全站 401。"

---

### 第 3 站 · M1 订单流水线（2 分钟）★ 亮点：策略模式 + 幂等 + 异常打标 + 限流

```bash
# 触发一次拉取（策略模式 → DTO 归一化 → 幂等落库 → 异常打标）
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/orders/pull
# → {"platform":"Amazon-Mock","success":true,"fetched":3,"created":0,"updated":3,"flagged":1,...}

# 统计（按状态 / 按仓 / 按异常标签的分布，给报表看板用）
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/orders/stats

# 异常单列表：AMZ-1003 因缺收货城市被打上 ADDRESS_INVALID
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/orders?status=CREATED"

# 客服远程修正 → 清除标签，重新进入履约流水线
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/orders/AMZ-1003/correct?warehouseCode=Bruchsal&status=PAID"

# 限流：平台拉取接口 1 次/秒（令牌桶），并发 4 次必然有 429
for i in 1 2 3 4; do curl -s -o /dev/null -w "%{http_code} " -X POST \
  -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/orders/pull & done; wait; echo
# → 429 429 429 200
```

**台词**："`created:0 / updated:3` 就是幂等——以平台单号为唯一键，重复拉取只更新不新增。限流不是全局的，只挂在会放大成平台调用的入口上。"
**追问预案**：为什么不用全局限流？→ §4-Q2。

---

### 第 4 站 · M2 多仓库存防超卖（2 分钟）★ 亮点：Redisson 锁 + 乐观锁 + 邮件节流

```bash
# 扣减：可用库存 → 锁定库存（预占）
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/inventory/deduct?sku=AMZ-1001&warehouseCode=Bruchsal&qty=2"
# → {"sku":"AMZ-1001","warehouse":"Bruchsal","qty":2,"success":true}

# 查询：SKU 汇总 + 各仓明细（可用/锁定双状态）
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/inventory/AMZ-1001"
# → Bruchsal 可用 318 / 锁定 14；Mönchengladbach 可用 95 / 锁定 3（两仓互不影响）

# 超量扣减必须被拒（否则就是超卖）
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/inventory/deduct?sku=AMZ-1001&warehouseCode=Bruchsal&qty=999999"
# → {"success":false}

# 释放（支付失败/取消/超时）：锁定 → 可用
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/inventory/release?sku=AMZ-1001&warehouseCode=Bruchsal&qty=2"

# 低库存预警 + 补货邮件（阈值 10，种子数据 AMZ-9999 只剩 5）
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/inventory/warnings/trigger?force=true"
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/inventory/warnings/status"
```

**然后打开浏览器看真实邮件**：<http://localhost:8025>（MailHog 收件箱）
主题：`[库存补货建议] AMZ-9999@Mönchengladbach 剩余 5（阈值 10）`，HTML 表格 + 建议补货量 15。

**台词**（这一段信息量最大，挑 3 点讲）：
1. **锁粒度是 `sku + warehouse`**（`inventory:lock:{sku}:{warehouse}`）——两仓操作互不阻塞，需求里问的正是"两仓库存不冲突"。
2. **双层防护**：Redisson 分布式锁 + JPA `@Version` 乐观锁；释放路径也走同一把锁，避免"扣减/释放并发互相覆盖"。
3. **邮件节流在 Redis 里**（`inventory:warning:last-mail-at`，TTL 30 分钟）——否则每 60 秒扫一次就发一封，收件人一天上千封。

> 想现场证明节流**不在进程内**：`docker exec aslp_redis redis-cli ttl inventory:warning:last-mail-at` → 会返回剩余秒数。

---

### 第 5 站 · M3 路径优化 VRP（2 分钟）★ 算法亮点：真实 km

```bash
# 不带 body = 内置演示问题（Bruchsal 总仓 → Karlsruhe / Frankfurt / Düsseldorf / Mönchengladbach，2 辆车）
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/routes/optimize
# → {"feasible":true,"routeCount":1,"stopCount":4,"totalDistanceKm":615.1,...}

# 几何锁定：单作业往返 Bruchsal→Karlsruhe 必须精确等于 38.6 km
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"depot":{"id":"Bruchsal","lat":49.1243,"lon":8.5987},
       "vehicles":[{"id":"V-01","capacity":10}],
       "deliveries":[{"id":"D-1","name":"Karlsruhe","lat":49.0069,"lon":8.4037,"demand":2}]}' \
  http://localhost:8080/api/routes/optimize
# → "totalDistanceKm":38.6

# 运力不足：不报 500，而是 200 + feasible:false + 未指派作业
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"depot":{"id":"B","lat":49.1243,"lon":8.5987},
       "vehicles":[{"id":"V-01","capacity":1}],
       "deliveries":[{"id":"D-BIG","name":"Karlsruhe","lat":49.0069,"lon":8.4037,"demand":50}]}' \
  http://localhost:8080/api/routes/optimize
```

**台词**（这里能讲出"踩过坑并修好"的深度）：
> "jsprit 默认用**坐标单位的欧氏距离** —— 经纬度下算出来的单位是『度』，Bruchsal→Karlsruhe 得到 0.19。我换成了自带的 Haversine 成本模型，单位才是 km；`38.6 km` 这个数字就是几何锁定的回归证据。
> 另外原来 `new SearchStrategyManager()` 是空策略注册表，算法必抛 `no search-strategy found`——改成官方高层入口 `Jsprit.Builder` 才真正跑起来。"

---

### 第 6 站 · M3 尾程追踪 DHL / DPD（2 分钟）★ 本轮新增，最"外部契约"的一站

```bash
# 单号自动识别承运商（DHL 20 位 / DPD 14 位）→ 统一状态
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/routes/tracking/00340434161094000000
# → "carrier":"DHL","state":"DELIVERED","carrierStatus":"delivered"
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/routes/tracking/01234567890123
# → "carrier":"DPD","state":"IN_TRANSIT"

# 异常态归一化（最容易错的地方：'Delivery attempt failed' 里含 deliver）
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/routes/tracking/00340434161094000001
# → "state":"EXCEPTION"

# 缓存硬证据：同一单号连查 3 次，上游只被打 1 次
curl -s -o /dev/null -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/routes/tracking/00340434161094000000
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/routes/tracking/00340434161094000000  # "stale":true
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"method":"GET","urlPath":"/track/shipments","queryParameters":{"trackingNumber":{"equalTo":"00340434161094000000"}}}' \
  http://localhost:8099/__admin/requests/count
# → {"count":1}   ← 这就是"缓存真的生效"的证据，不是嘴上说说

# 失败语义：查无此单 404 / 限流 503+Retry-After / 5xx 503 / 读超时 503 / 识别不出 400
curl -s -o /dev/null -w "查无此单: %{http_code}\n" -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/routes/tracking/00340434161094000002
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/routes/tracking/00340434161094000429
# → 503 {"error":"TRACKING_RATE_LIMITED","retryAfterSeconds":7,...}
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/routes/tracking/ABC-123"
# → 400（无法识别就要求显式指定，不猜）
```

**台词**：
> "两家承运商状态码体系完全不同（DHL 的 `pre-transit/transit/delivered/failure`，DPD 的 `PICKUP/IN_TRANSIT/...` 还大小写不统一）。**归一化只做一次、做在服务端**，前端不用为每家写一套判断。
> 失败分四类：查无此单 404（让用户核对单号）、不可用 503（稍后重试，限流带 `retryAfterSeconds`）、上游拒绝 502（我们自己看日志）、识别不出 400（要求显式指定）——**分错了，前端只能写一句『操作失败』**。"

> 这里用的桩在 `wiremock/mappings/tracking-*.json`（8 个），与 Amazon SP-API 的桩同一套机制。

---

### 第 7 站 · M4 状态机 + 审计 + **重启不丢**（2 分钟）★ 最硬的证据

```bash
# FBA 退货换标全流程
for e in PAY PICK SHIP FBA_RETURN RELABEL; do
  curl -s -o /dev/null -X POST -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8080/api/orders/state/DEMO-001/trigger?event=$e"
done
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/orders/state/DEMO-001
# → "currentState":"FBA_RELABELED"

# 事件轨迹（审计）：含被拒绝的尝试 → 回答"客户说点过按钮，为什么没生效"
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/orders/state/DEMO-001/history

# ★ 现场重启，状态仍在
docker compose restart aslp_order_service && sleep 15
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/orders/state/DEMO-001
# → "currentState":"FBA_RELABELED"   ← 内存实现会回到 CREATED
```

**台词**：
> "状态机的状态原来放在进程内的 `ConcurrentHashMap` 里——**服务一重启就回到起点**，多副本还各看一套。现在 DB 是唯一真相源：`order_state`（当前状态 + `@Version` 乐观锁）+ `order_state_event`（只追加的事件轨迹）。
> 每个请求都是『读库 → 纯逻辑判定 → 同事务写回 + 追加事件』，进程内不存任何状态。"

---

### 第 8 站 · M5 可观测性（3 分钟）★ 用来收尾最漂亮

按顺序打开四个界面（提前把标签页开好）：

| 顺序 | 地址 | 说什么 |
|---|---|---|
| ① | <http://localhost:3000/d/aslp-overview> | Grafana 看板 11 个面板：业务指标（订单拉取结局、VRP 里程、库存锁结果）+ 日志面板。**数据源与看板都是文件版本化的**，不靠手工点击 |
| ② | <http://localhost:9090/targets> | Prometheus 6/6 抓取目标 healthy（**注意 targets 用的是连字符别名** `aslp-order-service`，下划线会让 Tomcat 直接回 400） |
| ③ | <http://localhost:9411/zipkin/> | 点一条 trace：gateway → order-service 的跨服务调用链，同一个 traceId |
| ④ | <http://localhost:8085/dashboard.html> | **ECharts 业务看板**（订单分布 + 各仓分布 + 低库存带阈值线），15s 自刷新 |

抓一个跨服务 traceId 现场演示：

```bash
# 日志里的 traceId 能一键跳 Zipkin（Loki 数据源配了 derivedFields）
docker logs --since 5m aslp_order_service 2>&1 | grep -o '\[[0-9a-f]\{32\}-[0-9a-f]\{16\}\]' | head -1
```

报表看板的**降级语义**（如果时间够，这是最精彩的一段）：

```bash
# 停掉订单服务的一个上游 → 看板不白屏，而是明确标注"订单数据暂不可用"
docker compose stop aslp_order_service && sleep 5
curl -s "http://localhost:8085/api/reports/dashboard" | head -c 400
# → "degraded":true,"unavailable":["order-service"]，orders.available=false，库存区块照常显示
docker compose start aslp_order_service
```

**台词**："只读聚合方的依赖故障不该让整页 500，也不该返回一个『看起来正常的全 0 看板』——那会被读成『今天真的没有订单』。所以是 200 + `degraded:true` + `unavailable` 列表，**分块标注**。"

**如果想再压一个亮点**——单据对象存储（MinIO + 面单 PDF）：

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/orders/AMZ-1001/documents/shipping-label
# → objectKey=orders/AMZ-1001/shipping-label-<UTC>.pdf + 64 位 sha256；重复调用会新增版本（重打留痕）
```
MinIO 控制台 <http://localhost:9001>（`aslp-minio-admin` / `aslp-minio-secret`），桶 `aslp-documents`。

---

## 3. 测试数据速查表（背数字，别现场翻文件）

### 订单（`POST /api/orders/pull` 后落库 3 条）
| 单号 | 平台 | 商品 | 履约仓 | 状态 | 异常 |
|---|---|---|---|---|---|
| `AMZ-1001` | Amazon-Mock | 奶粉一件代发(6 罐装) | Bruchsal | PAID | — |
| `AMZ-1002` | Amazon-Mock | 婴儿车 AeroSleep 6 件套 | Mönchengladbach | PAID | — |
| `AMZ-1003` | Amazon-Mock | 婴儿床 | — | CREATED | **ADDRESS_INVALID**（可现场修正） |

### 库存（Flyway 种子数据）
| SKU | 仓库 | 可用 | 锁定 | 单价 |
|---|---|---|---|---|
| `AMZ-1001` | Bruchsal | 320 | 12 | 89.90 |
| `AMZ-1001` | Mönchengladbach | 95 | 3 | 89.90 |
| `AMZ-1002` | Bruchsal | 150 | 5 | 249.00 |
| `AMZ-1002` | Mönchengladbach | 28 | 2 | 249.00 |
| `AMZ-1003` | Bruchsal | 45 | 0 | 35.50 |
| `EBAY-2001` | Bruchsal / Mönchengladbach | 210 / 64 | 8 / 0 | 59.90 |
| **`AMZ-9999`** | **Mönchengladbach** | **5** | 0 | 119.00 ← **低库存样板，触发补货邮件** |

### 追踪单号（WireMock 桩场景开关）
| 单号 | 承运商 | 场景 | 期望 |
|---|---|---|---|
| `00340434161094000000` | DHL | 正常 | `state=DELIVERED` |
| `00340434161094000001` | DHL | 异常 | `state=EXCEPTION` |
| `00340434161094000002` | DHL | 查无此单 | 404 `TRACKING_NOT_FOUND` |
| `00340434161094000429` | DHL | 限流 | 503 `TRACKING_RATE_LIMITED` + `retryAfterSeconds=7` |
| `00340434161094000503` | DHL | 服务端故障 | 503 `TRACKING_UNAVAILABLE` |
| `00340434161094000003` | DHL | 超时（桩延迟 3s > 容器超时 1s） | 503（**未等满 3s**） |
| `01234567890123` | DPD | 正常 | `state=IN_TRANSIT` |
| `01234567890999` | DPD | 查无此单 | 404 |
| `ABC-123` | — | 识别不出 | 400（要求显式 `carrier`） |

### SP-API 契约桩（`POST /api/orders/spapi/probe?marketplaceId=`）
`A1PA6795UKMFR9`（正常 2 页）/ `AMZN-RATE-LIMITED` / `AMZN-TIMEOUT` / `AMZN-SERVER-ERROR` / `AMZN-BAD-REQUEST` / `AMZN-EMPTY`

### 状态机
订单号 `DEMO-001` 或任意自定义；事件 `PAY → PICK → SHIP → FBA_RETURN → RELABEL → DELIVER → COMPLETE`

### 账号与端口
- JWT：`curl -X POST "http://localhost:8080/api/auth/login?username=admin"`（拿 `token` 字段；docker profile 下所有接口都要带）
- 网关 8080 / order 8081 / inventory 8082 / route 8083 / auth 8084 / report 8085
- Grafana 3000（匿名只读，admin 密码 `aslp-admin`）· Prometheus 9090 · Zipkin 9411 · MailHog 8025 · MinIO 9001 · WireMock 管理 8099

---

## 4. 被追问时的对答（10 条，按被问概率排序）

**Q1：为什么是微服务？这么小的系统做成单体不是更好？**
> "对，这个规模**单体完全够用**，而且我现在这台机器上 6 个 JVM 就是主要开销。
> 选微服务是因为它是我要练的目标：独立部署、独立扩容、故障隔离、按领域分工。
> 但我把代价都记在文档里了——比如所有服务共用一个 PostgreSQL 实例，这其实更接近**分布式单体**（`readme §10 限制`）。
> 真正的微服务要每个服务自己的库，那需要先有服务注册/配置中心（我列在 P2 路线图里）。"

**Q2：限流为什么不用全局限流？**
> "试过，踩了两个坑：一是 SCG 的 `RedisRateLimiter` **令牌桶 key 不含 routeId**（我核对了 4.1.5 的字节码，`getKeys(String)` 只收解析器输出），两条路由复用同一解析器会**共用桶**，阈值小的会把阈值大的也限死；二是全局桶被突发流量耗尽后，正常请求成片 429。
> 所以改成**按需挂路由**：`/api/orders/pull` 用户维度 1 次/秒（它一次会放大成 N 次平台调用），认证入口按 IP（登录前没有用户身份）。并且给两边加 `user:` / `ip:` 前缀隔离桶。"

**Q3：分布式锁怎么保证两个仓不冲突？**
> "锁粒度是 `sku + warehouse`：`inventory:lock:{sku}:{warehouse}`。两仓操作落不同的键，**天然不互相阻塞**；同仓同 SKU 才互斥，这才是防超卖需要的最小粒度。
> 等待 2 秒、租约 10 秒（租约到期自动释放，防死锁）；再叠一层 JPA `@Version` 乐观锁兜住分布式锁失效的情况（比如 Redis 主从切换）。
> 释放路径走同一把锁，并校验『释放量 ≤ 当前锁定量』——原来的实现用 `Math.max(0, ...)` 掩盖了越界，会**凭空增加可用库存**。"

**Q4：熔断降级后接口返回什么？**
> "200 + `degraded:true`，而不是 500。因为『平台暂时挂了』不是调用方的错，返回 500 会让前端把它当 bug。
> Resilience4j 注解顺序是 Retry → CircuitBreaker → Bulkhead，**fallback 挂在最外层 Retry 上**（挂在 CB 上会被内层吞掉异常，导致重试永不触发）。
> 端到端可验证：连续失败后 `/actuator/circuitbreakers` 显示 OPEN，随后自动半开恢复。"

**Q5：重试是怎么分类的？**
> "**重试语义由异常继承线承载**，而不是散落在各处 `if`：429 `RateLimitedException` **继承** `PlatformUnavailableException` → 自动命中 yml 里的 `retry-exceptions`，一行配置都不用改；其他 4xx `SpApiClientException` 落在体系外 → 天然不重试。
> 这条线其实是『平台抖动』与『我们自己写错了』的分界线。我用真实的 `RetryConfig.getExceptionPredicate()` 做了断言，防止代码与配置漂移。"

**Q6：你怎么知道这些功能真的在工作，而不是"看起来在工作"？**
> 这是我最有体会的一点，可以举三个例子：
> - **报表服务原来是硬编码假数组**，所有断言都过（因为只断言了 `report-service up`）。所以我把断言换成『与订单库同源的数字』和『与邮件 SKU 完全一致的明细』。
> - **"缓存生效"我用 WireMock 按单号精确计数证明**：3 次查询上游只被调 1 次，`refresh` 后变 2 次。
> - **"状态持久化"必须重启容器才算证明**：单测里"换一个 service 实例"只覆盖同一 JVM；真重启才能覆盖 Flyway 重跑 + Spring 重新装配。所以 `container-verify.sh` 里有 6/6 阶段专门做这件事。"

**Q7：单测全绿就够了吗？**
> "不够，我自己踩过两次：`@ConfigurationProperties` 忘了注册进 `@EnableConfigurationProperties`、`@Scheduled` 写了 `60s`（它只认毫秒或 ISO-8601）——这两类问题**编译期无错、单元测试全绿**，只有真启动 Spring 上下文才炸。
> 所以流程里强制加了一步『容器冷启动』。"

**Q8：观测数据是怎么组织的？**
> "一条原则：**低基数进指标，高基数进 span 或日志**。
> 比如订单号、traceId、里程这些高基数字段，绝不进 Prometheus 标签（时间序列会爆），只进 span；traceId 也不做 Loki 标签，而是在 Grafana 数据源里用 `derivedFields` 查询时抽取，生成『跳 Zipkin』的链接。
> 业务埋点用 `Observation` 一份代码同时产出 span 和指标——**不手写同名 Timer**，否则计数翻倍。"

**Q9：如果让你上线，还差什么？**
> "`readme §10` 列了完整清单，我最在意这四条：
> ① 数据库还没拆（现在是共享库，属分布式单体）；
> ② 观测端点没鉴权、Grafana 是匿名只读，生产要独立 management 端口 + 来源限制；
> ③ 没有 CI/CD 与质量门禁（SonarQube/JaCoCo 门槛）；
> ④ 追踪没有落地成事件表，所以做不了『包裹卡住 3 天』的主动告警。
> 另外 JWT 是对称密钥、auth-service 还没接用户表，这两条在 P2 路线图里。"

**Q10：这个项目你最大的收获是什么？**
> "**把『看起来对了』和『验证过是对的』分开。**
> 这个项目 54 个缺陷里，有一大半不是我写错逻辑，而是**我误以为它在工作**：配置没生效、异常被吞、假数据通过了断言、状态只活在内存里。
> 所以我现在的习惯是：每加一个依赖或配置类，一定跑一次容器冷启动；每写一条断言，先问自己『它失败的时候，我会不会知道原因』。"

---

## 5. 现场出问题怎么办（排障速查）

| 症状 | 原因 / 处理 |
|---|---|
| `docker compose` 命令直接失败、报 YAML 错 | 用 `docker compose config` 先校验；historically 是给服务加网络别名时出现重复的 `networks:` 键 |
| 某个服务容器反复重启 | `docker logs --tail 50 <容器>`。最常见两类：`@ConfigurationProperties` 没注册、`@Scheduled` 写了 `60s` 简写 |
| 所有服务起不来，日志报 `No space left on device` | Docker 虚拟机磁盘满：`docker image prune -f && docker builder prune -f`（**不要加 `--volumes`**，本机还有别的项目在用数据卷） |
| MinIO 报 `reached its minimum free drive threshold` | 同上，磁盘满。单据接口会返回 503 + `STORAGE_UNAVAILABLE` + 原因（这是设计好的失败语义，不是 bug） |
| 网关返回 503、路由不通 | 检查是否用了**下划线主机名**（`aslp_order_service`）。`java.net.URI` 会把含 `_` 的 authority 解析成 registry-based，导致 `getSchemeSpecificPart()` 异常。容器间寻址一律用连字符别名 `aslp-order-service` |
| 报 401/403 但明明带了 token | docker profile 才强制鉴权；`/actuator/prometheus` 已在白名单里。注意 auth-service 关了 httpBasic，所以**匿名是 403 而不是 401**（容易误判成权限问题） |
| Prometheus 里查不到指标 | 抓取周期 15s，刚产生的指标需要等一轮；**断言要用有界轮询**，不能一次查空就判失败 |
| 追踪接口全返回 503 | 检查 `aslp_wiremock` 容器是否 healthy（`curl localhost:8099/__admin/health`） |
| 想全部重来 | `docker compose down -v && bash scripts/container-verify.sh`（`-v` 会清掉数据卷，让 Flyway 重新灌种子数据） |

---

## 6. 一句话收尾（离场前说）

> "整个项目 6 个服务、256 个单元测试、124 项端到端断言，全部由两条命令验证：
> `mvn clean package -T 1C` 和 `bash scripts/container-verify.sh`。
> 而且**每一个踩过的坑都写在 `readme.md §9 的 54 条缺陷表里**——包括根因和我改错了什么。
> 因为我认为一个工程师的能力，一半体现在『能跑通』，另一半体现在『知道自己哪里错过、以及怎么避免再错』。"
