package com.aslp.inventory.service;

import com.aslp.inventory.entity.InventoryItem;
import com.aslp.inventory.repository.InventoryRepository;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class InventoryLockService {

    private final RedissonClient redissonClient;
    private final InventoryRepository repository;

    public InventoryLockService(RedissonClient redissonClient, InventoryRepository repository) {
        this.redissonClient = redissonClient;
        this.repository = repository;
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
        // 防御：负数会让 availableQty 反向增加（凭空造库存）
        if (qty <= 0) {
            return false;
        }
        RLock lock = redissonClient.getLock(lockKey(sku, warehouseCode));
        try {
            if (!lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS)) {
                return false; // 获取锁失败，可能并发冲突
            }
            Optional<InventoryItem> opt = repository.findBySkuAndWarehouseCode(sku, warehouseCode);
            if (opt.isEmpty()) {
                return false;
            }
            InventoryItem item = opt.get();
            if (qtyOf(item.getAvailableQty()) < qty) {
                return false; // 库存不足
            }
            item.setAvailableQty(qtyOf(item.getAvailableQty()) - qty);
            item.setLockedQty(qtyOf(item.getLockedQty()) + qty);
            repository.save(item);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
        if (qty <= 0) {
            return false;
        }
        RLock lock = redissonClient.getLock(lockKey(sku, warehouseCode));
        try {
            if (!lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS)) {
                return false;
            }
            Optional<InventoryItem> opt = repository.findBySkuAndWarehouseCode(sku, warehouseCode);
            if (opt.isEmpty()) {
                return false;
            }
            InventoryItem item = opt.get();
            if (qtyOf(item.getLockedQty()) < qty) {
                return false; // 释放量超过锁定量
            }
            item.setLockedQty(qtyOf(item.getLockedQty()) - qty);
            item.setAvailableQty(qtyOf(item.getAvailableQty()) + qty);
            repository.save(item);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 字段虽为 NOT NULL，仍防御 null（历史故障：种子数据 version=NULL 曾致扣减必失败）。 */
    private static int qtyOf(Integer value) {
        return value == null ? 0 : value;
    }
}
