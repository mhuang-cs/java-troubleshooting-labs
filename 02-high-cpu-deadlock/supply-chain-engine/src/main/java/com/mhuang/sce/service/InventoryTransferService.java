package com.mhuang.sce.service;

import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class InventoryTransferService {

    /**
     * 单把仓库锁最大等待超时时间（毫秒）：
     * 1. 远大于单次内存账本扣减耗时（~100ms），保障正常并发请求平滑排队完成；
     * 2. 极端热点仓突发争抢时触发快速退避，防止 Tomcat 工作线程无限期挂起导致全站雪崩。
     */
    private static final long LOCK_WAIT_TIMEOUT_MS = 500L;

    // 模拟各仓库的细粒度内存行锁
    private final Map<String, ReentrantLock> warehouseLocks = new ConcurrentHashMap<>();
    private final Map<String, Integer> stockLedger = new ConcurrentHashMap<>(Map.of(
            "WH-SH:SKU-001", 1000,
            "WH-BJ:SKU-001", 1000
    ));

    /**
     * 修复方案：按仓库编码字典序全局统一加锁顺序 + ReentrantLock 超时退避，彻底消除 AB-BA 死锁
     */
    public String transferStock(String fromWh, String toWh, String sku, int count) {
        if (fromWh.equals(toWh)) {
            return "INVALID_SAME_WAREHOUSE";
        }

        // 1. 全局统一按字典序确定加锁顺序，破坏死锁必要条件中的「循环等待」
        String firstWh = fromWh.compareTo(toWh) < 0 ? fromWh : toWh;
        String secondWh = fromWh.compareTo(toWh) < 0 ? toWh : fromWh;

        ReentrantLock firstLock = warehouseLocks.computeIfAbsent(firstWh, k -> new ReentrantLock());
        ReentrantLock secondLock = warehouseLocks.computeIfAbsent(secondWh, k -> new ReentrantLock());

        try {
            // 2. 使用 tryLock 超时退避替代 synchronized，破坏「不可抢占」并保障线程活性
            if (!firstLock.tryLock(LOCK_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return "BUSY_RETRY_LATER";
            }
            try {
                // 模拟校验源仓库库存、记录流水等耗时操作，放大锁竞争窗口
                sleepQuietly(100);

                if (!secondLock.tryLock(LOCK_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    return "BUSY_RETRY_LATER";
                }
                try {
                    String fromKey = fromWh + ":" + sku;
                    String toKey = toWh + ":" + sku;
                    int current = stockLedger.getOrDefault(fromKey, 0);
                    if (current < count) {
                        return "INSUFFICIENT_STOCK";
                    }
                    stockLedger.put(fromKey, current - count);
                    stockLedger.put(toKey, stockLedger.getOrDefault(toKey, 0) + count);
                    return "SUCCESS: " + fromWh + " -> " + toWh + " (" + count + ")";
                } finally {
                    secondLock.unlock();
                }
            } finally {
                firstLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "INTERRUPTED";
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}