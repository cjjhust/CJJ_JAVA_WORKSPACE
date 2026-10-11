# OpenPlan Engine - 企业级数据规划、写回与运筹优化系统

## 🎯 项目总体目标

构建一个基于 Java (Spring Boot) 与 React 的企业级多维数据规划、协同写回与运筹优化引擎。该系统允许业务人员与财务分析师在前端类 Excel 界面中进行**数据填报写回 (Writeback)**、**多维数据拆分**、**What-If 假设分析**，并内置**运筹学优化算法（线性规划/整数规划）\**与\**动态规划模型**，自动计算最优资源配置、预算分配与目标反推（Goal Seek）。

## 🛠️ 技术栈选型

- **后端 (Backend)**: Java 21 (Spring Boot 3.x) + Spring Security (JWT) + Spring Data JPA / MyBatis-Plus
- **运筹学与算法引擎 (OR/Algo Engine)**:
  - Apache Commons Math / Google OR-Tools Java API (用于线性规划 LP / 混合整数规划 MIP)
  - Java 自研动态规划 (DP) 状态转移计算模块 (用于多阶段预算最优分配)
- **前端 (Frontend)**: React 18 + TypeScript + Tailwind CSS + Ag-Grid Community / Enterprise (或 `@univerjs/core`) + Lucide Icons
- **数据库/缓存**: PostgreSQL (主存事实表/维度表) + Redis (分布式单元格锁/缓存)
- **运维与部署 (DevOps)**: Docker + Docker Compose + GitHub Actions

## 📋 分阶段开发任务清单 (Roadmap)

### Phase 1: 基础架构、Docker 环境与数据模型 (Foundation & Docker)

- [ ] **1.1 Docker 与开发环境构建**
  - [ ] 配置 `docker-compose.yml` (PostgreSQL 16, Redis 7, Spring Boot Admin)
  - [ ] 构建 Spring Boot 3.x 项目骨架 (集成 Lombok, Spring Security, OpenAPI/Swagger)
  - [ ] 搭建 React + Vite + Tailwind CSS 前端基础工程
- [ ] **1.2 数据库 Schema 设计 (星型模型)**
  - [ ] 设计事实表：实际值表 (`fact_actuals`)、规划写回表 (`fact_plan`)
  - [ ] 设计维度表：科目维度 (`dim_account`)、组织/部门维度 (`dim_entity`)、时间维度 (`dim_time`)
  - [ ] 设计控制表：版本表 (`sys_versions`)、数据变更审计日志表 (`sys_audit_logs`)
- [ ] **1.3 前端高性能矩阵视图与后端多维聚合 API**
  - [ ] 后端实现基于 PostgreSQL 窗口函数的高性能多维数据切片/切块 API
  - [ ] 前端基于 React + Tailwind CSS 集成 Ag-Grid，实现数据网格渲染
  - [ ] 实现树形层级展开/折叠（例如：总公司 -> 分公司 -> 部门 -> 项目）

### Phase 2: 核心写回引擎、分布式锁与行级安全 (Writeback & Security)

- [ ] **2.1 单元格写回与审计 (Cell-Level Writeback & Audit)**
  - [ ] 前端实现单元格编辑高亮 (Dirty Cell State) 与“一键批量提交”
  - [ ] 后端实现 `@Transactional` 事务写回，将新值增量更新/写入 `fact_plan`
  - [ ] 实现完整的审计日志（记录：修改人、时间戳、维度坐标、修改前旧值、修改后新值）
- [ ] **2.2 分布式并发锁与协同 (Concurrency Control)**
  - [ ] 基于 Redis (Redisson) 实现单元格/维度区域锁，防止多人同时修改同一部门预算
  - [ ] WebSocket 实现前端实时加锁通知（提示：“该单元格正由张三编辑中”）
- [ ] **2.3 行级安全与权限管控 (Row-Level Security - RLS)**
  - [ ] 设计基于维度授权的 RBAC 规则（如：华东区经理仅对 `dim_entity = 'EAST_CHINA'` 拥有写权限）
  - [ ] 在 Spring Security 中集成自定义 RLS 拦截器，动态拼接 SQL/JPA 过滤条件

### Phase 3: 运筹学 (OR) 与动态规划 (DP) 算法引擎 (Operations Research & Smart Planning)

- [ ] **3.1 比例拆分与目标反推 (Top-Down Allocation & Dynamic Goal Seek)**
  - [ ] 实现自顶向下（Top-Down）分摊算法（修改父节点指标，自动按历史比例/均等比例下发至子节点）
  - [ ] **[动态规划算法]** 实现基于 DP 的多阶段预算求解：在给定的总预算约束下，通过 DP 寻找各季度/各项目收益最大化的预算分配路径
- [ ] **3.2 运筹学优化引擎集成 (LP / MIP Optimization)**
  - [ ] 后端集成 **Google OR-Tools** / **Apache Commons Math** 求解器
  - [ ] **[运筹学场景 1：资源约束下的利润最大化]**
    - *定义约束条件*：原材料上限、工时上限、预算上限
    - *求解目标*：计算各产品线的最优计划产量，并将结果自动回填至规划表格
  - [ ] **[运筹学场景 2：成本最小化采购/供应链规划]**
    - 根据不同供应商的阶梯报价与运输成本，自动优化采购计划配额
- [ ] **3.3 What-If 假设分析与多场景并行比对 (Scenario Management)**
  - [ ] 实现一键基于当前 Plan 创建基准 (Base Case)、乐观 (Best Case) 与悲观 (Worst Case) 场景
  - [ ] 前端提供并排（Side-by-Side）差异对比视图，并实时计算 Variance（方差与偏差百分比）

### Phase 4: 工作流审批、系统发布与 Docker 交付 (Workflow & DevOps)

- [ ] **4.1 预算提交与状态机控制 (Workflow Engine)**
  - [ ] 基于状态模式设计预算生命周期：`DRAFT (草稿)` -> `SUBMITTED (已提交)` -> `APPROVED (已批准)` / `REJECTED (已退回)`
  - [ ] 状态锁定机制：状态为 `APPROVED` 后，数据库自动加锁，禁止任何写回操作
- [ ] **4.2 导出、可视化与 Docker 部署包**
  - [ ] 使用 Apache POI 实现带样式、带公式的 Excel 导出
  - [ ] 提供前端 Tailwind CSS 风格的可视化看板（配合 ECharts 展示规划 vs 实际仪表盘）
  - [ ] 编写多阶段构建 Dockerfile (Multi-stage Build)，将 React 前端构建产物注入 Spring Boot 静态资源或配置 Nginx 反向代理，实现 `docker compose up -d` 一键部署

###  运筹学与算法结合的硬核亮点

在这个规划中，我们将运筹学与算法以极具实用价值的方式嵌入到了企业的“预算与规划”场景中：

1. **动态规划 (Dynamic Programming - DP)**:
   - **应用场景**：**多阶段资源/预算分配问题**（Resource Allocation Problem）。
   - **解决问题**：例如企业有 1000 万的总资本，如何在 5 个不同的业务线、跨 4 个季度进行多阶段投资，使得最终的年终总 ROI（投资回报率）最大化？DP 可以在多阶段决策中求得全局最优解。
2. **运筹学/线性规划 (Operations Research / Linear Programming - LP)**:
   - **应用场景**：**目标求解与最佳配额（Goal Seek & Product Mix Optimization）**。
   - **解决问题**：用户只需要在前端填写“期望达到 5000 万利润”，系统通过 OR-Tools 求解器，在考虑产能、人力、原料成本等数十个约束条件下，反推出各个部门**必须完成的最低销售量和成本限额**，并直接**写回**到规划表格中。

这种“传统 BI 写回 + 运筹学优化求解”的结合，直接将 OpenPlan Engine 的定位从普通的“Excel 替代品”提升到了“智能决策支持系统 (Prescriptive Analytics / DSS)” 的高度！

**OpenPlan Engine 自身就是一个完整的“现代 Web 版数据规划系统”。它自带数据源和计算引擎，不需要依附于 Power BI 或 Excel！你完全可以使用真实的模拟业务数据进行极其酷炫的动态展示。**

### 一、 做完这个项目，你能实现哪些硬核功能？

这个项目做完后，你拥有的是一个**Web 端的 Enterprise Planning（企业级规划与写回）系统**。

核心功能包括：

1. **类 Excel 的交互体验（Web 端）**：
   - 在浏览器里直接展示跨区域、跨产品、跨月份的多维数据矩阵表。
   - 支持单元格直接双击修改、高亮、撤销/重做、列冻结。
2. **智能自顶向下分摊算法（Allocation Engine）**：
   - **示例**：你在“德国区-总销售额”单元格里把数值从 100 万改成 120 万，系统会在 10 毫秒内，自动按照历史占比将这 20 万增量**拆分**给杜塞、科隆、法兰克福等各个分公司的具体产品线。
3. **并发安全的实时写回（Writeback with Transaction Lock）**：
   - 点击“提交写回”，数据实时存入 PostgreSQL / Delta Lake 数据库，并产生不可篡改的**审计日志（Audit Trail）**（记录：谁在几点几分把哪一行从多少改成了多少）。
4. **运筹学/目标求解（Goal Seek Optimization）**：
   - **示例**：设定“2027年公司总利润目标 = 500 万欧元”，点击“求解”，后端运筹学引擎（OR-Tools）在满足“预算不超过 80 万、人力上限 20 人”的约束下，**反向计算出每个月各产品的最佳计划产量**并填入表格。
5. **动态规划 scenario（What-If 模拟）**：
   - 拖动滑动条改变原材料成本参数，整张表的预测数据实时动态联动更新。

### 二、 这个项目不包含 Excel/Power BI，数据从哪里来？

**不需要依赖外界的 Excel 或 Power BI，你的系统自己就是数据源的管理者！**

你在做项目时，可以在后端（PostgreSQL）中准备或通过 Seed 脚本生成**真实的企业业务数据集（Mock Data）**。例如：

- **数据维度**：包含 3 个地区（德国、法国、英国）、5 条产品线、12 个月份。
- **数据度量**：销售量（Units）、单价（Price）、销售额（Revenue）、成本（Cost）、利润（Profit）。
- **单元格数量**：3 * 5 * 12 * 5 = 900 个核心单元格（或者扩充到数万个格）存放在你的数据库中。

你的系统**架构**是这样的：

> * PostgreSQL 数据库 -Spring Boot 21 后端引擎-React + Ag-Grid 前端 Web 界面

Power BI 和 Excel 在这里扮演的角色，只是**原版 Lumel 产品的前端宿主**；而在你的开源项目中，**React + Ag-Grid 网页就是你的“专属宿主界面”**，不仅更轻量，还能加入你自定义的图形与交互！

### 三、 怎么把这个项目向德国 HR / 面试官展示？（展示方式）

在投递简历和面试时，纯代码库（GitHub）很少有人会仔细看，你必须用**最直观、最现代**的方式展示它：

#### 1. 部署一个在线 Live Demo（最强杀伤力）

- 利用 **Docker**，将前端、后端、PostgreSQL 和 Redis 一键部署在免费/低成本云服务器（如 Hetzner、AWS、Render）上。
- 在你的简历顶部和 LinkedIn 上附上链接：`[https://openplan-demo.yourdomain.com](https://openplan-demo.yourdomain.com)`。
- 面试官在浏览器点开就能直接试用！

#### 2. 录制 1~2 分钟的精致 GIF / 视频 Demo

- 德国 HR 或 Tech Lead 浏览简历的时间非常短。在你的 GitHub `README.md` 首页，直接放几个**动态 GIF 动图**：
  - **GIF 1：分摊与写回** —— 展示在前端父节点修改数值，子节点按比例联动自动更新，并弹出“Writeback Successful”通知。
  - **GIF 2：运筹学求解** —— 点击“Goal Seek”，输入目标利润，表格自动充填最优方案并弹出收敛曲线。
  - **GIF 3：审计追踪** —— 点击某个单元格，右侧抽屉弹出来展示该单元格的历史修改日志。

#### 3. 架构图与技术亮点展示（System Architecture Diagram）

- 在 GitHub README 和简历中附上一张使用 Mermaid 或 Excalidraw 画的**系统架构图**，列出技术硬实力：
  - 后端：`Java 21 Virtual Threads` + `Spring Boot 3` + `Google OR-Tools`
  - 事务与并发：`Redis (Redisson)` 分布式锁，确保并发写回不冲突
  - 存储：`PostgreSQL` + `Flyway` 数据库版本管理
  - 部署：`Docker Compose` 容器化

### 总结

- 你不需要担心没有数据，你通过后端数据库 Seed 脚本注入的**企业财务/供应链模拟数据**，足以让系统完美运转。
- 这个项目展示出来的效果**不是一个简单的网站**，而是一个**能够处理海量多维数据、具备复杂算法与高并发锁控制的企业级 Web 软件**，展示方式以 **Live Demo + 动图演示 + 架构图** 为主！