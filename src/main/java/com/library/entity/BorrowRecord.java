package com.library.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Entity
@Table(name = "borrow_records")
@Data
@EqualsAndHashCode(callSuper = false)
public class BorrowRecord {

    @Id
    @Column(name = "record_id", unique = true, nullable = false)
    private String recordId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "user_id", insertable = false, updatable = false)
    private User user;

    @Column(name = "copy_id", nullable = false)
    private String copyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "copy_id", referencedColumnName = "copy_id", insertable = false, updatable = false)
    private BookCopy bookCopy;

    @Column(name = "borrow_library_id")
    private String borrowLibraryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private BorrowStatus status;

    @Column(name = "borrow_date", nullable = false)
    private LocalDateTime borrowDate = LocalDateTime.now();

    @Column(name = "due_date", nullable = false)
    private LocalDateTime dueDate;

    @Column(name = "return_date")
    private LocalDateTime returnDate;

    @Column(name = "return_library_id")
    private String returnLibraryId;

    @Column(name = "renew_count")
    private Integer renewCount = 0;

    @Column(name = "operator_id")
    private String operatorId;

    @Column(name = "created_time")
    private LocalDateTime createdTime = LocalDateTime.now();

    @Column(name = "last_modified_time")
    private LocalDateTime lastModifiedTime = LocalDateTime.now();

    @Version
    private Long version;

    @PrePersist
    public void prePersist() {
        if (this.recordId == null) {
            this.recordId = "BR" + System.currentTimeMillis();
        }
        if (this.dueDate == null) {
            this.dueDate = this.borrowDate.plusDays(30); // 默认借期30天
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.lastModifiedTime = LocalDateTime.now();
    }
}