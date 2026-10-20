package com.library.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Entity
@Table(name = "sync_logs")
@Data
@EqualsAndHashCode(callSuper = false)
public class DataConflict {

    @Id
    @Column(name = "log_id", unique = true)
    private String logId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_db", nullable = false)
    private DBType sourceDb;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_db", nullable = false)
    private DBType targetDb;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false)
    private OperationType operation;

    @Column(name = "data_id", nullable = false)
    private String dataId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false)
    private SyncStatus syncStatus;

    @Column(name = "conflict_detail", length = 2000)
    private String conflictDetail;

    @Column(name = "sync_time")
    private LocalDateTime syncTime = LocalDateTime.now();

    @Column(name = "resolved")
    private Boolean resolved = false;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolved_time")
    private LocalDateTime resolvedTime;

    @Column(name = "resolution_action")
    private String resolutionAction;

    @Column(name = "is_three_db", columnDefinition = "BOOLEAN DEFAULT false")
    private Boolean isThreeDb = false;

    @PrePersist
    public void prePersist() {
        if (this.logId == null) {
            this.logId = "LOG" + System.currentTimeMillis();
        }
    }

    public enum OperationType {
        INSERT,
        UPDATE,
        DELETE,
        SYNC
    }

    public enum SyncStatus {
        SUCCESS,
        CONFLICT,
        FAILED
    }

    public enum DBType {
        MYSQL("mysql"),
        POSTGRESQL("postgresql"),
        SQL_SERVER("sqlserver"),
        ALL("all");

        private final String name;

        DBType(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public static DBType fromName(String name) {
            for (DBType dbType : values()) {
                if (dbType.name.equals(name)) {
                    return dbType;
                }
            }
            return MYSQL;
        }
    }
}