package com.aslp.inventory.service;

import com.aslp.inventory.entity.InventoryItem;
import com.aslp.inventory.repository.InventoryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 库存预占/释放（M2 防超卖核心）。
 *
 * <p>P1-1：把操作结果导出为 Micrometer 指标
 * {@code aslp_inventory_lock_operations_total{operation,result}}。
 * 只暴露一个 boolean 是无法运维的：
 * 「扣减失败」可能是库存不足（需补货）或抢锁失败（需看并发压力）—— 两者处置完全不同，
 * 因此内部用 {@link Outcome} 区分，对外仍只返回 boolean（不改调用方契约）。
 */
@Service
public class InventoryLockService {

    /** P1-1：锁操作计数器（Prometheus: aslp_inventory_lock_operations_total）。 */
    public static final String METRIC_LOCK_OPERATIONS = "aslp.inventory.lock.operations";

    private final RedissonClient redissonClient;
    private final InventoryRepository repository;
    private final MeterRegistry meterRegistry;

    public InventoryLockService(RedissonClient redissonClient, InventoryRepository repository,
                                MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    /** 获取锁的等待时间 / 租约时间（秒）—— 租约到期自动释放，防死锁 */
    private static final long WAIT_SECONDS = 2;
    private static final long LEASE_SECONDS = 10;

    /** 分布式锁键：同 SKU + 同仓互斥；不同仓库用不同键，两仓操作互不阻塞。 */
    private String lockKey(String sku, String warehouseCode) {
        return "inventory:lock:" + sku + ":" + warehouseCode;
    }

    /**
     * 两仓库存扣减（防超卖）— Redisson 分布式锁 + JPA 乐观锁双层防护。
     *
     * <p>语义：可用库存 -&gt; 锁定库存（预占）。支付成功后由出库流程转实际扣减，
     * 支付失败 / 订单取消则调 {@link #releaseLockedInventory} 释放。
     *
     * @return true=预占成功；false=数量非法 / 锁竞争失败 / SKU 不存在 / 可用库存不足
     */
    @Transactional
    public boolean deductInventory(String sku, String warehouseCode, int qty) {
        return record("deduct", doDeductInventory(sku, warehouseCode, qty));
    }

    /** 实际扣减逻辑（返回值带拒绝原因，供指标分类）。 */
    private Outcome doDeductInventory(String sku, String warehouseCode, int qty) {
        // 防御：负数会让 availableQty 反向增加（凭空造库存）
        if (qty <= 0) {
            return Outcome.REJECTED_INVALID_QTY;
        }
        RLock lock = redissonClient.getLock(lockKey(sku, warehouseCode));
        try {
            if (!lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS)) {
                return Outcome.REJECTED_LOCK; // 获取锁失败，可能并发冲突
            }
            Optional<InventoryItem> opt = repository.findBySkuAndWarehouseCode(sku, warehouseCode);
            if (opt.isEmpty()) {
                return Outcome.REJECTED_NOT_FOUND;
            }
            InventoryItem item = opt.get();
            if (qtyOf(item.getAvailableQty()) < qty) {
                return Outcome.REJECTED_BALANCE; // 可用库存不足
            }
            item.setAvailableQty(qtyOf(item.getAvailableQty()) - qty);
            item.setLockedQty(qtyOf(item.getLockedQty()) + qty);
            repository.save(item);
            return Outcome.APPLIED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.REJECTED_LOCK;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 释放锁定库存（支付失败 / 订单取消 / 超时未支付）。
     *
     * <p>语义：锁定库存 -&gt; 可用库存。与扣减使用同一把分布式锁，
     * 避免「释放」与「扣减」并发时互相覆盖。
     *
     * <p>边界：释放量不得超当前锁定量 —— 否则会凭空增加可用库存（原实现的真实缺陷）。
     *
     * @return true=释放成功；false=数量非法 / 锁竞争失败 / SKU 不存在 / 释放量 &gt; 锁定量
     */
    @Transactional
    public boolean releaseLockedInventory(String sku, String warehouseCode, int qty) {
        return record("release", doReleaseLockedInventory(sku, warehouseCode, qty));
    }

    /** 实际释放逻辑（返回值带拒绝原因，供指标分类）。 */
    private Outcome doReleaseLockedInventory(String sku, String warehouseCode, int qty) {
        if (qty <= 0) {
            return Outcome.REJECTED_INVALID_QTY;
        }
        RLock lock = redissonClient.getLock(lockKey(sku, warehouseCode));
        try {
            if (!lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS)) {
                return Outcome.REJECTED_LOCK;
            }
            Optional<InventoryItem> opt = repository.findBySkuAndWarehouseCode(sku, warehouseCode);
            if (opt.isEmpty()) {
                return Outcome.REJECTED_NOT_FOUND;
            }
            InventoryItem item = opt.get();
            if (qtyOf(item.getLockedQty()) < qty) {
                return Outcome.REJECTED_BALANCE; // 释放量超过锁定量
            }
            item.setLockedQty(qtyOf(item.getLockedQty()) - qty);
            item.setAvailableQty(qtyOf(item.getAvailableQty()) + qty);
            repository.save(item);
            return Outcome.APPLIED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.REJECTED_LOCK;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** P1-1：记一次指标并保持对外契约（boolean）。 */
    private boolean record(String operation, Outcome outcome) {
        meterRegistry.counter(METRIC_LOCK_OPERATIONS, "operation", operation, "result", outcome.tag())
                .increment();
        return outcome == Outcome.APPLIED;
    }

    /**
     * 锁操作结果（P1-1 指标标签）。
     *
     * <p>刻意把「业务性拒绝」与「拿不到锁」分开：前者要看库存数据（该补货了），
     * 后者要看并发压力（该调锁等待或拆热点 SKU）—— 混成一个 false 无法定位。
     */
    private enum Outcome {
        APPLIED,
        /** 数量非法（<= 0） */
        REJECTED_INVALID_QTY,
        /** SKU / 仓库不存在 */
        REJECTED_NOT_FOUND,
        /** 余额不满足：扣减时可用不足，或释放时超过锁定量 */
        REJECTED_BALANCE,
        /** 拿不到锁：竞争超时或被中断 */
        REJECTED_LOCK;

        /** 指标标签用小写，符合 Prometheus 命名习惯。 */
        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 字段虽为 NOT NULL，仍防御 null（历史故障：种子数据 version=NULL 曾致扣减必失败）。 */
    private static int qtyOf(Integer value) {
        return value == null ? 0 : value;
    }
}
