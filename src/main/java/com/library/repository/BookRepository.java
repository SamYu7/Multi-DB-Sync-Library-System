package com.library.repository;

import com.library.entity.Book;
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
public interface BookRepository extends JpaRepository<Book, String> {

    Optional<Book> findByBookId(String bookId);

    List<Book> findByLibraryId(String libraryId);

    List<Book> findByAuthorContaining(String author);

    List<Book> findByTitleContaining(String title);

    List<Book> findByIsbn(String isbn);

    @Query("SELECT b FROM Book b WHERE b.title LIKE %:keyword% OR b.author LIKE %:keyword% OR b.isbn LIKE %:keyword%")
    List<Book> searchBooks(@Param("keyword") String keyword);

    @Query("SELECT b.libraryId, COUNT(b) as bookCount FROM Book b GROUP BY b.libraryId")
    List<Object[]> countBooksByLibrary();

    @Query("SELECT b.author, COUNT(b) as bookCount FROM Book b GROUP BY b.author ORDER BY bookCount DESC")
    List<Object[]> countBooksByAuthor();

    @Query("SELECT b FROM Book b WHERE b.lastModifiedTime > :since")
    List<Book> findModifiedSince(@Param("since") LocalDateTime since);

    @Query("SELECT b.bookId FROM Book b")
    List<String> findAllBookIds();

    @Modifying
    @Query("UPDATE Book b SET b.title = :title, b.author = :author, b.isbn = :isbn, " +
            "b.publisher = :publisher, b.lastModifiedTime = :lastModifiedTime, b.version = b.version + 1 " +
            "WHERE b.bookId = :bookId AND b.version = :version")
    int updateBookWithVersion(@Param("title") String title,
                              @Param("author") String author,
                              @Param("isbn") String isbn,
                              @Param("publisher") String publisher,
                              @Param("lastModifiedTime") LocalDateTime lastModifiedTime,
                              @Param("bookId") String bookId,
                              @Param("version") Long version);

}