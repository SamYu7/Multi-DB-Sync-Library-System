package com.library.controller;

import com.library.config.DataSourceContextHolder;
import com.library.entity.BookCopy;
import com.library.entity.BorrowRecord;
import com.library.entity.BorrowStatus;
import com.library.repository.BookCopyRepository;
import com.library.repository.BorrowRecordRepository;
import com.library.service.MultiDatabaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.*;

@Controller
@RequestMapping("/borrow")
@Slf4j
public class BorrowController {

    @Autowired
    private MultiDatabaseService multiDatabaseService;

    @Autowired
    private BookCopyRepository bookCopyRepository;

    @Autowired
    private BorrowRecordRepository borrowRecordRepository;

    @Autowired
    private DataSourceContextHolder dataSourceContextHolder;

    @Autowired
    private javax.sql.DataSource mysqlDataSource;

    private JdbcTemplate mysqlJdbc;

    @org.springframework.beans.factory.annotation.Autowired
    public void setMysqlDataSource(DataSource mysqlDataSource) {
        this.mysqlJdbc = new JdbcTemplate(mysqlDataSource);
    }

    /**
     * 初始化图书副本 - 为所有图书创建默认副本
     */
    @PostMapping("/init-copies")
    @ResponseBody
    @Transactional
    @PreAuthorize("hasAnyRole('LIB_ADMIN', 'SYS_ADMIN')")
    public ResponseEntity<Map<String, Object>> initializeBookCopies() {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("🔄 开始初始化图书副本...");

            // 1. 获取所有图书
            String booksSql = "SELECT book_id, title, library_id FROM books";
            List<Map<String, Object>> allBooks = mysqlJdbc.queryForList(booksSql);

            int createdCount = 0;
            int updatedCount = 0;

            for (Map<String, Object> book : allBooks) {
                String bookId = (String) book.get("book_id");
                String libraryId = (String) book.get("library_id");
                if (libraryId == null || libraryId.trim().isEmpty()) {
                    libraryId = "总馆";
                }

                // 2. 检查是否已有副本
                String checkSql = "SELECT COUNT(*) as count FROM book_copies WHERE book_id = ?";
                Integer existingCount = mysqlJdbc.queryForObject(checkSql, Integer.class, bookId);

                if (existingCount == null || existingCount == 0) {
                    // 3. 创建副本
                    String copyId = bookId + "_001";
                    String insertSql = "INSERT INTO book_copies (copy_id, book_id, status, library_id, storage_time, created_time) " +
                            "VALUES (?, ?, 'IN_LIBRARY', ?, NOW(), NOW())";

                    mysqlJdbc.update(insertSql, copyId, bookId, libraryId);
                    createdCount++;

                    log.info("✅ 为图书 {} 创建副本: {}", bookId, copyId);
                } else {
                    // 4. 确保至少有一个副本是可借状态
                    String checkAvailableSql = "SELECT COUNT(*) FROM book_copies WHERE book_id = ? AND status = 'IN_LIBRARY'";
                    Integer availableCount = mysqlJdbc.queryForObject(checkAvailableSql, Integer.class, bookId);

                    if (availableCount == null || availableCount == 0) {
                        // 将第一个副本设为可借
                        String firstCopySql = "SELECT copy_id FROM book_copies WHERE book_id = ? LIMIT 1";
                        String firstCopyId = mysqlJdbc.queryForObject(firstCopySql, String.class, bookId);

                        String updateSql = "UPDATE book_copies SET status = 'IN_LIBRARY', last_modified_time = NOW() WHERE copy_id = ?";
                        mysqlJdbc.update(updateSql, firstCopyId);
                        updatedCount++;

                        log.info("🔄 更新副本 {} 状态为可借", firstCopyId);
                    }
                }
            }

            result.put("success", true);
            result.put("message", String.format("副本初始化完成！创建了 %d 个新副本，更新了 %d 个副本状态", createdCount, updatedCount));
            result.put("createdCount", createdCount);
            result.put("updatedCount", updatedCount);
            result.put("totalBooks", allBooks.size());

            log.info("🎉 副本初始化完成！处理了 {} 本图书", allBooks.size());

        } catch (Exception e) {
            log.error("❌ 初始化副本失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 借阅图书请求
     */
    @PostMapping("/request/{bookId}")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> borrowBook(@PathVariable String bookId) {
        Map<String, Object> result = new HashMap<>();

        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String userId = auth.getName();

            log.info("📚 用户 {} 请求借阅图书: {}", userId, bookId);

            // 1. 先检查图书是否存在
            String checkBookSql = "SELECT book_id, title FROM books WHERE book_id = ?";
            List<Map<String, Object>> books = mysqlJdbc.queryForList(checkBookSql, bookId);

            if (books.isEmpty()) {
                result.put("success", false);
                result.put("message", "该图书不存在");
                log.warn("❌ 图书不存在: {}", bookId);
                return ResponseEntity.ok(result);
            }

            Map<String, Object> bookInfo = books.get(0);
            String bookTitle = (String) bookInfo.get("title");

            // 2. 检查是否有可借副本 - 使用更可靠的查询
            String checkSql = "SELECT copy_id, library_id FROM book_copies WHERE book_id = ? AND status = 'IN_LIBRARY' LIMIT 1";

            List<Map<String, Object>> availableCopies = mysqlJdbc.queryForList(checkSql, bookId);

            log.info("🔍 检查可借副本 - 图书ID: {}, 找到 {} 个可借副本", bookId, availableCopies.size());

            if (availableCopies.isEmpty()) {
                // 检查是否有任何副本
                String totalCopiesSql = "SELECT COUNT(*) as total, status FROM book_copies WHERE book_id = ? GROUP BY status";
                List<Map<String, Object>> allCopies = mysqlJdbc.queryForList(totalCopiesSql, bookId);

                if (allCopies.isEmpty()) {
                    result.put("success", false);
                    result.put("message", "该图书没有副本，请联系管理员添加副本");
                    log.warn("❌ 图书没有副本: {}", bookId);
                } else {
                    StringBuilder statusInfo = new StringBuilder("无可借副本。当前状态：");
                    for (Map<String, Object> copy : allCopies) {
                        statusInfo.append(copy.get("status"))
                                .append(":")
                                .append(copy.get("total"))
                                .append("本; ");
                    }
                    result.put("success", false);
                    result.put("message", statusInfo.toString());
                    log.warn("❌ 无可借副本: {} - {}", bookId, statusInfo.toString());
                }
                return ResponseEntity.ok(result);
            }

            Map<String, Object> copyInfo = availableCopies.get(0);
            String copyId = (String) copyInfo.get("copy_id");
            String libraryId = (String) copyInfo.get("library_id");

            log.info("✅ 找到可借副本: {} (分馆: {})", copyId, libraryId);

            // 3. 检查用户是否有未归还的图书（可选）
            String userCheckSql = "SELECT COUNT(*) as count FROM borrow_records WHERE user_id = ? AND return_date IS NULL";
            Integer activeBorrows = mysqlJdbc.queryForObject(userCheckSql, Integer.class, userId);

            if (activeBorrows != null && activeBorrows >= 10) {
                result.put("success", false);
                result.put("message", "您已达到最大借阅数量（10本），请先归还部分图书");
                log.warn("❌ 用户借阅数量已达上限: {} - 当前借阅: {}", userId, activeBorrows);
                return ResponseEntity.ok(result);
            }

            // 4. 创建借阅记录
            String recordId = "BR" + System.currentTimeMillis();

            // 开始事务操作
            String insertRecordSql = "INSERT INTO borrow_records " +
                    "(record_id, user_id, copy_id, status, borrow_date, due_date, " +
                    "borrow_library_id, created_time, last_modified_time) " +
                    "VALUES (?, ?, ?, 'BORROWED', NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), " +
                    "?, NOW(), NOW())";

            int recordInserted = mysqlJdbc.update(insertRecordSql,
                    recordId, userId, copyId, libraryId);

            if (recordInserted == 0) {
                result.put("success", false);
                result.put("message", "创建借阅记录失败");
                log.error("❌ 创建借阅记录失败: {}", recordId);
                return ResponseEntity.ok(result);
            }

            log.info("📝 借阅记录创建成功: {}", recordId);

            // 5. 更新副本状态
            String updateCopySql = "UPDATE book_copies SET status = 'BORROWED', " +
                    "last_modified_time = NOW() WHERE copy_id = ?";
            int copyUpdated = mysqlJdbc.update(updateCopySql, copyId);

            if (copyUpdated == 0) {
                result.put("success", false);
                result.put("message", "更新副本状态失败");
                log.error("❌ 更新副本状态失败: {}", copyId);

                // 回滚借阅记录
                try {
                    String rollbackSql = "DELETE FROM borrow_records WHERE record_id = ?";
                    mysqlJdbc.update(rollbackSql, recordId);
                    log.info("↩️ 已回滚借阅记录: {}", recordId);
                } catch (Exception rollbackError) {
                    log.error("回滚失败: {}", rollbackError.getMessage());
                }

                return ResponseEntity.ok(result);
            }

            log.info("🔄 副本状态更新成功: {} -> BORROWED", copyId);

            // 6. 获取完整的借阅信息
            String borrowDetailSql = "SELECT br.*, b.title, b.author, u.name " +
                    "FROM borrow_records br " +
                    "JOIN books b ON (SELECT book_id FROM book_copies WHERE copy_id = br.copy_id) = b.book_id " +
                    "JOIN users u ON br.user_id = u.user_id " +
                    "WHERE br.record_id = ?";

            Map<String, Object> borrowDetails = mysqlJdbc.queryForMap(borrowDetailSql, recordId);

            // 7. 成功响应
            result.put("success", true);
            result.put("message", String.format("借阅成功！《%s》已借出，请于30天内归还", bookTitle));
            result.put("recordId", recordId);
            result.put("copyId", copyId);
            result.put("bookId", bookId);
            result.put("bookTitle", bookTitle);
            result.put("borrowDate", borrowDetails.get("borrow_date"));
            result.put("dueDate", borrowDetails.get("due_date"));
            result.put("details", borrowDetails);

            log.info("🎉 借阅成功 - 用户: {}, 图书: {}, 记录: {}, 副本: {}",
                    userId, bookTitle, recordId, copyId);

        } catch (Exception e) {
            log.error("❌ 借阅图书失败: {}", bookId, e);
            result.put("success", false);
            result.put("error", "借阅失败: " + e.getMessage());
            result.put("message", "系统错误，请稍后重试");
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 归还图书
     */
    @PostMapping("/return/{recordId}")
    @ResponseBody
    @Transactional
    @PreAuthorize("hasAnyRole('LIB_ADMIN', 'SYS_ADMIN')")
    public ResponseEntity<Map<String, Object>> returnBook(@PathVariable String recordId) {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("📚 归还图书记录: {}", recordId);

            // 1. 查找借阅记录
            String findSql = "SELECT br.*, bc.copy_id, bc.book_id FROM borrow_records br " +
                    "JOIN book_copies bc ON br.copy_id = bc.copy_id " +
                    "WHERE br.record_id = ? AND br.return_date IS NULL";

            List<Map<String, Object>> records = mysqlJdbc.queryForList(findSql, recordId);

            if (records.isEmpty()) {
                result.put("success", false);
                result.put("message", "借阅记录不存在或已归还");
                return ResponseEntity.ok(result);
            }

            Map<String, Object> record = records.get(0);
            String copyId = (String) record.get("copy_id");
            String bookId = (String) record.get("book_id");

            // 2. 更新借阅记录
            String updateRecordSql = "UPDATE borrow_records SET return_date = NOW(), " +
                    "status = 'RETURNED', last_modified_time = NOW() " +
                    "WHERE record_id = ?";
            int updatedRecords = mysqlJdbc.update(updateRecordSql, recordId);

            // 3. 更新副本状态
            String updateCopySql = "UPDATE book_copies SET status = 'IN_LIBRARY', " +
                    "last_modified_time = NOW() WHERE copy_id = ?";
            int updatedCopies = mysqlJdbc.update(updateCopySql, copyId);

            if (updatedRecords > 0 && updatedCopies > 0) {
                result.put("success", true);
                result.put("message", "归还成功！图书ID: " + bookId + " 已重新上架");
                result.put("bookId", bookId);
                result.put("copyId", copyId);

                log.info("✅ 成功归还图书记录: {}，图书ID: {}", recordId, bookId);
            } else {
                result.put("success", false);
                result.put("message", "归还失败，请重试");
            }

        } catch (Exception e) {
            log.error("归还图书失败: {}", recordId, e);
            result.put("success", false);
            result.put("error", "归还失败: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 续借图书
     */
    @PostMapping("/renew/{recordId}")
    @ResponseBody
    @Transactional
    public ResponseEntity<Map<String, Object>> renewBook(@PathVariable String recordId) {
        Map<String, Object> result = new HashMap<>();

        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String userId = auth.getName();

            log.info("📚 用户 {} 请求续借记录: {}", userId, recordId);

            // 1. 检查借阅记录
            String checkSql = "SELECT * FROM borrow_records br " +
                    "WHERE br.record_id = ? AND br.user_id = ? " +
                    "AND br.return_date IS NULL AND br.renew_count < 2"; // 假设最多续借2次

            List<Map<String, Object>> records = mysqlJdbc.queryForList(checkSql, recordId, userId);

            if (records.isEmpty()) {
                result.put("success", false);
                result.put("message", "无法续借：记录不存在、已归还、或已达到最大续借次数");
                return ResponseEntity.ok(result);
            }

            Map<String, Object> record = records.get(0);
            Integer currentRenewCount = (Integer) record.getOrDefault("renew_count", 0);
            LocalDateTime dueDate = (LocalDateTime) record.get("due_date");

            // 2. 检查是否已逾期
            if (dueDate.isBefore(LocalDateTime.now())) {
                result.put("success", false);
                result.put("message", "图书已逾期，请先归还并处理逾期费用");
                return ResponseEntity.ok(result);
            }

            // 3. 更新借阅记录（续借30天）
            String updateSql = "UPDATE borrow_records SET due_date = DATE_ADD(due_date, INTERVAL 30 DAY), " +
                    "renew_count = renew_count + 1, last_modified_time = NOW() " +
                    "WHERE record_id = ?";

            int updated = mysqlJdbc.update(updateSql, recordId);

            if (updated > 0) {
                // 获取新的到期日
                String newDueDateSql = "SELECT due_date FROM borrow_records WHERE record_id = ?";
                LocalDateTime newDueDate = mysqlJdbc.queryForObject(newDueDateSql, LocalDateTime.class, recordId);

                result.put("success", true);
                result.put("message", "续借成功！新的到期日: " + newDueDate);
                result.put("newDueDate", newDueDate);
                result.put("renewCount", currentRenewCount + 1);

                log.info("✅ 用户 {} 成功续借记录: {}，新到期日: {}", userId, recordId, newDueDate);
            } else {
                result.put("success", false);
                result.put("message", "续借失败");
            }

        } catch (Exception e) {
            log.error("续借图书失败: {}", recordId, e);
            result.put("success", false);
            result.put("error", "续借失败: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 获取用户的借阅记录
     */
    @GetMapping("/my-borrows")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getMyBorrows() {
        Map<String, Object> result = new HashMap<>();

        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String userId = auth.getName();

            log.info("📚 获取用户 {} 的借阅记录", userId);

            String sql = "SELECT br.*, b.title, b.author, bc.copy_id " +
                    "FROM borrow_records br " +
                    "JOIN book_copies bc ON br.copy_id = bc.copy_id " +
                    "JOIN books b ON bc.book_id = b.book_id " +
                    "WHERE br.user_id = ? " +
                    "ORDER BY br.borrow_date DESC";

            List<Map<String, Object>> borrows = mysqlJdbc.queryForList(sql, userId);

            // 分类统计
            int activeCount = 0;
            int returnedCount = 0;
            int overdueCount = 0;

            for (Map<String, Object> borrow : borrows) {
                LocalDateTime dueDate = (LocalDateTime) borrow.get("due_date");
                LocalDateTime returnDate = (LocalDateTime) borrow.get("return_date");

                if (returnDate != null) {
                    returnedCount++;
                } else if (dueDate.isBefore(LocalDateTime.now())) {
                    overdueCount++;
                    activeCount++;
                } else {
                    activeCount++;
                }
            }

            result.put("success", true);
            result.put("borrows", borrows);
            result.put("stats", Map.of(
                    "total", borrows.size(),
                    "active", activeCount,
                    "returned", returnedCount,
                    "overdue", overdueCount
            ));

            log.info("✅ 获取到用户 {} 的 {} 条借阅记录", userId, borrows.size());

        } catch (Exception e) {
            log.error("获取借阅记录失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 搜索借阅记录（管理员）
     */
    @GetMapping("/search")
    @ResponseBody
    @PreAuthorize("hasAnyRole('LIB_ADMIN', 'SYS_ADMIN')")
    public ResponseEntity<Map<String, Object>> searchBorrowRecords(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String bookId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        Map<String, Object> result = new HashMap<>();

        try {
            log.info("🔍 搜索借阅记录 - userId: {}, bookId: {}, status: {}", userId, bookId, status);

            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("SELECT br.*, u.username, b.title, b.book_id, bc.copy_id ");
            sqlBuilder.append("FROM borrow_records br ");
            sqlBuilder.append("JOIN users u ON br.user_id = u.user_id ");
            sqlBuilder.append("JOIN book_copies bc ON br.copy_id = bc.copy_id ");
            sqlBuilder.append("JOIN books b ON bc.book_id = b.book_id ");
            sqlBuilder.append("WHERE 1=1 ");

            List<Object> params = new ArrayList<>();

            if (userId != null && !userId.trim().isEmpty()) {
                sqlBuilder.append("AND br.user_id LIKE ? ");
                params.add("%" + userId.trim() + "%");
            }

            if (bookId != null && !bookId.trim().isEmpty()) {
                sqlBuilder.append("AND b.book_id = ? ");
                params.add(bookId.trim());
            }

            if (status != null && !status.trim().isEmpty()) {
                sqlBuilder.append("AND br.status = ? ");
                params.add(status.trim());
            }

            sqlBuilder.append("ORDER BY br.borrow_date DESC ");

            // 获取总记录数
            String countSql = "SELECT COUNT(*) FROM (" + sqlBuilder.toString() + ") as temp";
            Integer totalCount = mysqlJdbc.queryForObject(countSql, Integer.class, params.toArray());

            // 分页查询
            sqlBuilder.append("LIMIT ? OFFSET ? ");
            params.add(size);
            params.add((page - 1) * size);

            List<Map<String, Object>> records = mysqlJdbc.queryForList(sqlBuilder.toString(), params.toArray());

            // 计算总页数
            int totalPages = (int) Math.ceil((double) totalCount / size);

            result.put("success", true);
            result.put("records", records);
            result.put("totalElements", totalCount);
            result.put("totalPages", totalPages);
            result.put("currentPage", page);
            result.put("pageSize", size);

            log.info("✅ 搜索完成，找到 {} 条借阅记录", totalCount);

        } catch (Exception e) {
            log.error("搜索借阅记录失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 获取逾期图书列表
     */
    @GetMapping("/overdue")
    @ResponseBody
    @PreAuthorize("hasAnyRole('LIB_ADMIN', 'SYS_ADMIN')")
    public ResponseEntity<Map<String, Object>> getOverdueBooks() {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("📚 获取逾期图书列表");

            String sql = "SELECT br.*, u.username, u.email, b.title, b.book_id, bc.copy_id, " +
                    "DATEDIFF(NOW(), br.due_date) as overdue_days " +
                    "FROM borrow_records br " +
                    "JOIN users u ON br.user_id = u.user_id " +
                    "JOIN book_copies bc ON br.copy_id = bc.copy_id " +
                    "JOIN books b ON bc.book_id = b.book_id " +
                    "WHERE br.return_date IS NULL AND br.due_date < NOW() " +
                    "ORDER BY br.due_date ASC";

            List<Map<String, Object>> overdueBooks = mysqlJdbc.queryForList(sql);

            // 统计总逾期天数和罚款（假设每天0.5元）
            double totalFine = 0;
            int totalOverdueDays = 0;

            for (Map<String, Object> book : overdueBooks) {
                Integer days = (Integer) book.get("overdue_days");
                if (days != null) {
                    totalOverdueDays += days;
                    totalFine += days * 0.5;
                }
            }

            result.put("success", true);
            result.put("overdueBooks", overdueBooks);
            result.put("stats", Map.of(
                    "totalCount", overdueBooks.size(),
                    "totalOverdueDays", totalOverdueDays,
                    "estimatedFine", String.format("%.2f", totalFine)
            ));

            log.info("✅ 找到 {} 本逾期图书，预计罚款: {} 元", overdueBooks.size(), totalFine);

        } catch (Exception e) {
            log.error("获取逾期图书失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }
}