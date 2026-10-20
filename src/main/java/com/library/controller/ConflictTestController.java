package com.library.controller;

import com.library.config.DataSourceContextHolder;
import com.library.service.DataSyncService;
import com.library.service.MultiDatabaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/test-conflict")
@Slf4j
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ConflictTestController {

    @Autowired
    private DataSourceContextHolder dataSourceContextHolder;

    @Autowired
    private MultiDatabaseService multiDatabaseService;

    @Autowired
    private DataSource dataSource; // 动态数据源

    // 线程池用于并发测试
    private final ExecutorService executorService = Executors.newFixedThreadPool(3);

    /**
     * 提供HTML测试页面
     */
    @GetMapping("/test-page")
    public String testPage() {
        return "datasource-test";
    }

    /* ==================== 前端可直接测试的GET接口 ==================== */

    /**
     * 获取数据源信息（简化版）- 直接测试预设的三个数据源
     */
    @GetMapping("/datasource-info-simple")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getDataSourceInfoSimple() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> dataSources = new ArrayList<>();

        try {
            log.info("📊 开始检查预设数据源配置...");

            String[] dbTypes = {"mysql", "postgresql", "sqlserver"};

            for (String dbType : dbTypes) {
                Map<String, Object> dsInfo = new HashMap<>();
                dsInfo.put("name", dbType);
                dsInfo.put("expectedType", dbType);

                Connection conn = null;
                try {
                    // 清理并切换数据源
                    dataSourceContextHolder.clearDataSource();

                    // 切换数据源
                    switch (dbType) {
                        case "mysql":
                            dataSourceContextHolder.setMySQLDataSource();
                            break;
                        case "postgresql":
                            dataSourceContextHolder.setPostgreSQLDataSource();
                            break;
                        case "sqlserver":
                            dataSourceContextHolder.setSQLServerDataSource();
                            break;
                    }

                    // 获取连接
                    conn = dataSource.getConnection();

                    // 获取连接信息
                    DatabaseMetaData metaData = conn.getMetaData();
                    String actualProduct = metaData.getDatabaseProductName();
                    String url = metaData.getURL();
                    String driver = metaData.getDriverName();
                    String version = metaData.getDatabaseProductVersion();

                    dsInfo.put("actualProduct", actualProduct);
                    dsInfo.put("url", url);
                    dsInfo.put("driver", driver);
                    dsInfo.put("version", version);

                    // 验证是否为预期数据库类型
                    String expectedProduct = dbType.replace("sqlserver", "sql server");
                    boolean isExpected = actualProduct.toLowerCase().contains(expectedProduct.toLowerCase());
                    dsInfo.put("isExpectedType", isExpected);

                    if (isExpected) {
                        dsInfo.put("status", "✅ 正确");
                        dsInfo.put("statusColor", "success");
                        log.info("✅ {} 数据源正确: {} ({})", dbType, actualProduct, url);
                    } else {
                        dsInfo.put("status", "⚠️ 不匹配");
                        dsInfo.put("statusColor", "warning");
                        log.warn("⚠️ {} 数据源不匹配！期望: {}, 实际: {}", dbType, expectedProduct, actualProduct);
                    }

                    // 测试连接是否可用
                    try (Statement stmt = conn.createStatement()) {
                        stmt.executeQuery("SELECT 1");
                        dsInfo.put("testQuery", "✅ 通过");
                    } catch (SQLException e) {
                        dsInfo.put("testQuery", "❌ 失败: " + e.getMessage());
                    }

                } catch (Exception e) {
                    dsInfo.put("status", "❌ 连接失败");
                    dsInfo.put("statusColor", "danger");
                    dsInfo.put("error", e.getMessage());
                    log.error("❌ {} 数据源连接失败: {}", dbType, e.getMessage());
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

                dataSources.add(dsInfo);
            }

            // 检查所有数据源是否都正确
            long correctCount = dataSources.stream()
                    .filter(ds -> ds.get("isExpectedType") == Boolean.TRUE)
                    .count();

            boolean allCorrect = correctCount == 3;

            result.put("dataSources", dataSources);
            result.put("dataSourceClass", dataSource.getClass().getName());
            result.put("success", true);
            result.put("message", allCorrect ?
                    "🎉 所有数据源配置正确！" :
                    "⚠️ 有 " + (3 - correctCount) + "/3 个数据源可能配置错误");
            result.put("allCorrect", allCorrect);
            result.put("correctCount", correctCount);
            result.put("timestamp", LocalDateTime.now());

        } catch (Exception e) {
            log.error("获取数据源信息失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("timestamp", LocalDateTime.now());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 前端可用的简单数据源测试（GET请求）
     */
    @GetMapping("/test-simple-ds")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> testSimpleDataSource() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> testResults = new ArrayList<>();

        String[] dbTypes = {"mysql", "postgresql", "sqlserver"};

        for (String dbType : dbTypes) {
            Map<String, Object> dbResult = new HashMap<>();
            dbResult.put("database", dbType);

            Connection conn = null;
            try {
                // 1. 清理上下文
                dataSourceContextHolder.clearDataSource();

                // 2. 切换数据源
                switch (dbType) {
                    case "mysql":
                        dataSourceContextHolder.setMySQLDataSource();
                        break;
                    case "postgresql":
                        dataSourceContextHolder.setPostgreSQLDataSource();
                        break;
                    case "sqlserver":
                        dataSourceContextHolder.setSQLServerDataSource();
                        break;
                }

                // 3. 获取连接
                conn = dataSource.getConnection();

                // 4. 获取连接信息
                String productName = conn.getMetaData().getDatabaseProductName();
                String url = conn.getMetaData().getURL();

                // 5. 验证是否是目标数据库
                if (!productName.toLowerCase().contains(dbType.replace("sqlserver", "sql server"))) {
                    throw new SQLException("数据库类型不匹配！期望 " + dbType + " 但实际是 " + productName);
                }

                // 6. 执行简单查询
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeQuery("SELECT 1");
                }

                dbResult.put("status", "✅ 成功");
                dbResult.put("product", productName);
                dbResult.put("url", url);
                dbResult.put("connectTime", LocalDateTime.now().toString());

                log.info("✅ {} 连接测试成功: {}", dbType, productName);

            } catch (Exception e) {
                dbResult.put("status", "❌ 失败");
                dbResult.put("error", e.getMessage());
                log.error("❌ {} 连接测试失败: {}", dbType, e.getMessage());
            } finally {
                // 清理资源
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (SQLException e) {
                        log.warn("关闭连接失败: {}", e.getMessage());
                    }
                }
                dataSourceContextHolder.clearDataSource();
            }

            testResults.add(dbResult);
        }

        result.put("testResults", testResults);
        result.put("success", true);
        result.put("timestamp", LocalDateTime.now());

        // 检查是否所有数据库都连接成功
        long successCount = testResults.stream()
                .filter(r -> "✅ 成功".equals(r.get("status")))
                .count();

        if (successCount == 3) {
            result.put("message", "🎉 所有三个数据库连接成功！");
            result.put("icon", "success");
        } else {
            result.put("message", "⚠️ 只有 " + successCount + "/3 个数据库连接成功");
            result.put("icon", "warning");
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 前端可用的直接数据库对比测试（GET请求）
     * 这个测试会直接查询三个数据库，返回各自的唯一数据
     */
    @GetMapping("/direct-db-comparison")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> directDBComparison() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> dbData = new HashMap<>();

        String testId = "TEST_" + System.currentTimeMillis();
        log.info("🔍 开始直接数据库对比测试，ID: {}", testId);

        // 为每个数据库插入唯一数据
        String[] dbTypes = {"mysql", "postgresql", "sqlserver"};

        for (String dbType : dbTypes) {
            Connection conn = null;
            try {
                // 清理并切换数据源
                dataSourceContextHolder.clearDataSource();

                // 切换数据源
                switch (dbType) {
                    case "mysql":
                        dataSourceContextHolder.setMySQLDataSource();
                        break;
                    case "postgresql":
                        dataSourceContextHolder.setPostgreSQLDataSource();
                        break;
                    case "sqlserver":
                        dataSourceContextHolder.setSQLServerDataSource();
                        break;
                }

                conn = dataSource.getConnection();
                conn.setAutoCommit(false);

                // 先清理可能存在的测试数据
                try (PreparedStatement deleteStmt = conn.prepareStatement(
                        "DELETE FROM books WHERE book_id LIKE 'TEST_%'")) {
                    deleteStmt.executeUpdate();
                }

                // 插入本数据库的唯一数据
                String uniqueTitle = dbType.toUpperCase() + "_唯一数据_" + System.currentTimeMillis();
                String uniqueAuthor = dbType.toUpperCase() + "_作者_" + Thread.currentThread().getId();

                String insertSql = "INSERT INTO books (book_id, title, author, isbn, publisher, " +
                        "library_id, last_modified_time, version, created_time) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    stmt.setString(1, testId);
                    stmt.setString(2, uniqueTitle);
                    stmt.setString(3, uniqueAuthor);
                    stmt.setString(4, "ISBN_" + dbType + "_" + System.currentTimeMillis());
                    stmt.setString(5, dbType + "_出版社");
                    stmt.setString(6, "TEST_LIB");
                    stmt.setTimestamp(7, new Timestamp(System.currentTimeMillis()));
                    stmt.setLong(8, 1L);
                    stmt.setTimestamp(9, new Timestamp(System.currentTimeMillis()));

                    stmt.executeUpdate();
                }

                conn.commit();

                // 查询刚插入的数据
                String querySql = "SELECT title, author, version FROM books WHERE book_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(querySql)) {
                    stmt.setString(1, testId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            Map<String, String> data = new HashMap<>();
                            data.put("title", rs.getString("title"));
                            data.put("author", rs.getString("author"));
                            data.put("version", String.valueOf(rs.getLong("version")));
                            data.put("database", dbType);
                            data.put("status", "✅ 插入并查询成功");

                            dbData.put(dbType, data);
                            log.info("✅ {} 插入唯一数据: {}", dbType, data.get("title"));
                        }
                    }
                }

            } catch (Exception e) {
                Map<String, String> error = new HashMap<>();
                error.put("status", "❌ 失败");
                error.put("error", e.getMessage());
                dbData.put(dbType, error);
                log.error("❌ {} 测试失败: {}", dbType, e.getMessage());
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

        // 清理测试数据
        cleanupTestDataHelper(testId);

        result.put("testId", testId);
        result.put("databases", dbData);
        result.put("success", true);

        // 检查数据是否真的不同
        boolean dataIsDifferent = true;
        Set<String> dataSignatures = new HashSet<>();

        for (Object data : dbData.values()) {
            if (data instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> dataMap = (Map<String, String>) data;
                if (dataMap.containsKey("title")) {
                    String signature = dataMap.get("title") + "|" + dataMap.get("author");
                    dataSignatures.add(signature);
                }
            }
        }

        dataIsDifferent = dataSignatures.size() > 1;
        result.put("dataIsDifferent", dataIsDifferent);

        if (dataIsDifferent) {
            result.put("message", "🎉 成功！三个数据库的数据都不同（真实多数据源）");
            result.put("icon", "success");
        } else {
            result.put("message", "⚠️ 警告：三个数据库的数据相同（可能都在同一数据库）");
            result.put("icon", "warning");
        }

        result.put("timestamp", LocalDateTime.now());
        result.put("dataSignatures", dataSignatures);
        result.put("signatureCount", dataSignatures.size());

        return ResponseEntity.ok(result);
    }

    /**
     * 清理测试数据的辅助方法
     */
    private void cleanupTestDataHelper(String bookId) {
        String[] dbTypes = {"mysql", "postgresql", "sqlserver"};

        for (String dbType : dbTypes) {
            Connection conn = null;
            try {
                dataSourceContextHolder.clearDataSource();

                // 切换数据源
                switch (dbType) {
                    case "mysql":
                        dataSourceContextHolder.setMySQLDataSource();
                        break;
                    case "postgresql":
                        dataSourceContextHolder.setPostgreSQLDataSource();
                        break;
                    case "sqlserver":
                        dataSourceContextHolder.setSQLServerDataSource();
                        break;
                }

                conn = dataSource.getConnection();

                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM books WHERE book_id = ?")) {
                    stmt.setString(1, bookId);
                    int rows = stmt.executeUpdate();
                    if (rows > 0) {
                        log.debug("🧹 清理 {} 测试数据: {} ({}行)", dbType, bookId, rows);
                    }
                }

            } catch (Exception e) {
                log.warn("清理 {} 测试数据失败: {}", dbType, e.getMessage());
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
    }

    /**
     * 强制制造跨库冲突
     */
    @PostMapping("/create-force-conflict")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createForceConflict() {
        String testBookId = "FF" + System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();

        Connection mysqlConn = null;
        Connection pgConn = null;
        Connection ssConn = null;

        try {
            log.info("🚨【前端按钮】开始强制制造冲突，图书ID: {}", testBookId);
            result.put("testId", testBookId);
            result.put("startTime", LocalDateTime.now().toString());

            /* ========== 关键修复1：进入安全模式 ========== */
            log.info("🛡️ 进入安全测试模式...");
            dataSourceContextHolder.enterTestMode();

            /* ========== 关键修复2：彻底清理所有数据 ========== */
            log.info("🧹 清理旧数据...");
            cleanupWithNativeConnections(testBookId);
            result.put("cleanup", "completed");

            /* ========== 关键修复3：使用原生连接 ========== */
            log.info("📝 插入冲突数据到三个数据库...");

            // MySQL 插入
            dataSourceContextHolder.setMySQLDataSource();
            mysqlConn = dataSource.getConnection();
            mysqlConn.setAutoCommit(false);
            insertWithRawSQL(mysqlConn, testBookId,
                    "MySQL-专用数据-" + System.currentTimeMillis(),
                    "MySQL作者", 1L);
            mysqlConn.commit();
            log.info("✅ MySQL 插入完成");
            result.put("mysql", "插入成功");

            // PostgreSQL 插入（相同ID，不同内容，相同版本）
            dataSourceContextHolder.setPostgreSQLDataSource();
            pgConn = dataSource.getConnection();
            pgConn.setAutoCommit(false);
            insertWithRawSQL(pgConn, testBookId,
                    "PostgreSQL-专用数据-" + (System.currentTimeMillis() + 1),
                    "PG作者", 1L);
            pgConn.commit();
            log.info("✅ PostgreSQL 插入完成");
            result.put("postgresql", "插入成功");

            // SQL Server 插入（相同ID，不同内容，相同版本）
            dataSourceContextHolder.setSQLServerDataSource();
            ssConn = dataSource.getConnection();
            ssConn.setAutoCommit(false);
            insertWithRawSQL(ssConn, testBookId,
                    "SQLServer-专用数据-" + (System.currentTimeMillis() + 2),
                    "SS作者", 1L);
            ssConn.commit();
            log.info("✅ SQL Server 插入完成");
            result.put("sqlserver", "插入成功");

            /* ========== 关键修复4：等待并验证数据差异 ========== */
            log.info("⏳ 等待3秒，确保没有后台同步干扰...");
            Thread.sleep(3000);

            // 直接查询三个数据库的原始数据
            Map<String, String> rawData = queryRawDataFromAllDBs(testBookId);
            result.put("rawData", rawData);

            log.info("📊 原始数据检查结果:");
            for (Map.Entry<String, String> entry : rawData.entrySet()) {
                String data = entry.getValue();
                log.info("  {}: {}", entry.getKey(),
                        data.length() > 50 ? data.substring(0, 50) + "..." : data);
            }

            // 检查是否有真实冲突
            boolean realConflict = hasRealConflict(rawData);
            result.put("hasRealConflict", realConflict);

            if (realConflict) {
                log.warn("🚨 成功制造真实冲突！");
                result.put("conflictStatus", "REAL_CONFLICT_CREATED");
            } else {
                log.warn("⚠️ 数据相同，可能被同步了");
                result.put("conflictStatus", "DATA_IDENTICAL");
            }

            /* ========== 关键修复5：恢复环境并触发检测 ========== */
            log.info("🔄 恢复环境，触发冲突检测...");
            dataSourceContextHolder.exitTestMode();

            // 触发冲突检测
            Map<String, Object> detectResult = multiDatabaseService.detectAllConflicts();
            int conflictCount = (int) detectResult.getOrDefault("conflictCount", 0);
            result.put("detectedConflicts", conflictCount);
            result.put("detectResult", detectResult);

            // 获取冲突记录
            List<Map<String, Object>> conflicts = (List<Map<String, Object>>)
                    detectResult.getOrDefault("conflicts", new ArrayList<>());
            result.put("conflictRecords", conflicts);
            result.put("recordCount", conflicts.size());

            /* ========== 返回给前端的友好信息 ========== */
            if (conflictCount > 0) {
                result.put("success", true);
                result.put("message", "🚨 成功制造并检测到 " + conflictCount + " 个冲突！");
                result.put("icon", "success");
                result.put("alertType", "success");
            } else if (realConflict) {
                result.put("success", false);
                result.put("message", "⚠️ 制造了冲突但系统未检测到");
                result.put("icon", "warning");
                result.put("alertType", "warning");
            } else {
                result.put("success", false);
                result.put("message", "❌ 未能制造出冲突（数据被同步）");
                result.put("icon", "error");
                result.put("alertType", "error");
            }

            result.put("endTime", LocalDateTime.now().toString());
            result.put("duration", System.currentTimeMillis() - Long.parseLong(testBookId.substring(2)));

            log.info("🎯 冲突制造完成！结果: {}", result.get("message"));

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("❌ 制造冲突失败", e);

            // 回滚未提交的事务
            rollbackConnection(mysqlConn);
            rollbackConnection(pgConn);
            rollbackConnection(ssConn);

            // 确保清理资源
            try {
                dataSourceContextHolder.exitTestMode();
            } catch (Exception ex) {
                log.error("清理资源失败", ex);
            }

            result.put("success", false);
            result.put("message", "❌ 制造冲突失败: " + e.getMessage());
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            result.put("icon", "error");
            result.put("alertType", "error");

            return ResponseEntity.status(500).body(result);
        } finally {
            // 关闭连接
            closeConnection(mysqlConn);
            closeConnection(pgConn);
            closeConnection(ssConn);
        }
    }

    /* ==================== 私有工具方法 ==================== */

    /**
     * 获取原生数据库连接
     */
    private Connection getNativeConnection(String dbType) throws SQLException {
        log.info("🔄 获取 {} 原生连接...", dbType);

        try {
            // 清理上下文
            dataSourceContextHolder.clearDataSource();

            // 切换到目标数据源
            switch (dbType) {
                case "mysql":
                    dataSourceContextHolder.setMySQLDataSource();
                    break;
                case "postgresql":
                    dataSourceContextHolder.setPostgreSQLDataSource();
                    break;
                case "sqlserver":
                    dataSourceContextHolder.setSQLServerDataSource();
                    break;
            }

            // 获取连接
            Connection conn = dataSource.getConnection();

            // 验证连接的真实数据库类型
            String productName = conn.getMetaData().getDatabaseProductName();
            String url = conn.getMetaData().getURL();

            log.info("✅ 获取到 {} 连接 - 产品: {}, URL: {}", dbType, productName, url);

            // 验证这是否真的是我们想要的数据源
            String expectedProduct = dbType.replace("sqlserver", "sql server");
            if (!productName.toLowerCase().contains(expectedProduct.toLowerCase())) {
                log.error("❌ 数据源类型不匹配！期望: {}, 实际: {}", dbType, productName);
                throw new SQLException("数据源类型不匹配: 期望 " + dbType + " 但实际是 " + productName);
            }

            return conn;

        } finally {
            // 立即清除上下文，避免污染后续操作
            dataSourceContextHolder.clearDataSource();
        }
    }

    /**
     * 使用原生SQL插入数据
     */
    private void insertWithRawSQL(Connection conn, String bookId,
                                  String title, String author, Long version)
            throws SQLException {

        String dbProduct = conn.getMetaData().getDatabaseProductName();
        log.info("🔍 连接信息 - 数据库产品: {}, URL: {}",
                dbProduct, conn.getMetaData().getURL());

        // 1. 检查表是否存在，不存在则创建
        ensureTableExists(conn);

        // 2. 强制删除旧数据
        String deleteSql = "DELETE FROM books WHERE book_id = ?";
        try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
            deleteStmt.setString(1, bookId);
            int deleted = deleteStmt.executeUpdate();
            if (deleted > 0) {
                log.info("🧹 强制删除旧数据: {} ({}行)", bookId, deleted);
            }
        }

        // 3. 等待避免同步
        try {
            Thread.sleep(300);
            log.debug("⏳ 等待300ms避免同步");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 4. 插入数据
        String uniqueIsbn = generateValidISBN(dbProduct);
        String uniquePublisher = "测试-" + dbProduct;

        String insertSql = "INSERT INTO books (book_id, title, author, isbn, publisher, " +
                "library_id, last_modified_time, version, created_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            stmt.setString(1, bookId);
            stmt.setString(2, title);
            stmt.setString(3, author);
            stmt.setString(4, uniqueIsbn);
            stmt.setString(5, uniquePublisher);
            stmt.setString(6, "TEST");
            stmt.setTimestamp(7, new Timestamp(System.currentTimeMillis()));
            stmt.setLong(8, version);
            stmt.setTimestamp(9, new Timestamp(System.currentTimeMillis()));

            int rows = stmt.executeUpdate();
            log.info("✅ 插入唯一数据到 {}: {} -> {} (ISBN: {})",
                    dbProduct, bookId, title, uniqueIsbn);
        }
    }

    private void ensureTableExists(Connection conn) throws SQLException {
        String dbProduct = conn.getMetaData().getDatabaseProductName().toLowerCase();

        try {
            // 检查表是否存在
            String checkTableSql = "";
            if (dbProduct.contains("mysql")) {
                checkTableSql = "SHOW TABLES LIKE 'books'";
            } else if (dbProduct.contains("postgresql")) {
                checkTableSql = "SELECT to_regclass('public.books')";
            } else if (dbProduct.contains("sql server")) {
                checkTableSql = "SELECT OBJECT_ID('books', 'U')";
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.executeQuery(checkTableSql);
                log.info("✅ books表已存在");
                return;
            } catch (SQLException e) {
                log.info("📝 books表不存在，准备创建...");
            }

            // 创建表
            String createTableSql = "";
            if (dbProduct.contains("mysql")) {
                createTableSql = "CREATE TABLE books (" +
                        "book_id VARCHAR(20) PRIMARY KEY," +
                        "title VARCHAR(100) NOT NULL," +
                        "author VARCHAR(50)," +
                        "isbn VARCHAR(13)," +
                        "publisher VARCHAR(50)," +
                        "library_id VARCHAR(10)," +
                        "created_time DATETIME," +
                        "last_modified_time DATETIME," +
                        "version BIGINT" +
                        ")";
            } else if (dbProduct.contains("postgresql")) {
                createTableSql = "CREATE TABLE books (" +
                        "book_id VARCHAR(20) PRIMARY KEY," +
                        "title VARCHAR(100) NOT NULL," +
                        "author VARCHAR(50)," +
                        "isbn VARCHAR(13)," +
                        "publisher VARCHAR(50)," +
                        "library_id VARCHAR(10)," +
                        "created_time TIMESTAMP," +
                        "last_modified_time TIMESTAMP," +
                        "version BIGINT" +
                        ")";
            } else if (dbProduct.contains("sql server")) {
                createTableSql = "CREATE TABLE books (" +
                        "book_id VARCHAR(20) PRIMARY KEY," +
                        "title VARCHAR(100) NOT NULL," +
                        "author VARCHAR(50)," +
                        "isbn VARCHAR(13)," +
                        "publisher VARCHAR(50)," +
                        "library_id VARCHAR(10)," +
                        "created_time DATETIME," +
                        "last_modified_time DATETIME," +
                        "version BIGINT" +
                        ")";
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createTableSql);
                log.info("✅ 成功创建books表");
            }

        } catch (SQLException e) {
            log.warn("⚠️ 创建表失败: {}", e.getMessage());
        }
    }

    // 生成合规的ISBN（13位以内）
    private String generateValidISBN(String dbType) {
        // ISBN格式: 978 + 随机9位 + 校验位
        // 我们简化为: TEST + 时间戳后9位
        String timestamp = String.valueOf(System.currentTimeMillis());
        String suffix = timestamp.substring(timestamp.length() - 9);

        // 根据数据库类型生成不同的ISBN前缀
        String prefix;
        switch (dbType.toLowerCase()) {
            case "mysql": prefix = "MYS"; break;
            case "postgresql": prefix = "PGS"; break;
            case "sqlserver": prefix = "SQL"; break;
            default: prefix = "TST";
        }

        // 总长度控制在13位以内
        return prefix + suffix;
    }

    /**
     * 查询所有数据库的原始数据
     */
    private Map<String, String> queryRawDataFromAllDBs(String bookId) throws SQLException {
        Map<String, String> result = new HashMap<>();
        String[] dbs = {"mysql", "postgresql", "sqlserver"};

        for (String db : dbs) {
            try (Connection conn = getNativeConnection(db)) {
                String sql = "SELECT title, author, version FROM books WHERE book_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, bookId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            String title = rs.getString("title");
                            String author = rs.getString("author");
                            long version = rs.getLong("version");
                            result.put(db, String.format("标题:%s, 作者:%s, 版本:%d",
                                    title, author, version));
                        } else {
                            result.put(db, "NOT_FOUND");
                        }
                    }
                }
            } catch (SQLException e) {
                result.put(db, "ERROR: " + e.getMessage());
                log.error("查询 {} 失败", db, e);
            }
        }

        return result;
    }

    /**
     * 检查是否有真实冲突
     */
    private boolean hasRealConflict(Map<String, String> data) {
        // 收集所有有效数据
        List<String> validData = new ArrayList<>();
        for (String value : data.values()) {
            if (!value.startsWith("NOT_FOUND") && !value.startsWith("ERROR")) {
                validData.add(value);
            }
        }

        // 至少需要两个数据库有数据
        if (validData.size() < 2) {
            return false;
        }

        // 检查是否所有数据都相同
        String first = validData.get(0);
        for (int i = 1; i < validData.size(); i++) {
            if (!validData.get(i).equals(first)) {
                return true; // 有不同，存在冲突
            }
        }

        return false; // 所有数据相同，没有冲突
    }

    /**
     * 原生连接清理数据
     */
    private void cleanupWithNativeConnections(String bookId) {
        String[] dbs = {"mysql", "postgresql", "sqlserver"};

        for (String db : dbs) {
            Connection conn = null;
            try {
                conn = getNativeConnection(db);
                conn.setAutoCommit(false);

                // 1. 清理books表
                String sql = "DELETE FROM books WHERE book_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, bookId);
                    int rows = stmt.executeUpdate();
                    if (rows > 0) {
                        log.debug("🧹 清理 {} books: 删除 {} 行", db, rows);
                    }
                }

                // 2. 清理sync_logs表（注意表名！）
                try {
                    sql = "DELETE FROM sync_logs WHERE data_id LIKE ?";
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, bookId + "%");
                        int rows = stmt.executeUpdate();
                        if (rows > 0) {
                            log.debug("🧹 清理 {} sync_logs: 删除 {} 行", db, rows);
                        }
                    }
                } catch (SQLException e) {
                    // 表可能不存在，检查错误信息
                    String errorMsg = e.getMessage().toLowerCase();
                    if (errorMsg.contains("doesn't exist") ||
                            errorMsg.contains("does not exist") ||
                            errorMsg.contains("sync_logs")) {
                        log.debug("⚠️ sync_logs表在 {} 中不存在，等待JPA自动创建", db);
                    } else {
                        log.warn("⚠️ 清理 {} 的sync_logs表失败: {}", db, e.getMessage());
                    }
                }

                conn.commit();

            } catch (Exception e) {
                log.warn("清理 {} 失败: {}", db, e.getMessage());
                if (conn != null) {
                    try {
                        conn.rollback();
                    } catch (SQLException rollbackEx) {
                        log.error("回滚失败: {}", rollbackEx.getMessage());
                    }
                }
            } finally {
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (SQLException e) {
                        log.warn("关闭连接失败: {}", e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 关闭连接
     */
    private void closeConnection(Connection conn) {
        if (conn != null) {
            try {
                if (!conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException e) {
                log.warn("关闭连接失败", e);
            }
        }
    }

    /**
     * 回滚连接
     */
    private void rollbackConnection(Connection conn) {
        if (conn != null) {
            try {
                if (!conn.isClosed() && !conn.getAutoCommit()) {
                    conn.rollback();
                }
            } catch (SQLException e) {
                log.warn("回滚连接失败", e);
            }
        }
    }
}