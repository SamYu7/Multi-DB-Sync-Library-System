package com.library.controller;

import com.library.config.DataSourceContextHolder;
import com.library.entity.Book;
import com.library.entity.DataConflict;
import com.library.repository.BookRepository;
import com.library.repository.DataConflictRepository;
import com.library.service.DataSyncService;
import com.library.service.MultiDatabaseService;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/books")
@Slf4j
public class BookController {

    @PersistenceContext
    private EntityManager entityManager;  // 添加这行

    @Autowired
    private MultiDatabaseService multiDatabaseService;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private DataConflictRepository dataConflictRepository;

    @Autowired
    @Qualifier("mysqlDataSource")  // 使用Bean名称限定
    private DataSource mysqlDataSource;

    private JdbcTemplate mysqlJdbc;

    @PostConstruct
    public void init() {
        // 初始化JdbcTemplate
        this.mysqlJdbc = new JdbcTemplate(mysqlDataSource);
        log.info("✅ BookController 初始化完成");
    }

    @Autowired
    private DataSyncService dataSyncService;

    @Autowired
    private DataSourceContextHolder dataSourceContextHolder;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // 获取当前数据源的JdbcTemplate
    private org.springframework.jdbc.core.JdbcTemplate currentJdbc() {
        return jdbcTemplate;
    }

    /**
     * 图书管理页面 - 显示所有数据库的数据对比（修复版）
     */
    @GetMapping
    public String booksPage(Model model) {
        try {
            log.info("📊 加载图书管理页面 (使用MultiDatabaseService)...");

            // 1. 直接使用MultiDatabaseService获取数据
            Map<String, Object> data = multiDatabaseService.getAllBooksFromAllDatabases();

            // 2. 调试：打印获取到的数据量
            @SuppressWarnings("unchecked")
            Map<String, Integer> counts = (Map<String, Integer>) data.get("counts");
            log.info("📈 数据库统计 - MySQL: {}, PostgreSQL: {}, SQL Server: {}",
                    counts.get("mysql"), counts.get("postgresql"), counts.get("sqlserver"));

            // 3. 提取数据供页面使用
            @SuppressWarnings("unchecked")
            List<String> allBookIds = (List<String>) data.get("allBookIds");

            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> booksByDb =
                    (Map<String, List<Map<String, Object>>>) data.get("booksByDatabase");

            // 4. 构建前端需要的对比数据结构
            List<Map<String, Object>> bookComparisons = new ArrayList<>();

            for (String bookId : allBookIds) {
                Map<String, Object> comparison = new HashMap<>();
                comparison.put("bookId", bookId);

                // 从各数据库找到这本书的数据
                Map<String, Object> mysqlBook = findBookInList(booksByDb.get("mysql"), bookId);
                Map<String, Object> pgBook = findBookInList(booksByDb.get("postgresql"), bookId);
                Map<String, Object> ssBook = findBookInList(booksByDb.get("sqlserver"), bookId);

                comparison.put("mysqlBook", mysqlBook);
                comparison.put("postgresqlBook", pgBook);
                comparison.put("sqlserverBook", ssBook);

                // 检查是否有冲突（比较标题和作者）
                boolean hasConflict = false;
                if (mysqlBook != null && pgBook != null) {
                    if (!Objects.equals(mysqlBook.get("title"), pgBook.get("title")) ||
                            !Objects.equals(mysqlBook.get("author"), pgBook.get("author"))) {
                        hasConflict = true;
                    }
                }
                if (!hasConflict && mysqlBook != null && ssBook != null) {
                    if (!Objects.equals(mysqlBook.get("title"), ssBook.get("title")) ||
                            !Objects.equals(mysqlBook.get("author"), ssBook.get("author"))) {
                        hasConflict = true;
                    }
                }
                if (!hasConflict && pgBook != null && ssBook != null) {
                    if (!Objects.equals(pgBook.get("title"), ssBook.get("title")) ||
                            !Objects.equals(pgBook.get("author"), pgBook.get("author"))) {
                        hasConflict = true;
                    }
                }

                comparison.put("hasConflict", hasConflict);
                bookComparisons.add(comparison);

                // 记录冲突详情
                if (hasConflict) {
                    log.warn("🚨 页面检测到冲突 - 图书ID: {}", bookId);
                    log.warn("  MySQL: {}", mysqlBook != null ? mysqlBook.get("title") : "无");
                    log.warn("  PostgreSQL: {}", pgBook != null ? pgBook.get("title") : "无");
                    log.warn("  SQL Server: {}", ssBook != null ? ssBook.get("title") : "无");
                }
            }

            // 5. 设置模型属性
            model.addAttribute("bookComparisons", bookComparisons);
            model.addAttribute("totalBooks", allBookIds.size());
            model.addAttribute("mysqlCount", counts.get("mysql"));
            model.addAttribute("postgresqlCount", counts.get("postgresql"));
            model.addAttribute("sqlserverCount", counts.get("sqlserver"));

            // 6. 打印最终统计
            log.info("🎯 页面数据准备完成 - 总图书: {}, MySQL: {}, PostgreSQL: {}, SQL Server: {}",
                    allBookIds.size(), counts.get("mysql"), counts.get("postgresql"), counts.get("sqlserver"));

            return "books";

        } catch (Exception e) {
            log.error("❌ 加载图书页面失败", e);
            model.addAttribute("error", "加载数据失败: " + e.getMessage());
            return "error";
        }
    }

    // 辅助方法：在列表中查找图书
    private Map<String, Object> findBookInList(List<Map<String, Object>> books, String bookId) {
        if (books == null) return null;
        return books.stream()
                .filter(b -> bookId.equals(b.get("book_id")))
                .findFirst()
                .orElse(null);
    }
    // 辅助方法：构建前端需要的对比数据结构

    @GetMapping("/debug-conflicts")
    @ResponseBody
    public Map<String, Object> debugConflicts() {
        Map<String, Object> result = new HashMap<>();

        try {
            // 1. 直接查询 sync_logs 表（使用mysqlJdbc）
            String sql = "SELECT log_id, data_id, table_name, conflict_detail, sync_time, resolved " +
                    "FROM sync_logs WHERE sync_status = 'CONFLICT' ORDER BY sync_time DESC";

            List<Map<String, Object>> conflicts = this.mysqlJdbc.queryForList(sql);

            result.put("success", true);
            result.put("conflictCount", conflicts.size());
            result.put("conflicts", conflicts);
            result.put("tableName", "sync_logs");

            log.info("🔍 直接查询 sync_logs 表，找到 {} 条冲突记录", conflicts.size());

            // 2. 同时检查 DataConflictRepository 的查询
            try {
                List<DataConflict> repoConflicts = this.dataConflictRepository.findUnresolvedConflicts();
                result.put("repoConflictCount", repoConflicts.size());
                log.info("🔍 DataConflictRepository 查询到 {} 条冲突记录", repoConflicts.size());

                // 输出详细信息
                if (!repoConflicts.isEmpty()) {
                    List<Map<String, Object>> repoDetails = new ArrayList<>();
                    for (DataConflict conflict : repoConflicts) {
                        Map<String, Object> detail = new HashMap<>();
                        detail.put("logId", conflict.getLogId());
                        detail.put("dataId", conflict.getDataId());
                        detail.put("detail", conflict.getConflictDetail());
                        detail.put("time", conflict.getSyncTime());
                        repoDetails.add(detail);
                    }
                    result.put("repoConflictsDetails", repoDetails);
                }
            } catch (Exception e) {
                result.put("repoError", e.getMessage());
                log.error("DataConflictRepository 查询失败", e);
            }

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            log.error("debugConflicts 失败", e);
        }

        return result;
    }

    /**
     * 调试方法：验证图书在三个数据库中的存在情况
     */
    @GetMapping("/debug/{bookId}")
    @ResponseBody
    public Map<String, Object> debugBook(@PathVariable String bookId) {
        Map<String, Object> result = new HashMap<>();
        result.put("bookId", bookId);

        Map<String, Object> dbStatus = new HashMap<>();

        try {
            // 1. 检查MySQL
            dataSourceContextHolder.setMySQLDataSource();
            entityManager.clear();

            // 方法1：使用Repository（可能有缓存）
            Optional<Book> mysqlBookRepo = bookRepository.findByBookId(bookId);

            // 方法2：使用原生SQL（绕过缓存）
            String sql = "SELECT book_id, title, author, version FROM books WHERE book_id = ?";
            List<Map<String, Object>> mysqlRaw = new ArrayList<>();
            try {
                mysqlRaw = currentJdbc().queryForList(sql, bookId);
            } catch (Exception e) {
                log.warn("MySQL原生查询失败: {}", e.getMessage());
            }

            dbStatus.put("mysql", Map.of(
                    "repository", mysqlBookRepo.isPresent() ?
                            Map.of("title", mysqlBookRepo.get().getTitle(),
                                    "version", mysqlBookRepo.get().getVersion()) :
                            "NOT_FOUND",
                    "rawQuery", mysqlRaw.isEmpty() ? "NOT_FOUND" : mysqlRaw.get(0),
                    "count", bookRepository.count()
            ));

            // 2. 检查PostgreSQL
            dataSourceContextHolder.clearDataSource();
            entityManager.clear();
            dataSourceContextHolder.setPostgreSQLDataSource();

            Optional<Book> pgBookRepo = bookRepository.findByBookId(bookId);
            List<Map<String, Object>> pgRaw = new ArrayList<>();
            try {
                pgRaw = currentJdbc().queryForList(sql, bookId);
            } catch (Exception e) {
                log.warn("PostgreSQL原生查询失败: {}", e.getMessage());
            }

            dbStatus.put("postgresql", Map.of(
                    "repository", pgBookRepo.isPresent() ?
                            Map.of("title", pgBookRepo.get().getTitle(),
                                    "version", pgBookRepo.get().getVersion()) :
                            "NOT_FOUND",
                    "rawQuery", pgRaw.isEmpty() ? "NOT_FOUND" : pgRaw.get(0),
                    "count", bookRepository.count()
            ));

            // 3. 检查SQL Server
            dataSourceContextHolder.clearDataSource();
            entityManager.clear();
            dataSourceContextHolder.setSQLServerDataSource();

            Optional<Book> ssBookRepo = bookRepository.findByBookId(bookId);
            List<Map<String, Object>> ssRaw = new ArrayList<>();
            try {
                ssRaw = currentJdbc().queryForList(sql, bookId);
            } catch (Exception e) {
                log.warn("SQL Server原生查询失败: {}", e.getMessage());
            }

            dbStatus.put("sqlserver", Map.of(
                    "repository", ssBookRepo.isPresent() ?
                            Map.of("title", ssBookRepo.get().getTitle(),
                                    "version", ssBookRepo.get().getVersion()) :
                            "NOT_FOUND",
                    "rawQuery", ssRaw.isEmpty() ? "NOT_FOUND" : ssRaw.get(0),
                    "count", bookRepository.count()
            ));

            result.put("dbStatus", dbStatus);
            result.put("success", true);
            result.put("timestamp", LocalDateTime.now());

            log.info("🔍 调试信息 - 图书ID: {}", bookId);
            for (String db : dbStatus.keySet()) {
                Map<?, ?> status = (Map<?, ?>) dbStatus.get(db);
                log.info("  {}: Repository={}, RawQuery={}",
                        db, status.get("repository"), status.get("rawQuery"));
            }

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        } finally {
            dataSourceContextHolder.clearDataSource();
            entityManager.clear();
        }

        return result;
    }

    /**
     * 清除JPA缓存
     */
    @GetMapping("/clear-cache")
    @ResponseBody
    public Map<String, Object> clearCache() {
        Map<String, Object> result = new HashMap<>();

        try {
            entityManager.clear();
            entityManager.getEntityManagerFactory().getCache().evictAll();

            log.info("🧹 清理JPA缓存完成");

            result.put("success", true);
            result.put("message", "JPA缓存已清理");

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 检查图书是否存在跨库冲突
     */
    private boolean checkBookConflict(Map<String, Book> booksByDb) {
        // 找出所有有数据的数据库
        List<Book> existingBooks = new ArrayList<>();
        for (Book book : booksByDb.values()) {
            if (book != null) {
                existingBooks.add(book);
            }
        }

        // 如果少于2个数据库有数据，没有冲突
        if (existingBooks.size() < 2) {
            return false;
        }

        // 检查所有数据是否相同
        Book firstBook = existingBooks.get(0);
        for (int i = 1; i < existingBooks.size(); i++) {
            Book otherBook = existingBooks.get(i);

            // 比较关键字段
            if (!Objects.equals(firstBook.getTitle(), otherBook.getTitle()) ||
                    !Objects.equals(firstBook.getAuthor(), otherBook.getAuthor()) ||
                    !Objects.equals(firstBook.getIsbn(), otherBook.getIsbn()) ||
                    !Objects.equals(firstBook.getPublisher(), otherBook.getPublisher())) {
                return true; // 有冲突
            }
        }

        return false; // 没有冲突
    }

    /**
     * 获取所有数据库的图书列表（API接口）
     */
    @GetMapping("/all-databases")
    @ResponseBody
    public Map<String, Object> getAllDatabaseBooks() {
        Map<String, Object> result = new HashMap<>();

        try {
            Map<String, List<Book>> booksByDatabase = new HashMap<>();

            // MySQL
            dataSourceContextHolder.setMySQLDataSource();
            booksByDatabase.put("mysql", bookRepository.findAll());

            // PostgreSQL
            dataSourceContextHolder.setPostgreSQLDataSource();
            booksByDatabase.put("postgresql", bookRepository.findAll());

            // SQL Server
            dataSourceContextHolder.setSQLServerDataSource();
            booksByDatabase.put("sqlserver", bookRepository.findAll());

            result.put("success", true);
            result.put("booksByDatabase", booksByDatabase);
            result.put("timestamp", new Date());

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        } finally {
            dataSourceContextHolder.clearDataSource();
        }

        return result;
    }

    /**
     * 获取图书列表
     */
    @GetMapping("/list")
    @ResponseBody
    public List<Book> listBooks() {
        dataSourceContextHolder.setMySQLDataSource();
        try {
            return bookRepository.findAll();
        } finally {
            dataSourceContextHolder.clearDataSource();
        }
    }

    /**
     * 添加图书（增强同步功能）
     */
    @PostMapping
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addBook(@RequestBody Book book) {
        log.info("📚 添加图书: {} - {}", book.getBookId(), book.getTitle());

        // 将Book对象转换为Map
        Map<String, Object> bookData = new HashMap<>();
        bookData.put("title", book.getTitle());
        bookData.put("author", book.getAuthor());
        bookData.put("isbn", book.getIsbn());
        bookData.put("publisher", book.getPublisher());
        bookData.put("libraryId", book.getLibraryId());

        Map<String, Object> result = multiDatabaseService.insertOrUpdateBookToAllDatabases(
                book.getBookId(), bookData);

        return ResponseEntity.ok(result);
    }

    /**
     * 安全地更新图书字段（处理版本冲突）
     */
    private void updateBookFieldsSafely(Book target, Book source) {
        try {
            // 先获取原始版本
            Long originalVersion = target.getVersion();

            // 更新内容字段
            target.setTitle(source.getTitle());
            target.setAuthor(source.getAuthor());
            target.setIsbn(source.getIsbn());
            target.setPublisher(source.getPublisher());
            target.setPublishDate(source.getPublishDate());
            target.setDescription(source.getDescription());
            target.setCoverImage(source.getCoverImage());
            target.setLibraryId(source.getLibraryId());
            target.setLocation(source.getLocation());
            target.setLastModifiedTime(LocalDateTime.now());

            log.debug("更新字段: {} v{} -> {} (保留版本控制)",
                    target.getBookId(), originalVersion, source.getTitle());

        } catch (Exception e) {
            log.error("更新字段失败: {}", target.getBookId(), e);
            throw e;
        }
    }

    /**
     * 更新图书
     */
    @PutMapping("/{bookId}")
    @ResponseBody
    public ResponseEntity<String> updateBook(@PathVariable String bookId, @RequestBody Book book) {
        dataSourceContextHolder.setMySQLDataSource();
        try {
            Optional<Book> existingBook = bookRepository.findByBookId(bookId);
            if (existingBook.isPresent()) {
                Book toUpdate = existingBook.get();
                toUpdate.setTitle(book.getTitle());
                toUpdate.setAuthor(book.getAuthor());
                toUpdate.setIsbn(book.getIsbn());
                toUpdate.setPublisher(book.getPublisher());
                toUpdate.setPublishDate(book.getPublishDate());
                toUpdate.setDescription(book.getDescription());
                toUpdate.setLibraryId(book.getLibraryId());
                toUpdate.setLocation(book.getLocation());
                bookRepository.save(toUpdate);
                // 触发实时同步
                dataSyncService.syncBookRealTime(toUpdate, com.library.config.DataSourceConfig.DataSourceType.MYSQL);
                return ResponseEntity.ok("图书更新成功");
            } else {
                return ResponseEntity.notFound().build();
            }
        } finally {
            dataSourceContextHolder.clearDataSource();
        }
    }

    /**
     * 删除图书（从所有数据库删除）- 修复版
     */
    @DeleteMapping("/{bookId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteBook(@PathVariable String bookId) {
        log.info("🗑️ 调用MultiDatabaseService删除图书: {}", bookId);
        Map<String, Object> result = multiDatabaseService.deleteBookFromAllDatabases(bookId);
        return ResponseEntity.ok(result);
    }
    @GetMapping("/status/{bookId}")
    @ResponseBody
    public Map<String, Object> getBookStatus(@PathVariable String bookId) {
        return multiDatabaseService.checkBookInAllDatabases(bookId);
    }


    @DeleteMapping("/batch")
    @ResponseBody
    @Transactional
    @PreAuthorize("hasAnyRole('LIB_ADMIN', 'SYS_ADMIN')")
    public ResponseEntity<Map<String, Object>> batchDeleteBooks(@RequestBody List<String> bookIds) {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("🗑️ 开始批量删除图书: {} 本", bookIds.size());

            Map<String, Integer> deleteStats = new HashMap<>();
            deleteStats.put("total", bookIds.size());
            deleteStats.put("success", 0);
            deleteStats.put("partialSuccess", 0);
            deleteStats.put("failed", 0);

            List<Map<String, Object>> details = new ArrayList<>();

            for (String bookId : bookIds) {
                // 对每个图书调用单个删除方法
                ResponseEntity<Map<String, Object>> response = deleteBook(bookId);
                Map<String, Object> deleteResult = response.getBody();

                if (deleteResult != null) {
                    deleteResult.put("bookId", bookId);
                    details.add(deleteResult);

                    Boolean success = (Boolean) deleteResult.get("success");
                    if (Boolean.TRUE.equals(success)) {
                        deleteStats.put("success", deleteStats.get("success") + 1);
                    } else if (deleteResult.containsKey("deletedFrom") &&
                            ((List<?>) deleteResult.get("deletedFrom")).size() > 0) {
                        deleteStats.put("partialSuccess", deleteStats.get("partialSuccess") + 1);
                    } else {
                        deleteStats.put("failed", deleteStats.get("failed") + 1);
                    }
                }
            }

            result.put("success", true);
            result.put("deleteStats", deleteStats);
            result.put("details", details);
            result.put("message", String.format(
                    "批量删除完成。成功: %d, 部分成功: %d, 失败: %d",
                    deleteStats.get("success"),
                    deleteStats.get("partialSuccess"),
                    deleteStats.get("failed")
            ));

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("批量删除图书失败", e);

            result.put("success", false);
            result.put("error", "批量删除失败: " + e.getMessage());

            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 从特定数据库删除图书
     */
    @DeleteMapping("/delete-from/{dbName}/{bookId}")
    @ResponseBody
    @Transactional
    @PreAuthorize("hasAnyRole('LIB_ADMIN', 'SYS_ADMIN')")
    public ResponseEntity<Map<String, Object>> deleteFromDatabase(
            @PathVariable String dbName,
            @PathVariable String bookId) {

        Map<String, Object> result = new HashMap<>();

        try {
            log.info("🗑️ 从 {} 删除图书: {}", dbName, bookId);

            // 切换到指定数据库
            switchToDataSource(dbName);

            Optional<Book> bookOpt = bookRepository.findByBookId(bookId);
            if (bookOpt.isPresent()) {
                bookRepository.delete(bookOpt.get());

                result.put("success", true);
                result.put("message", String.format("已从 %s 删除图书 %s", dbName, bookId));
                log.info("✅ 从 {} 删除成功", dbName);
            } else {
                result.put("success", false);
                result.put("message", String.format("图书 %s 不存在于 %s 中", bookId, dbName));
                log.info("ℹ️ {} 中不存在图书 {}", dbName, bookId);
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("从 {} 删除图书失败", dbName, e);

            result.put("success", false);
            result.put("error", "删除失败: " + e.getMessage());

            return ResponseEntity.status(500).body(result);
        } finally {
            dataSourceContextHolder.clearDataSource();
        }
    }

    /**
     * 搜索图书
     */
    @GetMapping("/search")
    @ResponseBody
    public List<Book> searchBooks(@RequestParam String keyword) {
        dataSourceContextHolder.setMySQLDataSource();
        try {
            return bookRepository.searchBooks(keyword);
        } finally {
            dataSourceContextHolder.clearDataSource();
        }
    }

    /**
     * 获取图书的跨库对比详情
     */
    @GetMapping("/comparison/{bookId}")
    @ResponseBody
    public Map<String, Object> getBookComparison(@PathVariable String bookId) {
        Map<String, Object> result = new HashMap<>();

        try {
            result.put("bookId", bookId);

            // 从MySQL获取
            dataSourceContextHolder.setMySQLDataSource();
            Optional<Book> mysqlBook = bookRepository.findByBookId(bookId);
            result.put("mysql", mysqlBook.map(this::bookToMap).orElse(null));

            // 从PostgreSQL获取
            dataSourceContextHolder.setPostgreSQLDataSource();
            Optional<Book> pgBook = bookRepository.findByBookId(bookId);
            result.put("postgresql", pgBook.map(this::bookToMap).orElse(null));

            // 从SQL Server获取
            dataSourceContextHolder.setSQLServerDataSource();
            Optional<Book> ssBook = bookRepository.findByBookId(bookId);
            result.put("sqlserver", ssBook.map(this::bookToMap).orElse(null));

            // 检查冲突
            Map<String, Book> booksByDb = new HashMap<>();
            booksByDb.put("mysql", mysqlBook.orElse(null));
            booksByDb.put("postgresql", pgBook.orElse(null));
            booksByDb.put("sqlserver", ssBook.orElse(null));

            boolean hasConflict = checkBookConflict(booksByDb);
            result.put("hasConflict", hasConflict);
            result.put("success", true);

            log.info("📊 获取图书对比详情: {} - 冲突状态: {}", bookId, hasConflict);

        } catch (Exception e) {
            log.error("获取图书对比详情失败: {}", bookId, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        } finally {
            dataSourceContextHolder.clearDataSource();
        }

        return result;
    }


    /**
     * 智能同步单个图书（基于最新修改时间）
     */
    @PostMapping("/sync/smart/{bookId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> smartSyncBook(@PathVariable String bookId) {
        log.info("🤖 智能同步图书: {}", bookId);
        Map<String, Object> result = multiDatabaseService.smartSyncBookToAllDatabases(bookId);
        return ResponseEntity.ok(result);
    }
    /**
     * 手动选择源数据库同步
     */
    @PostMapping("/sync/from/{sourceDb}/{bookId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> syncFromDatabase(
            @PathVariable String sourceDb,
            @PathVariable String bookId) {
        log.info("🔄 从 {} 同步图书: {}", sourceDb, bookId);
        Map<String, Object> result = multiDatabaseService.syncFromDatabase(sourceDb, bookId);
        return ResponseEntity.ok(result);
    }

    /**
     * 切换到指定数据源
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
                throw new IllegalArgumentException("未知的数据库: " + dbName);
        }
    }


    /**
     * 将Book对象转换为Map（用于JSON响应）
     */
    private Map<String, Object> bookToMap(Book book) {
        if (book == null) {
            return null;
        }

        Map<String, Object> map = new HashMap<>();
        map.put("bookId", book.getBookId());
        map.put("title", book.getTitle());
        map.put("author", book.getAuthor());
        map.put("isbn", book.getIsbn());
        map.put("publisher", book.getPublisher());
        map.put("publishDate", book.getPublishDate());
        map.put("description", book.getDescription());
        map.put("libraryId", book.getLibraryId());
        map.put("location", book.getLocation());
        map.put("version", book.getVersion());
        map.put("createdTime", book.getCreatedTime());
        map.put("lastModifiedTime", book.getLastModifiedTime());

        return map;
    }

    /**
     * 手动触发冲突检测（API接口）
     */
    @PostMapping("/detect-conflicts")
    @ResponseBody
    @PreAuthorize("hasAnyRole('LIB_ADMIN', 'SYS_ADMIN')")
    public ResponseEntity<Map<String, Object>> detectConflicts() {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("🔄 手动触发冲突检测...");

            // 调用DataSyncService的冲突检测方法
            int conflictCount = dataSyncService.detectAndResolveConflicts();

            result.put("success", true);
            result.put("conflictCount", conflictCount);
            result.put("message", conflictCount > 0 ?
                    "发现 " + conflictCount + " 个冲突，请查看冲突管理页面" :
                    "未检测到数据冲突，所有数据库数据一致");
            result.put("timestamp", LocalDateTime.now());

            log.info("✅ 冲突检测完成: 发现 {} 个冲突", conflictCount);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("冲突检测失败", e);

            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("timestamp", LocalDateTime.now());

            return ResponseEntity.status(500).body(result);
        }
    }
}