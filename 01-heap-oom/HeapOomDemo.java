import java.time.Duration;
import java.util.ArrayList;
import java.util.List;



/**
 * 模拟场景：本地静态缓存缺乏容量上限与淘汰机制，对象持续晋升老年代触发 Java heap space
 * 适用环境：JDK 17 / JDK 21 (G1 GC)
 */
public class HeapOomDemo {

    // 模拟无淘汰机制的本地静态缓存（GC Roots 强引用持有，无法被回收）
    private static final List<OrderCachePayload> LOCAL_CACHE = new ArrayList<>();

    static class OrderCachePayload {
        private final long orderId;
        // 每个对象分配 256KB（避开 G1 默认 1MB Region 的大对象阈值，模拟正常分代晋升）
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
            LOCAL_CACHE.add(new OrderCachePayload(count));
            if (count % 10 == 0) {
                System.out.printf("[运行监控] 已缓存对象数: %d | 预计占用堆内存: ~%.1f MB%n",
                        count, (count * 256.0) / 1024);
            }
            // 每 180ms 分配 256KB（约 3.1MB/s），在 -Xmx64m 下约 18 秒触发 OOM，留足在线排查窗口
            Thread.sleep(Duration.ofMillis(180));
        }
    }
}