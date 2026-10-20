package com.library.repository;

import com.library.entity.DataConflict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DataConflictRepository extends JpaRepository<DataConflict, String> {

    List<DataConflict> findBySyncStatus(DataConflict.SyncStatus status);

    List<DataConflict> findByResolved(Boolean resolved);

    List<DataConflict> findBySourceDbAndTargetDb(DataConflict.DBType sourceDb, DataConflict.DBType targetDb);

    List<DataConflict> findByTableName(String tableName);

    List<DataConflict> findBySyncTimeBetween(LocalDateTime start, LocalDateTime end);

    @Query("SELECT dc FROM DataConflict dc WHERE dc.resolved = false AND dc.syncStatus = 'CONFLICT'")
    List<DataConflict> findUnresolvedConflicts();

    @Query("SELECT dc.tableName, COUNT(dc) as conflictCount FROM DataConflict dc WHERE dc.syncStatus = 'CONFLICT' GROUP BY dc.tableName")
    List<Object[]> countConflictsByTable();

    @Query("SELECT dc.sourceDb, dc.targetDb, COUNT(dc) as conflictCount FROM DataConflict dc WHERE dc.syncStatus = 'CONFLICT' GROUP BY dc.sourceDb, dc.targetDb")
    List<Object[]> countConflictsByDatabasePair();

    @Query(value = "SELECT DATE(sync_time) as conflict_date, COUNT(*) as daily_count FROM sync_logs WHERE sync_time BETWEEN :start AND :end GROUP BY DATE(sync_time)", nativeQuery = true)
    List<Object[]> countConflictsByDate(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}