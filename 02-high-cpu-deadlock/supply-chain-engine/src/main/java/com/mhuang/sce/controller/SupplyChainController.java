package com.mhuang.sce.controller;

import com.mhuang.sce.service.InventoryTransferService;

import com.mhuang.sce.service.RoutingCalculateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SupplyChainController {

    private final InventoryTransferService transferService;
    private final RoutingCalculateService routingService;

    public SupplyChainController(InventoryTransferService transferService,
                                 RoutingCalculateService routingService) {
        this.transferService = transferService;
        this.routingService = routingService;
    }

    @PostMapping("/inventory/transfer")
    public String transfer(@RequestParam String fromWh,
                           @RequestParam String toWh,
                           @RequestParam(defaultValue = "SKU-001") String sku,
                           @RequestParam(defaultValue = "10") int count) {
        return transferService.transferStock(fromWh, toWh, sku, count);
    }

    @GetMapping("/routing/optimal")
    public String optimalRoute(@RequestParam String startWh) {
        return routingService.calculateOptimalHub(startWh);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.unprocessableEntity().body("FAST_FAIL: " + ex.getMessage());
    }
}
