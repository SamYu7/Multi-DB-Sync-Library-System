package com.library.repository;

import com.library.entity.BookCopy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookCopyRepository extends JpaRepository<BookCopy, String> {

    Optional<BookCopy> findByCopyId(String copyId);

    List<BookCopy> findByBookId(String bookId);

    List<BookCopy> findByLibraryId(String libraryId);

    List<BookCopy> findByBookIdAndLibraryId(String bookId, String libraryId);

    List<BookCopy> findByStatus(BookCopy.CopyStatus status);

    @Query("SELECT bc FROM BookCopy bc WHERE bc.bookId = :bookId AND bc.status = 'IN_LIBRARY'")
    List<BookCopy> findAvailableCopiesByBookId(@Param("bookId") String bookId);

    @Query("SELECT bc.libraryId, COUNT(bc) as copyCount FROM BookCopy bc WHERE bc.status = 'IN_LIBRARY' GROUP BY bc.libraryId")
    List<Object[]> countAvailableCopiesByLibrary();

    @Query("SELECT bc.bookId, COUNT(bc) as borrowCount FROM BookCopy bc WHERE bc.status = 'BORROWED' GROUP BY bc.bookId ORDER BY borrowCount DESC")
    List<Object[]> findPopularBooks();

    @Query("SELECT bc FROM BookCopy bc WHERE bc.lastModifiedTime > :since")
    List<BookCopy> findModifiedSince(@Param("since") LocalDateTime since);

    @Modifying
    @Query("UPDATE BookCopy bc SET bc.status = :status, " +
            "bc.lastModifiedTime = :lastModifiedTime, bc.version = bc.version + 1 " +
            "WHERE bc.copyId = :copyId AND bc.version = :version")
    int updateCopyWithVersion(@Param("status") String status,
                              @Param("lastModifiedTime") LocalDateTime lastModifiedTime,
                              @Param("copyId") String copyId,
                              @Param("version") Long version);
}