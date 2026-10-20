package com.library.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "books")
@Data
@EqualsAndHashCode(callSuper = false)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class Book {

    @Id
    @Column(name = "book_id", unique = true, nullable = false)
    @NotBlank(message = "图书ID不能为空")
    @Size(max = 20, message = "图书ID长度不能超过20个字符")
    private String bookId;

    @Column(name = "isbn")
    @Size(max = 13, message = "ISBN长度不能超过13个字符")
    private String isbn;

    @Column(name = "title", nullable = false)
    @NotBlank(message = "书名不能为空")
    @Size(max = 100, message = "书名长度不能超过100个字符")
    private String title;

    @Column(name = "author")
    @Size(max = 50, message = "作者长度不能超过50个字符")
    private String author;

    @Column(name = "publisher")
    @Size(max = 50, message = "出版社长度不能超过50个字符")
    private String publisher;

    @Column(name = "publish_date")
    private LocalDate publishDate;

    @Column(name = "cover_image")
    private String coverImage;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "library_id")
    private String libraryId;

    @Column(name = "location")
    private String location;

    @Column(name = "created_time")
    private LocalDateTime createdTime = LocalDateTime.now();

    @Column(name = "last_modified_time")
    private LocalDateTime lastModifiedTime = LocalDateTime.now();

    @Version
    private Long version;

    @PreUpdate
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void preUpdate() {
        this.lastModifiedTime = LocalDateTime.now();
    }
}