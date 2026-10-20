package com.library.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/verify")
@Slf4j
public class DataSourceVerificationController {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private com.library.config.DataSourceContextHolder dataSourceContextHolder;

    /**
     * 验证三个数据源是否真的不同
     */
    @GetMapping("/datasources")
    public ResponseEntity<Map<String, Object>> verifyDataSources() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> connections = new HashMap<>();

        String[] dataSources = {"mysql", "postgresql", "sqlserver"};

        for (String dsName : dataSources) {
            Connection conn = null;
            try {
                log.info("🔍 验证数据源: {}", dsName);

                // 切换到目标数据源
                if ("mysql".equals(dsName)) {
                    dataSourceContextHolder.setMySQLDataSource();
                } else if ("postgresql".equals(dsName)) {
                    dataSourceContextHolder.setPostgreSQLDataSource();
                } else if ("sqlserver".equals(dsName)) {
                    dataSourceContextHolder.setSQLServerDataSource();
                }

                // 获取连接
                conn = dataSource.getConnection();
                DatabaseMetaData metaData = conn.getMetaData();

                Map<String, String> info = new HashMap<>();
                info.put("databaseProductName", metaData.getDatabaseProductName());
                info.put("databaseProductVersion", metaData.getDatabaseProductVersion());
                info.put("url", metaData.getURL());
                info.put("driverName", metaData.getDriverName());
                info.put("driverVersion", metaData.getDriverVersion());

                connections.put(dsName, info);
                log.info("✅ {} 连接成功: {}", dsName, info);

            } catch (SQLException e) {
                log.error("❌ {} 连接失败: {}", dsName, e.getMessage());
                connections.put(dsName, Map.of("error", e.getMessage()));
            } finally {
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (SQLException e) {
                        log.warn("关闭连接失败: {}", e.getMessage());
                    }
                }
                dataSourceContextHolder.clearDataSource();
            }
        }

        result.put("connections", connections);

        // 检查是否所有数据库都不同
        boolean allDifferent = connections.values().stream()
                .filter(v -> v instanceof Map)
                .map(v -> (Map<?, ?>) v)
                .filter(map -> map.containsKey("url"))
                .map(map -> (String) map.get("url"))
                .distinct()
                .count() == 3;

        result.put("allDifferent", allDifferent);
        result.put("message", allDifferent ?
                "🎉 三个数据源配置正确，指向不同的数据库！" :
                "⚠️ 警告：部分数据源可能指向相同的数据库");

        return ResponseEntity.ok(result);
    }

    /**
     * 简单测试 - 在不同数据库创建测试表
     */
    @GetMapping("/create-test-data")
    public ResponseEntity<Map<String, Object>> createTestData() {
        Map<String, Object> result = new HashMap<>();

        try {
            // 在MySQL创建测试数据
            dataSourceContextHolder.setMySQLDataSource();
            createTestTable("mysql");

            // 在PostgreSQL创建测试数据
            dataSourceContextHolder.setPostgreSQLDataSource();
            createTestTable("postgresql");

            // 在SQL Server创建测试数据
            dataSourceContextHolder.setSQLServerDataSource();
            createTestTable("sqlserver");

            result.put("success", true);
            result.put("message", "测试数据创建成功");
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        } finally {
            dataSourceContextHolder.clearDataSource();
        }

        return ResponseEntity.ok(result);
    }

    private void createTestTable(String prefix) {
        try (Connection conn = dataSource.getConnection()) {
            String tableName = "test_" + prefix;
            String createTableSQL = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                    "id VARCHAR(50) PRIMARY KEY," +
                    "data VARCHAR(100)," +
                    "created_at TIMESTAMP" +
                    ")";

            conn.createStatement().execute(createTableSQL);

            // 插入测试数据
            String insertSQL = "INSERT INTO " + tableName + " (id, data, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)";
            try (var stmt = conn.prepareStatement(insertSQL)) {
                stmt.setString(1, prefix + "_001");
                stmt.setString(2, "来自 " + prefix + " 的测试数据");
                stmt.executeUpdate();
            }

            log.info("✅ 在 {} 中创建测试表成功", prefix);
        } catch (SQLException e) {
            log.error("❌ 在 {} 中创建测试表失败: {}", prefix, e.getMessage());
            throw new RuntimeException(e);
        }
    }
}