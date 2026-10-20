package com.library.controller;

import com.library.service.MultiDatabaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/conflict-resolution")
@Slf4j
public class ConflictResolutionController {

    @Autowired
    private MultiDatabaseService multiDatabaseService;

    /**
     * 解决冲突 - 统一接口
     */
    @PostMapping("/resolve")
    public ResponseEntity<Map<String, Object>> resolveConflict(@RequestBody Map<String, Object> request) {
        String conflictId = (String) request.get("conflictId");
        String strategy = (String) request.get("strategy");
        String resolvedBy = (String) request.get("resolvedBy");

        log.info("收到冲突解决请求: ID={}, 策略={}, 操作人={}", conflictId, strategy, resolvedBy);

        Map<String, Object> result = multiDatabaseService.resolveDataConflict(conflictId, strategy, resolvedBy);

        return ResponseEntity.ok(result);
    }

    /**
     * 批量解决冲突
     */
    @PostMapping("/resolve-batch")
    public ResponseEntity<Map<String, Object>> resolveConflictsBatch(@RequestBody Map<String, Object> request) {
        // 实现批量解决逻辑
        // 可以遍历冲突ID列表，逐个调用resolveDataConflict
        return ResponseEntity.ok(Map.of("success", true, "message", "批量解决功能待实现"));
    }
}