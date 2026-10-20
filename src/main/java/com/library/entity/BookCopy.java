package com.library.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Entity
@Table(name = "book_copies")
@Data
@EqualsAndHashCode(callSuper = false)
public class BookCopy {

    @Id
    @Column(name = "copy_id", unique = true, nullable = false)
    @NotBlank(message = "副本ID不能为空")
    @Size(max = 20, message = "副本ID长度不能超过20个字符")
    private String copyId;

    @Column(name = "book_id", nullable = false)
    private String bookId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", referencedColumnName = "book_id", insertable = false, updatable = false)
    private Book book;

    @Column(name = "library_id", nullable = false)
    private String libraryId;

    @Column(name = "barcode", unique = true)
    @Size(max = 20, message = "条形码长度不能超过20个字符")
    private String barcode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CopyStatus status = CopyStatus.IN_LIBRARY;

    @Column(name = "storage_time")
    private LocalDateTime storageTime = LocalDateTime.now();

    @Column(name = "last_modified_time")
    private LocalDateTime lastModifiedTime = LocalDateTime.now();

    @Version
    private Long version;

    public enum CopyStatus {
        IN_LIBRARY,   // 在馆
        BORROWED,     // 借出
        LOST,         // 遗失
        PROCESSING    // 在编
    }

    @PreUpdate
    public void preUpdate() {
        this.lastModifiedTime = LocalDateTime.now();
    }
}