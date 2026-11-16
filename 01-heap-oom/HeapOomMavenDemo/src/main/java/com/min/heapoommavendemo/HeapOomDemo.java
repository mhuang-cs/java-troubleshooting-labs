package com.min.heapoommavendemo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;

/**
 * 模拟场景：使用 Caffeine 本地缓存限制容量上限与过期淘汰，防止对象持续晋升老年代触发 OOM
 * 适用环境：JDK 17 / JDK 21 (G1 GC)
 */
public class HeapOomDemo {

    private static final Cache<Long, OrderCachePayload> LOCAL_CACHE = Caffeine.newBuilder()
            .maximumSize(100)                          // 限制最大条目数，内存上限约 25MB
            .expireAfterWrite(Duration.ofMinutes(5))   // 写入 5 分钟后自动过期淘汰
            .build();

    static class OrderCachePayload {
        private final long orderId;
        // 每个对象分配 256KB
        private final byte[] payload = new byte[256 * 1024];

        public OrderCachePayload(long orderId) {
            this.orderId = orderId;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        long pid = ProcessHandle.current().pid();
        System.out.println("============================================================");
        System.out.println("HeapOomDemo 已启动 | 当前进程 PID: " + pid);
        System.out.println("请在终端 B 执行: jcmd " + pid + " GC.heap_info");
        System.out.println("============================================================");

        long count = 0;
        while (true) {
            count++;
            // 修改点 1：改为 put(key, value) 写入缓存
            LOCAL_CACHE.put(count, new OrderCachePayload(count));

            if (count % 10 == 0) {
                // 修改点 2：读取实际缓存条目数（上限会稳定在 100 左右）
                long currentSize = LOCAL_CACHE.estimatedSize();
                System.out.printf("[运行监控] 累计生成: %d | 当前缓存对象数: %d | 预计占用堆内存: ~%.1f MB%n",
                        count, currentSize, (currentSize * 256.0) / 1024);
            }
            Thread.sleep(Duration.ofMillis(180));
        }
    }
}