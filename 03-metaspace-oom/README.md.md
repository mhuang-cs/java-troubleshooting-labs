# 03：元空间溢出 (Metaspace) 排查实录

## 1. 故障现象
应用在运行过程中并未发生堆内存不足，但抛出 `java.lang.OutOfMemoryError: Metaspace`（JDK 8 之前为 `PermGen space`）。

## 2. 复现步骤

在 **终端窗口 A** 编译并限制最大元空间为 `48MB` 运行：
```bash
cd ~/github-labs/java-troubleshooting-labs/03-metaspace-oom
javac MetaspaceOomDemo.java
java -XX:MetaspaceSize=24m \
     -XX:MaxMetaspaceSize=48m \
     -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=./metaspace_oom.hprof \
     MetaspaceOomDemo
```

在 **终端窗口 B** 实时监控元空间容量变化（将 `<PID>` 替换为实际进程号）：
```bash
jstat -gcmetacapacity <PID> 1000
```

*(进阶可选：在程序抛出 OOM 前，可用 `java -jar ~/tools/arthas-boot.jar` 连接，执行 `classloader` 查看类加载器数量，执行 `memory` 查看 `metaspace` 分区实时占用)*

## 3. 排查与定位过程
1. **`jstat -gcmetacapacity` 观测**：
   - `MC`（当前 Metaspace 容量）从 `24576 KB` 自动扩容至上限 `49152 KB`（48MB）。
   - `CCSC`（压缩类空间容量）同步增长，伴随多次 `FGC` 尝试卸载无用类，但回收无效，最终触发 `OutOfMemoryError: Metaspace`。
2. **类卸载条件分析（面试核心考点）**：
   - JVM 卸载一个类必须同时满足 3 个严苛条件：
     1. 该类的**所有实例**都已被回收；
     2. 加载该类的 **`ClassLoader` 实例**已被回收；
     3. 该类对应的 `java.lang.Class` 对象没有任何地方被引用。
   - 本例中 `proxyHolder` 持有了代理实例的强引用，导致代理实例 -> 代理 `Class` -> `CustomClassLoader` 整条引用链全部存活，触发元空间泄漏。
3. **截图记录**（将 `jstat` 或 `Arthas classloader` 截图粘贴至 `images/01-metaspace-jstat.png`）：

![](images/01-metaspace-jstat.png)

## 4. 根因总结与解决方案
- **根因**：程序在循环或高频请求中动态生成新类（如未缓存的 CGLIB/JDK 动态代理、反射生成字节码、未复用引擎的 Groovy/Aviator 脚本解析），且类加载器无法被回收。
- **修复方案**：
  1. **复用类加载器与代理缓存**：对动态生成的代理类、脚本编译结果进行缓存复用（如 `ConcurrentHashMap` 缓存），避免重复创建 `ClassLoader`。
  2. **设置合理的元空间上限**：生产环境务必显式指定 `-XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m`（默认无上限，若不限制可能把机器物理内存吃光导致 OS OOM Killer 强杀进程）。