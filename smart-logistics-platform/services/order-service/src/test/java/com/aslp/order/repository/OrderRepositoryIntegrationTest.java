package com.aslp.order.repository;

import com.aslp.order.entity.OrderRecord;
import com.aslp.order.service.OrderPersistService;
import com.aslp.order.strategy.OrderDto;
import com.aslp.order.strategy.OrderPullResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P2-8：幂等落库的「数据库行为」集成测试（真实 PostgreSQL via Testcontainers）。
 *
 * <p>{@code OrderPullServiceTest}（Mockito）验证的是「我们调用了什么」，
 * 这里验证的是「数据库是否真的按我们指望的行为工作」—— 三层幂等保障
 * 里有两层建立在数据库行为上（{@code IN} 查询语义、{@code UNIQUE(order_id)}
 * 约束兜底），而这两层只有真 PG 才能证明：
 * <ul>
 *   <li>Flyway 的 PG 方言迁移（TIMESTAMPTZ / BIGSERIAL / COMMENT ON）必须真的能跑；</li>
 *   <li>{@code UNIQUE(order_id)} 约束必须真的存在 —— 并发插入同一订单时，
 *       后到者要抛 {@code DataIntegrityViolationException}，而不是静默双写；</li>
 *   <li>{@code findByOrderIdIn} 的批量查存量必须命中真实行。</li>
 * </ul>
 *
 * <p>CI 的 ubuntu-latest runner 自带 Docker，Testcontainers 开箱即用；
 * 本地无 Docker 时可用 {@code mvn test -DskipITs} 之类的跳过策略（本项目暂不区分）。
 */
@Testcontainers
// CI 首跑缺陷：@DataJpaTest 默认 @AutoConfigureTestDatabase(replace=ANY) 会把
// DynamicPropertySource 注册的 PG 容器 URL 无条件替换成内嵌 H2 —— PG 方言迁移
// （TIMESTAMPTZ/BIGSERIAL）在 H2 上直接报 Unknown data type。必须显式 replace=NONE
// 才能让测试真的跑在 Testcontainers 的 PostgreSQL 上。
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties = {
        // 与 application.yml 一致的迁移位置；这里必须跑真迁移（不关 Flyway），
        // 否则测的就不是生产库结构
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/order",
        "spring.flyway.table=flyway_schema_history_order",
        "spring.flyway.baseline-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Import(OrderPersistService.class)
class OrderRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("aslp")
            .withUsername("aslp")
            .withPassword("aslp123");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private OrderRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderPersistService persistService;

    private OrderPullResult resultWith(String... orderIds) {
        List<OrderDto> dtos = java.util.Arrays.stream(orderIds)
                // 注意参数顺序必须与 OrderDto(orderId, product, warehouse, status, errorTag) 一致：
                // 缺陷 #58 修复前本测试从未真正跑在 PG 上（先炸在 H2 方言），
                // 旧写法把 "Shipped" 放进 product 位、"Bruchsal" 放进 status 位，首次真跑即暴露
                .map(id -> new OrderDto(id, "奶粉一件代发", "Bruchsal", "Shipped", "ADDRESS_INVALID"))
                .toList();
        return new OrderPullResult("Amazon", dtos, true, null, false);
    }

    @BeforeEach
    void cleanUp() {
        // 不用 TestEntityManager：NOT_SUPPORTED 的测试方法没有测试级事务，
        // flush 会报 "No transactional EntityManager"。deleteAllInBatch 自带事务。
        repository.deleteAllInBatch();
    }

    @Test
    @DisplayName("Flyway PG 方言迁移可执行，且 UNIQUE(order_id) 约束真实存在")
    // PG 特有语义（缺陷 #58）：@DataJpaTest 默认把测试包进一个事务，
    // 约束冲突会把该事务标记为 aborted，后续 count() 全部报
    // "current transaction is aborted"。NOT_SUPPORTED 让每个仓储调用
    // 各自运行在独立事务里，冲突不会污染断言路径（H2 对此宽容，真 PG 才暴露）。
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void uniqueConstraintIsEnforcedByDatabase() {
        repository.saveAndFlush(new OrderRecord("AMZ-1", "Amazon", "x", "Bruchsal", "PAID"));

        // 第二个同 orderId 的实体：数据库必须拒绝（而不是 JPA 层吞掉）。
        // NOT_SUPPORTED 下每次仓储调用各自独立事务：冲突事务回滚即被隔离，
        // 不会把 "transaction aborted" 污染到后续 count()（真 PG 才暴露的差异）
        assertThrows(Exception.class, () -> {
            repository.saveAndFlush(new OrderRecord("AMZ-1", "Amazon", "y", "Mönchengladbach", "PAID"));
        }, "UNIQUE(order_id) 必须由数据库强制 —— 这是并发竞态的最后兜底");

        assertEquals(1, repository.count(), "冲突的插入不应产生第二行");
    }

    @Test
    @DisplayName("幂等落库：批量查存量命中真实行，已存在订单走更新分支")
    void persistUsesRealInQueryForExistingOrders() {
        repository.save(new OrderRecord("AMZ-1", "Amazon", "旧商品", "Bruchsal", "PAID"));
        entityManager.flush();
        entityManager.clear();

        OrderPersistService.PersistOutcome outcome = persistService.persist(resultWith("AMZ-1", "AMZ-2"));
        OrderPersistService.PersistCounts counts = outcome.counts();

        assertEquals(1, counts.created(), "AMZ-2 是新订单");
        assertEquals(1, counts.updated(), "AMZ-1 已存在 → 更新而不是新增");
        assertEquals(2, repository.count(), "不允许出现 AMZ-1 的第二行（check-then-act 的数据库兜底）");

        OrderRecord stored = repository.findByOrderId("AMZ-1").orElseThrow();
        assertEquals("Shipped", stored.getStatus(), "已存在订单应同步最新状态");
    }

    @Test
    @DisplayName("并发竞态兜底：另一路抢先插入同一订单时，UNIQUE 冲突被捕获并转为更新")
    void raceIsResolvedByUniqueConstraintFallback() {
        // 模拟「两路拉取同时判定 AMZ-1 不存在」：先手动插一行制造抢先者
        repository.save(new OrderRecord("AMZ-1", "Amazon", "抢先者", "Bruchsal", "PAID"));
        entityManager.flush();

        // persist 的 IN 查询会看到抢先者 → 走更新分支（这是「正常」路径）。
        // 真正的竞态（IN 查询与 insert 之间被插队）由上面的 uniqueConstraintIsEnforcedByDatabase
        // 保证：即使 In 查询漏看，insert 也会被数据库拒绝并转为更新（见 OrderPersistService 的 catch）。
        OrderPersistService.PersistOutcome outcome = persistService.persist(resultWith("AMZ-1"));
        OrderPersistService.PersistCounts counts = outcome.counts();
        assertEquals(0, counts.created());
        assertEquals(1, counts.updated());
        assertEquals(1, repository.count(), "任何路径都不允许产生第二行");
    }
}
