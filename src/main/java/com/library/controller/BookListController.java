package com.library.controller;

import com.library.service.MultiDatabaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.util.*;

@Controller
@RequestMapping("/book-list")
@Slf4j
public class BookListController {

    @Autowired
    private MultiDatabaseService multiDatabaseService;

    private JdbcTemplate mysqlJdbc;

    @PostConstruct
    public void init() {
        this.mysqlJdbc = multiDatabaseService.getJdbcForDb("mysql");
        log.info("✅ BookListController 初始化完成");
    }

    /**
     * 图书列表页面 - 简化版本
     */
    @GetMapping
    public String bookListPage(Model model) {
        try {
            log.info("📚 加载图书列表页面");

            // 获取所有分馆（去重）
            String libSql = "SELECT DISTINCT library_id FROM books WHERE library_id IS NOT NULL AND library_id != '' ORDER BY library_id";
            List<String> libraries = mysqlJdbc.queryForList(libSql, String.class);

            model.addAttribute("libraries", libraries);
            log.info("✅ 找到 {} 个分馆", libraries.size());

            return "book-list";

        } catch (Exception e) {
            log.error("❌ 加载页面失败", e);
            model.addAttribute("error", e.getMessage());
            return "error";
        }
    }

    /**
     * 搜索图书 - 修复版本
     */
    @GetMapping("/search")
    @ResponseBody
    public Map<String, Object> searchBooks(
            @RequestParam(required = false) String bookId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String libraryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {

        Map<String, Object> result = new HashMap<>();

        try {
            log.info("🔍 搜索图书 - bookId={}, title={}, author={}", bookId, title, author);

            // 构建基础查询
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("SELECT book_id, title, author, isbn, publisher, library_id, ");
            sqlBuilder.append("description, location, created_time ");
            sqlBuilder.append("FROM books WHERE 1=1");

            List<Object> params = new ArrayList<>();

            if (bookId != null && !bookId.trim().isEmpty()) {
                sqlBuilder.append(" AND book_id LIKE ?");
                params.add("%" + bookId.trim() + "%");
            }
            if (title != null && !title.trim().isEmpty()) {
                sqlBuilder.append(" AND title LIKE ?");
                params.add("%" + title.trim() + "%");
            }
            if (author != null && !author.trim().isEmpty()) {
                sqlBuilder.append(" AND author LIKE ?");
                params.add("%" + author.trim() + "%");
            }
            if (libraryId != null && !libraryId.trim().isEmpty()) {
                sqlBuilder.append(" AND library_id = ?");
                params.add(libraryId.trim());
            }

            String baseSql = sqlBuilder.toString();

            // 获取总数
            String countSql = "SELECT COUNT(*) FROM (" + baseSql + ") AS temp";
            int totalCount = mysqlJdbc.queryForObject(countSql, Integer.class, params.toArray());

            // 分页查询
            sqlBuilder.append(" ORDER BY title LIMIT ? OFFSET ?");
            params.add(size);
            params.add((page - 1) * size);

            List<Map<String, Object>> books = mysqlJdbc.queryForList(sqlBuilder.toString(), params.toArray());

            // 为每本书获取副本信息
            for (Map<String, Object> book : books) {
                String id = (String) book.get("book_id");
                Map<String, Object> copiesInfo = getBookCopiesInfo(id);
                book.put("availableCopies", copiesInfo.get("available"));
                book.put("totalCopies", copiesInfo.get("total"));
                book.put("status", copiesInfo.get("status"));
            }

            result.put("success", true);
            result.put("books", books);
            result.put("totalElements", totalCount);
            result.put("totalPages", (int) Math.ceil((double) totalCount / size));
            result.put("currentPage", page);
            result.put("pageSize", size);

            log.info("✅ 搜索完成，找到 {} 本图书", totalCount);

        } catch (Exception e) {
            log.error("❌ 搜索失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    @GetMapping("/detail/{bookId}")
    @ResponseBody
    public Map<String, Object> getBookDetail(@PathVariable String bookId) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 验证参数
            if (bookId == null || bookId.trim().isEmpty() || "undefined".equalsIgnoreCase(bookId) || "null".equalsIgnoreCase(bookId)) {
                log.error("❌ 无效的图书ID参数: {}", bookId);
                result.put("success", false);
                result.put("message", "图书ID不能为空");
                return result;
            }

            bookId = bookId.trim();
            log.info("📖 获取图书详情: '{}'", bookId);

            // 查询图书信息
            String sql = "SELECT book_id, title, author, isbn, publisher, " +
                    "library_id, description, location, created_time " +
                    "FROM books WHERE book_id = ?";

            List<Map<String, Object>> books = mysqlJdbc.queryForList(sql, bookId);

            if (books.isEmpty()) {
                log.warn("⚠️ 未找到图书: {}", bookId);
                result.put("success", false);
                result.put("message", "图书不存在");
                return result;
            }

            Map<String, Object> bookData = books.get(0);

            // 获取副本信息
            Map<String, Object> copiesInfo = getBookCopiesInfo(bookId);

            // 构建响应
            Map<String, Object> book = new HashMap<>();
            book.put("bookId", bookData.get("book_id"));
            book.put("title", bookData.get("title"));
            book.put("author", bookData.get("author"));
            book.put("isbn", bookData.get("isbn"));
            book.put("publisher", bookData.get("publisher"));
            book.put("libraryId", bookData.get("library_id"));
            book.put("description", bookData.get("description"));
            book.put("location", bookData.get("location"));
            book.put("createdTime", bookData.get("created_time"));
            book.put("availableCopies", copiesInfo.get("available"));
            book.put("totalCopies", copiesInfo.get("total"));
            book.put("status", copiesInfo.get("status"));

            result.put("success", true);
            result.put("book", book);

            log.info("✅ 获取详情成功: {} - 《{}》", bookId, bookData.get("title"));

        } catch (Exception e) {
            log.error("❌ 获取详情失败: {}", bookId, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }
    /**
     * 检查图书是否可借
     */
    @GetMapping("/check-available/{bookId}")
    @ResponseBody
    public Map<String, Object> checkAvailable(@PathVariable String bookId) {
        Map<String, Object> result = new HashMap<>();

        try {
            Map<String, Object> copiesInfo = getBookCopiesInfo(bookId);
            int available = (Integer) copiesInfo.get("available");
            int total = (Integer) copiesInfo.get("total");

            result.put("success", true);
            result.put("hasAvailable", available > 0);
            result.put("availableCount", available);
            result.put("totalCount", total);
            result.put("message", available > 0 ? "有可借副本" : "无可借副本");

        } catch (Exception e) {
            log.error("检查可借状态失败: {}", bookId, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 获取图书副本信息 - 优化版本
     */
    private Map<String, Object> getBookCopiesInfo(String bookId) {
        Map<String, Object> info = new HashMap<>();

        try {
            // 查询副本统计
            String sql = "SELECT " +
                    "COUNT(*) as total, " +
                    "SUM(CASE WHEN status = 'IN_LIBRARY' THEN 1 ELSE 0 END) as available " +
                    "FROM book_copies WHERE book_id = ?";

            Map<String, Object> stats;
            try {
                stats = mysqlJdbc.queryForMap(sql, bookId);
            } catch (Exception e) {
                // 如果查询失败，可能是表不存在或没有数据
                log.warn("查询副本信息失败，初始化副本: {}", bookId);
                initBookCopy(bookId);
                stats = mysqlJdbc.queryForMap(sql, bookId);
            }

            int total = ((Number) stats.get("total")).intValue();
            Number availableNum = (Number) stats.get("available");
            int available = availableNum != null ? availableNum.intValue() : 0;

            // 如果没有副本，创建默认副本
            if (total == 0) {
                log.info("📋 图书 {} 没有副本，创建默认副本", bookId);
                if (createDefaultCopy(bookId)) {
                    stats = mysqlJdbc.queryForMap(sql, bookId);
                    total = ((Number) stats.get("total")).intValue();
                    availableNum = (Number) stats.get("available");
                    available = availableNum != null ? availableNum.intValue() : 0;
                }
            }

            // 如果有副本但没有可借状态，修复第一个副本
            if (total > 0 && available == 0) {
                log.info("🔧 修复图书 {} 的副本状态", bookId);
                fixCopyStatus(bookId);
                stats = mysqlJdbc.queryForMap(sql, bookId);
                total = ((Number) stats.get("total")).intValue();
                availableNum = (Number) stats.get("available");
                available = availableNum != null ? availableNum.intValue() : 0;
            }

            info.put("available", available);
            info.put("total", total);
            info.put("status", available > 0 ? "IN_LIBRARY" : "BORROWED");

        } catch (Exception e) {
            log.error("获取副本信息失败: {}", bookId, e);
            info.put("available", 0);
            info.put("total", 0);
            info.put("status", "ERROR");
        }

        return info;
    }

    /**
     * 初始化图书副本
     */
    private boolean initBookCopy(String bookId) {
        try {
            // 检查图书是否存在
            String checkBookSql = "SELECT book_id, title, library_id FROM books WHERE book_id = ?";
            List<Map<String, Object>> books = mysqlJdbc.queryForList(checkBookSql, bookId);

            if (books.isEmpty()) {
                log.error("❌ 图书 {} 不存在，无法创建副本", bookId);
                return false;
            }

            Map<String, Object> book = books.get(0);
            String title = (String) book.get("title");
            String libraryId = (String) book.get("library_id");
            if (libraryId == null || libraryId.trim().isEmpty()) {
                libraryId = "总馆";
            }

            // 创建副本
            String copyId = bookId + "_001";
            String insertSql = "INSERT INTO book_copies (copy_id, book_id, status, library_id, storage_time, created_time) " +
                    "VALUES (?, ?, 'IN_LIBRARY', ?, NOW(), NOW())";

            int rows = mysqlJdbc.update(insertSql, copyId, bookId, libraryId);

            if (rows > 0) {
                log.info("✅ 成功为图书 {}《{}》创建副本: {}", bookId, title, copyId);
                return true;
            }

        } catch (Exception e) {
            log.error("初始化副本失败: {}", bookId, e);
        }

        return false;
    }

    private boolean createDefaultCopy(String bookId) {
        try {
            log.info("🆕 为图书 {} 创建副本", bookId);

            // 检查图书是否存在
            String checkBookSql = "SELECT book_id, title, library_id FROM books WHERE book_id = ?";
            List<Map<String, Object>> books = mysqlJdbc.queryForList(checkBookSql, bookId);

            if (books.isEmpty()) {
                log.error("❌ 图书 {} 不存在于books表", bookId);
                return false;
            }

            Map<String, Object> book = books.get(0);
            String title = (String) book.get("title");
            String libraryId = (String) book.get("library_id");
            if (libraryId == null || libraryId.trim().isEmpty()) {
                libraryId = "总馆";
            }

            // 生成符合长度的副本ID（varchar(20)限制）
            // 计算可用长度：20 - 固定部分长度
            String copyId;
            if (bookId.length() <= 16) {
                // 如果bookId足够短，直接拼接
                copyId = bookId + "_001";
            } else {
                // 如果bookId太长，截取前部分
                int maxLength = 20 - 4; // 保留4个字符给后缀
                String shortBookId = bookId.substring(0, Math.min(bookId.length(), maxLength));
                copyId = shortBookId + "_001";
            }

            // 确保不超过20字符
            if (copyId.length() > 20) {
                copyId = copyId.substring(0, 20);
            }

            log.info("📝 生成副本ID: {} (长度: {})", copyId, copyId.length());

            // 根据实际表结构插入（没有created_time字段）
            String insertSql = "INSERT INTO book_copies (copy_id, book_id, status, library_id, storage_time) " +
                    "VALUES (?, ?, 'IN_LIBRARY', ?, NOW()) " +
                    "ON DUPLICATE KEY UPDATE status = 'IN_LIBRARY'";

            int rows = mysqlJdbc.update(insertSql, copyId, bookId, libraryId);

            if (rows > 0) {
                log.info("✅ 成功为图书 {}《{}》创建副本: {}", bookId, title, copyId);
                return true;
            } else {
                // 检查是否已存在
                String checkSql = "SELECT COUNT(*) FROM book_copies WHERE book_id = ?";
                Integer exists = mysqlJdbc.queryForObject(checkSql, Integer.class, bookId);
                if (exists != null && exists > 0) {
                    log.info("ℹ️ 副本已存在: {}", bookId);
                    return true;
                }
                return false;
            }

        } catch (Exception e) {
            log.error("❌ 创建副本失败: {}", bookId, e);

            // 如果是字段太长错误，尝试更短的ID
            if (e.getMessage().contains("Data too long") || e.getMessage().contains("too long")) {
                try {
                    // 使用更短的副本ID
                    String shortCopyId;
                    if (bookId.length() <= 10) {
                        shortCopyId = bookId + "_C1";
                    } else {
                        shortCopyId = bookId.substring(0, Math.min(bookId.length(), 10)) + "_C1";
                    }

                    String simpleInsert = "INSERT IGNORE INTO book_copies (copy_id, book_id, status, library_id, storage_time) " +
                            "VALUES (?, ?, 'IN_LIBRARY', '总馆', NOW())";
                    int rows = mysqlJdbc.update(simpleInsert, shortCopyId, bookId);
                    log.info("🔄 使用短ID插入结果: {} 行 (ID: {})", rows, shortCopyId);
                    return rows > 0;
                } catch (Exception ex) {
                    log.error("❌ 短ID插入也失败: {}", ex.getMessage());
                    return false;
                }
            }
            return false;
        }
    }

    /**
     * 修复副本状态
     */
    private boolean fixCopyStatus(String bookId) {
        try {
            // 获取第一个副本
            String getCopySql = "SELECT copy_id FROM book_copies WHERE book_id = ? LIMIT 1";
            List<String> copies = mysqlJdbc.queryForList(getCopySql, String.class, bookId);

            if (!copies.isEmpty()) {
                String copyId = copies.get(0);
                String updateSql = "UPDATE book_copies SET status = 'IN_LIBRARY', last_modified_time = NOW() " +
                        "WHERE copy_id = ?";

                int rows = mysqlJdbc.update(updateSql, copyId);

                if (rows > 0) {
                    log.info("🔄 修复副本状态成功: {} -> IN_LIBRARY", copyId);
                    return true;
                }
            }

        } catch (Exception e) {
            log.error("修复副本状态失败: {}", bookId, e);
        }

        return false;
    }

    /**
     * 批量修复所有副本 - 使用安全的副本ID
     */
    @GetMapping("/safe-fix-copies")
    @ResponseBody
    public Map<String, Object> safeFixAllCopies() {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("🛡️ 安全修复所有副本...");

            // 获取所有图书
            String sql = "SELECT book_id, title, library_id FROM books";
            List<Map<String, Object>> allBooks = mysqlJdbc.queryForList(sql);

            int successCount = 0;
            int failCount = 0;

            for (Map<String, Object> book : allBooks) {
                String bookId = (String) book.get("book_id");
                String title = (String) book.get("title");

                try {
                    // 检查是否已有副本
                    String checkSql = "SELECT COUNT(*) FROM book_copies WHERE book_id = ?";
                    Integer existing = mysqlJdbc.queryForObject(checkSql, Integer.class, bookId);

                    if (existing == null || existing == 0) {
                        // 创建安全的副本ID
                        String safeCopyId = generateSafeCopyId(bookId);

                        String libraryId = (String) book.get("library_id");
                        if (libraryId == null || libraryId.trim().isEmpty()) {
                            libraryId = "总馆";
                        }

                        String insertSql = "INSERT INTO book_copies (copy_id, book_id, status, library_id, storage_time) " +
                                "VALUES (?, ?, 'IN_LIBRARY', ?, NOW())";

                        int rows = mysqlJdbc.update(insertSql, safeCopyId, bookId, libraryId);

                        if (rows > 0) {
                            successCount++;
                            log.info("✅ 为 {}《{}》创建副本: {}", bookId, title, safeCopyId);
                        } else {
                            failCount++;
                            log.warn("⚠️ 创建副本失败: {}", bookId);
                        }
                    } else {
                        log.info("ℹ️ 已有副本: {}", bookId);
                    }

                } catch (Exception e) {
                    log.error("处理图书 {} 失败: {}", bookId, e.getMessage());
                    failCount++;
                }
            }

            // 统计副本状态
            String statsSql = "SELECT status, COUNT(*) as count FROM book_copies GROUP BY status";
            List<Map<String, Object>> stats = mysqlJdbc.queryForList(statsSql);

            result.put("success", true);
            result.put("message", String.format("修复完成！成功: %d, 失败: %d", successCount, failCount));
            result.put("successCount", successCount);
            result.put("failCount", failCount);
            result.put("totalBooks", allBooks.size());
            result.put("copyStats", stats);

            log.info("✅ 安全修复完成: {}/{} 成功", successCount, allBooks.size());

        } catch (Exception e) {
            log.error("❌ 安全修复失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 生成安全的副本ID（不超过20字符）
     */
    private String generateSafeCopyId(String bookId) {
        // 基础后缀
        String suffix = "_C001";

        // 计算可用长度
        int maxBookIdLength = 20 - suffix.length();

        if (bookId.length() <= maxBookIdLength) {
            return bookId + suffix;
        } else {
            // 截取前部分
            return bookId.substring(0, maxBookIdLength) + suffix;
        }
    }
    /**
     * 数据库测试端点
     */
    @GetMapping("/test-db")
    @ResponseBody
    public Map<String, Object> testDatabase() {
        Map<String, Object> result = new HashMap<>();

        try {
            // 测试1: 检查books表
            String booksSql = "SELECT COUNT(*) as book_count, MAX(book_id) as max_id FROM books";
            Map<String, Object> booksStats = mysqlJdbc.queryForMap(booksSql);

            // 测试2: 检查book_copies表
            String copiesSql = "SELECT COUNT(*) as copy_count, " +
                    "SUM(CASE WHEN status = 'IN_LIBRARY' THEN 1 ELSE 0 END) as available_count " +
                    "FROM book_copies";
            Map<String, Object> copiesStats = mysqlJdbc.queryForMap(copiesSql);

            // 测试3: 获取几个示例图书
            String sampleSql = "SELECT book_id, title, author, library_id FROM books LIMIT 5";
            List<Map<String, Object>> sampleBooks = mysqlJdbc.queryForList(sampleSql);

            result.put("success", true);
            result.put("books_table", booksStats);
            result.put("copies_table", copiesStats);
            result.put("sample_books", sampleBooks);

            log.info("📊 数据库测试结果: books={}, copies={}", booksStats, copiesStats);

        } catch (Exception e) {
            log.error("数据库测试失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }
}