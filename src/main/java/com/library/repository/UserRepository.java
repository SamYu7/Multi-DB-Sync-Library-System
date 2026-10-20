package com.library.repository;

import com.library.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByUserId(String userId);

    List<User> findByLibraryId(String libraryId);

    List<User> findByRole(User.UserRole role);

    List<User> findByStatus(User.UserStatus status);

    boolean existsByUserId(String userId);

    boolean existsByEmail(String email);

    @Query("SELECT u FROM User u WHERE u.name LIKE %:name%")
    List<User> findByNameContaining(@Param("name") String name);

    @Query("SELECT COUNT(u) as readerCount FROM User u WHERE u.libraryId = :libraryId AND u.role = 'READER'")
    Long countReadersByLibrary(@Param("libraryId") String libraryId);

    @Query("SELECT u.libraryId, COUNT(u) as userCount FROM User u GROUP BY u.libraryId")
    List<Object[]> countUsersByLibrary();
}