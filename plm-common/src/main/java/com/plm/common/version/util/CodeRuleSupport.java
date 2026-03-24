package com.plm.common.version.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

public final class CodeRuleSupport {

    public static final String HASH_ALGORITHM_MD5 = "MD5";

    public static final int CATEGORY_SEQUENCE_WIDTH = 4;
    public static final int ATTRIBUTE_SEQUENCE_WIDTH = 6;
    public static final int LOV_SEQUENCE_WIDTH = 2;
    public static final int INSTANCE_SEQUENCE_WIDTH = 4;
    public static final int DEFAULT_SEQUENCE_WIDTH = 5;

    private static final Map<String, Integer> SEQUENCE_WIDTHS = Map.of(
            "CATEGORY", CATEGORY_SEQUENCE_WIDTH,
            "ATTRIBUTE", ATTRIBUTE_SEQUENCE_WIDTH,
            "LOV", LOV_SEQUENCE_WIDTH,
            "INSTANCE", INSTANCE_SEQUENCE_WIDTH
    );

    private CodeRuleSupport() {
    }

    public static int sequenceWidth(String ruleCode) {
        if (ruleCode == null) {
            return DEFAULT_SEQUENCE_WIDTH;
        }
        return SEQUENCE_WIDTHS.getOrDefault(ruleCode.trim().toUpperCase(), DEFAULT_SEQUENCE_WIDTH);
    }

    public static String md5Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM_MD5);
            byte[] encoded = digest.digest(String.valueOf(input).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(encoded.length * 2);
            for (byte item : encoded) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(HASH_ALGORITHM_MD5 + " not available", ex);
        }
    }
}