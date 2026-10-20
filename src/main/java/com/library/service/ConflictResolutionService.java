package com.library.service;

import com.library.config.DataSourceContextHolder;
import com.library.entity.*;
import com.library.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Slf4j
public class ConflictResolutionService {

    @Autowired
    private DataConflictRepository dataConflictRepository;

    @Autowired
    private DataSourceContextHolder dataSourceContextHolder;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private BookCopyRepository bookCopyRepository;

    @Autowired
    private BorrowRecordRepository borrowRecordRepository;

    @Autowired
    private UserRepository userRepository;

    /**
     * 解决冲突 - 使用源数据覆盖目标数据
     */
    @Transactional
    public void resolveConflictWithSource(String conflictId, String resolvedBy) {  // 改为String
        Optional<DataConflict> conflictOpt = dataConflictRepository.findById(conflictId);
        if (conflictOpt.isPresent()) {
            DataConflict conflict = conflictOpt.get();

            try {
                // 根据表名和数据ID执行具体的数据同步
                boolean success = syncDataFromSourceToTarget(
                        conflict.getTableName(),
                        conflict.getDataId(),
                        conflict.getSourceDb(),
                        conflict.getTargetDb()
                );

                if (success) {
                    conflict.setResolved(true);
                    conflict.setResolvedBy(resolvedBy);
                    conflict.setResolvedTime(LocalDateTime.now());
                    conflict.setResolutionAction("使用源数据覆盖 - 同步成功");
                    dataConflictRepository.save(conflict);
                    log.info("冲突解决成功: 冲突ID {}, 表 {}, 数据ID {}", conflictId, conflict.getTableName(), conflict.getDataId());
                } else {
                    conflict.setResolutionAction("使用源数据覆盖 - 同步失败");
                    dataConflictRepository.save(conflict);
                    log.error("冲突解决失败: 冲突ID {}, 表 {}, 数据ID {}", conflictId, conflict.getTableName(), conflict.getDataId());
                }
            } catch (Exception e) {
                conflict.setResolutionAction("使用源数据覆盖 - 异常: " + e.getMessage());
                dataConflictRepository.save(conflict);
                log.error("冲突解决异常: 冲突ID {}, 表 {}, 数据ID {}", conflictId, conflict.getTableName(), conflict.getDataId(), e);
            }
        }
    }

    /**
     * 解决冲突 - 使用目标数据保留
     */
    @Transactional
    public void resolveConflictWithTarget(String conflictId, String resolvedBy) {  // 改为String
        Optional<DataConflict> conflictOpt = dataConflictRepository.findById(conflictId);
        if (conflictOpt.isPresent()) {
            DataConflict conflict = conflictOpt.get();

            // 标记冲突为已解决，但不做数据同步（保留目标数据）
            conflict.setResolved(true);
            conflict.setResolvedBy(resolvedBy);
            conflict.setResolvedTime(LocalDateTime.now());
            conflict.setResolutionAction("保留目标数据");
            dataConflictRepository.save(conflict);

            log.info("冲突标记为已解决（保留目标数据）: 冲突ID {}, 表 {}, 数据ID {}", conflictId, conflict.getTableName(), conflict.getDataId());
        }
    }

    /**
     * 解决冲突 - 手动合并数据
     */
    @Transactional
    public void resolveConflictWithManual(String conflictId, String resolvedBy, String resolutionAction) {  // 改为String
        Optional<DataConflict> conflictOpt = dataConflictRepository.findById(conflictId);
        if (conflictOpt.isPresent()) {
            DataConflict conflict = conflictOpt.get();

            try {
                // 解析手动合并操作
                boolean success = executeManualMerge(
                        conflict.getTableName(),
                        conflict.getDataId(),
                        conflict.getSourceDb(),
                        conflict.getTargetDb(),
                        resolutionAction
                );

                if (success) {
                    conflict.setResolved(true);
                    conflict.setResolvedBy(resolvedBy);
                    conflict.setResolvedTime(LocalDateTime.now());
                    conflict.setResolutionAction("手动合并 - " + resolutionAction);
                    dataConflictRepository.save(conflict);
                    log.info("手动合并成功: 冲突ID {}, 表 {}, 数据ID {}, 操作: {}", conflictId, conflict.getTableName(), conflict.getDataId(), resolutionAction);
                } else {
                    conflict.setResolutionAction("手动合并失败 - " + resolutionAction);
                    dataConflictRepository.save(conflict);
                    log.error("手动合并失败: 冲突ID {}, 表 {}, 数据ID {}, 操作: {}", conflictId, conflict.getTableName(), conflict.getDataId(), resolutionAction);
                }
            } catch (Exception e) {
                conflict.setResolutionAction("手动合并异常 - " + resolutionAction + ": " + e.getMessage());
                dataConflictRepository.save(conflict);
                log.error("手动合并异常: 冲突ID {}, 表 {}, 数据ID {}, 操作: {}", conflictId, conflict.getTableName(), conflict.getDataId(), resolutionAction, e);
            }
        }
    }

    // 其他方法保持不变...
    private boolean syncDataFromSourceToTarget(String tableName, String dataId, DataConflict.DBType sourceDb, DataConflict.DBType targetDb) {
        switch (tableName) {
            case "books":
                return syncBookData(dataId, sourceDb, targetDb);
            case "book_copies":
                return syncBookCopyData(dataId, sourceDb, targetDb);
            case "borrow_records":
                return syncBorrowRecordData(dataId, sourceDb, targetDb);
            case "users":
                return syncUserData(dataId, sourceDb, targetDb);
            default:
                log.warn("未知的表名: {}", tableName);
                return false;
        }
    }

    private boolean syncBookData(String bookId, DataConflict.DBType sourceDb, DataConflict.DBType targetDb) {
        try {
            // 从源数据库读取
            dataSourceContextHolder.setDataSource(sourceDb);
            Optional<Book> sourceBook = bookRepository.findByBookId(bookId);
            if (!sourceBook.isPresent()) {
                log.warn("源数据库中未找到图书: {}", bookId);
                return false;
            }

            // 写入目标数据库
            dataSourceContextHolder.setDataSource(targetDb);
            Optional<Book> targetBook = bookRepository.findByBookId(bookId);
            if (targetBook.isPresent()) {
                // 更新现有记录
                Book toUpdate = targetBook.get();
                updateBookFields(toUpdate, sourceBook.get());
                bookRepository.save(toUpdate);
            } else {
                // 插入新记录
                bookRepository.save(sourceBook.get());
            }

            return true;
        } catch (Exception e) {
            log.error("同步图书数据失败: {} 从 {} 到 {}", bookId, sourceDb, targetDb, e);
            return false;
        } finally {
            dataSourceContextHolder.clearDataSource();
        }
    }

    private boolean syncBookCopyData(String copyId, DataConflict.DBType sourceDb, DataConflict.DBType targetDb) {
        try {
            // 从源数据库读取
            dataSourceContextHolder.setDataSource(sourceDb);
            Optional<BookCopy> sourceCopy = bookCopyRepository.findByCopyId(copyId);
            if (!sourceCopy.isPresent()) {
                log.warn("源数据库中未找到副本: {}", copyId);
                return false;
            }

            // 写入目标数据库
            dataSourceContextHolder.setDataSource(targetDb);
            Optional<BookCopy> targetCopy = bookCopyRepository.findByCopyId(copyId);
            if (targetCopy.isPresent()) {
                BookCopy toUpdate = targetCopy.get();
                updateBookCopyFields(toUpdate, sourceCopy.get());
                bookCopyRepository.save(toUpdate);
            } else {
                bookCopyRepository.save(sourceCopy.get());
            }

            return true;
        } catch (Exception e) {
            log.error("同步副本数据失败: {} 从 {} 到 {}", copyId, sourceDb, targetDb, e);
            return false;
        } finally {
            dataSourceContextHolder.clearDataSource();
        }
    }

    private boolean syncBorrowRecordData(String recordId, DataConflict.DBType sourceDb, DataConflict.DBType targetDb) {
        try {
            // 从源数据库读取
            dataSourceContextHolder.setDataSource(sourceDb);
            Optional<BorrowRecord> sourceRecord = borrowRecordRepository.findByRecordId(recordId);
            if (!sourceRecord.isPresent()) {
                log.warn("源数据库中未找到借阅记录: {}", recordId);
                return false;
            }

            // 写入目标数据库
            dataSourceContextHolder.setDataSource(targetDb);
            Optional<BorrowRecord> targetRecord = borrowRecordRepository.findByRecordId(recordId);
            if (targetRecord.isPresent()) {
                BorrowRecord toUpdate = targetRecord.get();
                updateBorrowRecordFields(toUpdate, sourceRecord.get());
                borrowRecordRepository.save(toUpdate);
            } else {
                borrowRecordRepository.save(sourceRecord.get());
            }

            return true;
        } catch (Exception e) {
            log.error("同步借阅记录失败: {} 从 {} 到 {}", recordId, sourceDb, targetDb, e);
            return false;
        } finally {
            dataSourceContextHolder.clearDataSource();
        }
    }

    private boolean syncUserData(String userId, DataConflict.DBType sourceDb, DataConflict.DBType targetDb) {
        try {
            // 从源数据库读取
            dataSourceContextHolder.setDataSource(sourceDb);
            Optional<User> sourceUser = userRepository.findByUserId(userId);
            if (!sourceUser.isPresent()) {
                log.warn("源数据库中未找到用户: {}", userId);
                return false;
            }

            // 写入目标数据库
            dataSourceContextHolder.setDataSource(targetDb);
            Optional<User> targetUser = userRepository.findByUserId(userId);
            if (targetUser.isPresent()) {
                User toUpdate = targetUser.get();
                updateUserFields(toUpdate, sourceUser.get());
                userRepository.save(toUpdate);
            } else {
                userRepository.save(sourceUser.get());
            }

            return true;
        } catch (Exception e) {
            log.error("同步用户数据失败: {} 从 {} 到 {}", userId, sourceDb, targetDb, e);
            return false;
        } finally {
            dataSourceContextHolder.clearDataSource();
        }
    }

    private boolean executeManualMerge(String tableName, String dataId, DataConflict.DBType sourceDb, DataConflict.DBType targetDb, String resolutionAction) {
        log.info("执行手动合并: 表{}, 数据ID{}, 从{}到{}, 操作: {}", tableName, dataId, sourceDb, targetDb, resolutionAction);
        return syncDataFromSourceToTarget(tableName, dataId, sourceDb, targetDb);
    }

    // 更新字段的方法保持不变...
    private void updateBookFields(Book toUpdate, Book from) {
        toUpdate.setTitle(from.getTitle());
        toUpdate.setAuthor(from.getAuthor());
        toUpdate.setIsbn(from.getIsbn());
        toUpdate.setPublisher(from.getPublisher());
        toUpdate.setPublishDate(from.getPublishDate());
        toUpdate.setDescription(from.getDescription());
        toUpdate.setLibraryId(from.getLibraryId());
        toUpdate.setLocation(from.getLocation());
        toUpdate.setLastModifiedTime(LocalDateTime.now());
    }

    private void updateBookCopyFields(BookCopy toUpdate, BookCopy from) {
        toUpdate.setBookId(from.getBookId());
        toUpdate.setLibraryId(from.getLibraryId());
        toUpdate.setBarcode(from.getBarcode());
        toUpdate.setStatus(from.getStatus());
        toUpdate.setLastModifiedTime(LocalDateTime.now());
    }

    private void updateBorrowRecordFields(BorrowRecord toUpdate, BorrowRecord from) {
        toUpdate.setUserId(from.getUserId());
        toUpdate.setCopyId(from.getCopyId());
        toUpdate.setBorrowLibraryId(from.getBorrowLibraryId());
        toUpdate.setBorrowDate(from.getBorrowDate());
        toUpdate.setDueDate(from.getDueDate());
        toUpdate.setReturnDate(from.getReturnDate());
        toUpdate.setReturnLibraryId(from.getReturnLibraryId());
        toUpdate.setRenewCount(from.getRenewCount());
        toUpdate.setOperatorId(from.getOperatorId());
        toUpdate.setLastModifiedTime(LocalDateTime.now());
    }

    private void updateUserFields(User toUpdate, User from) {
        toUpdate.setName(from.getName());
        toUpdate.setPassword(from.getPassword());
        toUpdate.setRole(from.getRole());
        toUpdate.setEmail(from.getEmail());
        toUpdate.setPhone(from.getPhone());
        toUpdate.setLibraryId(from.getLibraryId());
        toUpdate.setStatus(from.getStatus());
        toUpdate.setLastModifiedTime(LocalDateTime.now());
    }
}