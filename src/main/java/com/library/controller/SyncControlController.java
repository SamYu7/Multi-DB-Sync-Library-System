package com.library.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/sync-control")
public class SyncControlController {

    @Value("${library.sync.realtime-enabled:true}")
    private boolean realtimeSyncEnabled;

    @Value("${library.sync.enabled:true}")
    private boolean syncEnabled;

    /**
     * 获取同步状态
     */
    @GetMapping("/status")
    public Map<String, Object> getSyncStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("realtimeSyncEnabled", realtimeSyncEnabled);
        status.put("syncEnabled", syncEnabled);
        status.put("lastSyncTime", System.currentTimeMillis());
        return status;
    }

    /**
     * 启用/禁用实时同步
     */
    @PostMapping("/realtime/{enabled}")
    public ResponseEntity<String> setRealtimeSync(@PathVariable boolean enabled) {
        this.realtimeSyncEnabled = enabled;
        return ResponseEntity.ok("实时同步已" + (enabled ? "启用" : "禁用"));
    }

    /**
     * 启用/禁用所有同步
     */
    @PostMapping("/all/{enabled}")
    public ResponseEntity<String> setAllSync(@PathVariable boolean enabled) {
        this.syncEnabled = enabled;
        return ResponseEntity.ok("所有同步已" + (enabled ? "启用" : "禁用"));
    }
}