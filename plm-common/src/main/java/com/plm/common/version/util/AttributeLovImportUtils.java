package com.plm.common.version.util;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Locale;

/** Lightweight utilities without external JSON libs to keep dependency surface minimal. */
public final class AttributeLovImportUtils {
    private AttributeLovImportUtils() {}

    public static String slug(String raw) {
        if (raw == null) return "";
        String normalized = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC);
        if (normalized.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean lastDash = false;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toLowerCase(ch));
                lastDash = false;
            } else if (Character.isWhitespace(ch) || isSeparator(ch)) {
                if (!lastDash && sb.length() > 0) { sb.append('-'); lastDash = true; }
            } else {
                // other symbol mapped to dash
                if (!lastDash && sb.length() > 0) { sb.append('-'); lastDash = true; }
            }
        }
        String s = sb.toString().replaceAll("-+", "-");
        s = s.replaceAll("^-+|-+$", "");
        if (s.isEmpty()) {
            String h = sha256Hex(normalized).substring(0, 8);
            s = "attr_" + h;
        }
        return s;
    }

    private static boolean isSeparator(char ch) {
        return ch == '_' || ch == '-' || ch == '/' || ch == '.' || ch == ':' || ch == ';' || ch == '·';
    }

    public static String generateLovKey(String categoryCode, String attributeDisplayName) {
        String cat = slug(categoryCode == null? "cat" : categoryCode).replace('-', '_');
        String attr = slug(attributeDisplayName == null? "attr" : attributeDisplayName).replace('-', '_');
        String base = cat + "_" + attr + "__lov"; // 双下划线保持与旧格式类似
        if (base.length() <= 120) return base;
        String hash = sha256Hex(base).substring(0, 8);
        return (base.substring(0, Math.min(100, base.length())) + "_" + hash).replaceAll("_+", "_");
    }

    public static BigDecimal parseNumeric(String value) {
        if (value == null) return null;
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (v.isEmpty()) return null;
        if (!v.matches("^[+-]?((\\d+\\.?\\d*)|(\\d*\\.?\\d+))(E[+-]?\\d+)?$")) {
            return null;
        }
        try { return new BigDecimal(v); } catch (NumberFormatException e) { return null; }
    }

    /** Simple structural-insensitive hash: remove whitespace characters outside quotes then sha256. */
    public static String jsonHash(String json) {
        if (json == null) return null;
        String compact = json.replaceAll("\\s+", "");
        return sha256Hex(compact).substring(0, 32);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
