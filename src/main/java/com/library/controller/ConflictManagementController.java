package com.library.controller;

import com.library.service.MultiDatabaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conflicts")
@Slf4j
public class ConflictManagementController {

    @Autowired
    private MultiDatabaseService multiDatabaseService;

    /**
     * 统一解决冲突接口
     */
    @PostMapping("/resolve")
    @PreAuthorize("hasRole('SYS_ADMIN')")  // 只有系统管理员可以解决冲突
    public ResponseEntity<Map<String, Object>> resolveConflict(
            @RequestBody Map<String, Object> request) {

        String conflictId = (String) request.get("conflictId");
        String strategy = (String) request.get("strategy");
        String resolvedBy = (String) request.get("resolvedBy");
        String resolutionAction = (String) request.get("resolutionAction");

        log.info("解决冲突请求: ID={}, 策略={}, 操作人={}",
                conflictId, strategy, resolvedBy);

        // 策略映射
        String mappedStrategy = mapLegacyStrategy(strategy, resolutionAction);

        Map<String, Object> result = multiDatabaseService.resolveDataConflict(
                conflictId, mappedStrategy, resolvedBy);

        return ResponseEntity.ok(result);
    }

    /**
     * 映射旧策略到新策略
     */
    private String mapLegacyStrategy(String strategy, String resolutionAction) {
        if (strategy == null && resolutionAction != null) {
            return "USE_LATEST"; // 手动合并对应最新数据
        }

        switch (strategy != null ? strategy.toUpperCase() : "") {
            case "SOURCE":
            case "USE_SOURCE":
                return "USE_SOURCE";
            case "TARGET":
            case "USE_TARGET":
                return "USE_TARGET";
            case "MANUAL":
            case "USE_LATEST":
                return "USE_LATEST";
            default:
                return strategy != null ? strategy : "USE_LATEST";
        }
    }

    /**
     * 获取未解决冲突列表
     */
    @GetMapping("/unresolved")
    @PreAuthorize("hasAnyRole('LIB_ADMIN', 'SYS_ADMIN')")
    public ResponseEntity<Map<String, Object>> getUnresolvedConflicts() {
        log.info("获取未解决冲突列表");
        Map<String, Object> result = multiDatabaseService.getAllUnresolvedConflicts();
        return ResponseEntity.ok(result);
    }

    /**
     * 刷新冲突检测
     */
    @PostMapping("/refresh")
    @PreAuthorize("hasAnyRole('LIB_ADMIN', 'SYS_ADMIN')")
    public ResponseEntity<Map<String, Object>> refreshConflicts() {
        log.info("刷新冲突检测");
        Map<String, Object> result = multiDatabaseService.detectAllConflicts();
        return ResponseEntity.ok(result);
    }

    /**
     * 获取所有冲突（包括已处理的）
     */
    @GetMapping("/all")
    @PreAuthorize("hasAnyRole('LIB_ADMIN', 'SYS_ADMIN')")
    public ResponseEntity<Map<String, Object>> getAllConflicts() {
        log.info("获取所有冲突列表");
        Map<String, Object> result = new HashMap<>();

        try {
            // 使用现有的MultiDatabaseService方法获取冲突
            Map<String, Object> unresolvedResult = multiDatabaseService.getAllUnresolvedConflicts();
            List<Map<String, Object>> unresolved = (List<Map<String, Object>>) unresolvedResult.get("conflicts");

            // 获取已处理的冲突（需要从数据库查询）
            String sql = "SELECT * FROM sync_logs WHERE resolved = true ORDER BY sync_time DESC";
            List<Map<String, Object>> resolved = new ArrayList<>();
            try {
                // 这里需要获取mysqlJdbc来执行查询
                // resolved = mysqlJdbc.queryForList(sql);
            } catch (Exception e) {
                log.error("获取已处理冲突失败", e);
            }

            // 合并结果
            List<Map<String, Object>> allConflicts = new ArrayList<>();
            if (unresolved != null) allConflicts.addAll(unresolved);
            allConflicts.addAll(resolved);

            result.put("success", true);
            result.put("total", allConflicts.size());
            result.put("unresolved", unresolved != null ? unresolved.size() : 0);
            result.put("resolved", resolved.size());
            result.put("conflicts", allConflicts);

        } catch (Exception e) {
            log.error("获取所有冲突失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 获取冲突统计（管理员可查看）
     */
    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('LIB_ADMIN', 'SYS_ADMIN')")
    public ResponseEntity<Map<String, Object>> getConflictStats() {
        try {
            // 获取冲突统计数据
            Map<String, Object> unresolved = multiDatabaseService.getAllUnresolvedConflicts();
            int unresolvedCount = (int) unresolved.getOrDefault("total", 0);

            Map<String, Object> stats = Map.of(
                    "unresolvedCount", unresolvedCount,
                    "todayCount", 0,  // 可以添加日期统计
                    "byTable", unresolved.get("tableStats"),
                    "byDatabase", unresolved.get("dbPairStats"),
                    "timestamp", new java.util.Date()
            );

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("获取冲突统计失败", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}