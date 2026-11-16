# 02：CPU 飙升与线程死锁排查实录

## 1. 故障现象
- 服务器监控告警显示 Java 进程 CPU 使用率长期处于高位（200%+），设备发热明显。
- 同时核心业务接口大量超时（线程永久阻塞在 `BLOCKED` 状态）。

## 2. 复现步骤

在 **终端窗口 A** 编译并启动程序：
```bash
cd ~/github-labs/java-troubleshooting-labs/02-high-cpu-deadlock
javac ThreadDeadlockDemo.java
java ThreadDeadlockDemo
```

## 3. 排查与定位过程

### 方式一：原生 JDK 命令定位（面试必背流程）

1. **找出高 CPU 进程与线程**：
   - **macOS 终端**：macOS 的 `top` 不支持 `-Hp` 参数，可执行 `ps -M <PID>` 查看进程下各线程 CPU 时间，并执行 `jstack <PID> > thread.dump` 导出线程栈。
   - **Linux 生产环境标准流程**（面试回答）：
     ```bash
     top -c                          # 1. 找到 CPU 最高的 Java 进程 PID
     top -Hp <PID>                   # 2. 找出该进程下 CPU 最高的线程 TID（十进制）
     printf "%x\n" <TID>             # 3. 将 TID 转为十六进制（如 0x1a03）
     jstack <PID> | grep -A 30 1a03  # 4. 在线程栈中匹配 nid=0x1a03 定位代码行
     ```
2. **分析 `jstack` 线程栈**：
   - 在 `biz-cpu-spin-worker-1` 线程栈中，状态为 `RUNNABLE`，栈顶精确指向 `ThreadDeadlockDemo.lambda$main$0(ThreadDeadlockDemo.java:18)`。
   - 翻到 `jstack` 输出最底部，JVM 直接检测到 **`Found one Java-level deadlock:`**，明确指出 `order-deadlock-thread-1` 与 `payment-deadlock-thread-2` 互相等待对方持有的监视器锁。

### 方式二：Arthas 在线诊断（高效实战）

在 **终端窗口 B** 启动 Arthas 并选择 `ThreadDeadlockDemo` 对应的序号：
```bash
java -jar ~/tools/arthas-boot.jar
```
进入 Arthas 交互终端后依次执行：
```bash
# 1. 查看最繁忙的前 3 个线程及其完整堆栈（直接标出 CPU 占比与代码行）
thread -n 3

# 2. 一键检测当前阻塞其他线程的“罪魁祸首”（直接定位死锁）
thread -b
```
- 截图保存至 `images/01-arthas-thread.png`：

![](images/01-arthas-thread.png)

*(排查完毕后，在 Arthas 中输入 `stop` 退出诊断，并在窗口 A 按 `Ctrl + C` 结束进程)*

## 4. 根因总结与解决方案
- **CPU 飙升根因**：`biz-cpu-spin-worker` 线程执行无休眠、无退出条件的 `while(true)` 密集计算，占满 CPU 时间片。（线上常见诱因还包括：正则表达式回溯、HashMap 并发死链或频繁 Full GC）。
- **死锁根因**：`t1` 按 `LOCK_ORDER -> LOCK_PAYMENT` 加锁，`t2` 按 `LOCK_PAYMENT -> LOCK_ORDER` 加锁，满足死锁四大必要条件（互斥、占有且等待、不可剥夺、循环等待）。
- **修复方案**：
  1. **消除死循环**：为循环增加明确的终止条件或合理的 `Thread.sleep` / 阻塞队列等待（`BlockingQueue.take()`）。
  2. **破坏死锁条件**：统一所有业务线程的**加锁顺序**（如按资源 ID 字典序加锁）；或使用 `ReentrantLock.tryLock(timeout, TimeUnit.SECONDS)` 设置锁获取超时时间，超时后主动释放已持有的锁并降级重试。