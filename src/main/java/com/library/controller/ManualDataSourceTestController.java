package com.library.controller;

import com.library.config.DataSourceContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/manual-test")
@Slf4j
public class ManualDataSourceTestController {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DataSourceContextHolder dataSourceContextHolder;

    @GetMapping("/test-all")
    public Map<String, Object> testAllDataSources() {
        Map<String, Object> result = new HashMap<>();

        // 测试MySQL
        log.info("测试MySQL连接...");
        try {
            dataSourceContextHolder.setMySQLDataSource();
            Connection conn = dataSource.getConnection();
            result.put("mysql", getConnectionInfo(conn));
            conn.close();
            log.info("✅ MySQL测试成功");
        } catch (Exception e) {
            result.put("mysql", "❌ " + e.getMessage());
            log.error("❌ MySQL测试失败: {}", e.getMessage());
        } finally {
            dataSourceContextHolder.clearDataSource();
        }

        // 测试PostgreSQL
        log.info("测试PostgreSQL连接...");
        try {
            dataSourceContextHolder.setPostgreSQLDataSource();
            Connection conn = dataSource.getConnection();
            result.put("postgresql", getConnectionInfo(conn));
            conn.close();
            log.info("✅ PostgreSQL测试成功");
        } catch (Exception e) {
            result.put("postgresql", "❌ " + e.getMessage());
            log.error("❌ PostgreSQL测试失败: {}", e.getMessage());
        } finally {
            dataSourceContextHolder.clearDataSource();
        }

        // 测试SQL Server
        log.info("测试SQL Server连接...");
        try {
            dataSourceContextHolder.setSQLServerDataSource();
            Connection conn = dataSource.getConnection();
            result.put("sqlserver", getConnectionInfo(conn));
            conn.close();
            log.info("✅ SQL Server测试成功");
        } catch (Exception e) {
            result.put("sqlserver", "❌ " + e.getMessage());
            log.error("❌ SQL Server测试失败: {}", e.getMessage());
        } finally {
            dataSourceContextHolder.clearDataSource();
        }

        return result;
    }

    private Map<String, String> getConnectionInfo(Connection conn) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        Map<String, String> info = new HashMap<>();
        info.put("database", meta.getDatabaseProductName());
        info.put("url", meta.getURL());
        info.put("version", meta.getDatabaseProductVersion());
        info.put("status", "✅ 成功");
        return info;
    }
}