package com.plm.auth.util;

import java.util.Locale;
import java.util.regex.Pattern;

public final class AuthNormalizer {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9._-]{4,64}$");
    private static final Pattern WORKSPACE_CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{2,63}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern VERIFICATION_CODE_PATTERN = Pattern.compile("^\\d{6}$");

    private AuthNormalizer() {
    }

    public static String normalizeUsername(String username) {
        String value = trimToNull(username);
        if (value == null) {
            return null;
        }
        value = value.toLowerCase(Locale.ROOT);
        if (!USERNAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("username must match ^[a-z0-9._-]{4,64}$");
        }
        return value;
    }

    public static String normalizeIdentifier(String identifier) {
        String value = trimToNull(identifier);
        if (value == null) {
            return null;
        }
        String compact = value.replace(" ", "");
        if (compact.chars().allMatch(ch -> Character.isDigit(ch) || ch == '+' || ch == '-')) {
            return compact.replace("-", "");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    public static String normalizeEmail(String email) {
        String value = trimToNull(email);
        if (value == null) {
            return null;
        }
        value = value.toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("email is invalid");
        }
        return value;
    }

    public static String normalizePhone(String phone) {
        String value = trimToNull(phone);
        if (value == null) {
            return null;
        }
        return value.replace(" ", "").replace("-", "");
    }

    public static String normalizeWorkspaceCode(String workspaceCode) {
        String value = trimToNull(workspaceCode);
        if (value == null) {
            return null;
        }
        value = value.toLowerCase(Locale.ROOT);
        if (!WORKSPACE_CODE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("workspaceCode must match ^[a-z][a-z0-9_-]{2,63}$");
        }
        return value;
    }

    public static String normalizeVerificationCode(String verificationCode) {
        String value = trimToNull(verificationCode);
        if (value == null) {
            return null;
        }
        value = value.replace(" ", "");
        if (!VERIFICATION_CODE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("emailVerificationCode must be 6 digits");
        }
        return value;
    }

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}