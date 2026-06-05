package com.plm.auth.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

@Component
public class WorkspaceBucketNamingPolicy {
    public String generateBucketName(String bucketPrefix, UUID workspaceId) {
        if (bucketPrefix == null || bucketPrefix.isBlank()) {
            throw new IllegalArgumentException("bucketPrefix is required");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("workspaceId is required");
        }
        String normalizedPrefix = normalizeBucketPrefix(bucketPrefix);
        String workspaceSuffix = workspaceId.toString().replace("-", "").toLowerCase(Locale.ROOT);
        return normalizedPrefix + "-ws-" + workspaceSuffix;
    }

    private String normalizeBucketPrefix(String bucketPrefix) {
        String normalized = bucketPrefix.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-+", "-");
        while (normalized.startsWith("-")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("-")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.length() < 3 || normalized.length() > 23) {
            throw new IllegalArgumentException("bucketPrefix length must be between 3 and 23 after normalization");
        }
        return normalized;
    }
}