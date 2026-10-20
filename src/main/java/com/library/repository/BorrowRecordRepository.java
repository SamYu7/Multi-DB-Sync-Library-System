package com.library.repository;

import com.library.entity.BorrowRecord;
import com.library.entity.BorrowStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BorrowRecordRepository extends JpaRepository<BorrowRecord, String> {

    Optional<BorrowRecord> findByRecordId(String recordId);

    List<BorrowRecord> findByUserId(String userId);

    List<BorrowRecord> findByCopyId(String copyId);

    List<BorrowRecord> findByBorrowLibraryId(String libraryId);

    List<BorrowRecord> findByReturnLibraryId(String libraryId);

    List<BorrowRecord> findByReturnDateIsNull();

    List<BorrowRecord> findByReturnDateIsNullAndDueDateBefore(LocalDateTime dueDate);

    @Query("SELECT br FROM BorrowRecord br WHERE br.userId = :userId AND br.returnDate IS NULL")
    List<BorrowRecord> findCurrentBorrowsByUser(@Param("userId") String userId);

    @Query("SELECT br.borrowLibraryId, COUNT(br) as borrowCount FROM BorrowRecord br WHERE br.borrowDate BETWEEN :startDate AND :endDate GROUP BY br.borrowLibraryId")
    List<Object[]> countBorrowsByLibraryAndDateRange(@Param("startDate") LocalDateTime startDate,
                                                     @Param("endDate") LocalDateTime endDate);

    @Query("SELECT br.copyId, COUNT(br) as borrowCount FROM BorrowRecord br GROUP BY br.copyId ORDER BY borrowCount DESC")
    List<Object[]> findMostBorrowedBooks();

    @Query("SELECT br FROM BorrowRecord br WHERE br.lastModifiedTime > :since")
    List<BorrowRecord> findModifiedSince(@Param("since") LocalDateTime since);

    @Modifying
    @Query("UPDATE BorrowRecord br SET br.returnDate = :returnDate, " +
            "br.status = :status, br.lastModifiedTime = :lastModifiedTime, br.version = br.version + 1 " +
            "WHERE br.recordId = :recordId AND br.version = :version")
    int updateRecordWithVersion(@Param("returnDate") LocalDateTime returnDate,
                                @Param("status") BorrowStatus status,
                                @Param("lastModifiedTime") LocalDateTime lastModifiedTime,
                                @Param("recordId") String recordId,
                                @Param("version") Long version);

}