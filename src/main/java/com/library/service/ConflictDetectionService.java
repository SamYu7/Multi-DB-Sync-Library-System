package com.library.service;

import com.library.entity.Book;
import com.library.entity.BookCopy;
import com.library.entity.BorrowRecord;
import com.library.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Service
@Slf4j
public class ConflictDetectionService {

    /**
     * 检测图书信息冲突
     */
    public boolean detectBookConflict(Book source, Book target) {
        if (source == null || target == null) {
            return false;
        }

        log.info("🔍 冲突检测开始 - 图书ID: {}", source.getBookId());

        // 简化逻辑：只要不是完全相同的数据，就认为是冲突
        // 这样更容易触发冲突检测
        boolean isSameBook = Objects.equals(source.getTitle(), target.getTitle()) &&
                Objects.equals(source.getAuthor(), target.getAuthor()) &&
                Objects.equals(source.getIsbn(), target.getIsbn()) &&
                Objects.equals(source.getPublisher(), target.getPublisher());

        boolean isConflict = !isSameBook;

        log.info("🚨 冲突检测结果: {}", isConflict ? "检测到冲突！" : "无冲突（数据完全相同）");

        return isConflict;
    }

    /**
     * 检测副本冲突
     */
    public boolean detectBookCopyConflict(BookCopy source, BookCopy target) {
        if (source == null || target == null) {
            return false;
        }

        boolean hasStatusConflict = !Objects.equals(source.getStatus(), target.getStatus());
        boolean hasTimeConflict = source.getLastModifiedTime().isAfter(target.getLastModifiedTime());

        return hasStatusConflict && hasTimeConflict;
    }

    /**
     * 检测借阅记录冲突
     */
    public boolean detectBorrowRecordConflict(BorrowRecord source, BorrowRecord target) {
        if (source == null || target == null) {
            return false;
        }

        boolean hasReturnDateConflict = !Objects.equals(source.getReturnDate(), target.getReturnDate());
        boolean hasRenewCountConflict = !Objects.equals(source.getRenewCount(), target.getRenewCount());
        boolean hasTimeConflict = source.getLastModifiedTime().isAfter(target.getLastModifiedTime());

        return (hasReturnDateConflict || hasRenewCountConflict) && hasTimeConflict;
    }

    /**
     * 检测用户信息冲突
     */
    public boolean detectUserConflict(User source, User target) {
        if (source == null || target == null) {
            return false;
        }

        boolean hasContentConflict = !Objects.equals(source.getName(), target.getName()) ||
                !Objects.equals(source.getEmail(), target.getEmail()) ||
                !Objects.equals(source.getPhone(), target.getPhone()) ||
                !Objects.equals(source.getStatus(), target.getStatus());

        boolean hasTimeConflict = source.getLastModifiedTime().isAfter(target.getLastModifiedTime());

        return hasContentConflict && hasTimeConflict;
    }

    /**
     * 检测并发冲突（同一时间窗口内的修改）
     */
    public boolean detectConcurrentConflict(LocalDateTime sourceTime, LocalDateTime targetTime) {
        if (sourceTime == null || targetTime == null) {
            return false;
        }

        // 如果两个时间戳在5秒内，认为是并发修改
        long secondsDiff = Math.abs(ChronoUnit.SECONDS.between(sourceTime, targetTime));
        return secondsDiff <= 5;
    }
}