package com.riskyc.auth.config;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Race-proof backstop for the app_user unique constraints on phone_number/
 * email. The identifier-uniqueness pre-checks (UserRepository.existsBy*) catch
 * the common case with a clean 409 before any OTP is sent, but two concurrent
 * requests for the same brand-new identifier can both pass that check and
 * only the DB constraint itself stops the second save() — without this
 * handler that surfaced as a raw, undiagnosable 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ConflictError(String reason) {
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ConflictError> onUniqueConstraint(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ConflictError("IDENTIFIER_ALREADY_IN_USE"));
    }
}
