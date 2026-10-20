package com.library.service;

import com.library.entity.DataConflict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MultiDatabaseService {

    // 注入三个独立数据源
    private final DataSource mysqlDataSource;
    private final DataSource postgresqlDataSource;
    private final DataSource sqlserverDataSource;

    // 对应的JdbcTemplate
    private JdbcTemplate mysqlJdbc;
    private JdbcTemplate postgresqlJdbc;
    private JdbcTemplate sqlserverJdbc;

    public MultiDatabaseService(
            @Qualifier("mysqlDataSource") DataSource mysqlDataSource,
            @Qualifier("postgresqlDataSource") DataSource postgresqlDataSource,
            @Qualifier("sqlserverDataSource") DataSource sqlserverDataSource) {
        this.mysqlDataSource = mysqlDataSource;
        this.postgresqlDataSource = postgresqlDataSource;
        this.sqlserverDataSource = sqlserverDataSource;
    }

    @PostConstruct
    public void init() {
        this.mysqlJdbc = new JdbcTemplate(mysqlDataSource);
        this.postgresqlJdbc = new JdbcTemplate(postgresqlDataSource);
        this.sqlserverJdbc = new JdbcTemplate(sqlserverDataSource);
        log.info("✅ MultiDatabaseService 初始化完成，已连接三个独立数据库");
    }

    @Autowired
    private EmailService emailService;

    /**
     * 获取指定数据库的JdbcTemplate
     */
    public JdbcTemplate getJdbcForDb(String dbKey) {
        switch (dbKey.toLowerCase()) {
            case "mysql": return mysqlJdbc;
            case "postgresql": return postgresqlJdbc;
            case "sqlserver": return sqlserverJdbc;
            default: throw new IllegalArgumentException("未知数据库: " + dbKey);
        }
    }

    /**
     * 在三个数据库中查询图书是否存在（统一返回值格式）
     */
    public Map<String, Object> checkBookInAllDatabases(String bookId) {
        Map<String, Object> finalResult = new HashMap<>();
        Map<String, Map<String, Object>> dbResults = new HashMap<>();

        String[][] dbs = {{"mysql", "MySQL"}, {"postgresql", "PostgreSQL"}, {"sqlserver", "SQL Server"}};

        boolean existsInAny = false;
        List<String> existingIn = new ArrayList<>();

        for (String[] db : dbs) {
            String key = db[0];
            String name = db[1];
            Map<String, Object> dbResult = new HashMap<>();

            try {
                String sql = "SELECT book_id, title, author, version FROM books WHERE book_id = ?";
                List<Map<String, Object>> rows = getJdbcForDb(key).queryForList(sql, bookId);

                if (!rows.isEmpty()) {
                    Map<String, Object> row = rows.get(0);
                    dbResult.put("exists", true);
                    dbResult.put("data", row);
                    existsInAny = true;
                    existingIn.add(name);
                    log.debug("✅ {} 中存在图书 {}: {}", name, bookId, row.get("title"));
                } else {
                    dbResult.put("exists", false);
                    log.debug("ℹ️ {} 中不存在图书 {}", name, bookId);
                }
                dbResult.put("success", true);

            } catch (Exception e) {
                log.error("❌ 查询 {} 失败: {}", name, e.getMessage());
                dbResult.put("exists", false);
                dbResult.put("success", false);
                dbResult.put("error", e.getMessage());
            }

            dbResults.put(key, dbResult);
        }

        // 构造统一的返回格式
        finalResult.put("success", true);
        finalResult.put("bookId", bookId);
        finalResult.put("timestamp", new Date());
        finalResult.put("existsInAnyDatabase", existsInAny);
        finalResult.put("existingIn", existingIn);
        finalResult.put("databaseDetails", dbResults); // 保留详细数据

        return finalResult;
    }

    /**
     * 从三个数据库中删除图书（核心修复方法）
     */
    public Map<String, Object> deleteBookFromAllDatabases(String bookId) {
        Map<String, Object> finalResult = new HashMap<>();
        String[][] dbs = {{"mysql", "MySQL"}, {"postgresql", "PostgreSQL"}, {"sqlserver", "SQL Server"}};

        List<String> deletedFrom = new ArrayList<>();
        List<String> notFoundIn = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Object> details = new HashMap<>();

        for (String[] db : dbs) {
            String key = db[0];
            String name = db[1];
            Map<String, Object> dbResult = new HashMap<>();

            try {
                JdbcTemplate jdbc = getJdbcForDb(key);

                // 1. 先检查
                String checkSql = "SELECT COUNT(*) FROM books WHERE book_id = ?";
                Integer count = jdbc.queryForObject(checkSql, Integer.class, bookId);

                if (count != null && count > 0) {
                    // 2. 执行删除
                    String deleteSql = "DELETE FROM books WHERE book_id = ?";
                    int affected = jdbc.update(deleteSql, bookId);

                    dbResult.put("action", "DELETED");
                    dbResult.put("rowsAffected", affected);
                    deletedFrom.add(name);
                    log.info("✅ 已从 {} 删除图书 {} ({}行)", name, bookId, affected);
                } else {
                    dbResult.put("action", "NOT_FOUND");
                    dbResult.put("rowsAffected", 0);
                    notFoundIn.add(name);
                    log.debug("ℹ️ {} 中无图书 {}", name, bookId);
                }
                dbResult.put("success", true);

            } catch (Exception e) {
                log.error("❌ 在 {} 删除图书 {} 失败: {}", name, bookId, e.getMessage());
                dbResult.put("action", "ERROR");
                dbResult.put("success", false);
                dbResult.put("error", e.getMessage());
                errors.add(name + ": " + e.getMessage());
            }

            details.put(key, dbResult);
        }

        // 汇总结果
        finalResult.put("bookId", bookId);
        finalResult.put("details", details);
        finalResult.put("deletedFrom", deletedFrom);
        finalResult.put("notFoundIn", notFoundIn);
        finalResult.put("errors", errors);

        if (!errors.isEmpty()) {
            finalResult.put("success", false);
            finalResult.put("message", "删除完成但有错误");
        } else if (!deletedFrom.isEmpty()) {
            finalResult.put("success", true);
            finalResult.put("message", String.format("成功从 %s 删除", String.join(", ", deletedFrom)));
        } else {
            finalResult.put("success", false);
            finalResult.put("message", "图书在三个数据库中均不存在");
        }

        return finalResult;
    }

    /**
     * 向所有数据库插入或更新图书（修复添加/同步功能的核心）
     */
    public Map<String, Object> insertOrUpdateBookToAllDatabases(String bookId, Map<String, Object> bookData) {
        Map<String, Object> finalResult = new HashMap<>();

        // 准备插入/更新的数据
        String title = (String) bookData.getOrDefault("title", "");
        String author = (String) bookData.getOrDefault("author", "");
        String isbn = (String) bookData.getOrDefault("isbn", "");
        String publisher = (String) bookData.getOrDefault("publisher", "");
        String libraryId = (String) bookData.getOrDefault("libraryId", "default");

        String[][] dbs = {{"mysql", "MySQL"}, {"postgresql", "PostgreSQL"}, {"sqlserver", "SQL Server"}};

        Map<String, Object> details = new HashMap<>();
        List<String> successDbs = new ArrayList<>();
        List<String> failedDbs = new ArrayList<>();

        for (String[] db : dbs) {
            String key = db[0];
            String name = db[1];
            Map<String, Object> dbResult = new HashMap<>();

            try {
                JdbcTemplate jdbc = getJdbcForDb(key);

                // 检查是否已存在
                String checkSql = "SELECT COUNT(*) FROM books WHERE book_id = ?";
                Integer count = jdbc.queryForObject(checkSql, Integer.class, bookId);

                String action;
                int rowsAffected;

                if (count != null && count > 0) {
                    // 更新现有记录 - 使用数据库特定的UPDATE语句
                    String updateSql = "UPDATE books SET title = ?, author = ?, isbn = ?, publisher = ?, " +
                            "library_id = ?, last_modified_time = CURRENT_TIMESTAMP " +
                            "WHERE book_id = ?";
                    rowsAffected = jdbc.update(updateSql, title, author, isbn, publisher, libraryId, bookId);
                    action = "UPDATED";
                    log.info("✅ 在 {} 更新图书 {} ({}行)", name, bookId, rowsAffected);
                } else {
                    // 插入新记录 - 使用数据库特定的INSERT语句
                    String insertSql = "INSERT INTO books (book_id, title, author, isbn, publisher, " +
                            "library_id, created_time, last_modified_time) " +
                            "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
                    rowsAffected = jdbc.update(insertSql, bookId, title, author, isbn, publisher, libraryId);
                    action = "INSERTED";
                    log.info("✅ 在 {} 插入图书 {} ({}行)", name, bookId, rowsAffected);
                }

                dbResult.put("action", action);
                dbResult.put("rowsAffected", rowsAffected);
                dbResult.put("success", true);
                successDbs.add(name);

            } catch (Exception e) {
                log.error("❌ 在 {} 处理图书 {} 失败: {}", name, bookId, e.getMessage());
                dbResult.put("action", "FAILED");
                dbResult.put("success", false);
                dbResult.put("error", e.getMessage());
                failedDbs.add(name + ": " + e.getMessage());
            }

            details.put(key, dbResult);
        }

        // 构造结果
        finalResult.put("bookId", bookId);
        finalResult.put("title", title);
        finalResult.put("details", details);
        finalResult.put("successDbs", successDbs);
        finalResult.put("failedDbs", failedDbs);
        finalResult.put("success", failedDbs.isEmpty());
        finalResult.put("message", failedDbs.isEmpty() ?
                String.format("成功同步到 %s", String.join(", ", successDbs)) :
                String.format("部分成功。成功: %s; 失败: %s",
                        String.join(", ", successDbs),
                        String.join("; ", failedDbs)));

        return finalResult;
    }

    /**
     * 从所有数据库获取图书列表（用于页面显示）
     */
    public Map<String, Object> getAllBooksFromAllDatabases() {
        Map<String, Object> finalResult = new HashMap<>();

        String[][] dbs = {{"mysql", "MySQL"}, {"postgresql", "PostgreSQL"}, {"sqlserver", "SQL Server"}};

        Map<String, List<Map<String, Object>>> allBooks = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();

        for (String[] db : dbs) {
            String key = db[0];
            String name = db[1];

            try {
                String sql = "SELECT book_id, title, author, isbn, publisher, library_id, " +
                        "created_time, last_modified_time, version FROM books ORDER BY book_id";
                List<Map<String, Object>> books = getJdbcForDb(key).queryForList(sql);

                allBooks.put(key, books);
                counts.put(key, books.size());
                log.info("📚 从 {} 获取到 {} 本图书", name, books.size());

            } catch (Exception e) {
                log.error("❌ 从 {} 获取图书列表失败: {}", name, e.getMessage());
                allBooks.put(key, new ArrayList<>());
                counts.put(key, 0);
            }
        }

        // 合并所有图书ID（用于前端对比）
        Set<String> allBookIds = new HashSet<>();
        for (List<Map<String, Object>> books : allBooks.values()) {
            for (Map<String, Object> book : books) {
                allBookIds.add((String) book.get("book_id"));
            }
        }

        finalResult.put("success", true);
        finalResult.put("allBookIds", new ArrayList<>(allBookIds));
        finalResult.put("booksByDatabase", allBooks);
        finalResult.put("counts", counts);
        finalResult.put("totalUniqueBooks", allBookIds.size());
        finalResult.put("timestamp", new Date());

        return finalResult;
    }

    /**
     * 智能同步：使用最新修改时间的数据同步所有数据库
     */
    public Map<String, Object> smartSyncBookToAllDatabases(String bookId) {
        Map<String, Object> finalResult = new HashMap<>();

        String[][] dbs = {{"mysql", "MySQL"}, {"postgresql", "PostgreSQL"}, {"sqlserver", "SQL Server"}};

        // 1. 从所有数据库获取当前数据（包括修改时间）
        Map<String, Map<String, Object>> bookVersions = new HashMap<>();
        String latestDb = null;
        Map<String, Object> latestBookData = null;
        Date latestTime = null;

        for (String[] db : dbs) {
            String key = db[0];
            String name = db[1];

            try {
                String sql = "SELECT book_id, title, author, isbn, publisher, library_id, " +
                        "last_modified_time FROM books WHERE book_id = ?";
                List<Map<String, Object>> rows = getJdbcForDb(key).queryForList(sql, bookId);

                if (!rows.isEmpty()) {
                    Map<String, Object> bookData = rows.get(0);
                    bookVersions.put(key, bookData);

                    // 解析时间戳
                    Object timestampObj = bookData.get("last_modified_time");
                    Date bookTime = null;

                    if (timestampObj instanceof java.sql.Timestamp) {
                        bookTime = new Date(((java.sql.Timestamp) timestampObj).getTime());
                    } else if (timestampObj instanceof java.util.Date) {
                        bookTime = (Date) timestampObj;
                    }

                    // 找出最新的
                    if (bookTime != null && (latestTime == null || bookTime.after(latestTime))) {
                        latestTime = bookTime;
                        latestDb = key;
                        latestBookData = bookData;
                    }

                    log.info("📅 {} 的数据时间: {}", name, bookTime);
                }
            } catch (Exception e) {
                log.error("❌ 从 {} 获取图书 {} 失败: {}", name, bookId, e.getMessage());
            }
        }

        // 2. 如果没有找到数据或只有一个版本
        if (latestBookData == null) {
            finalResult.put("success", false);
            finalResult.put("message", "在所有数据库中均未找到此图书");
            return finalResult;
        }

        if (bookVersions.size() == 1) {
            finalResult.put("success", true);
            finalResult.put("message", "此图书只存在于一个数据库，无需同步");
            finalResult.put("source", latestDb);
            return finalResult;
        }

        log.info("🎯 选择 {} 的数据作为最新源 (时间: {})", latestDb, latestTime);

        // 3. 使用最新数据同步到其他数据库
        Map<String, Object> syncResult = insertOrUpdateBookToAllDatabases(bookId, latestBookData);

        // 4. 合并结果
        finalResult.putAll(syncResult);
        finalResult.put("sourceDatabase", latestDb);
        finalResult.put("sourceTime", latestTime);
        finalResult.put("smartSync", true);

        return finalResult;
    }

    /**
     * 从指定数据库同步到其他数据库
     */
    public Map<String, Object> syncFromDatabase(String sourceDb, String bookId) {
        Map<String, Object> finalResult = new HashMap<>();

        // 1. 从源数据库获取数据
        Map<String, Object> sourceData;
        try {
            String sql = "SELECT book_id, title, author, isbn, publisher, library_id " +
                    "FROM books WHERE book_id = ?";
            List<Map<String, Object>> rows = getJdbcForDb(sourceDb).queryForList(sql, bookId);

            if (rows.isEmpty()) {
                finalResult.put("success", false);
                finalResult.put("message", "图书不存在于源数据库 " + sourceDb + " 中");
                return finalResult;
            }

            sourceData = rows.get(0);
        } catch (Exception e) {
            finalResult.put("success", false);
            finalResult.put("message", "从源数据库获取数据失败: " + e.getMessage());
            return finalResult;
        }

        // 2. 同步到所有数据库（包括源数据库自身，会执行UPDATE）
        Map<String, Object> syncResult = insertOrUpdateBookToAllDatabases(bookId, sourceData);

        // 3. 标记源数据库
        syncResult.put("sourceDatabase", sourceDb);
        return syncResult;
    }

    /**
     * 检测所有图书的三库一致性冲突
     */
    public Map<String, Object> detectAllConflicts() {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("🔄 开始三库一致性冲突检测...");

            Map<String, Object> allBooksData = getAllBooksFromAllDatabases();
            @SuppressWarnings("unchecked")
            List<String> allBookIds = (List<String>) allBooksData.get("allBookIds");
            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> booksByDb =
                    (Map<String, List<Map<String, Object>>>) allBooksData.get("booksByDatabase");

            List<Map<String, Object>> conflicts = new ArrayList<>();

            for (String bookId : allBookIds) {
                // 获取三个库的数据
                Map<String, Object> mysqlBook = findBookInList(booksByDb.get("mysql"), bookId);
                Map<String, Object> pgBook = findBookInList(booksByDb.get("postgresql"), bookId);
                Map<String, Object> ssBook = findBookInList(booksByDb.get("sqlserver"), bookId);

                // 计算存在该图书的数据库数量
                int existCount = 0;
                if (mysqlBook != null) existCount++;
                if (pgBook != null) existCount++;
                if (ssBook != null) existCount++;

                // 只有至少两个库有数据时才检查冲突
                if (existCount >= 2) {
                    boolean hasConflict = false;
                    List<String> conflictDbs = new ArrayList<>();

                    // 三库一致性检查：任意两个库数据不同即为冲突
                    if (mysqlBook != null && pgBook != null) {
                        if (!isBooksEqual(mysqlBook, pgBook)) {
                            hasConflict = true;
                            conflictDbs.add("MySQL-PostgreSQL");
                        }
                    }
                    if (mysqlBook != null && ssBook != null) {
                        if (!isBooksEqual(mysqlBook, ssBook)) {
                            hasConflict = true;
                            conflictDbs.add("MySQL-SQLServer");
                        }
                    }
                    if (pgBook != null && ssBook != null) {
                        if (!isBooksEqual(pgBook, ssBook)) {
                            hasConflict = true;
                            conflictDbs.add("PostgreSQL-SQLServer");
                        }
                    }

                    if (hasConflict) {
                        Map<String, Object> conflict = new HashMap<>();
                        conflict.put("bookId", bookId);
                        conflict.put("conflictDbs", conflictDbs);
                        conflict.put("existsIn", List.of(
                                mysqlBook != null ? "MySQL" : null,
                                pgBook != null ? "PostgreSQL" : null,
                                ssBook != null ? "SQLServer" : null
                        ).stream().filter(Objects::nonNull).collect(Collectors.toList()));
                        conflict.put("mysql", mysqlBook);
                        conflict.put("postgresql", pgBook);
                        conflict.put("sqlserver", ssBook);
                        conflict.put("isThreeDbConflict", existCount == 3);

                        conflicts.add(conflict);
                        log.warn("🚨 三库冲突: {} - 涉及: {}", bookId, String.join(", ", conflictDbs));
                    }
                }
            }

            // 保存冲突记录时，为三库冲突创建一条记录
            saveThreeDbConflicts(conflicts);

            result.put("success", true);
            result.put("conflictCount", conflicts.size());
            result.put("conflicts", conflicts);
            result.put("threeDbConflicts", conflicts.stream()
                    .filter(c -> Boolean.TRUE.equals(c.get("isThreeDbConflict")))
                    .count());

            // === 添加邮件通知代码（这里 conflicts 变量可以访问）===
            if (!conflicts.isEmpty() && emailService != null) {
                try {
                    com.library.entity.DataConflict alertConflict = new com.library.entity.DataConflict();
                    alertConflict.setLogId("BATCH_" + System.currentTimeMillis());
                    alertConflict.setTableName("books");
                    alertConflict.setDataId("MULTIPLE");
                    alertConflict.setConflictDetail("批量检测发现 " + conflicts.size() + " 个三库数据冲突，请及时处理！");
                    alertConflict.setSyncTime(java.time.LocalDateTime.now());
                    alertConflict.setSourceDb(com.library.entity.DataConflict.DBType.ALL);
                    alertConflict.setTargetDb(com.library.entity.DataConflict.DBType.ALL);
                    alertConflict.setOperation(com.library.entity.DataConflict.OperationType.SYNC);
                    alertConflict.setSyncStatus(com.library.entity.DataConflict.SyncStatus.CONFLICT);

                    emailService.sendConflictNotification(alertConflict);
                    log.info("📧 批量冲突通知邮件已发送");
                } catch (Exception e) {
                    log.error("发送批量冲突邮件通知失败", e);
                }
            }

        } catch (Exception e) {
            log.error("❌ 三库冲突检测失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 比较两本书数据是否一致
     */
    private boolean isBooksEqual(Map<String, Object> book1, Map<String, Object> book2) {
        return Objects.equals(book1.get("title"), book2.get("title")) &&
                Objects.equals(book1.get("author"), book2.get("author")) &&
                Objects.equals(book1.get("isbn"), book2.get("isbn")) &&
                Objects.equals(book1.get("publisher"), book2.get("publisher"));
    }

    /**
     * 保存三库冲突记录
     */
    private void saveThreeDbConflicts(List<Map<String, Object>> conflicts) {
        for (Map<String, Object> conflict : conflicts) {
            String bookId = (String) conflict.get("bookId");
            boolean isThreeDb = Boolean.TRUE.equals(conflict.get("isThreeDbConflict"));

            // 对于三库冲突，创建一条总记录（而不是三对两两记录）
            if (isThreeDb) {
                String logId = "3DB_CONF_" + System.currentTimeMillis();
                String detail = String.format("三库数据不一致 - 图书ID: %s\n涉及: %s",
                        bookId, conflict.get("conflictDbs"));

                String insertSql = "INSERT INTO sync_logs (log_id, source_db, target_db, table_name, " +
                        "operation, data_id, sync_status, conflict_detail, sync_time, resolved, is_three_db) " +
                        "VALUES (?, 'ALL', 'ALL', 'books', 'SYNC', ?, 'CONFLICT', ?, NOW(), false, true)";
                mysqlJdbc.update(insertSql, logId, bookId, detail);
            }
        }
    }

    /**
     * 解决三库冲突 - 同步到所有相关数据库
     */
    private Map<String, Object> resolveWithSourceStrategy(String conflictId, String dataId, String tableName,
                                                          String sourceDbType, String targetDbType,
                                                          String resolvedBy) {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("🔧 解决三库冲突: 数据ID={}, 源={}", dataId, sourceDbType);

            // 1. 检查是否是三库冲突记录
            String checkSql = "SELECT is_three_db FROM sync_logs WHERE log_id = ?";
            Boolean isThreeDb = mysqlJdbc.queryForObject(checkSql, Boolean.class, conflictId);

            String sourceDb = convertDbTypeToKey(sourceDbType);
            Map<String, Object> sourceData = getDataFromDatabase(sourceDb, tableName, dataId);

            if (sourceData == null) {
                result.put("success", false);
                result.put("message", "源数据库中不存在此数据");
                return result;
            }

            // 2. 同步到所有三个数据库（不仅是目标库）
            int totalRows = 0;
            String[] allDbs = {"mysql", "postgresql", "sqlserver"};

            for (String db : allDbs) {
                // 如果是三库冲突，同步到所有库（包括源库本身，会执行UPDATE）
                // 如果不是三库冲突，只同步到目标库
                if (Boolean.TRUE.equals(isThreeDb) || db.equals(convertDbTypeToKey(targetDbType))) {
                    // 跳过源数据库（如果只需要更新其他库的话）
                    // if (!db.equals(sourceDb)) {
                    int rows = syncDataToDatabase(db, tableName, dataId, sourceData);
                    totalRows += rows;
                    log.info("同步到 {}: {} 行", db, rows);
                    // }
                }
            }

            // 3. 更新所有相关冲突记录
            String resolutionAction = Boolean.TRUE.equals(isThreeDb) ?
                    "三库冲突解决:使用" + sourceDbType + "数据同步所有库" :
                    "双库冲突解决:" + sourceDbType + "->" + targetDbType;

            updateConflictsForData(dataId, resolutionAction, resolvedBy);

            result.put("success", true);
            result.put("rowsAffected", totalRows);
            result.put("syncedDatabases", Boolean.TRUE.equals(isThreeDb) ? "ALL(3)" : sourceDbType + "->" + targetDbType);
            result.put("isThreeDbConflict", isThreeDb);

        } catch (Exception e) {
            log.error("❌ 冲突解决失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }
    /**
     * 获取图书的副本信息
     */
    public Map<String, Object> getBookCopiesInfo(String bookId) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 查询MySQL
            String sql = "SELECT status, COUNT(*) as count FROM book_copies WHERE book_id = ? GROUP BY status";
            List<Map<String, Object>> mysqlStats = mysqlJdbc.queryForList(sql, bookId);

            // 查询PostgreSQL
            List<Map<String, Object>> pgStats = postgresqlJdbc.queryForList(sql, bookId);

            // 查询SQL Server
            List<Map<String, Object>> ssStats = sqlserverJdbc.queryForList(sql, bookId);

            result.put("success", true);
            result.put("mysql", mysqlStats);
            result.put("postgresql", pgStats);
            result.put("sqlserver", ssStats);

        } catch (Exception e) {
            log.error("获取图书副本信息失败: {}", bookId, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 搜索图书（跨数据库）
     */
    public Map<String, Object> searchBooksCrossDatabase(String keyword) {
        Map<String, Object> result = new HashMap<>();

        try {
            Map<String, List<Map<String, Object>>> booksByDb = new HashMap<>();

            // 搜索所有数据库
            String searchSql = "SELECT book_id, title, author, isbn, publisher, library_id " +
                    "FROM books WHERE title LIKE ? OR author LIKE ? OR book_id LIKE ? " +
                    "ORDER BY title LIMIT 50";
            String likeKeyword = "%" + keyword + "%";

            // MySQL
            booksByDb.put("mysql", mysqlJdbc.queryForList(searchSql, likeKeyword, likeKeyword, likeKeyword));

            // PostgreSQL
            booksByDb.put("postgresql", postgresqlJdbc.queryForList(searchSql, likeKeyword, likeKeyword, likeKeyword));

            // SQL Server
            booksByDb.put("sqlserver", sqlserverJdbc.queryForList(searchSql, likeKeyword, likeKeyword, likeKeyword));

            result.put("success", true);
            result.put("booksByDatabase", booksByDb);
            result.put("keyword", keyword);

            log.info("🔍 跨数据库搜索: '{}'，结果: MySQL={}, PostgreSQL={}, SQL Server={}",
                    keyword,
                    booksByDb.get("mysql").size(),
                    booksByDb.get("postgresql").size(),
                    booksByDb.get("sqlserver").size());

        } catch (Exception e) {
            log.error("跨数据库搜索失败: {}", keyword, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 获取所有未解决的冲突
     */
    public Map<String, Object> getAllUnresolvedConflicts() {
        Map<String, Object> result = new HashMap<>();

        try {
            String sql = "SELECT * FROM sync_logs WHERE resolved = false ORDER BY sync_time DESC";
            List<Map<String, Object>> conflicts = mysqlJdbc.queryForList(sql);

            result.put("success", true);
            result.put("total", conflicts.size());
            result.put("conflicts", conflicts);

            log.info("📋 获取到 {} 条未解决冲突", conflicts.size());

        } catch (Exception e) {
            log.error("❌ 获取未解决冲突失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }
    
    /**
     * 在列表中查找图书（辅助方法）
     */
    private Map<String, Object> findBookInList(List<Map<String, Object>> books, String bookId) {
        if (books == null) return null;
        return books.stream()
                .filter(b -> bookId.equals(b.get("book_id")))
                .findFirst()
                .orElse(null);
    }

    /**
     * 将冲突保存到数据库
     */
    private void saveConflictsToDatabase(List<Map<String, Object>> conflicts) {
        if (conflicts.isEmpty()) {
            log.info("没有冲突需要保存");
            return;
        }

        log.info("开始保存 {} 条冲突记录到 sync_logs 表", conflicts.size());

        // 使用MySQL作为冲突记录的主数据库
        try {
            int savedCount = 0;
            for (Map<String, Object> conflict : conflicts) {
                String bookId = (String) conflict.get("bookId");
                String detail = (String) conflict.get("detail");

                // 构建完整的冲突详情
                StringBuilder fullDetail = new StringBuilder();
                fullDetail.append("冲突图书: ").append(bookId).append("\n");
                fullDetail.append("冲突原因: ").append(detail).append("\n\n");

                Map<String, Object> mysqlData = (Map<String, Object>) conflict.get("mysql");
                Map<String, Object> pgData = (Map<String, Object>) conflict.get("postgresql");
                Map<String, Object> ssData = (Map<String, Object>) conflict.get("sqlserver");

                fullDetail.append("各数据库数据对比:\n");
                if (mysqlData != null) {
                    fullDetail.append("  MySQL: ").append(mysqlData.get("title"))
                            .append(" (").append(mysqlData.get("author")).append(")\n");
                }
                if (pgData != null) {
                    fullDetail.append("  PostgreSQL: ").append(pgData.get("title"))
                            .append(" (").append(pgData.get("author")).append(")\n");
                }
                if (ssData != null) {
                    fullDetail.append("  SQL Server: ").append(ssData.get("title"))
                            .append(" (").append(ssData.get("author")).append(")\n");
                }

                // 插入到 sync_logs 表
                String insertSql = "INSERT INTO sync_logs (log_id, source_db, target_db, table_name, " +
                        "operation, data_id, sync_status, conflict_detail, sync_time, resolved) " +
                        "VALUES (?, 'MYSQL', 'POSTGRESQL', 'books', 'UPDATE', ?, 'CONFLICT', ?, NOW(), false)";

                try {
                    // 使用MySQL的JdbcTemplate
                    String logId = "CONF_" + System.currentTimeMillis() + "_" + savedCount;
                    int rows = mysqlJdbc.update(insertSql, logId, bookId, fullDetail.toString());

                    if (rows > 0) {
                        savedCount++;
                        log.info("✅ 保存冲突记录: {} -> {}", bookId, logId);
                    }
                } catch (Exception e) {
                    log.error("❌ 保存冲突记录失败 ({}): {}", bookId, e.getMessage());
                }
            }

            log.info("🎯 冲突记录保存完成: 成功 {} 条/总共 {} 条", savedCount, conflicts.size());

        } catch (Exception e) {
            log.error("❌ 保存冲突记录到数据库失败", e);
        }
    }

    /**
     * 解决数据冲突 - 统一入口
     */
    public Map<String, Object> resolveDataConflict(String conflictId, String strategy, String resolvedBy) {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("🔄 解决冲突[{}]: 策略={}, 操作人={}", conflictId, strategy, resolvedBy);

            // 1. 从冲突记录获取详细信息
            Map<String, Object> conflictInfo = getConflictInfo(conflictId);
            if (conflictInfo == null) {
                result.put("success", false);
                result.put("message", "冲突记录不存在");
                return result;
            }

            String dataId = (String) conflictInfo.get("data_id");
            String tableName = (String) conflictInfo.get("table_name");
            String sourceDbType = (String) conflictInfo.get("source_db");
            String targetDbType = (String) conflictInfo.get("target_db");

            log.info("冲突详情: 表={}, 数据ID={}, 源={}, 目标={}",
                    tableName, dataId, sourceDbType, targetDbType);

            // 2. 根据策略执行解决
            Map<String, Object> resolveResult = new HashMap<>();
            boolean resolved = false;

            switch (strategy.toUpperCase()) {
                case "USE_SOURCE":
                    resolveResult = resolveWithSourceStrategy(conflictId, dataId, tableName,
                            sourceDbType, targetDbType, resolvedBy);
                    resolved = (Boolean) resolveResult.getOrDefault("success", false);
                    break;
                case "USE_TARGET":
                    resolveResult = resolveWithTargetStrategy(conflictId, resolvedBy);
                    resolved = (Boolean) resolveResult.getOrDefault("success", false);
                    break;
                case "USE_LATEST":
                    resolveResult = resolveWithLatestStrategy(dataId, tableName, resolvedBy);
                    resolved = (Boolean) resolveResult.getOrDefault("success", false);
                    break;
                default:
                    result.put("success", false);
                    result.put("message", "未知的解决策略: " + strategy);
                    return result;
            }

            // 3. 如果解决成功，标记冲突为已解决
            if (resolved) {
                // 更新冲突记录
                updateConflictResolved(conflictId,
                        strategy + " - " + resolveResult.getOrDefault("message", "已解决"),
                        resolvedBy);

                result.put("success", true);
                result.put("message", "冲突已成功解决并标记为已处理");
                result.put("resolved", true);
                result.put("resolutionAction", strategy);
                result.put("resolvedBy", resolvedBy);
                result.put("resolvedTime", new Date());
            } else {
                result.put("success", false);
                result.put("message", "冲突解决失败: " + resolveResult.getOrDefault("message", "未知错误"));
            }

        } catch (Exception e) {
            log.error("❌ 解决冲突失败: {}", conflictId, e);
            result.put("success", false);
            result.put("error", "解决失败: " + e.getMessage());
        }

        return result;
    }
    /**
     * 获取冲突记录的详细信息
     */
    private Map<String, Object> getConflictInfo(String conflictId) {
        try {
            String sql = "SELECT data_id, table_name, source_db, target_db, conflict_detail " +
                    "FROM sync_logs WHERE log_id = ? AND resolved = false";
            List<Map<String, Object>> rows = mysqlJdbc.queryForList(sql, conflictId);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                // 直接返回原始行数据，保持数据库字段名（小写下划线）
                return row;
            }
        } catch (Exception e) {
            log.error("获取冲突信息失败: {}", conflictId, e);
        }
        return null;
    }

    /**
     * 策略2：保留目标数据（仅标记冲突为已解决）
     */
    private Map<String, Object> resolveWithTargetStrategy(String conflictId, String resolvedBy) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 只更新冲突记录状态，不修改实际数据
            updateConflictResolved(conflictId, "保留目标数据", resolvedBy);

            result.put("success", true);
            result.put("message", "冲突已标记为已解决（保留目标数据）");

            log.info("✅ 目标数据策略执行成功: 冲突ID={}", conflictId);

        } catch (Exception e) {
            log.error("❌ 目标数据策略执行失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 策略3：使用最新数据（基于修改时间）
     */
    private Map<String, Object> resolveWithLatestStrategy(String dataId, String tableName, String resolvedBy) {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("🔍 使用最新数据策略: 表={}, 数据ID={}", tableName, dataId);

            // 1. 从三个数据库获取数据及其修改时间
            Map<String, Map<String, Object>> dataFromAllDbs = new HashMap<>();
            Map<String, Date> lastModifiedTimes = new HashMap<>();

            String[] dbs = {"mysql", "postgresql", "sqlserver"};
            for (String db : dbs) {
                Map<String, Object> data = getDataFromDatabase(db, tableName, dataId);
                if (data != null && !data.isEmpty()) {
                    dataFromAllDbs.put(db, data);

                    // 提取修改时间
                    Object timestamp = data.get("last_modified_time");
                    if (timestamp instanceof java.sql.Timestamp) {
                        lastModifiedTimes.put(db, new Date(((java.sql.Timestamp) timestamp).getTime()));
                    }
                }
            }

            // 2. 找出最新的数据
            if (dataFromAllDbs.size() < 2) {
                result.put("success", false);
                result.put("message", "数据不存在于多个数据库，无需同步");
                return result;
            }

            String latestDb = findLatestDatabase(lastModifiedTimes);
            Map<String, Object> latestData = dataFromAllDbs.get(latestDb);

            log.info("📅 最新数据来自: {}，时间: {}", latestDb, lastModifiedTimes.get(latestDb));

            // 3. 同步到其他数据库
            int totalRows = 0;
            for (String db : dbs) {
                if (!db.equals(latestDb) && dataFromAllDbs.containsKey(db)) {
                    int rows = syncDataToDatabase(db, tableName, dataId, latestData);
                    totalRows += rows;
                }
            }

            // 4. 更新相关冲突记录
            String resolutionAction = "使用最新数据(" + latestDb + ")同步";
            updateConflictsForData(dataId, resolutionAction, resolvedBy);

            result.put("success", true);
            result.put("rowsAffected", totalRows);
            result.put("sourceDatabase", latestDb);
            result.put("message", String.format("使用 %s 的最新数据同步了 %d 个数据库",
                    latestDb, dataFromAllDbs.size() - 1));

            log.info("✅ 最新数据策略执行成功: 同步 {} 个数据库", dataFromAllDbs.size() - 1);

        } catch (Exception e) {
            log.error("❌ 最新数据策略执行失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 从指定数据库获取数据
     */
    private Map<String, Object> getDataFromDatabase(String dbKey, String tableName, String dataId) {
        try {
            log.info("🔍 查询数据: 数据库={}, 表={}, ID={}", dbKey, tableName, dataId);
            // 根据表名构建查询（这里以books表为例）
            if ("books".equals(tableName)) {
                String sql = "SELECT book_id, title, author, isbn, publisher, " +
                        "library_id, description, location, last_modified_time, version " +
                        "FROM books WHERE book_id = ?";

                List<Map<String, Object>> rows = getJdbcForDb(dbKey).queryForList(sql, dataId);
                return rows.isEmpty() ? null : rows.get(0);
            }
            // 可以扩展其他表的处理

        } catch (Exception e) {
            log.error("从 {} 获取数据失败: {}", dbKey, e.getMessage());
        }
        return null;
    }

    /**
     * 同步数据到指定数据库
     */
    private int syncDataToDatabase(String dbKey, String tableName, String dataId,
                                   Map<String, Object> sourceData) {
        try {
            Map<String, Object> existingData = getDataFromDatabase(dbKey, tableName, dataId);
            JdbcTemplate jdbc = getJdbcForDb(dbKey);

            if (existingData != null) {
                // 根据数据库类型构建不同的 SQL
                String updateSql;
                if ("sqlserver".equalsIgnoreCase(dbKey)) {
                    updateSql = "UPDATE books SET title = ?, author = ?, isbn = ?, " +
                            "publisher = ?, library_id = ?, description = ?, " +
                            "location = ?, last_modified_time = GETDATE() " +  // SQL Server 使用 GETDATE()
                            "WHERE book_id = ?";
                } else {
                    updateSql = "UPDATE books SET title = ?, author = ?, isbn = ?, " +
                            "publisher = ?, library_id = ?, description = ?, " +
                            "location = ?, last_modified_time = NOW() " +  // MySQL/PostgreSQL 使用 NOW()
                            "WHERE book_id = ?";
                }

                return jdbc.update(updateSql,
                        sourceData.get("title"),
                        sourceData.get("author"),
                        sourceData.get("isbn"),
                        sourceData.get("publisher"),
                        sourceData.get("library_id"),
                        sourceData.get("description"),
                        sourceData.get("location"),
                        dataId);
            } else {
                // 插入逻辑类似，也需要区分数据库
                String insertSql;
                if ("sqlserver".equalsIgnoreCase(dbKey)) {
                    insertSql = "INSERT INTO books (book_id, title, author, isbn, publisher, " +
                            "library_id, description, location, created_time, last_modified_time) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, GETDATE(), GETDATE())";
                } else {
                    insertSql = "INSERT INTO books (book_id, title, author, isbn, publisher, " +
                            "library_id, description, location, created_time, last_modified_time) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";
                }

                return jdbc.update(insertSql,
                        dataId,
                        sourceData.get("title"),
                        sourceData.get("author"),
                        sourceData.get("isbn"),
                        sourceData.get("publisher"),
                        sourceData.get("library_id"),
                        sourceData.get("description"),
                        sourceData.get("location"));
            }
        } catch (Exception e) {
            log.error("同步到 {} 失败: {}", dbKey, e.getMessage());
            throw e;
        }
    }

    /**
     * 更新冲突记录为已解决
     */
    private void updateConflictResolved(String conflictId, String resolutionAction, String resolvedBy) {
        String sql = "UPDATE sync_logs SET resolved = true, resolved_by = ?, " +
                "resolved_time = NOW(), resolution_action = ? WHERE log_id = ?";
        mysqlJdbc.update(sql, resolvedBy, resolutionAction, conflictId);
    }

    /**
     * 更新指定数据的所有冲突记录
     */
    private void updateConflictsForData(String dataId, String resolutionAction, String resolvedBy) {
        String sql = "UPDATE sync_logs SET resolved = true, resolved_by = ?, " +
                "resolved_time = NOW(), resolution_action = ? " +
                "WHERE data_id = ? AND resolved = false";
        mysqlJdbc.update(sql, resolvedBy, resolutionAction, dataId);
    }

    /**
     * 找出哪个数据库的数据最新
     */
    private String findLatestDatabase(Map<String, Date> lastModifiedTimes) {
        String latestDb = null;
        Date latestTime = null;

        for (Map.Entry<String, Date> entry : lastModifiedTimes.entrySet()) {
            if (latestTime == null || entry.getValue().after(latestTime)) {
                latestTime = entry.getValue();
                latestDb = entry.getKey();
            }
        }

        return latestDb;
    }

    /**
     * 转换数据库类型字符串为键名
     */
    private String convertDbTypeToKey(String dbType) {
        if (dbType == null || dbType.trim().isEmpty()) {
            log.warn("数据库类型为空，默认使用MySQL");
            return "mysql"; // 返回默认值
        }
        switch (dbType.toUpperCase()) {
            case "MYSQL": return "mysql";
            case "POSTGRESQL": return "postgresql";
            case "SQL_SERVER": return "sqlserver";
            default: return "mysql";
        }
    }

    /**
     * 获取冲突的详细状态信息
     */
    public Map<String, Object> getConflictStatus(String conflictId) {
        Map<String, Object> result = new HashMap<>();

        try {
            String sql = "SELECT * FROM sync_logs WHERE log_id = ?";
            List<Map<String, Object>> rows = mysqlJdbc.queryForList(sql, conflictId);

            if (!rows.isEmpty()) {
                Map<String, Object> conflict = rows.get(0);
                result.put("success", true);
                result.put("conflict", conflict);

                // 检查相关数据是否仍然存在冲突
                String dataId = (String) conflict.get("data_id");
                String tableName = (String) conflict.get("table_name");

                if (Boolean.FALSE.equals(conflict.get("resolved"))) {
                    // 如果是未解决的冲突，检查数据是否仍然不同
                    boolean stillHasConflict = checkIfDataStillConflicting(dataId, tableName);
                    result.put("stillHasConflict", stillHasConflict);
                }

            } else {
                result.put("success", false);
                result.put("message", "冲突记录不存在");
            }

        } catch (Exception e) {
            log.error("获取冲突状态失败: {}", conflictId, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 检查数据是否仍然存在冲突
     */
    private boolean checkIfDataStillConflicting(String dataId, String tableName) {
        if (!"books".equals(tableName)) {
            return false; // 目前只支持books表
        }

        try {
            // 从三个数据库获取数据
            Map<String, Object> mysqlData = getDataFromDatabase("mysql", tableName, dataId);
            Map<String, Object> pgData = getDataFromDatabase("postgresql", tableName, dataId);
            Map<String, Object> ssData = getDataFromDatabase("sqlserver", tableName, dataId);

            // 检查是否仍然有差异
            int dataCount = 0;
            String lastTitle = null;

            for (Map<String, Object> data : new Map[]{mysqlData, pgData, ssData}) {
                if (data != null) {
                    String title = (String) data.get("title");
                    if (lastTitle == null) {
                        lastTitle = title;
                    } else if (!Objects.equals(lastTitle, title)) {
                        return true; // 仍然存在冲突
                    }
                    dataCount++;
                }
            }

            return false; // 数据一致或只有一个数据库有数据

        } catch (Exception e) {
            log.error("检查数据冲突失败: {}", dataId, e);
            return true; // 出错时假定仍有冲突
        }
    }

    /**
     * 获取冲突统计信息
     */
    public Map<String, Object> getConflictStats(String date) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 使用 MySQL 作为冲突记录的主数据库
            String sql;
            List<Map<String, Object>> stats;

            if (date != null && !date.trim().isEmpty()) {
                sql = "SELECT " +
                        "COUNT(*) as total, " +
                        "SUM(CASE WHEN resolved = false THEN 1 ELSE 0 END) as unresolved, " +
                        "table_name, " +
                        "COUNT(*) as count " +
                        "FROM sync_logs " +
                        "WHERE DATE(sync_time) = ? " +
                        "GROUP BY table_name";
                stats = mysqlJdbc.queryForList(sql, date);
            } else {
                sql = "SELECT " +
                        "COUNT(*) as total, " +
                        "SUM(CASE WHEN resolved = false THEN 1 ELSE 0 END) as unresolved, " +
                        "table_name, " +
                        "COUNT(*) as count " +
                        "FROM sync_logs " +
                        "WHERE DATE(sync_time) = CURDATE() " +
                        "GROUP BY table_name";
                stats = mysqlJdbc.queryForList(sql);
            }

            int totalConflicts = 0;
            int unresolvedCount = 0;
            Map<String, Integer> tableStats = new HashMap<>();

            for (Map<String, Object> row : stats) {
                totalConflicts += ((Number) row.get("total")).intValue();
                unresolvedCount += ((Number) row.get("unresolved")).intValue();
                tableStats.put((String) row.get("table_name"), ((Number) row.get("count")).intValue());
            }

            result.put("success", true);
            result.put("totalConflicts", totalConflicts);
            result.put("unresolvedCount", unresolvedCount);
            result.put("resolvedCount", totalConflicts - unresolvedCount);
            result.put("tableStats", tableStats);
            result.put("date", date != null ? date : java.time.LocalDate.now().toString());

        } catch (Exception e) {
            log.error("❌ 获取冲突统计失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 获取今日冲突统计
     */
    public Map<String, Object> getTodayConflictStats() {
        return getConflictStats(null);
    }

    /**
     * 获取指定日期的冲突统计
     */
    public Map<String, Object> getConflictStatsByDate(String date) {
        return getConflictStats(date);
    }

    /**
     * 为报表API添加的额外方法
     */

    @RestController
    @RequestMapping("/multi-db")
    public class MultiDatabaseServiceController {

        @Autowired
        private MultiDatabaseService multiDatabaseService;

        /**
         * 获取今日冲突统计（API接口）
         */
        @GetMapping("/today-stats")
        @ResponseBody
        public Map<String, Object> getTodayConflictStatsApi() {
            return multiDatabaseService.getTodayConflictStats();
        }

        /**
         * 获取未解决冲突列表（API接口）
         */
        @GetMapping("/unresolved-conflicts")
        @ResponseBody
        public Map<String, Object> getUnresolvedConflictsApi() {
            return multiDatabaseService.getAllUnresolvedConflicts();
        }

        /**
         * 解决冲突（API接口）
         */
        @PostMapping("/resolve-conflict/{conflictId}")
        @ResponseBody
        @PreAuthorize("hasAnyRole('LIB_ADMIN', 'SYS_ADMIN')")
        public Map<String, Object> resolveConflictApi(
                @PathVariable String conflictId,
                @RequestBody Map<String, String> request) {

            String strategy = request.get("strategy");
            String resolvedBy = request.get("resolvedBy");

            if (strategy == null) strategy = "USE_LATEST";
            if (resolvedBy == null) resolvedBy = "system";

            return multiDatabaseService.resolveDataConflict(conflictId, strategy, resolvedBy);
        }
    }

}