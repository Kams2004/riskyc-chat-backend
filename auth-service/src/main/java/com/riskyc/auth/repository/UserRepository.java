package com.riskyc.auth.repository;

import com.riskyc.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByPhoneNumber(String phoneNumber);

    Optional<User> findByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);

    boolean existsByEmail(String email);

    /** Bulk lookups feeding contacts-only discovery's POST /api/users/match-contacts. */
    List<User> findByPhoneNumberIn(Collection<String> phoneNumbers);

    List<User> findByEmailIn(Collection<String> emails);

    @Query("""
            SELECT u FROM User u
            WHERE u.id <> :excludeId
              AND (
                :query = '' OR
                LOWER(u.displayName) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(u.phoneNumber) LIKE LOWER(CONCAT('%', :query, '%'))
              )
            ORDER BY u.displayName ASC NULLS LAST, u.createdAt DESC
            """)
    List<User> search(@Param("excludeId") UUID excludeId, @Param("query") String query);
}
