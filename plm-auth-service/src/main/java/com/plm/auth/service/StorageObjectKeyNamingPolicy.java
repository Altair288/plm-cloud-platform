package com.plm.auth.service;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

@Component
public class StorageObjectKeyNamingPolicy {
    public String generateObjectKey(String bizType, UUID objectId, OffsetDateTime now) {
        if (objectId == null) {
            throw new IllegalArgumentException("objectId is required");
        }
        OffsetDateTime effectiveNow = now == null ? OffsetDateTime.now() : now;
        String normalizedBizType = normalizeBizType(bizType);
        return "objects/" + normalizedBizType + "/"
                + effectiveNow.getYear() + "/"
                + twoDigits(effectiveNow.getMonthValue()) + "/"
                + twoDigits(effectiveNow.getDayOfMonth()) + "/"
                + objectId;
    }

    private String normalizeBizType(String bizType) {
        if (bizType == null || bizType.isBlank()) {
            throw new IllegalArgumentException("bizType is required");
        }
        String normalized = bizType.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-+", "-");
        while (normalized.startsWith("-")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("-")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("bizType is required");
        }
        return normalized;
    }

    private String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }
}