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

    /**
     * 两仓库存扣减（防超卖）— Redisson 分布式锁 + 乐观锁
     * 锁键格式：inventory:lock:{sku}:{warehouseCode}
     */
    @Transactional
    public boolean deductInventory(String sku, String warehouseCode, int qty) {
        String lockKey = "inventory:lock:" + sku + ":" + warehouseCode;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // 获取锁，等待 2s，持有 10s（防死锁）
            if (!lock.tryLock(2, 10, TimeUnit.SECONDS)) {
                return false; // 获取锁失败，可能并发冲突
            }
            Optional<InventoryItem> opt = repository.findBySkuAndWarehouseCode(sku, warehouseCode);
            if (opt.isEmpty()) return false;
            InventoryItem item = opt.get();
            if (item.getAvailableQty() < qty) return false; // 库存不足
            item.setAvailableQty(item.getAvailableQty() - qty);
            item.setLockedQty(item.getLockedQty() + qty);
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

    /** 释放锁定库存（支付失败 / 订单取消） */
    @Transactional
    public void releaseLockedInventory(String sku, String warehouseCode, int qty) {
        Optional<InventoryItem> opt = repository.findBySkuAndWarehouseCode(sku, warehouseCode);
        if (opt.isPresent()) {
            InventoryItem item = opt.get();
            item.setLockedQty(Math.max(0, item.getLockedQty() - qty));
            item.setAvailableQty(item.getAvailableQty() + qty);
            repository.save(item);
        }
    }
}
