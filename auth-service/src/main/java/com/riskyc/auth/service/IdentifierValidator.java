package com.riskyc.auth.service;

import java.util.regex.Pattern;

/**
 * Shared "exactly one of phone/email, correctly formatted" validation used
 * by both signup/login (AuthController) and the change-identifier flow
 * (UserController) — kept in one place so the two regexes can't drift.
 */
public final class IdentifierValidator {

    // Deliberately permissive (this isn't validating deliverability, just
    // rejecting obvious garbage before it reaches an SMTP call or gets
    // stored) — a real provider will reject anything it can't deliver anyway.
    public static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    // Requires a leading '+' (E.164) — Bird's SMS API rejects anything else
    // outright, so a number that would just bounce off the SMS provider is
    // caught here instead, with a clear 400, rather than surfacing as an
    // opaque failure from SmsOtpSender.
    public static final Pattern PHONE_PATTERN = Pattern.compile("^\\+[0-9]{7,15}$");

    private IdentifierValidator() {
    }

    public static String identifierOf(String phoneNumber, String email) {
        boolean hasPhone = phoneNumber != null && !phoneNumber.isBlank();
        boolean hasEmail = email != null && !email.isBlank();
        if (hasPhone == hasEmail) {
            throw new IllegalArgumentException("Provide exactly one of phoneNumber or email");
        }
        if (hasEmail && !EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Malformed email");
        }
        if (hasPhone && !PHONE_PATTERN.matcher(phoneNumber).matches()) {
            throw new IllegalArgumentException("Malformed phone number");
        }
        return hasPhone ? phoneNumber : email;
    }
}
