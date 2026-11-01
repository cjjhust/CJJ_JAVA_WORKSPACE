# 📋 GDPR Security Audit System — 27 项任务详细清单

> 共 4 个里程碑，27 个细分任务，总预估 ~40.5 小时

> Phase 1 项目现状、三语架构/流程、运行截图与 Debug 知识库见 [`readme.md`](readme.md)。企业级防篡改审计作为独立 Phase 2 路线规划于本文末尾；未完成 Phase 2 前，不应将当前实现描述为生产级不可篡改审计。

## 作品集收尾快照（2026-11-01）

### 收尾结论

- **Phase 1（Task 01-27）**：按既有记录完成，具备 Keycloak/RBAC、审计切面、PII 掩码、PG/ELK、Kibana 和容器化演示。
- **本地 Phase 2 能力**：已实现 HMAC 链、Transactional Outbox、数据库加固脚本、安全拒绝事件、完整性巡检、SAFE MODE、死信回放和指标；其中部分仍缺全栈故障/重投验收。
- **作品集结论**：可作为本地可运行的安全审计演示项目收尾；不能称为生产级不可篡改审计或 DSGVO 合规认证。

### 本轮实测

- `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock mvn -Dapi.version=1.44 verify`：`BUILD SUCCESS`；PG `AuditLogRepositoryIT` 4/4、ES `ElasticsearchAuditIT` 1/1 通过，JaCoCo `GdprDataMasker` 51/56 行（91.1%）达到 90% 门槛。
- 修复 `AuditLog` 的 `@CreationTimestamp` 与链签名输入不一致问题：链服务生成的微秒时间戳不再被 Hibernate 覆盖；正常链校验通过，数据库篡改后校验失败。
- `scripts/db-hardening.sql` 在一次性 PostgreSQL 16 容器验证：应用账号可 INSERT 审计行并推进链头；UPDATE/DELETE 审计行被拒；链头回退触发器生效。未对 Compose 长期运行数据库执行该脚本。
- `docker compose config --quiet` 通过；现有 Compose 容器运行中，`/api/public/health` 返回 `UP`。这是对既有容器的健康检查，不代表当前工作区源码已重新构建部署。
- 匿名请求 `/actuator/prometheus` 返回 401，符合当前 `anyRequest().authenticated()` 安全规则；实际 Prometheus 抓取需要配置认证凭据。

### 仍未完成的验收

- **本地可继续验证**：Task 30 Outbox 故障/重复投递端到端；Task 34 SIEM 通道；Task 35 Keycloak 登录与管理事件；Task 38 崩溃/重启注入；Task 39 重放去重；Task 40 Prometheus 认证抓取。Task 31 已通过一次性数据库验证，但未应用到当前演示数据库。
- **必须依赖外部条件**：Task 28 的 DPO/法务签署；Task 32 KMS/HSM 非导出密钥；Task 33 独立 WORM 账户；Task 36 DSGVO/DSAR 法律决策；Task 37 生产部署、CI、备份恢复及 RPO/RTO 演练。

详细项目状态及面向简历的表述边界见 [`readme.md`](readme.md)。

---

## 🟢 Milestone 1.1：Keycloak 身份认证与 OAuth2 集成（8 个任务，~8.5h）

---

### Task 01：Docker 启动 Keycloak 并创建 Realm
- **目标**：在本地通过 Docker 启动 Keycloak 实例，创建名为 `audit-realm` 的安全域
- **详细步骤**：
  1. 运行 `docker-compose up -d keycloak postgres`，启动 Keycloak 和 PostgreSQL
  2. 访问 `http://localhost:8080`，使用 admin/admin_password 登录 Keycloak Admin Console
  3. 点击左上角下拉菜单 → "Create Realm"
  4. 输入 Realm Name: `audit-realm`，点击 Create
  5. 验证新 Realm 创建成功，左上角显示 `audit-realm`
- **产出**：运行中的 Keycloak 实例 + `audit-realm` 安全域
- **验收标准**：浏览器可访问 `http://localhost:8080/realms/audit-realm/.well-known/openid-configuration` 并返回 JSON
- **预估工时**：1h

---

### Task 02：配置 Keycloak Client（audit-api）
- **目标**：在 `audit-realm` 中创建一个 OAuth2 Client，供 Spring Boot 后端进行 JWT 校验
- **详细步骤**：
  1. 在 Keycloak Admin → `audit-realm` → Clients → Create Client
  2. 设置 Client ID: `audit-api`
  3. Client Authentication: ON（即 confidential 模式）
  4. Valid Redirect URIs: `http://localhost:8081/*`
  5. Web Origins: `http://localhost:8081`
  6. 进入 Credentials 标签，记录 Client Secret
  7. 在 Client Scopes 中确认 `roles` scope 已包含，确保 JWT 的 `realm_access.roles` 字段被填充
- **产出**：Keycloak 中配置好的 `audit-api` Client
- **验收标准**：可以通过 `curl` 向 Keycloak Token Endpoint 请求 Access Token，且 Token 中包含 `realm_access.roles` 字段
- **预估工时**：0.5h

---

### Task 03：创建 Realm 角色与测试用户
- **目标**：创建 3 个角色和 3 个测试用户，用于 RBAC 权限验证
- **详细步骤**：
  1. Keycloak Admin → Realm Roles → Create Role，依次创建：
     - `ROLE_ADMIN`：系统管理员，拥有所有权限
     - `ROLE_AUDITOR`：审计员，拥有查看审计日志的权限
     - `ROLE_USER`：普通用户，仅有基本操作权限
  2. Users → Add User，依次创建 3 个测试用户：
     - `admin-user` → 分配 `ROLE_ADMIN`
     - `auditor-user` → 分配 `ROLE_AUDITOR`
     - `regular-user` → 分配 `ROLE_USER`
  3. 为每个用户在 Credentials 标签中设置密码，关闭 "Temporary" 开关
- **产出**：3 个角色 + 3 个测试用户
- **验收标准**：使用 `regular-user` 获取的 Token 中 `realm_access.roles` 仅包含 `ROLE_USER`
- **预估工时**：0.5h

---

### Task 04：导出 Realm 配置（realm-export.json）
- **目标**：将 Keycloak 的 Realm、Client、Role、User 配置导出为 JSON 文件，实现配置即代码，可重复部署
- **详细步骤**：
  1. 方式一：Keycloak Admin UI → Realm Settings → Action → Partial Export → 勾选所有选项 → Export
  2. 方式二（推荐）：修改 `docker-compose.yml`，在 Keycloak 启动命令中添加 `--import-realm`，并将 `realm-export.json` 挂载到 `/opt/keycloak/data/import/`
  3. 将导出的 JSON 文件保存到项目 `keycloak/realm-export.json`
  4. 修改 `docker-compose.yml`，添加 volume 挂载：
     ```yaml
     volumes:
       - ./keycloak/realm-export.json:/opt/keycloak/data/import/realm-export.json
     command: start-dev --import-realm
     ```
- **产出**：`keycloak/realm-export.json` + 更新后的 `docker-compose.yml`
- **验收标准**：删除 Keycloak 容器和 volume 后重新 `docker-compose up`，Realm/Client/Role/User 自动恢复
- **预估工时**：1h

---

### Task 05：编写 SecurityConfig — OAuth2 Resource Server 配置
- **目标**：配置 Spring Security，使应用作为 OAuth2 Resource Server，使用 JWT 进行身份验证
- **详细步骤**：
  1. 创建 `src/main/java/com/gdpr/audit/config/SecurityConfig.java`
  2. 使用 `@Configuration` + `@EnableWebSecurity` + `@EnableMethodSecurity` 注解
  3. 定义 `SecurityFilterChain` Bean：
     - 关闭 CSRF（REST API 不需要）
     - 设置无状态 Session 策略 (`SessionCreationPolicy.STATELESS`)
     - 配置路径权限：`/api/admin/**` 需要 `ROLE_ADMIN`，`/api/audit/**` 需要 `ROLE_AUDITOR`，`/api/public/**` 允许匿名
     - 启用 `oauth2ResourceServer().jwt()` 并注入自定义的 JwtRoleConverter
  4. 定义 `JwtDecoder` Bean（可选，Spring Boot 自动根据 `issuer-uri` 配置）
- **关键代码结构**：
  ```java
  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
      http
          .csrf(csrf -> csrf.disable())
          .sessionManagement(sm -> sm.sessionCreationPolicy(STATELESS))
          .authorizeHttpRequests(auth -> auth
              .requestMatchers("/api/public/**").permitAll()
              .requestMatchers("/api/admin/**").hasRole("ADMIN")
              .requestMatchers("/api/audit/**").hasRole("AUDITOR")
              .anyRequest().authenticated()
          )
          .oauth2ResourceServer(oauth2 -> oauth2
              .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtRoleConverter()))
          );
      return http.build();
  }
  ```
- **产出**：`config/SecurityConfig.java`
- **验收标准**：无 Token 访问受保护接口返回 401；携带有效 Token 且角色正确返回 200
- **预估工时**：2h

---

### Task 06：编写 JwtRoleConverter — Keycloak 角色映射
- **目标**：将 Keycloak JWT Token 中的 `realm_access.roles` 数组提取并转换为 Spring Security 的 `GrantedAuthority`
- **详细步骤**：
  1. 创建 `src/main/java/com/gdpr/audit/security/JwtRoleConverter.java`
  2. 实现 `Converter<Jwt, AbstractAuthenticationToken>` 接口
  3. 从 JWT Claims 中提取 `realm_access` → `roles` 列表
  4. 将每个角色转换为 `SimpleGrantedAuthority("ROLE_" + role)`（注意 Keycloak 的角色名可能不带 `ROLE_` 前缀，需统一处理）
  5. 返回 `JwtAuthenticationToken`，包含提取的权限列表
- **关键代码结构**：
  ```java
  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
      Map<String, Object> realmAccess = jwt.getClaim("realm_access");
      Collection<String> roles = (Collection<String>) realmAccess.get("roles");
      List<GrantedAuthority> authorities = roles.stream()
          .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
          .toList();
      return new JwtAuthenticationToken(jwt, authorities, jwt.getClaimAsString("preferred_username"));
  }
  ```
- **产出**：`security/JwtRoleConverter.java`
- **验收标准**：使用 `admin-user` Token 访问 `/api/admin/**` 返回 200，使用 `regular-user` Token 访问同一接口返回 403
- **预估工时**：1.5h

---

### Task 07：创建测试用 Auth Controller
- **目标**：创建用于验证认证和授权是否正确工作的 REST API 端点
- **详细步骤**：
  1. 创建 `src/main/java/com/gdpr/audit/controller/AuthTestController.java`
  2. 定义以下端点：
     - `GET /api/public/health` — 无需认证，返回 `{"status": "UP"}`
     - `GET /api/user/profile` — 需认证，返回当前用户信息（从 JWT 提取）
     - `GET /api/admin/dashboard` — 需 `ROLE_ADMIN`，返回管理后台信息
     - `GET /api/audit/logs` — 需 `ROLE_AUDITOR`，返回审计日志列表（暂时返回模拟数据）
  3. 使用 `@PreAuthorize` 注解进行方法级权限控制
  4. 通过 `@AuthenticationPrincipal Jwt jwt` 参数注入当前用户的 JWT 信息
- **产出**：`controller/AuthTestController.java`
- **验收标准**：4 个端点按预期的权限规则工作
- **预估工时**：1h

---

### Task 08：端到端认证流程验证
- **目标**：完整验证从 Keycloak 获取 Token → 调用受保护 API → RBAC 权限控制的全链路
- **详细步骤**：
  1. 使用 curl 向 Keycloak Token Endpoint 请求 Access Token：
     ```bash
     curl -X POST http://localhost:8080/realms/audit-realm/protocol/openid-connect/token \
       -d "grant_type=password" \
       -d "client_id=audit-api" \
       -d "client_secret=<YOUR_SECRET>" \
       -d "username=admin-user" \
       -d "password=admin123"
     ```
  2. 解码返回的 JWT Token（使用 jwt.io），确认 `realm_access.roles` 包含正确角色
  3. 携带 Token 访问各端点，验证：
     - `admin-user` 可访问 `/api/admin/**`，不可访问 `/api/audit/**`
     - `auditor-user` 可访问 `/api/audit/**`，不可访问 `/api/admin/**`
     - `regular-user` 仅可访问 `/api/user/**`
     - 无 Token 仅可访问 `/api/public/**`
  4. 记录测试结果，确认所有场景通过
- **产出**：验证通过的测试记录
- **验收标准**：所有 RBAC 场景均按预期工作，无一失败
- **预估工时**：1h

---

## 🔵 Milestone 1.2：审计日志切面与 GDPR 数据掩码（9 个任务，~13h）

---

### Task 09：设计 AuditLog 实体类
- **目标**：创建 JPA 实体，用于在 PostgreSQL 中持久化审计日志记录
- **详细步骤**：
  1. 创建 `src/main/java/com/gdpr/audit/entity/AuditLog.java`
  2. 使用 `@Entity` + `@Table(name = "audit_logs")` 注解
  3. 定义以下字段：
     | 字段 | 类型 | 说明 |
     |------|------|------|
     | `id` | `Long` | 主键，自增 |
     | `timestamp` | `LocalDateTime` | 操作时间 |
     | `userId` | `String` | 操作人 ID（从 JWT 提取） |
     | `username` | `String` | 操作人用户名 |
     | `userRoles` | `String` | 操作人角色（逗号分隔） |
     | `action` | `String (Enum)` | 操作类型：VIEW / DOWNLOAD / MODIFY / DELETE |
     | `resourceType` | `String` | 资源类型（如 User、Document、Report） |
     | `resourceId` | `String` | 被操作资源的 ID |
     | `requestMethod` | `String` | HTTP 方法（GET/POST/PUT/DELETE） |
     | `requestUri` | `String` | 请求路径 |
     | `requestBody` | `String (TEXT)` | 请求体（脱敏后） |
     | `responseStatus` | `Integer` | HTTP 响应状态码 |
     | `clientIp` | `String` | 客户端 IP 地址 |
     | `userAgent` | `String` | 客户端 User-Agent |
     | `duration` | `Long` | 方法执行耗时（毫秒） |
     | `success` | `Boolean` | 操作是否成功 |
     | `errorMessage` | `String (TEXT)` | 失败时的错误信息（脱敏后） |
  4. 添加 `@CreationTimestamp` 自动填充 `timestamp`
  5. 添加数据库索引：`@Index` on `(userId, timestamp)` 和 `(action, timestamp)`
  6. 使用 Lombok `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`
- **产出**：`entity/AuditLog.java`
- **验收标准**：应用启动后，PostgreSQL 中自动创建 `audit_logs` 表，字段和索引与设计一致
- **预估工时**：1h

---

### Task 10：创建 AuditLogRepository
- **目标**：创建 Spring Data JPA Repository，提供审计日志的 CRUD 和自定义查询能力
- **详细步骤**：
  1. 创建 `src/main/java/com/gdpr/audit/repository/AuditLogRepository.java`
  2. 继承 `JpaRepository<AuditLog, Long>`
  3. 定义自定义查询方法：
     ```java
     // 按用户ID查询操作记录
     List<AuditLog> findByUserIdOrderByTimestampDesc(String userId);
     
     // 按操作类型 + 时间范围查询
     List<AuditLog> findByActionAndTimestampBetween(String action, LocalDateTime start, LocalDateTime end);
     
     // 按资源类型查询
     List<AuditLog> findByResourceTypeOrderByTimestampDesc(String resourceType);
     
     // 统计某用户在指定时间段内的操作次数（用于异常检测）
     @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.userId = :userId AND a.timestamp > :since")
     long countRecentActionsByUser(@Param("userId") String userId, @Param("since") LocalDateTime since);
     ```
- **产出**：`repository/AuditLogRepository.java`
- **验收标准**：可通过 Repository 正确执行上述所有查询
- **预估工时**：0.5h

---

### Task 11：设计 @Auditable 自定义注解
- **目标**：创建一个方法级注解，用于声明式地标记需要审计的操作
- **详细步骤**：
  1. 创建 `src/main/java/com/gdpr/audit/annotation/Auditable.java`
  2. 定义注解属性：
     ```java
     @Target(ElementType.METHOD)
     @Retention(RetentionPolicy.RUNTIME)
     public @interface Auditable {
         /** 操作类型 */
         AuditAction action();
         /** 资源类型 (如 "User", "Document") */
         String resourceType();
         /** 描述 (可选) */
         String description() default "";
     }
     ```
  3. 创建 `AuditAction` 枚举：
     ```java
     public enum AuditAction {
         VIEW,       // 查看
         CREATE,     // 创建
         MODIFY,     // 修改
         DELETE,     // 删除
         DOWNLOAD,   // 下载
         EXPORT,     // 导出
         LOGIN,      // 登录
         LOGOUT      // 登出
     }
     ```
- **产出**：`annotation/Auditable.java` + `enums/AuditAction.java`
- **验收标准**：注解可以编译且被正确应用到 Controller 方法上
- **预估工时**：0.5h

---

### Task 12：实现 AuditAspect — AOP 审计切面
- **目标**：通过 AOP `@Around` 切面自动拦截标注了 `@Auditable` 的方法，记录审计日志
- **详细步骤**：
  1. 创建 `src/main/java/com/gdpr/audit/aspect/AuditAspect.java`
  2. 使用 `@Aspect` + `@Component` 注解
  3. 定义切点：`@annotation(com.gdpr.audit.annotation.Auditable)`
  4. 实现 `@Around` 通知逻辑：
     - **Before**：记录方法开始时间
     - 从 `JoinPoint` 获取方法签名、注解元数据（action, resourceType）
     - 从 `HttpServletRequest` (通过 `RequestContextHolder`) 获取 clientIp、userAgent、requestUri、requestMethod
     - 从方法参数中提取 `resourceId`（约定第一个路径参数或使用 SpEL 表达式）
     - **Proceed**：执行目标方法
     - **After**：计算耗时、获取响应状态码
     - **异常处理**：捕获异常，记录 errorMessage，标记 success=false
     - 构建 `AuditLog` 实体并通过 `AuditLogRepository.save()` 持久化
  5. 使用 `@Async` 或考虑异步保存，避免审计逻辑影响业务接口性能
- **关键技术点**：
  - `RequestContextHolder.getRequestAttributes()` 获取 HTTP 上下文
  - `SecurityContextHolder.getContext().getAuthentication()` 获取当前用户
  - `ProceedingJoinPoint.proceed()` 执行目标方法并捕获返回值
- **产出**：`aspect/AuditAspect.java`
- **验收标准**：任何标注了 `@Auditable` 的 Controller 方法被调用后，`audit_logs` 表中会自动写入一条记录
- **预估工时**：3h

---

### Task 13：从 JWT/SecurityContext 提取用户信息
- **目标**：在 AuditAspect 中正确获取当前登录用户的 ID、用户名和角色
- **详细步骤**：
  1. 创建 `src/main/java/com/gdpr/audit/security/SecurityUtils.java` 工具类
  2. 实现以下静态方法：
     ```java
     public static String getCurrentUserId() {
         Jwt jwt = getCurrentJwt();
         return jwt != null ? jwt.getSubject() : "anonymous";
     }

     public static String getCurrentUsername() {
         Jwt jwt = getCurrentJwt();
         return jwt != null ? jwt.getClaimAsString("preferred_username") : "anonymous";
     }

     public static String getCurrentUserRoles() {
         Jwt jwt = getCurrentJwt();
         if (jwt == null) return "";
         Map<String, Object> realmAccess = jwt.getClaim("realm_access");
         Collection<String> roles = (Collection<String>) realmAccess.get("roles");
         return String.join(",", roles);
     }

     private static Jwt getCurrentJwt() {
         Authentication auth = SecurityContextHolder.getContext().getAuthentication();
         if (auth instanceof JwtAuthenticationToken jwtAuth) {
             return jwtAuth.getToken();
         }
         return null;
     }
     ```
  3. 在 `AuditAspect` 中调用这些方法填充 `AuditLog` 的用户字段
- **产出**：`security/SecurityUtils.java` + 更新 `AuditAspect.java`
- **验收标准**：审计日志中 `userId`, `username`, `userRoles` 字段与 JWT Token 中的信息一致
- **预估工时**：1h

---

### Task 14：实现 GdprDataMasker — PII 数据脱敏工具
- **目标**：创建 GDPR 合规的数据脱敏工具类，在审计日志写入前对敏感个人信息进行掩码处理
- **详细步骤**：
  1. 创建 `src/main/java/com/gdpr/audit/gdpr/GdprDataMasker.java`
  2. 实现以下脱敏规则：

     | PII 类型 | 原始数据示例 | 脱敏后 | 正则模式 |
     |----------|-------------|--------|---------|
     | 邮箱 | `john.doe@example.com` | `j*****e@e****e.com` | `[\w.]+@[\w.]+` |
     | 德国电话 | `+49 170 1234567` | `+49 *** ***4567` | `\+?\d[\d\s-]{8,}` |
     | IBAN | `DE89370400440532013000` | `DE89****…3000` | `[A-Z]{2}\d{2}[A-Z0-9]{4,}` |
     | 姓名 | `Max Mustermann` | 不自动脱敏（需通过 `@SensitiveField` 标注） | N/A |

  3. 提供统一入口方法：
     ```java
     public static String maskPii(String input) {
         if (input == null) return null;
         String result = input;
         result = maskEmails(result);
         result = maskPhoneNumbers(result);
         result = maskIbans(result);
         return result;
     }
     ```
  4. 考虑 JSON 字符串中的嵌套 PII — 对 JSON body 先解析再逐字段处理
  5. 每个脱敏方法独立实现，方便后续扩展新的 PII 类型
- **产出**：`gdpr/GdprDataMasker.java`
- **验收标准**：输入包含邮箱/电话/IBAN 的字符串，输出中所有 PII 均被正确掩码
- **预估工时**：2h

---

### Task 15：GdprDataMasker 单元测试
- **目标**：为 PII 脱敏逻辑编写全面的单元测试，覆盖各种格式和边界情况
- **详细步骤**：
  1. 创建 `src/test/java/com/gdpr/audit/gdpr/GdprDataMaskerTest.java`
  2. 使用 JUnit 5 `@ParameterizedTest` + `@CsvSource` 批量测试
  3. 测试用例设计：

     **邮箱测试**：
     - 标准格式：`user@example.com` → 脱敏
     - 带点号：`first.last@company.de` → 脱敏
     - 子域名：`user@mail.company.co.uk` → 脱敏
     - 无效邮箱：`not-an-email` → 不变

     **电话测试**：
     - 德国手机：`+49 170 1234567` → 脱敏
     - 带横线：`+49-170-1234567` → 脱敏
     - 国内格式：`0170 1234567` → 脱敏
     - 短号码（不应误判）：`12345` → 不变

     **IBAN 测试**：
     - 德国 IBAN：`DE89370400440532013000` → 脱敏
     - 法国 IBAN：`FR7630006000011234567890189` → 脱敏
     - 带空格：`DE89 3704 0044 0532 0130 00` → 脱敏

     **边界情况**：
     - `null` 输入 → 返回 `null`
     - 空字符串 → 返回空字符串
     - 混合内容：`"用户 john@mail.com 的 IBAN 是 DE89370400440532013000"` → 仅 PII 部分被脱敏
     - JSON 字符串中的嵌套 PII

- **产出**：`test/.../GdprDataMaskerTest.java`
- **验收标准**：所有测试用例通过 ✅，覆盖率 > 90%
- **预估工时**：1.5h

---

### Task 16：将脱敏逻辑集成到 AuditAspect
- **目标**：在审计切面写入日志前，自动对 requestBody 和 errorMessage 中的 PII 数据进行脱敏
- **详细步骤**：
  1. 修改 `AuditAspect.java`
  2. 在构建 `AuditLog` 实体前，对以下字段调用 `GdprDataMasker.maskPii()`：
     - `requestBody`：HTTP 请求体
     - `errorMessage`：异常信息（可能包含用户输入）
     - 方法参数的 `toString()` 输出（如果被记录）
  3. 确保脱敏在持久化之前完成——无论是写入数据库还是写入日志系统
  4. 添加 `@SensitiveField` 自定义注解（可选），允许在 DTO 的字段上标注，AuditAspect 遇到时整体替换为 `***`
- **关键考虑**：
  - 脱敏不能修改原始请求/响应对象，只修改审计日志中的副本
  - 性能影响最小化：正则匹配在 MB 级文本上可能有性能问题，需设置最大长度限制
- **产出**：更新后的 `AuditAspect.java` + （可选）`annotation/SensitiveField.java`
- **验收标准**：调用含 PII 数据的接口后，`audit_logs` 表中的 `request_body` 字段内的邮箱/电话/IBAN 均已脱敏
- **预估工时**：1.5h

---

### Task 17：创建示例业务模块（User CRUD）
- **目标**：创建一个完整的 User CRUD 模块，演示 `@Auditable` 注解和 GDPR 脱敏的实际工作效果
- **详细步骤**：
  1. 创建 `entity/User.java`：
     - 字段：id, firstName, lastName, email, phone, iban, department, createdAt
  2. 创建 `repository/UserRepository.java`
  3. 创建 `service/UserService.java`：
     - 标准 CRUD 逻辑
  4. 创建 `dto/UserDTO.java`：
     - 请求/响应 DTO，用于序列化
  5. 创建 `controller/UserController.java`：
     ```java
     @GetMapping("/{id}")
     @Auditable(action = AuditAction.VIEW, resourceType = "User")
     public ResponseEntity<UserDTO> getUser(@PathVariable Long id) { ... }

     @PostMapping
     @Auditable(action = AuditAction.CREATE, resourceType = "User")
     public ResponseEntity<UserDTO> createUser(@RequestBody UserDTO dto) { ... }

     @DeleteMapping("/{id}")
     @Auditable(action = AuditAction.DELETE, resourceType = "User")
     public ResponseEntity<Void> deleteUser(@PathVariable Long id) { ... }
     ```
  6. 在 `data.sql` 中插入一些包含 PII 的示例数据
- **产出**：完整的 User CRUD 模块（Entity + Repository + Service + DTO + Controller）
- **验收标准**：
  - 调用 `POST /api/users`（body 含 email/phone/iban）后，`audit_logs` 中的 `request_body` 已脱敏
  - 调用 `DELETE /api/users/1` 后，`audit_logs` 记录操作人和被删用户 ID
- **预估工时**：2h

---

## 🟠 Milestone 1.3：ELK 异步日志链路与不可篡改审计（7 个任务，~11.5h）

---

### Task 18：添加 Logstash Logback Encoder 依赖
- **目标**：在 `build.gradle` 中引入 Logback → Logstash 的 JSON 编码器依赖
- **详细步骤**：
  1. 在 `build.gradle` 的 `dependencies` 块中添加：
     ```groovy
     // Logstash Logback Encoder - 将日志以 JSON 格式发送到 Logstash
     implementation 'net.logstash.logback:logstash-logback-encoder:7.4'
     ```
  2. 运行 `./gradlew dependencies` 确认依赖解析成功
  3. 确认版本与 Spring Boot 3.2.3 兼容
- **产出**：更新后的 `build.gradle`
- **验收标准**：`./gradlew compileJava` 无报错
- **预估工时**：0.5h

---

### Task 19：配置 logback-spring.xml — JSON + Logstash Appender
- **目标**：配置 Logback 将审计日志以 JSON 格式异步发送到 Logstash TCP 端口
- **详细步骤**：
  1. 创建 `src/main/resources/logback-spring.xml`
  2. 定义 3 个 Appender：
     - **CONSOLE**：控制台输出（开发用）
     - **LOGSTASH**：`LogstashTcpSocketAppender`，连接 `localhost:50000`，使用 `LogstashEncoder` 输出 JSON
     - **ASYNC_LOGSTASH**：`AsyncAppender` 包装 LOGSTASH appender，使用异步队列避免阻塞业务线程
  3. 关键配置：
     ```xml
     <appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
         <destination>localhost:50000</destination>
         <encoder class="net.logstash.logback.encoder.LogstashEncoder">
             <includeMdcKeyName>traceId</includeMdcKeyName>
             <includeMdcKeyName>userId</includeMdcKeyName>
             <customFields>{"application":"gdpr-audit-system"}</customFields>
         </encoder>
         <reconnectionDelay>5 seconds</reconnectionDelay>
     </appender>

     <appender name="ASYNC_LOGSTASH" class="ch.qos.logback.classic.AsyncAppender">
         <appender-ref ref="LOGSTASH" />
         <queueSize>512</queueSize>
         <discardingThreshold>0</discardingThreshold>
     </appender>
     ```
  4. 为审计日志定义专用 Logger：
     ```xml
     <logger name="AUDIT" level="INFO" additivity="false">
         <appender-ref ref="ASYNC_LOGSTASH" />
         <appender-ref ref="CONSOLE" />
     </logger>
     ```
- **产出**：`resources/logback-spring.xml`
- **验收标准**：应用启动后，审计日志以 JSON 格式出现在控制台；如果 Logstash 运行中，Logstash 容器日志可看到接收记录
- **预估工时**：2h

---

### Task 20：编写 Logstash Pipeline 配置
- **目标**：配置 Logstash 接收来自 Spring Boot 的 JSON 审计日志，解析并写入 Elasticsearch
- **详细步骤**：
  1. 创建/更新 `logstash/pipeline/logstash.conf`
  2. 配置三段式 pipeline：
     ```conf
     input {
       tcp {
         port => 50000
         codec => json_lines
       }
     }

     filter {
       # 解析时间戳
       date {
         match => ["timestamp", "ISO8601"]
         target => "@timestamp"
       }
       # 添加环境标签
       mutate {
         add_field => { "environment" => "development" }
         # 移除不需要的字段
         remove_field => ["host", "port"]
       }
       # 根据 action 类型添加严重级别
       if [action] in ["DELETE", "EXPORT", "DOWNLOAD"] {
         mutate { add_field => { "severity" => "HIGH" } }
       } else if [action] in ["MODIFY"] {
         mutate { add_field => { "severity" => "MEDIUM" } }
       } else {
         mutate { add_field => { "severity" => "LOW" } }
       }
     }

     output {
       elasticsearch {
         hosts => ["http://elasticsearch:9200"]
         index => "audit-logs-%{+YYYY.MM.dd}"
       }
       # 开发环境同时输出到控制台便于调试
       stdout { codec => rubydebug }
     }
     ```
- **产出**：`logstash/pipeline/logstash.conf`
- **验收标准**：Logstash 启动无报错，收到测试日志后可在 Elasticsearch 中查到对应文档
- **预估工时**：2h

---

### Task 21：设计 Elasticsearch Index Template
- **目标**：为审计日志定义 Elasticsearch 的 Index Template，确保字段类型正确（避免全部变 text 导致无法聚合）
- **详细步骤**：
  1. 创建 Index Template（通过 Kibana Dev Tools 或 API）：
     ```json
     PUT _index_template/audit-logs-template
     {
       "index_patterns": ["audit-logs-*"],
       "template": {
         "settings": {
           "number_of_shards": 1,
           "number_of_replicas": 0
         },
         "mappings": {
           "properties": {
             "@timestamp":    { "type": "date" },
             "userId":        { "type": "keyword" },
             "username":      { "type": "keyword" },
             "userRoles":     { "type": "keyword" },
             "action":        { "type": "keyword" },
             "resourceType":  { "type": "keyword" },
             "resourceId":    { "type": "keyword" },
             "requestMethod": { "type": "keyword" },
             "requestUri":    { "type": "text", "fields": { "keyword": { "type": "keyword" } } },
             "requestBody":   { "type": "text" },
             "responseStatus":{ "type": "integer" },
             "clientIp":      { "type": "ip" },
             "duration":      { "type": "long" },
             "success":       { "type": "boolean" },
             "severity":      { "type": "keyword" },
             "errorMessage":  { "type": "text" },
             "application":   { "type": "keyword" },
             "environment":   { "type": "keyword" }
           }
         }
       }
     }
     ```
  2. 将此模板保存为脚本 `scripts/es-init-template.sh`，方便重复执行
  3. (可选) 配置 ILM (Index Lifecycle Management) 策略：审计日志保留 90 天后自动归档
- **产出**：ES Index Template + 初始化脚本
- **验收标准**：写入 `audit-logs-2026.08.08` 的文档，`action` 字段为 `keyword` 类型，`clientIp` 为 `ip` 类型
- **预估工时**：1.5h

---

### Task 22：修改 AuditAspect — 双写 DB + ELK
- **目标**：让审计日志同时写入 PostgreSQL（可靠存储）和 ELK（实时查询/可视化）
- **详细步骤**：
  1. 在 `AuditAspect` 中注入一个名为 `AUDIT` 的 SLF4J Logger：
     ```java
     private static final Logger AUDIT_LOGGER = LoggerFactory.getLogger("AUDIT");
     ```
  2. 在 `AuditLogRepository.save()` 之后，使用 `AUDIT_LOGGER.info()` 输出结构化日志
  3. 使用 `StructuredArguments` (来自 logstash-logback-encoder) 输出键值对：
     ```java
     import static net.logstash.logback.argument.StructuredArguments.*;

     AUDIT_LOGGER.info("Audit event: {} on {} by {}",
         kv("action", auditLog.getAction()),
         kv("resourceType", auditLog.getResourceType()),
         kv("userId", auditLog.getUserId()),
         kv("username", auditLog.getUsername()),
         kv("resourceId", auditLog.getResourceId()),
         kv("clientIp", auditLog.getClientIp()),
         kv("duration", auditLog.getDuration()),
         kv("success", auditLog.getSuccess()),
         kv("requestUri", auditLog.getRequestUri())
     );
     ```
  4. 确保 ELK 链路是异步的（通过 `ASYNC_LOGSTASH` appender），不影响接口响应时间
  5. 如果 Logstash 不可用，日志会被缓存在 AsyncAppender 队列中，不会阻塞或抛异常
- **产出**：更新后的 `AuditAspect.java`
- **验收标准**：一次 API 调用后，PostgreSQL `audit_logs` 表和 Elasticsearch `audit-logs-*` 索引中都有对应记录
- **预估工时**：1.5h

---

### Task 23：配置 Kibana 仪表盘
- **目标**：在 Kibana 中创建可视化仪表盘，实时监控安全事件和异常行为
- **详细步骤**：
  1. 访问 `http://localhost:5601`，进入 Kibana
  2. 创建 Data View（Index Pattern）：`audit-logs-*`，时间字段选 `@timestamp`
  3. 创建以下 Visualizations：

     | 面板名称 | 类型 | 内容 |
     |---------|------|------|
     | 操作时间线 | Area Chart | X 轴 @timestamp，Y 轴 count，按 action 分色 |
     | 操作类型分布 | Pie Chart | 按 action 字段分组统计 |
     | 高频操作用户 Top 10 | Horizontal Bar | 按 username 聚合 count，降序取 Top 10 |
     | 最近失败操作 | Data Table | 过滤 success=false，展示时间/用户/操作/错误信息 |
     | 高风险操作 (DELETE/EXPORT/DOWNLOAD) | Metric + Table | 过滤 severity=HIGH，统计并列表 |
     | 异常登录检测 | Line Chart | 按 clientIp 分组，统计单 IP 短时间内的请求频次 |

  4. 将所有 Visualization 组装到一个 Dashboard：`GDPR Audit Dashboard`
  5. 配置自动刷新间隔（如每 30 秒）
- **产出**：Kibana Dashboard
- **验收标准**：打开 Dashboard 能实时看到最新的审计日志数据，各面板正确渲染
- **预估工时**：3h

---

### Task 24：导出 Kibana Saved Objects（可选）
- **目标**：将 Kibana 的 Dashboard、Visualization、Index Pattern 导出为 JSON，实现可重复部署
- **详细步骤**：
  1. Kibana → Stack Management → Saved Objects
  2. 选择所有 audit 相关的对象 → Export
  3. 保存到 `kibana/export.ndjson`
  4. (可选) 编写初始化脚本 `scripts/kibana-import.sh`：
     ```bash
     curl -X POST "http://localhost:5601/api/saved_objects/_import" \
       -H "kbn-xsrf: true" \
       --form file=@kibana/export.ndjson
     ```
  5. 在 `docker-compose.yml` 中可以通过 Kibana 启动后的 healthcheck + 初始化脚本自动导入
- **产出**：`kibana/export.ndjson` + `scripts/kibana-import.sh`
- **验收标准**：在全新环境中执行导入脚本后，Dashboard 及所有面板恢复正常
- **预估工时**：1h

---

## 🟣 Milestone 1.4：工程化与测试（3 个任务，~7.5h）

---

### Task 25：Testcontainers 集成测试 — PostgreSQL + AuditLog
- **目标**：使用 Testcontainers 启动真实的 PostgreSQL 容器，测试审计日志的持久化和查询逻辑
- **详细步骤**：
  1. 在 `build.gradle` 中添加 Testcontainers 依赖：
     ```groovy
     testImplementation 'org.testcontainers:testcontainers:1.19.7'
     testImplementation 'org.testcontainers:junit-jupiter:1.19.7'
     testImplementation 'org.testcontainers:postgresql:1.19.7'
     ```
  2. 创建 `src/test/java/com/gdpr/audit/repository/AuditLogRepositoryIT.java`
  3. 使用 `@Testcontainers` + `@Container` 启动 PostgreSQL
  4. 使用 `@DynamicPropertySource` 动态注入数据库连接信息
  5. 测试用例：
     - **保存测试**：创建 AuditLog → save → findById → 断言所有字段正确
     - **查询测试**：插入多条记录 → findByUserIdOrderByTimestampDesc → 验证排序和过滤
     - **统计测试**：插入不同时间的记录 → countRecentActionsByUser → 验证计数
     - **索引测试**：验证大量数据下按索引字段查询的性能可接受
  6. 使用 `@Transactional` + `@Rollback` 确保测试隔离
- **产出**：`test/.../AuditLogRepositoryIT.java`
- **验收标准**：`./gradlew test` 通过，测试使用真实 PostgreSQL 容器而非 H2
- **预估工时**：2.5h

---

### Task 26：Testcontainers 集成测试 — Elasticsearch
- **目标**：使用 Testcontainers 启动真实的 Elasticsearch 容器，验证审计日志的 ELK 链路
- **详细步骤**：
  1. 添加 ES Testcontainers 依赖：
     ```groovy
     testImplementation 'org.testcontainers:elasticsearch:1.19.7'
     ```
  2. 创建 `src/test/java/com/gdpr/audit/elk/ElasticsearchAuditIT.java`
  3. 使用 `@Testcontainers` 启动 Elasticsearch 8.12 容器
  4. 通过 Elasticsearch Java Client（或 RestHighLevelClient）直接与容器交互
  5. 测试用例：
     - **写入测试**：构造审计日志 JSON → 写入 ES → 查询 → 验证文档内容
     - **Mapping 测试**：创建 Index Template → 写入数据 → 验证字段类型正确
     - **搜索测试**：写入多条记录 → 按 `action=DELETE` 过滤 → 验证结果
     - **聚合测试**：写入多条记录 → 按 `username` 聚合统计 → 验证 Top N
     - **脱敏验证**：写入包含 PII 的日志 → 查询 → 确认 PII 已被掩码
  6. 注意 ES 容器启动较慢（~30s），配置合理的超时
- **产出**：`test/.../ElasticsearchAuditIT.java`
- **验收标准**：`./gradlew test` 通过，ES 相关测试使用真实容器
- **预估工时**：3h

---

### Task 27：完善 docker-compose.yml — 全栈一键部署
- **目标**：在 `docker-compose.yml` 中加入 Java App 服务，实现整个系统的一键拉起
- **详细步骤**：
  1. 添加 Dockerfile 用于构建 Java App：
     ```dockerfile
     FROM eclipse-temurin:21-jre-alpine
     WORKDIR /app
     COPY build/libs/gdpr-audit-*.jar app.jar
     EXPOSE 8081
     ENTRYPOINT ["java", "-jar", "app.jar"]
     ```
  2. 在 `docker-compose.yml` 中添加 `app` 服务：
     ```yaml
     app:
       build: .
       container_name: audit-app
       ports:
         - "8081:8081"
       environment:
         SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/audit_db
         SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: http://keycloak:8080/realms/audit-realm
       depends_on:
         postgres:
           condition: service_healthy
         keycloak:
           condition: service_started
         elasticsearch:
           condition: service_started
     ```
  3. 创建 `.env` 文件，集中管理环境变量：
     ```env
     POSTGRES_DB=audit_db
     POSTGRES_USER=audit_user
     POSTGRES_PASSWORD=audit_password
     KEYCLOAK_ADMIN=admin
     KEYCLOAK_ADMIN_PASSWORD=admin_password
     ```
  4. 更新 `docker-compose.yml` 中的硬编码值为 `${VARIABLE}` 引用
  5. 编写 `README.md` 中的快速启动指南：
     ```bash
     # 构建 Java App
     ./gradlew bootJar
     # 一键启动所有服务
     docker-compose up -d
     # 查看服务状态
     docker-compose ps
     ```
  6. 验证所有服务的健康检查和依赖链
- **产出**：`Dockerfile` + 更新后的 `docker-compose.yml` + `.env` + `README.md`
- **验收标准**：在全新环境执行 `docker-compose up -d` 后，所有 6 个服务（Postgres + Keycloak + ES + Logstash + Kibana + App）全部健康运行
- **预估工时**：2h

---

## 📊 总览汇总

| # | 任务名称 | 里程碑 | 预估 |
|---|---------|--------|------|
| 01 | Docker 启动 Keycloak 并创建 Realm | M1.1 | 1h |
| 02 | 配置 Keycloak Client (audit-api) | M1.1 | 0.5h |
| 03 | 创建 Realm 角色与测试用户 | M1.1 | 0.5h |
| 04 | 导出 realm-export.json | M1.1 | 1h |
| 05 | 编写 SecurityConfig (OAuth2 Resource Server) | M1.1 | 2h |
| 06 | 编写 JwtRoleConverter (角色映射) | M1.1 | 1.5h |
| 07 | 创建测试用 Auth Controller | M1.1 | 1h |
| 08 | 端到端认证流程验证 | M1.1 | 1h |
| 09 | 设计 AuditLog 实体类 (JPA Entity) | M1.2 | 1h |
| 10 | 创建 AuditLogRepository | M1.2 | 0.5h |
| 11 | 设计 @Auditable 自定义注解 | M1.2 | 0.5h |
| 12 | 实现 AuditAspect (AOP 审计切面) | M1.2 | 3h |
| 13 | 从 JWT/SecurityContext 提取用户信息 | M1.2 | 1h |
| 14 | 实现 GdprDataMasker (PII 数据脱敏) | M1.2 | 2h |
| 15 | GdprDataMasker 单元测试 | M1.2 | 1.5h |
| 16 | 将脱敏逻辑集成到 AuditAspect | M1.2 | 1.5h |
| 17 | 创建示例业务模块 (User CRUD) | M1.2 | 2h |
| 18 | 添加 Logstash Logback Encoder 依赖 | M1.3 | 0.5h |
| 19 | 配置 logback-spring.xml (JSON + Logstash) | M1.3 | 2h |
| 20 | 编写 Logstash Pipeline 配置 | M1.3 | 2h |
| 21 | 设计 Elasticsearch Index Template | M1.3 | 1.5h |
| 22 | 修改 AuditAspect — 双写 DB + ELK | M1.3 | 1.5h |
| 23 | 配置 Kibana 仪表盘 | M1.3 | 3h |
| 24 | 导出 Kibana Saved Objects (可选) | M1.3 | 1h |
| 25 | Testcontainers 集成测试 — PostgreSQL | M1.4 | 2.5h |
| 26 | Testcontainers 集成测试 — Elasticsearch | M1.4 | 3h |
| 27 | 完善 docker-compose.yml — 全栈一键部署 | M1.4 | 2h |
| | **合计** | | **~40.5h** |

---

## 🛡️ Milestone 2.0：企业级防篡改审计与生产加固（10 个新增任务，~40h）

> 本阶段是后续路线图，不代表当前系统已经具备生产级不可篡改能力。哈希链可检测记录被改写/删除；要抵抗数据库管理员或平台管理员，还必须将签名检查点锚定到独立的 WORM 存储，并隔离密钥管理权限。

### Task 28：威胁建模与审计控制基线
- **目标**：明确要保护的审计证据、信任边界、攻击者能力、法定保留约束和故障策略。
- **步骤**：绘制数据流/信任边界；列出应用管理员、DBA、Keycloak 管理员、云账户管理员等威胁主体；确定事件分类、最小化字段、保留期、法律保全流程及审计不可用时 fail-open/fail-closed 决策。
- **产出**：`docs/threat-model.md`、控制矩阵和 ADR。
- **验收标准**：每种威胁均有预防/检测/响应控制及责任人；法律保留和删除策略由组织合规/法务确认，不能仅由代码推定。
- **预估工时**：3h

### Task 29：规范化审计事件与哈希链
- **目标**：为数据库、消息队列和归档定义稳定、版本化的审计事件 envelope。
- **步骤**：定义 `eventId`、`schemaVersion`、租户/主体、动作、资源、UTC 时间、来源、PII 分类、`sequence`、`previousHash`、`recordHash`、`keyId`；采用确定性序列化和 SHA-256；明确链分区（例如按 tenant/stream）及并发序号分配规则。
- **产出**：事件 schema、规范化器、链计算/验证组件及迁移方案。
- **验收标准**：相同事件规范化后字节完全一致；修改、删除、重排或插入记录会导致链校验失败；并发写不产生重复序号或断链；旧 schema 有向前兼容测试。
- **关键边界**：哈希链本身不能阻止拥有全部写权限的管理员重算整条链。
- **预估工时**：5h

### Task 30：Transactional Outbox 与可靠投递
- **目标**：消除当前 PostgreSQL 与 Logstash/Elasticsearch 分开写入造成的双写不一致。
- **步骤**：在业务事务内同时写业务/审计记录及 outbox；独立 publisher 以至少一次方式投递；以 `eventId` 幂等去重；实现重试、退避、死信、积压告警和回放。
- **产出**：Outbox 表/实体、发布器、重试策略和运行手册。
- **验收标准**：注入进程崩溃、ES/Logstash 暂停、重复投递后，PG 审计与下游最终一致且无重复事件；业务事务回滚时不留下成功审计事件。
- **预估工时**：4h

### Task 31：审计存储最小权限与追加写控制
- **目标**：减少应用账号或普通 DBA 修改/删除历史审计记录的能力。
- **步骤**：拆分业务、审计写入、只读查询和迁移账号；应用账号只允许插入/必要读取；配置 UPDATE/DELETE revoke、触发器拒绝修改、独立 migration 权限；数据库审计超级用户操作。
- **产出**：Flyway/Liquibase migration、角色授权脚本和权限负向测试。
- **验收标准**：应用账号 UPDATE/DELETE 审计行被拒绝；正常追加及只读查询成功；DBA 越权尝试产生外部告警。说明：超级用户仍可能绕过本地控制，不能单独作为 WORM 证明。
- **预估工时**：3h

### Task 32：KMS/HSM 签名、密钥轮换与验证
- **目标**：使审计检查点可以独立验签，并降低数据库管理员伪造证据的能力。
- **步骤**：使用云 KMS/HSM 管理非导出私钥；对链检查点进行非对称签名；记录 `keyId`/算法；设计轮换、吊销、旧公钥留存和灾难恢复；本地开发使用独立测试密钥。
- **产出**：签名/验签接口、KMS adapter、密钥生命周期运行手册。
- **验收标准**：无签名权限的应用/DB 账号不能生成有效检查点；轮换后新旧检查点均可验签；密钥服务不可用时行为符合威胁模型策略。
- **预估工时**：4h
- **⛔ 当前受阻原因（2026-10-30）**：现有 HMAC 审计链为对称密钥（`audit.chain.hmac-key`），持钥者可伪造整链；要实现"非导出私钥 + 非对称验签"，必须依赖外部密钥管理设施。本地演示环境没有任何 KMS/HSM，也没有可用的云账号，因此**非导出性**这一核心安全属性无法在本地成立——用 Java KeyStore 模拟只是把密钥从配置文件挪到另一个文件，信任假设没有变化。
- **🧰 需要具备的条件**：
  1. 任一云 KMS（AWS KMS / GCP Cloud KMS / Azure Key Vault 任一即可）或硬件 HSM 的可用账号与权限：`kms:Sign`、`kms:GetPublicKey`、密钥创建/轮换权限；
  2. 密钥策略中应用身份（IAM Role/Service Account）只能签名、不能导出私钥；
  3. 开发/演示用测试密钥的配额（费用极低，签名调用约 $0.03/万次）。
- **✅ 具备条件后的开发内容**：
  1. 定义 `CheckpointSigner` 接口 + KMS 实现（`kms:Sign` RSA/ECDSA 对链头 `nextSequence`+`lastHash` 签名），本地用 RSA KeyPair 测试实现并行开发；
  2. 新增 `audit_chain_checkpoints` 表（sequence、lastHash、keyId、算法、签名、签名时间），定期（如每 100 条或每小时）生成检查点；
  3. `AuditChainVerifier` 扩展：按 `keyId` 选择公钥验签检查点，支持多密钥并存；
  4. 密钥轮换流程：新密钥上线→旧密钥只验签不签名→旧公钥归档留存；轮换操作本身写入 `audit_maintenance_log`；
  5. 密钥服务不可用时的降级策略（按威胁模型决策 D1：降级为仅 HMAC 校验并告警，或暂停检查点生成）；
  6. 运行手册：轮换/吊销/灾难恢复步骤。


### Task 33：独立 WORM 归档与外部检查点锚定
- **目标**：让拥有应用库/ES 管理权限的人员也不能静默重写已归档证据。
- **步骤**：定期生成签名链头/批次 Merkle root；写入与应用/DB 管理权限隔离的 S3 Object Lock Compliance bucket 或等效合规 WORM 服务；配置保留期、法律保全、跨账户复制和存储加密。
- **产出**：归档 publisher、基础设施即代码、恢复/法律保全手册。
- **验收标准**：保留期内无法覆盖/删除对象；在测试环境尝试覆盖被拒绝；从独立账户可验证签名检查点与数据库链头一致。保留时长由法务/数据保护负责人批准。
- **预估工时**：4h
- **⛔ 当前受阻原因（2026-10-30）**：WORM（一次写入不可改写）的本质是**由外部独立系统强制执行**的不可覆盖性——它必须运行在与应用/数据库不同的信任域里。本地 docker-compose 栈内没有任何组件能提供这一保证：MinIO 等 S3 兼容对象存储即使本地可装，也与应用同处一个 compose 网络和宿主机，管理员一样能删卷，"独立账户隔离"与"Object Lock Compliance 模式"都无法成立。
- **🧰 需要具备的条件**：
  1. 一个独立于应用运行环境的对象存储账户：AWS S3（Object Lock Compliance 模式）为主，或 Azure Blob（immutable storage）/ GCS（retention lock）；
  2. 用于归档的 IAM 身份与应用账户分离，且归档账户**无删除权限**（s3:PutObject、s3:GetObject only）；
  3. 独立的对账验证账户（不同凭证，用于第三方视角验证检查点）；
  4. 法务/数据保护负责人批准的保留时长（法律保全期）。
- **✅ 具备条件后的开发内容**：
  1. `WormArchivePublisher`：消费 Task 32 产出的签名检查点，定期写入 WORM bucket（对象路径含日期/序号，Content addressed）；
  2. 基础设施即代码：bucket 声明（Object Lock、保留期、加密、跨账户复制）用 Terraform/CloudFormation 固化；
  3. 独立对账 job：从独立账户读回检查点，与 DB 链头比对，不一致触发 Task 34 的告警通道；
  4. 恢复/法律保全手册：从 WORM 恢复链头的演练流程；归档缺失检测接入 `AuditIntegrityMonitor`。


### Task 34：持续完整性巡检、告警与事件响应
- **目标**：在篡改、断链、延迟或归档缺失时及时发现并可调查。
- **步骤**：周期性重算链、验证签名和 WORM 检查点；监控序号缺口、outbox lag、死信、归档延迟；向独立 SIEM/告警通道发送不可变告警；编写证据保全与响应流程。
- **产出**：Verifier job/service、Prometheus 指标、Grafana/Kibana 告警、Runbook。
- **验收标准**：自动化测试分别篡改、删除、重排记录及移除检查点，均在 SLO 内告警；告警本身不只写回被监控的同一数据库。
- **预估工时**：4h

### Task 35：身份、授权拒绝与管理面事件覆盖
- **目标**：补齐当前主要由业务 `@Auditable` 记录的事件范围。
- **步骤**：通过 Keycloak Event Listener/SPI 或受支持的外部事件导出记录 LOGIN/LOGOUT、失败认证、用户/角色/Client 管理变更；应用侧记录 401/403、授权策略变更、审计配置和密钥操作；做主体 ID 映射及幂等事件关联。
- **产出**：Keycloak 事件集成、应用安全事件过滤器、事件类型字典。
- **验收标准**：成功/失败登录、权限拒绝、角色变更和管理员配置操作均能追溯到主体、来源、目标和时间；重复事件不重复入账；不得记录密码、Token 或未脱敏 PII。
- **预估工时**：4h

### Task 36：DSGVO 数据最小化、保留与数据主体请求
- **目标**：解决“不可变保留”与 GDPR/DSGVO 数据最小化、删除/限制处理权利之间的冲突。
- **步骤**：审计 schema 只保存必要主体标识；对可识别字段采用 tokenization/pseudonymization；实现经审批的 retention、legal hold、访问/导出和到期处置流程；评估静态/传输加密及按主体密钥销毁的可行性。
- **产出**：数据分类/保留策略、DSAR 流程和技术控制说明。
- **验收标准**：能够按主体检索/导出；到期删除或限制处理有审批、记录和验证；任何不可删除的记录均有明确法律依据和保留期限。需由 DPO/法务确认适用性。
- **预估工时**：4h
- **⛔ 当前受阻原因（2026-10-30）**：本任务的技术底座（DSAR 导出端点、假名化）可以本地开发，但**审批与法律定性环节无法由代码替代**：Art. 17 删除权与审计记录"不可变保留"的冲突能否成立、审计日志对个人数据是否具有 Art. 17(3)(b) 的合法性依据、90 天保留期（威胁模型决策 D2）是否适当、legal hold 的触发条件——这些都需要**DPO（数据保护官）/法务**基于具体业务场景做书面裁定并签字。没有这份裁定，实现出来的删除/保留流程在法律上是空转的。
- **🧰 需要具备的条件**：
  1. DPO 或数据保护法务对三份材料签字确认：威胁模型（`docs/threat-model.md` 的 D1/D2/D3 决策）、审计数据保留期限与依据、DSAR 处理流程与时限；
  2. 业务方确认审计系统的适用法规范围（DSGVO + 行业性要求如 GoBD/KWG，决定保留期下限）；
  3. （可选）Mock 审批工作流的裁决人角色定义（谁有权批准删除/导出）。
- **✅ 具备条件后的开发内容**（技术底座可先行）：
  1. **DSAR 导出端点**：`GET /api/admin/dsar/{subjectId}/export`（ROLE_ADMIN + 双人审批记录）— 汇总该主体全部审计记录为 JSON/CSV，字段按 `docs/threat-model.md` §3 最小化表过滤；
  2. **假名化 tokenization**：审计写入时对 `userId`/`username` 施加 keyed HMAC 假名（密钥独立于链签名密钥），原始映射存独立受限表，支持"知道 subject 才能反查"的方向性；
  3. **Retention 到期处置 job**：按批准的保留期删除/假名化超期记录，处置动作本身写入审计链（处置日志）；
  4. **Legal hold**：`legal_holds` 表 + 处置 job 跳过被 hold 主体，hold 的设置/解除需审批并留痕；
  5. **删除/限制流程**：Art. 17/18 请求 → 审批 → 对非链签名必需字段做删除或限制标记（链完整性用 `recordHash` 校验，删除实现为" tombstone + 摘要保留"而非物理删除，避免断链——具体方案以 DPO 裁定为准）。


### Task 37：生产部署、安全验证与恢复演练
- **目标**：把本地演示栈提升为可运维的生产部署基线。
- **步骤**：启用 TLS、Keycloak production mode、Elasticsearch 认证/授权、网络隔离、Secret Manager、非 root 镜像、镜像签名/SBOM、漏洞扫描；定义备份加密、跨故障域复制、RPO/RTO；建设 CI/CD、安全/依赖扫描、负载与故障注入测试。
- **产出**：production Compose/Kubernetes 部署模板、CI 工作流、SBOM、恢复/演练记录和安全基线。
- **验收标准**：CI 全绿；无明文生产密钥；TLS/访问策略通过自动化检查；从备份恢复满足批准的 RPO/RTO；对关键故障和权限边界有演练证据。
- **预估工时**：5h
- **⛔ 当前受阻原因（2026-10-30）**："生产部署"的全部验收项（TLS 证书、真实域名/DNS、RPO/RTO 演练、备份恢复、CI/CD 流水线）都需要一个**真实的生产/准生产部署目标**。本地 docker-compose 是单机演示栈：没有可挂 TLS 的域名、没有跨故障域可谈、没有独立 CI runner、备份恢复演练也无法在演练对象与被演练对象同宿主机的情况下取信。安全基线（镜像签名、SBOM 验证）同样需要 CI 与 registry 基础设施。
- **🧰 需要具备的条件**：
  1. 一个部署目标环境：云 VPS/Kubernetes 集群，或至少一台可 24x7 运行、有域名与 DNS 控制权的服务器（TLS 证书签发需要）；
  2. CI/CD 平台（GitHub Actions/GitLab CI/Jenkins 任一）与镜像 registry（含签名/sbom 扫描能力，如 cosign + Trivy）；
  3. Secret Manager 或等效设施（Vault/云 KMS/至少 SOPS 加密的 Git 仓库）存放生产密钥；
  4. 业务方批准的 RPO/RTO 目标值（备份策略由此推导）。
- **✅ 具备条件后的开发内容**：
  1. **production compose/K8s 模板**：TLS 终结（Traefik/nginx + Let's Encrypt）、Keycloak production mode（HTTPS、hostname 配置）、ES 安全配置（xpack security、TLS 内部通信）、网络隔离（服务间仅内网互通）；
  2. **Secret 外置**：application.yml 全部敏感项改环境变量注入，`.env.example` 文档化每个变量的来源（Secret Manager 路径）；
  3. **CI 工作流**：build → 单测/集成测试 → 镜像构建 → Trivy 扫描 → cosign 签名 → SBOM（syft）→ 部署；
  4. **备份与恢复**：PG 定时备份（加密、异机存放）+ 恢复演练脚本，记录实测 RPO/RTO；
  5. **安全基线清单**：非 root 容器、只读根文件系统、最小镜像（distroless/JRE-alpine）核对表与自动化检查；
  6. **负载与故障注入测试**：审计写入压测（链式 append 吞吐基线）、Kill PG/ES/Logstash 验证 Outbox 补投与 SAFE MODE 行为。


### Milestone 2.0 估算
- 新增 Task 28-37 共 10 项，约 **40h**；与 Milestone 1 的约 40.5h 合计约 **80.5h**。
- 只有完成签名检查点、独立 WORM 锚定、权限隔离、完整性巡检和恢复演练后，才可谨慎称为“具备可验证的防篡改审计能力”；是否达到法律/行业合规要求仍需组织合规评估。
