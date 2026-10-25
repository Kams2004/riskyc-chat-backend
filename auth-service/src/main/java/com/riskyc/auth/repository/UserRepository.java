package com.riskyc.auth.repository;

import com.riskyc.auth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * Admin-service's user list — paginated, matches by exact id too
     * (string-compared, not cast — Postgres' UUID cast throws instead of
     * just not-matching on a non-UUID query string, which would turn every
     * plain-text search into a 500) so Reports/Blocked pages can deep-link
     * straight to an account by id, not just by name/email/phone.
     *
     * There is deliberately no regular-user equivalent: a signed-in client
     * can only ever find someone else by their exact phone number
     * (UserController#lookupByPhone) or already-matched device contacts
     * (match-contacts above) — never by browsing/searching the whole user
     * base by partial name, same privacy model as WhatsApp. A prior GET
     * /api/users?q= did exactly that and was removed outright.
     */
    @Query("""
            SELECT u FROM User u
            WHERE (
                :query = '' OR
                LOWER(u.displayName) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) OR
                LOWER(u.phoneNumber) LIKE LOWER(CONCAT('%', :query, '%')) OR
                CAST(u.id AS string) = :query
              )
            """)
    Page<User> adminSearch(@Param("query") String query, Pageable pageable);

    long countBySuspendedTrue();
}
