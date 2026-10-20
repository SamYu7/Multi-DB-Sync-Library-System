package com.library.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@EqualsAndHashCode(callSuper = false)
public class User {

    @Id
    @Column(name = "user_id", unique = true, nullable = false)
    @NotBlank(message = "用户ID不能为空")
    @Size(max = 20, message = "用户ID长度不能超过20个字符")
    private String userId;

    @Column(name = "name", nullable = false)
    @NotBlank(message = "姓名不能为空")
    @Size(max = 50, message = "姓名长度不能超过50个字符")
    private String name;

    @Column(name = "password", nullable = false)
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, message = "密码长度不能少于6个字符")
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private UserRole role;

    @Email(message = "邮箱格式不正确")
    @Column(name = "email")
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "library_id")
    private String libraryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "created_time")
    private LocalDateTime createdTime = LocalDateTime.now();

    @Column(name = "last_modified_time")
    private LocalDateTime lastModifiedTime = LocalDateTime.now();

    @Version
    private Long version;

    public enum UserRole {
        SYS_ADMIN,    // 系统管理员
        LIB_ADMIN,    // 分馆管理员
        READER        // 普通读者
    }

    public enum UserStatus {
        ACTIVE,       // 正常
        FROZEN        // 冻结
    }

    @PreUpdate
    public void preUpdate() {
        this.lastModifiedTime = LocalDateTime.now();
    }
}