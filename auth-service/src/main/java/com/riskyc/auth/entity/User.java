package com.riskyc.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * A user identifies with a phone number, an email address, or both — OTP
 * verification accepts either channel (see AuthController), so neither
 * column can be required at the database level. Postgres treats each NULL
 * as distinct under a unique constraint, so multiple users may have a NULL
 * phone_number or a NULL email without conflict.
 */
@Entity
@Table(name = "app_user", uniqueConstraints = {
        @UniqueConstraint(columnNames = "phone_number"),
        @UniqueConstraint(columnNames = "email")
})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "email")
    private String email;

    @Column(name = "display_name")
    private String displayName;

    // The MinIO object key for this user's avatar (media-service owns actual
    // storage/URLs) — not a URL, since presigned URLs expire; other clients
    // resolve a fresh download URL from this key when they need to render it.
    @Column(name = "avatar_object_key")
    private String avatarObjectKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected User() {
        // JPA
    }

    public static User withPhoneNumber(String phoneNumber, String displayName) {
        User user = new User();
        user.phoneNumber = phoneNumber;
        user.displayName = displayName;
        return user;
    }

    public static User withEmail(String email, String displayName) {
        User user = new User();
        user.email = email;
        user.displayName = displayName;
        return user;
    }

    public UUID getId() {
        return id;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    // Identifiers are otherwise immutable post-creation (only the withX
    // factories set them) — these exist solely for UserController's
    // change-identifier flow, which only calls them after the NEW value has
    // already passed OTP verification.
    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAvatarObjectKey() {
        return avatarObjectKey;
    }

    public void setAvatarObjectKey(String avatarObjectKey) {
        this.avatarObjectKey = avatarObjectKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
