package com.mhuang.sce.service;

import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InventoryTransferService {

    // 模拟各仓库的细粒度内存行锁
    private final Map<String, Object> warehouseLocks = new ConcurrentHashMap<>();
    private final Map<String, Integer> stockLedger = new ConcurrentHashMap<>(Map.of(
            "WH-SH:SKU-001", 1000,
            "WH-BJ:SKU-001", 1000
    ));

    /**
     * 故障代码：直接按照调用方传入的 fromWh -> toWh 顺序加锁
     * 当并发出现 A->B 与 B->A 双向调拨时，必然触发 AB-BA 死锁
     */
    public String transferStock(String fromWh, String toWh, String sku, int count) {
        Object firstLock = warehouseLocks.computeIfAbsent(fromWh, k -> new Object());
        Object secondLock = warehouseLocks.computeIfAbsent(toWh, k -> new Object());

        synchronized (firstLock) {
            // 模拟校验源仓库库存、记录流水等耗时操作，放大锁竞争窗口
            sleepQuietly(100);

            synchronized (secondLock) {
                String fromKey = fromWh + ":" + sku;
                String toKey = toWh + ":" + sku;
                int current = stockLedger.getOrDefault(fromKey, 0);
                if (current < count) {
                    return "INSUFFICIENT_STOCK";
                }
                stockLedger.put(fromKey, current - count);
                stockLedger.put(toKey, stockLedger.getOrDefault(toKey, 0) + count);
                return "SUCCESS: " + fromWh + " -> " + toWh + " (" + count + ")";
            }
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
