package com.aslp.inventory.service;

import com.aslp.inventory.entity.InventoryItem;
import com.aslp.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P0-5 验收测试：库存「扣减 → 查询 → 释放」三步闭环 + 边界防护。
 *
 * <p>策略：不启动 Spring 上下文（避免依赖真实 Redis / PostgreSQL），
 * 用 Mockito 提供 {@link RedissonClient} / {@link RLock} / {@link InventoryRepository}，
 * 因此被测的仍是 {@link InventoryLockService} 的真实业务逻辑 ——
 * 断言直接落在被修改的 {@link InventoryItem} 上，等同观察数据库行状态。
 */
@ExtendWith(MockitoExtension.class)
class InventoryLockServiceTest {

    private static final String SKU = "AMZ-1001";
    private static final String WAREHOUSE = "Bruchsal";
    private static final String LOCK_KEY = "inventory:lock:" + SKU + ":" + WAREHOUSE;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private InventoryRepository repository;

    @Mock
    private RLock lock;

    private InventoryLockService service;

    @BeforeEach
    void setUp() {
        service = new InventoryLockService(redissonClient, repository);
    }

    /** Bruchsal 总仓 AMZ-1001 的种子态：可用 320 / 锁定 12。 */
    private static InventoryItem stock(int available, int locked) {
        InventoryItem item = new InventoryItem();
        item.setSku(SKU);
        item.setWarehouseCode(WAREHOUSE);
        item.setAvailableQty(available);
        item.setLockedQty(locked);
        item.setUnitPrice(new BigDecimal("89.90"));
        return item;
    }

    private void givenExistingStock(InventoryItem item) {
        when(repository.findBySkuAndWarehouseCode(SKU, WAREHOUSE)).thenReturn(Optional.of(item));
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
    }

    private void givenLockAcquired() throws InterruptedException {
        when(lock.tryLock(2, 10, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    @DisplayName("三步闭环：扣减 -> 查询 -> 释放，两仓库存回到初始值")
    void deductQueryRelease_closesTheLoop() throws InterruptedException {
        InventoryItem item = stock(320, 12);
        givenExistingStock(item);
        givenLockAcquired();

        // (1) 扣减 2 件：可用 320 -> 318，锁定 12 -> 14（预占）
        assertTrue(service.deductInventory(SKU, WAREHOUSE, 2));
        assertEquals(318, item.getAvailableQty().intValue());
        assertEquals(14, item.getLockedQty().intValue());

        // (2) 查询：重新读库拿到同一行，双状态与扣减结果一致
        InventoryItem queried = repository.findBySkuAndWarehouseCode(SKU, WAREHOUSE).orElseThrow();
        assertEquals(318, queried.getAvailableQty().intValue());
        assertEquals(14, queried.getLockedQty().intValue());
        assertEquals(new BigDecimal("89.90"), queried.getUnitPrice());

        // (3) 释放 2 件（支付失败 / 订单取消）：锁定 14 -> 12，可用 318 -> 320
        assertTrue(service.releaseLockedInventory(SKU, WAREHOUSE, 2));
        assertEquals(320, item.getAvailableQty().intValue());
        assertEquals(12, item.getLockedQty().intValue());

        verify(repository, times(2)).save(item);
        verify(lock, times(2)).unlock();
    }

    @Test
    @DisplayName("释放量超过锁定量被拒绝，可用库存不被凭空增加")
    void release_isRejectedWhenExceedingLockedQty() throws InterruptedException {
        InventoryItem item = stock(100, 3);
        givenExistingStock(item);
        givenLockAcquired();

        assertFalse(service.releaseLockedInventory(SKU, WAREHOUSE, 50));

        assertEquals(100, item.getAvailableQty().intValue(), "可用库存不得被凭空增加");
        assertEquals(3, item.getLockedQty().intValue());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("非正数数量（0 / 负数）被拒绝，且不触碰锁与数据库")
    void nonPositiveQtyIsRejected() {
        assertFalse(service.deductInventory(SKU, WAREHOUSE, 0));
        assertFalse(service.deductInventory(SKU, WAREHOUSE, -5));
        assertFalse(service.releaseLockedInventory(SKU, WAREHOUSE, -1));

        verifyNoInteractions(redissonClient, repository);
    }

    @Test
    @DisplayName("可用库存不足时扣减失败，且不写库")
    void deduct_failsWhenStockInsufficient() throws InterruptedException {
        InventoryItem item = stock(1, 0);
        givenExistingStock(item);
        givenLockAcquired();

        assertFalse(service.deductInventory(SKU, WAREHOUSE, 2));

        assertEquals(1, item.getAvailableQty().intValue());
        assertEquals(0, item.getLockedQty().intValue());
        verify(repository, never()).save(any());
        verify(lock).unlock();
    }

    @Test
    @DisplayName("未抢到分布式锁时扣减与释放均返回 false")
    void failsWhenLockNotAcquired() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(2, 10, TimeUnit.SECONDS)).thenReturn(false);

        assertFalse(service.deductInventory(SKU, WAREHOUSE, 2));
        assertFalse(service.releaseLockedInventory(SKU, WAREHOUSE, 2));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("SKU / 仓库不存在时扣减与释放均返回 false，且不写库")
    void returnsFalseWhenStockRowMissing() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(2, 10, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(repository.findBySkuAndWarehouseCode("NOPE", WAREHOUSE)).thenReturn(Optional.empty());

        assertFalse(service.deductInventory("NOPE", WAREHOUSE, 1));
        assertFalse(service.releaseLockedInventory("NOPE", WAREHOUSE, 1));

        verify(repository, never()).save(any());
    }
}
