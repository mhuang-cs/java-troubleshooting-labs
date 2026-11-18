package com.mhuang.sce.service;

import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

@Service
public class RoutingCalculateService {

    // 模拟配置中心下发的仓间路由下一跳拓扑：
    // 运维误操作将 WH-NJ 的下一跳配置回 WH-SH，形成闭环 WH-SH -> WH-HZ -> WH-NJ -> WH-SH
    private final Map<String, String> nextHopTopology = Map.of(
            "WH-SH", "WH-HZ",
            "WH-HZ", "WH-NJ",
            "WH-NJ", "WH-SH",
            "WH-GZ", "WH-SZ" // 正常无环链路
    );

    /**
     * 故障代码：缺少环路检测与最大跳数限制，且循环内包含密集哈希/浮点权重计算（防止被 JIT 消除）
     */
    public String calculateOptimalHub(String startWh) {
        String current = startWh;
        double routeCostScore = 0.0;

        while (current != null) {
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
