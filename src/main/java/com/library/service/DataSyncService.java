package com.library.service;

import com.library.config.DataSourceContextHolder;
import com.library.config.DataSourceConfig;
import com.library.entity.*;
import com.library.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DataSyncService {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private MultiDatabaseService multiDatabaseService;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private BookCopyRepository bookCopyRepository;

    @Autowired
    private BorrowRecordRepository borrowRecordRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DataConflictRepository dataConflictRepository;

    @Autowired
    private DataSourceContextHolder dataSourceContextHolder;

    @Autowired
    private ConflictDetectionService conflictDetectionService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    /* ==================== 实时同步方法 ==================== */

    // 添加测试数据检测
    private boolean isTestData(String dataId) {
        return dataId != null && (
                dataId.startsWith("FF") ||  // 强制冲突测试
                        dataId.startsWith("CC") ||  // 并发冲突测试
                        dataId.startsWith("VC") ||  // 版本冲突测试
                        dataId.startsWith("DC")     // 数据冲突测试
        );
    }

    @Transactional
    public void syncBookRealTime(Book book, DataSourceConfig.DataSourceType sourceDb) {
        log.info("🔥 syncBookRealTime被调用 - 书ID: {}, 标题: {}, 来源: {}",
                book.getBookId(), book.getTitle(), sourceDb);

        // 检查是否是测试数据
        if (isTestData(book.getBookId())) {
            log.warn("🛑 检测到测试数据，跳过同步: {}", book.getBookId());
            return;
        }

        for (DataSourceConfig.DataSourceType targetDb : DataSourceConfig.DataSourceType.values()) {
            if (targetDb == sourceDb) continue;
            syncSingleBook(book, sourceDb, targetDb);
        }
    }

    /**
     * 实时同步副本信息
     */
    @Transactional
    public void syncBookCopyRealTime(BookCopy copy, DataSourceConfig.DataSourceType sourceDb) {
        for (DataSourceConfig.DataSourceType targetDb : DataSourceConfig.DataSourceType.values()) {
            if (targetDb == sourceDb) continue;
            syncSingleBookCopy(copy, sourceDb, targetDb);
        }
    }

    /**
     * 实时同步借阅记录
     */
    @Transactional
    public void syncBorrowRecordRealTime(BorrowRecord record, DataSourceConfig.DataSourceType sourceDb) {
        for (DataSourceConfig.DataSourceType targetDb : DataSourceConfig.DataSourceType.values()) {
            if (targetDb == sourceDb) continue;
            syncSingleBorrowRecord(record, sourceDb, targetDb);
        }
    }

    /* ==================== 定时同步方法 ==================== */

    /**
     * 定时全量同步
     */
    @Transactional
    public void scheduledFullSync() {
        log.info("开始定时全量同步");
        LocalDateTime since = LocalDateTime.now().minusDays(1);

        dataSourceContextHolder.setMySQLDataSource();
        List<Book> recentBooks = bookRepository.findModifiedSince(since);
        recentBooks.forEach(b -> syncBookRealTime(b, DataSourceConfig.DataSourceType.MYSQL));

        List<BookCopy> recentCopies = bookCopyRepository.findModifiedSince(since);
        recentCopies.forEach(c -> syncBookCopyRealTime(c, DataSourceConfig.DataSourceType.MYSQL));

        List<BorrowRecord> recentRecords = borrowRecordRepository.findModifiedSince(since);
        recentRecords.forEach(r -> syncBorrowRecordRealTime(r, DataSourceConfig.DataSourceType.MYSQL));

        log.info("定时全量同步完成");
    }

    /* ==================== 冲突检测方法 ==================== */

    /**
     * 主动检测并记录数据冲突 - 返回检测到的冲突数量
     */
    @Transactional
    public int detectAndResolveConflicts() {
        log.info("🔄 开始主动冲突检测（增强版）");
        int conflictCount = 0;

        try {
            // 1. 获取所有数据库中的所有图书ID
            List<String> allBookIds = new ArrayList<>();

            // 从MySQL获取
            switchToDataSource("mysql");
            List<String> mysqlIds = currentJdbc().queryForList(
                    "SELECT book_id FROM books", String.class);
            allBookIds.addAll(mysqlIds);

            // 从PostgreSQL获取
            switchToDataSource("postgresql");
            List<String> pgIds = currentJdbc().queryForList(
                    "SELECT book_id FROM books", String.class);
            allBookIds.addAll(pgIds);

            // 从SQL Server获取
            switchToDataSource("sqlserver");
            List<String> ssIds = currentJdbc().queryForList(
                    "SELECT book_id FROM books", String.class);
            allBookIds.addAll(ssIds);

            // 去重
            Set<String> uniqueBookIds = new HashSet<>(allBookIds);
            log.info("📚 待检查图书数量: {}", uniqueBookIds.size());

            // 2. 为每个图书ID检查三个数据库
            for (String bookId : uniqueBookIds) {
                Map<String, Optional<Map<String, Object>>> booksFromAllDbs = new HashMap<>();

                // 检查MySQL
                switchToDataSource("mysql");
                Optional<Map<String, Object>> mysqlBook = queryBookById(bookId);
                booksFromAllDbs.put("mysql", mysqlBook);

                // 检查PostgreSQL
                switchToDataSource("postgresql");
                Optional<Map<String, Object>> pgBook = queryBookById(bookId);
                booksFromAllDbs.put("postgresql", pgBook);

                // 检查SQL Server
                switchToDataSource("sqlserver");
                Optional<Map<String, Object>> ssBook = queryBookById(bookId);
                booksFromAllDbs.put("sqlserver", ssBook);

                // 3. 检查是否有冲突
                if (hasRealConflict(booksFromAllDbs)) {
                    conflictCount++;
                    recordConflictDetail(bookId, booksFromAllDbs);
                    log.warn("🚨 发现数据冲突 - 图书ID: {}", bookId);

                    // 输出详细对比信息
                    logConflictDetails(bookId, booksFromAllDbs);
                }
            }

            log.info("✅ 冲突检测完成，发现 {} 个冲突", conflictCount);

            // 4. 如果有冲突，发送通知
            if (conflictCount > 0) {
                sendConflictAlertByEmail(conflictCount);
            }

        } catch (Exception e) {
            log.error("❌ 冲突检测失败", e);
        } finally {
            dataSourceContextHolder.clearDataSource();
        }

        return conflictCount;
    }

    /**
     * 查询指定图书ID的数据
     */
    private Optional<Map<String, Object>> queryBookById(String bookId) {
        try {
            String sql = "SELECT book_id, title, author, isbn, publisher, " +
                    "last_modified_time, version FROM books WHERE book_id = ?";
            Map<String, Object> row = currentJdbc().queryForMap(sql, bookId);
            return Optional.of(row);
        } catch (Exception e) {
            return Optional.empty(); // 图书不存在
        }
    }
    /* ==================== 私有同步方法 ==================== */

    /**
     * 检查是否有真实的冲突
     */
    private boolean hasRealConflict(Map<String, Optional<Map<String, Object>>> booksByDb) {
        // 找出所有有数据的数据库
        List<Map<String, Object>> existingBooks = new ArrayList<>();
        for (Optional<Map<String, Object>> bookOpt : booksByDb.values()) {
            if (bookOpt.isPresent()) {
                existingBooks.add(bookOpt.get());
            }
        }

        // 如果少于2个数据库有数据，没有冲突
        if (existingBooks.size() < 2) {
            return false;
        }

        // 比较第一个有效数据和其他数据
        Map<String, Object> firstBook = existingBooks.get(0);

        for (int i = 1; i < existingBooks.size(); i++) {
            Map<String, Object> otherBook = existingBooks.get(i);

            // 比较关键字段
            String title1 = (String) firstBook.get("title");
            String title2 = (String) otherBook.get("title");
            String author1 = (String) firstBook.get("author");
            String author2 = (String) otherBook.get("author");
            String isbn1 = (String) firstBook.get("isbn");
            String isbn2 = (String) otherBook.get("isbn");
            String publisher1 = (String) firstBook.get("publisher");
            String publisher2 = (String) otherBook.get("publisher");

            if (!Objects.equals(title1, title2) ||
                    !Objects.equals(author1, author2) ||
                    !Objects.equals(isbn1, isbn2) ||
                    !Objects.equals(publisher1, publisher2)) {
                return true; // 有冲突
            }
        }

        return false; // 没有冲突
    }

    /**
     * 记录冲突详情
     */
    private void recordConflictDetail(String bookId, Map<String, Optional<Map<String, Object>>> booksByDb) {
        StringBuilder detail = new StringBuilder();
        detail.append("跨数据库数据冲突详情：\n");

        for (String dbName : new String[]{"mysql", "postgresql", "sqlserver"}) {
            Optional<Map<String, Object>> bookOpt = booksByDb.get(dbName);

            detail.append("【").append(dbName).append("】");

            if (bookOpt.isPresent()) {
                Map<String, Object> book = bookOpt.get();
                detail.append(" 标题='").append(book.get("title")).append("'")
                        .append(" 作者='").append(book.get("author")).append("'")
                        .append(" ISBN='").append(book.get("isbn")).append("'")
                        .append(" 版本=").append(book.get("version"));
            } else {
                detail.append(" 数据不存在");
            }

            detail.append("\n");
        }

        // 记录到冲突日志
        try {
            DataConflict conflict = new DataConflict();
            conflict.setLogId("CONFLICT_" + System.currentTimeMillis());
            conflict.setSourceDb(DataConflict.DBType.MYSQL);
            conflict.setTargetDb(DataConflict.DBType.POSTGRESQL);
            conflict.setTableName("books");
            conflict.setOperation(DataConflict.OperationType.UPDATE);
            conflict.setDataId(bookId);
            conflict.setSyncStatus(DataConflict.SyncStatus.CONFLICT);
            conflict.setConflictDetail(detail.toString());
            conflict.setSyncTime(LocalDateTime.now());
            conflict.setResolved(false);

            switchToDataSource("mysql");
            dataConflictRepository.save(conflict);

            log.info("✅ 冲突记录已保存: {}", bookId);
        } catch (Exception e) {
            log.error("保存冲突记录失败", e);
        }
    }

    /**
     * 输出冲突详情到日志
     */
    private void logConflictDetails(String bookId, Map<String, Optional<Map<String, Object>>> booksByDb) {
        log.warn("========================================");
        log.warn("🚨 冲突详情 - 图书ID: {}", bookId);
        log.warn("----------------------------------------");

        for (String dbName : new String[]{"mysql", "postgresql", "sqlserver"}) {
            Optional<Map<String, Object>> bookOpt = booksByDb.get(dbName);

            if (bookOpt.isPresent()) {
                Map<String, Object> book = bookOpt.get();
                log.warn("{}: 标题='{}', 作者='{}', ISBN='{}', 版本={}",
                        dbName,
                        book.get("title"),
                        book.get("author"),
                        book.get("isbn"),
                        book.get("version"));
            } else {
                log.warn("{}: ❌ 数据不存在", dbName);
            }
        }

        log.warn("========================================");
    }

    /**
     * 发送每日冲突摘要报告
     */
    @Scheduled(cron = "0 0 9 * * ?") // 每天上午9点发送
    @Async
    public void sendDailyConflictReport() {
        try {
            log.info("📊 开始生成每日冲突报告...");

            // 使用 MultiDatabaseService 获取统计信息
            Map<String, Object> stats = multiDatabaseService.getTodayConflictStats();

            if (!(Boolean) stats.getOrDefault("success", false)) {
                log.error("❌ 获取冲突统计失败");
                return;
            }

            int totalConflicts = (int) stats.getOrDefault("totalConflicts", 0);
            int unresolvedCount = (int) stats.getOrDefault("unresolvedCount", 0);
            @SuppressWarnings("unchecked")
            Map<String, Integer> tableStats = (Map<String, Integer>) stats.getOrDefault("tableStats", new HashMap<>());

            if (totalConflicts > 0) {
                emailService.sendConflictSummaryReport(totalConflicts, unresolvedCount, tableStats);
                log.info("✅ 每日冲突报告已发送，共 {} 个冲突（{} 个未处理）",
                        totalConflicts, unresolvedCount);
            } else {
                log.info("📊 今日无冲突记录，不发送报告");
            }

        } catch (Exception e) {
            log.error("❌ 生成每日冲突报告失败", e);
        }
    }

    /**
     * 通过邮件发送冲突警报
     */
    private void sendConflictAlertByEmail(int conflictCount) {
        try {
            log.warn("🚨 发送冲突警报: 发现 {} 个数据冲突", conflictCount);

            if (conflictCount > 0 && emailService != null) {
                try {
                    DataConflict alertConflict = new DataConflict();
                    alertConflict.setLogId("ALERT_" + System.currentTimeMillis());
                    alertConflict.setTableName("SYSTEM");
                    alertConflict.setDataId("BATCH_DETECT");
                    alertConflict.setConflictDetail("系统检测到 " + conflictCount + " 个数据冲突，请及时处理！");
                    alertConflict.setSyncTime(LocalDateTime.now());
                    alertConflict.setSourceDb(DataConflict.DBType.ALL);
                    alertConflict.setTargetDb(DataConflict.DBType.ALL);
                    alertConflict.setOperation(DataConflict.OperationType.SYNC);
                    alertConflict.setSyncStatus(DataConflict.SyncStatus.CONFLICT);

                    emailService.sendConflictNotification(alertConflict);
                } catch (Exception e) {
                    log.error("发送邮件通知失败，转为日志记录", e);
                    log.warn("⚠️ 请立即登录系统处理 {} 个数据冲突！", conflictCount);
                }
            } else if (conflictCount > 0) {
                log.warn("⚠️ 邮件服务未启用，请登录系统处理 {} 个数据冲突！", conflictCount);
            }
        } catch (Exception e) {
            log.error("发送冲突警报失败", e);
        }
    }
    /**
     * 同步单个图书到目标数据库
     */
    private void syncSingleBook(Book book, DataSourceConfig.DataSourceType sourceDb,
                                DataSourceConfig.DataSourceType targetDb) {
        entityManager.clear();

        try {
            switchToDataSource(targetDb);
            Optional<Book> existingBook = findBookInTargetDb(book.getBookId());

            if (existingBook.isPresent()) {
                updateBookIfNeeded(book, existingBook.get(), sourceDb, targetDb);
            } else {
                insertBookToTargetDb(book, sourceDb, targetDb);
            }
        } catch (Exception e) {
            handleSyncException(sourceDb, targetDb, "books",
                    DataConflict.OperationType.INSERT, book.getBookId(), e);
        } finally {
            cleanupAfterSync();
        }
    }

    /**
     * 同步单个副本到目标数据库
     */
    private void syncSingleBookCopy(BookCopy copy, DataSourceConfig.DataSourceType sourceDb,
                                    DataSourceConfig.DataSourceType targetDb) {
        entityManager.clear();

        try {
            switchToDataSource(targetDb);
            Optional<BookCopy> existing = findBookCopyInTargetDb(copy.getCopyId());

            if (existing.isPresent()) {
                updateBookCopyIfNeeded(copy, existing.get(), sourceDb, targetDb);
            } else {
                insertBookCopyToTargetDb(copy, sourceDb, targetDb);
            }
        } catch (Exception e) {
            handleSyncException(sourceDb, targetDb, "book_copies",
                    DataConflict.OperationType.INSERT, copy.getCopyId(), e);
        } finally {
            cleanupAfterSync();
        }
    }

    /**
     * 同步单个借阅记录到目标数据库
     */
    private void syncSingleBorrowRecord(BorrowRecord record, DataSourceConfig.DataSourceType sourceDb,
                                        DataSourceConfig.DataSourceType targetDb) {
        entityManager.clear();

        try {
            switchToDataSource(targetDb);
            Optional<BorrowRecord> existing = findBorrowRecordInTargetDb(record.getRecordId());

            if (existing.isPresent()) {
                updateBorrowRecordIfNeeded(record, existing.get(), sourceDb, targetDb);
            } else {
                insertBorrowRecordToTargetDb(record, sourceDb, targetDb);
            }
        } catch (Exception e) {
            handleSyncException(sourceDb, targetDb, "borrow_records",
                    DataConflict.OperationType.INSERT, record.getRecordId(), e);
        } finally {
            cleanupAfterSync();
        }
    }

    /* ==================== 数据源切换辅助方法 ==================== */

    /**
     * 切换到指定数据源
     */
    private void switchToDataSource(DataSourceConfig.DataSourceType dbType) {
        dataSourceContextHolder.clearDataSource();
        switch (dbType) {
            case MYSQL:
                dataSourceContextHolder.setMySQLDataSource();
                break;
            case POSTGRESQL:
                dataSourceContextHolder.setPostgreSQLDataSource();
                break;
            case SQL_SERVER:
                dataSourceContextHolder.setSQLServerDataSource();
                break;
        }
    }

    /**
     * 切换到指定数据源（字符串参数）
     */
    private void switchToDataSource(String dbName) {
        dataSourceContextHolder.clearDataSource();
        switch (dbName.toLowerCase()) {
            case "mysql":
                dataSourceContextHolder.setMySQLDataSource();
                break;
            case "postgresql":
                dataSourceContextHolder.setPostgreSQLDataSource();
                break;
            case "sqlserver":
                dataSourceContextHolder.setSQLServerDataSource();
                break;
            default:
                dataSourceContextHolder.setMySQLDataSource();
        }
    }

    /**
     * 同步后清理资源
     */
    private void cleanupAfterSync() {
        dataSourceContextHolder.clearDataSource();
        entityManager.clear();
    }

    /* ==================== 数据库操作工具方法 ==================== */

    /**
     * 获取当前JdbcTemplate
     */
    private JdbcTemplate currentJdbc() {
        try {
            String dsKey = dataSourceContextHolder.getCurrentDataSource();
            log.debug(">>> 准备查库: {}", dsKey);
            return jdbcTemplate;
        } catch (Exception e) {
            log.error("获取当前JdbcTemplate失败", e);
            throw new RuntimeException("获取数据库连接失败", e);
        }
    }

    /* ==================== 图书相关操作方法 ==================== */

    private Optional<Book> findBookInTargetDb(String bookId) {
        try {
            String sql = "SELECT book_id, title, author, isbn, publisher, last_modified_time, version " +
                    "FROM books WHERE book_id = ?";
            Map<String, Object> row = currentJdbc().queryForMap(sql, bookId);
            return Optional.of(mapRowToBook(row));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private void updateBookIfNeeded(Book sourceBook, Book targetBook,
                                    DataSourceConfig.DataSourceType sourceDb,
                                    DataSourceConfig.DataSourceType targetDb) {
        log.warn(">>> 源[{}] {}|v={}", sourceDb, sourceBook.getTitle(), sourceBook.getVersion());
        log.warn(">>> 目标[{}] {}|v={}", targetDb, targetBook.getTitle(), targetBook.getVersion());

        if (isBookDataDifferent(sourceBook, targetBook)) {
            String updateSql = "UPDATE books SET title = ?, author = ?, isbn = ?, publisher = ?, " +
                    "last_modified_time = ?, version = version + 1 " +
                    "WHERE book_id = ? AND version = ?";

            int updatedRows = currentJdbc().update(updateSql,
                    sourceBook.getTitle(), sourceBook.getAuthor(), sourceBook.getIsbn(), sourceBook.getPublisher(),
                    sourceBook.getLastModifiedTime(), sourceBook.getBookId(), targetBook.getVersion());

            if (updatedRows == 0) {
                log.warn("🚨 检测到版本冲突 - 图书ID: {}, 源版本: {}, 目标版本: {}",
                        sourceBook.getBookId(), sourceBook.getVersion(), targetBook.getVersion());
                handleConflict(sourceBook, targetBook, targetDb, sourceDb, "books", sourceBook.getBookId());
            } else {
                log.info("✅ 数据更新成功，更新行数: {}", updatedRows);
            }
        } else {
            log.info("✅ 数据完全相同，无需更新");
        }
    }

    private void insertBookToTargetDb(Book book, DataSourceConfig.DataSourceType sourceDb,
                                      DataSourceConfig.DataSourceType targetDb) {
        String insertSql = "INSERT INTO books (book_id, title, author, isbn, publisher, " +
                "last_modified_time, version, created_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        currentJdbc().update(insertSql,
                book.getBookId(), book.getTitle(), book.getAuthor(), book.getIsbn(), book.getPublisher(),
                book.getLastModifiedTime(), book.getVersion(), LocalDateTime.now());
        log.info("✅ 实时同步新增图书: {} 从 {} 到 {}", book.getBookId(), sourceDb, targetDb);
    }

    private boolean isBookDataDifferent(Book book1, Book book2) {
        return !Objects.equals(book1.getTitle(), book2.getTitle()) ||
                !Objects.equals(book1.getAuthor(), book2.getAuthor()) ||
                !Objects.equals(book1.getIsbn(), book2.getIsbn()) ||
                !Objects.equals(book1.getPublisher(), book2.getPublisher()) ||
                (book1.getLastModifiedTime() != null && !book1.getLastModifiedTime().equals(book2.getLastModifiedTime()));
    }

    /* ==================== 副本相关操作方法 ==================== */

    private Optional<BookCopy> findBookCopyInTargetDb(String copyId) {
        try {
            String sql = "SELECT copy_id, status, last_modified_time, version FROM book_copies WHERE copy_id = ?";
            Map<String, Object> row = currentJdbc().queryForMap(sql, copyId);
            return Optional.of(mapRowToBookCopy(row));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private void updateBookCopyIfNeeded(BookCopy sourceCopy, BookCopy targetCopy,
                                        DataSourceConfig.DataSourceType sourceDb,
                                        DataSourceConfig.DataSourceType targetDb) {
        boolean needsUpdate = !Objects.equals(sourceCopy.getStatus(), targetCopy.getStatus()) ||
                (sourceCopy.getLastModifiedTime() != null && !sourceCopy.getLastModifiedTime().equals(targetCopy.getLastModifiedTime()));

        if (needsUpdate) {
            String updateSql = "UPDATE book_copies SET status = ?, last_modified_time = ?, version = version + 1 " +
                    "WHERE copy_id = ? AND version = ?";
            int updatedRows = currentJdbc().update(updateSql,
                    sourceCopy.getStatus().name(), sourceCopy.getLastModifiedTime(),
                    sourceCopy.getCopyId(), targetCopy.getVersion());

            if (updatedRows == 0) {
                log.warn("🚨 检测到版本冲突 - 副本ID: {}", sourceCopy.getCopyId());
                handleConflict(sourceCopy, targetCopy, targetDb, sourceDb, "book_copies", sourceCopy.getCopyId());
            } else {
                log.info("✅ 副本更新成功，行数: {}", updatedRows);
            }
        }
    }

    private void insertBookCopyToTargetDb(BookCopy copy, DataSourceConfig.DataSourceType sourceDb,
                                          DataSourceConfig.DataSourceType targetDb) {
        String insertSql = "INSERT INTO book_copies (copy_id, book_id, status, last_modified_time, version, created_time) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        currentJdbc().update(insertSql,
                copy.getCopyId(), copy.getBookId(), copy.getStatus().name(),
                copy.getLastModifiedTime(), copy.getVersion(), LocalDateTime.now());
        log.info("✅ 实时同步新增副本: {} 从 {} 到 {}", copy.getCopyId(), sourceDb, targetDb);
    }

    /* ==================== 借阅记录相关操作方法 ==================== */

    private Optional<BorrowRecord> findBorrowRecordInTargetDb(String recordId) {
        return borrowRecordRepository.findByRecordId(recordId);
    }

    private void updateBorrowRecordIfNeeded(BorrowRecord sourceRecord, BorrowRecord targetRecord,
                                            DataSourceConfig.DataSourceType sourceDb,
                                            DataSourceConfig.DataSourceType targetDb) {
        boolean needsUpdate = !Objects.equals(sourceRecord.getReturnDate(), targetRecord.getReturnDate()) ||
                !Objects.equals(sourceRecord.getStatus(), targetRecord.getStatus());

        if (needsUpdate) {
            int updatedRows = borrowRecordRepository.updateRecordWithVersion(
                    sourceRecord.getReturnDate(),
                    sourceRecord.getStatus(),
                    sourceRecord.getLastModifiedTime(),
                    sourceRecord.getRecordId(),
                    targetRecord.getVersion());

            if (updatedRows == 0) {
                log.warn("🚨 检测到版本冲突 - 记录ID: {}", sourceRecord.getRecordId());
                handleConflict(sourceRecord, targetRecord, targetDb, sourceDb, "borrow_records", sourceRecord.getRecordId());
            } else {
                log.info("✅ 借阅记录更新成功，行数: {}", updatedRows);
            }
        }
    }

    private void insertBorrowRecordToTargetDb(BorrowRecord record, DataSourceConfig.DataSourceType sourceDb,
                                              DataSourceConfig.DataSourceType targetDb) {
        borrowRecordRepository.save(record);
        log.info("✅ 实时同步新增借阅记录: {} 从 {} 到 {}", record.getRecordId(), sourceDb, targetDb);
    }

    /* ==================== 冲突检测工具方法 ==================== */

    private List<String> getAllBookIdsFromAllDatabasesNative() {
        Set<String> bookIds = new HashSet<>();
        for (DataSourceConfig.DataSourceType db : DataSourceConfig.DataSourceType.values()) {
            switchToDataSource(db);
            try {
                List<String> ids = currentJdbc().queryForList("SELECT book_id FROM books", String.class);
                bookIds.addAll(ids);
            } catch (Exception e) {
                log.warn("查询 {} 数据库失败: {}", db, e.getMessage());
            } finally {
                dataSourceContextHolder.clearDataSource();
            }
        }
        return new ArrayList<>(bookIds);
    }

    private Map<DataSourceConfig.DataSourceType, Optional<Book>> getBookFromAllDatabasesNative(String bookId) {
        Map<DataSourceConfig.DataSourceType, Optional<Book>> map = new HashMap<>();

        for (DataSourceConfig.DataSourceType db : DataSourceConfig.DataSourceType.values()) {
            switchToDataSource(db);
            try {
                String sql = "SELECT * FROM books WHERE book_id = ?";
                Map<String, Object> row = currentJdbc().queryForMap(sql, bookId);
                map.put(db, Optional.of(mapRowToBook(row)));
            } catch (Exception e) {
                map.put(db, Optional.empty());
            } finally {
                dataSourceContextHolder.clearDataSource();
            }
        }
        return map;
    }

    private boolean hasDataConflictNative(Map<DataSourceConfig.DataSourceType, Optional<Book>> map) {
        List<Book> existing = map.values().stream()
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        if (existing.size() <= 1) return false;

        Book first = existing.get(0);
        for (int i = 1; i < existing.size(); i++) {
            Book other = existing.get(i);
            if (!Objects.equals(first.getTitle(), other.getTitle()) ||
                    !Objects.equals(first.getAuthor(), other.getAuthor()) ||
                    !Objects.equals(first.getIsbn(), other.getIsbn()) ||
                    !Objects.equals(first.getPublisher(), other.getPublisher())) {
                return true;
            }
        }
        return false;
    }

    private void recordDataConflict(String bookId, Map<DataSourceConfig.DataSourceType, Optional<Book>> map) {
        StringBuilder detail = new StringBuilder("数据冲突详情:\n");
        for (DataSourceConfig.DataSourceType db : map.keySet()) {
            Optional<Book> opt = map.get(db);
            if (opt.isPresent()) {
                Book b = opt.get();
                detail.append(String.format("- %s: 标题='%s', 作者='%s', 版本=%d\n",
                        db, b.getTitle(), b.getAuthor(), b.getVersion()));
            } else {
                detail.append(String.format("- %s: 数据不存在\n", db));
            }
        }

        DataConflict conflict = new DataConflict();
        conflict.setSourceDb(DataConflict.DBType.MYSQL);
        conflict.setTargetDb(DataConflict.DBType.POSTGRESQL);
        conflict.setTableName("books");
        conflict.setOperation(DataConflict.OperationType.UPDATE);
        conflict.setDataId(bookId);
        conflict.setSyncStatus(DataConflict.SyncStatus.CONFLICT);
        conflict.setConflictDetail(detail.toString());
        conflict.setSyncTime(LocalDateTime.now());
        conflict.setResolved(false);

        dataConflictRepository.save(conflict);
        emailService.sendConflictNotification(conflict);
        log.info("✅ 冲突记录已保存: {}", bookId);
    }

    /* ==================== 对象映射方法 ==================== */

    private Book mapRowToBook(Map<String, Object> row) {
        Book book = new Book();
        book.setBookId((String) row.get("book_id"));
        book.setTitle((String) row.get("title"));
        book.setAuthor((String) row.get("author"));
        book.setIsbn((String) row.get("isbn"));
        book.setPublisher((String) row.get("publisher"));

        Object lastModified = row.get("last_modified_time");
        if (lastModified instanceof java.sql.Timestamp) {
            book.setLastModifiedTime(((java.sql.Timestamp) lastModified).toLocalDateTime());
        } else {
            book.setLastModifiedTime((LocalDateTime) lastModified);
        }

        Object versionObj = row.get("version");
        if (versionObj instanceof Number) {
            book.setVersion(((Number) versionObj).longValue());
        } else {
            book.setVersion((Long) versionObj);
        }

        return book;
    }

    private BookCopy mapRowToBookCopy(Map<String, Object> row) {
        BookCopy copy = new BookCopy();
        copy.setCopyId((String) row.get("copy_id"));
        copy.setStatus(BookCopy.CopyStatus.valueOf((String) row.get("status")));

        Object lastModified = row.get("last_modified_time");
        if (lastModified instanceof java.sql.Timestamp) {
            copy.setLastModifiedTime(((java.sql.Timestamp) lastModified).toLocalDateTime());
        } else {
            copy.setLastModifiedTime((LocalDateTime) lastModified);
        }

        Object versionObj = row.get("version");
        if (versionObj instanceof Number) {
            copy.setVersion(((Number) versionObj).longValue());
        } else {
            copy.setVersion((Long) versionObj);
        }

        return copy;
    }

    /* ==================== 异常处理方法 ==================== */

    private void handleConflict(Object sourceData, Object targetData, DataSourceConfig.DataSourceType targetDb,
                                DataSourceConfig.DataSourceType sourceDb, String tableName, String dataId) {
        DataConflict conflict = new DataConflict();
        conflict.setSourceDb(convertDBType(sourceDb));
        conflict.setTargetDb(convertDBType(targetDb));
        conflict.setTableName(tableName);
        conflict.setOperation(DataConflict.OperationType.UPDATE);
        conflict.setDataId(dataId);
        conflict.setSyncStatus(DataConflict.SyncStatus.CONFLICT);
        conflict.setConflictDetail("数据冲突: 源数据[" + sourceData + "], 目标数据[" + targetData + "]");
        conflict.setSyncTime(LocalDateTime.now());
        conflict.setResolved(false);
        dataConflictRepository.save(conflict);
        emailService.sendConflictNotification(conflict);
        log.warn("检测到数据冲突: 表{} 数据ID{} 从{}到{}", tableName, dataId, sourceDb, targetDb);
    }

    private void handleSyncException(DataSourceConfig.DataSourceType sourceDb, DataSourceConfig.DataSourceType targetDb,
                                     String tableName, DataConflict.OperationType operation, String dataId, Exception e) {
        log.error("❌ 同步失败: {} 从 {} 到 {}", dataId, sourceDb, targetDb, e);
        recordSyncFailure(sourceDb, targetDb, tableName, operation, dataId, e.getMessage());
    }

    private void recordSyncFailure(DataSourceConfig.DataSourceType sourceDb, DataSourceConfig.DataSourceType targetDb,
                                   String tableName, DataConflict.OperationType operation, String dataId, String errorMsg) {
        DataConflict failure = new DataConflict();
        failure.setSourceDb(convertDBType(sourceDb));
        failure.setTargetDb(convertDBType(targetDb));
        failure.setTableName(tableName);
        failure.setOperation(operation);
        failure.setDataId(dataId);
        failure.setSyncStatus(DataConflict.SyncStatus.FAILED);
        failure.setConflictDetail("同步失败: " + errorMsg);
        failure.setSyncTime(LocalDateTime.now());
        failure.setResolved(false);
        dataConflictRepository.save(failure);
        log.error("同步失败: 表{} 数据ID{} 从{}到{} - {}", tableName, dataId, sourceDb, targetDb, errorMsg);
    }

    private DataConflict.DBType convertDBType(DataSourceConfig.DataSourceType dbType) {
        switch (dbType) {
            case MYSQL:
                return DataConflict.DBType.MYSQL;
            case POSTGRESQL:
                return DataConflict.DBType.POSTGRESQL;
            case SQL_SERVER:
                return DataConflict.DBType.SQL_SERVER;
            default:
                return DataConflict.DBType.MYSQL;
        }
    }

}