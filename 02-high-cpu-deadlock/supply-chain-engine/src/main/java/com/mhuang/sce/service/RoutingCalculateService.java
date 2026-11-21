package com.mhuang.sce.service;

import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
public class RoutingCalculateService {

    /**
     * 单次路由寻址允许的最大跳数阈值（生产环境可接入配置中心动态调整）：
     * 1. 业务冗余：真实供应链仓网中转通常为 3~8 跳，32 跳预留了 4 倍以上安全余量，避免误杀正常链路；
     * 2. 算力硬隔离：补齐 visitedNodes 仅能判环的盲区，防止「无环但超长」的脏配置链路耗尽 CPU 算力；
     * 3. 容器对齐：2^5 = 32 贴合 HashSet 2 的幂次扩容机制（16 -> 32），单次扩容即可容纳全路径且无冗余重排。
     */
    private static final int MAX_ALLOWED_HOPS = 32;

    // 模拟配置中心下发的仓间路由下一跳拓扑：
    // 运维误操作将 WH-NJ 的下一跳配置回 WH-SH，形成闭环 WH-SH -> WH-HZ -> WH-NJ -> WH-SH
    private final Map<String, String> nextHopTopology = Map.of(
            "WH-SH", "WH-HZ",
            "WH-HZ", "WH-NJ",
            "WH-NJ", "WH-SH",
            "WH-GZ", "WH-SZ" // 正常无环链路
    );

    /**
     * 修复方案：引入 visitedNodes 实时判环 + 最大跳数阈值熔断，阻断脏配置引发 CPU 100% 死循环
     */
    public String calculateOptimalHub(String startWh) {
        String current = startWh;
        double routeCostScore = 0.0;
        Set<String> visitedNodes = new HashSet<>();
        int hops = 0;

        while (current != null) {
            // 1. O(1) 实时判环：检测拓扑有向闭环并触发 Fail-Fast（HTTP 422）
            if (!visitedNodes.add(current)) {
                throw new IllegalStateException("Cyclic routing topology detected at node: " + current);
            }
            // 2. 最大跳数硬上限熔断：将最坏计算复杂度收敛至 O(1) 常量级
            if (++hops > MAX_ALLOWED_HOPS) {
                throw new IllegalStateException("Routing hops exceeded max threshold: " + MAX_ALLOWED_HOPS);
            }

            // 模拟复杂路由运费与时效权重计算（CPU 密集型）
            routeCostScore += computeHopWeight(current, routeCostScore);
            String next = nextHopTopology.get(current);
            if (next == null) {
                return String.format("TerminalHub=%s, CostScore=%.4f", current, routeCostScore);
            }
            current = next;
        }
        return "UNREACHABLE";
    }

    private double computeHopWeight(String node, double seed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((node + seed).getBytes(StandardCharsets.UTF_8));
            return Math.abs(Math.sin(hash[0])) * 10.5;
        } catch (NoSuchAlgorithmException e) {
            return 1.0;
        }
    }
}