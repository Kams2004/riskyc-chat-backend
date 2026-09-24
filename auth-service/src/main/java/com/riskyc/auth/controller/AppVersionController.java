package com.riskyc.auth.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the mobile app prompt the user to update instead of relying solely
 * on the Play Store's own auto-update (which a user can disable, or which
 * can lag) — checked once at app launch. Unauthenticated: this needs to
 * work before anyone has signed in, and it reveals nothing sensitive.
 * {@code latestVersion}/{@code minSupportedVersion} are blank until
 * deliberately set (see application.yml), in which case the mobile client
 * treats this as "no check configured" and never prompts.
 */
@RestController
@RequestMapping("/api/app-version")
public class AppVersionController {

    @Value("${riskyc.app-version.latest-android:}")
    private String latestAndroidVersion;

    @Value("${riskyc.app-version.min-supported-android:}")
    private String minSupportedAndroidVersion;

    public record AppVersionInfo(String latestVersion, String minSupportedVersion, String playStoreUrl) {
    }

    @GetMapping
    public AppVersionInfo get() {
        return new AppVersionInfo(
                blankToNull(latestAndroidVersion),
                blankToNull(minSupportedAndroidVersion),
                "https://play.google.com/store/apps/details?id=com.riskyc.chat"
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
