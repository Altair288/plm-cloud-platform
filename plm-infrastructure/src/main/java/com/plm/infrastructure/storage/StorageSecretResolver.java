package com.plm.infrastructure.storage;

import org.springframework.stereotype.Component;

@Component
public class StorageSecretResolver {
    public StorageCredentials resolve(String secretRef) {
        String normalized = normalize(secretRef);
        if (normalized.startsWith("env:")) {
            return resolveFromEnvironment(normalized.substring(4));
        }
        if (normalized.startsWith("inline:")) {
            return parsePair(normalized.substring(7));
        }
        throw new IllegalArgumentException("unsupported secretRef scheme, expected env: or inline:");
    }

    private StorageCredentials resolveFromEnvironment(String environmentKey) {
        String key = normalize(environmentKey);
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("environment secret is blank: " + key);
        }
        return parsePair(value.trim());
    }

    private StorageCredentials parsePair(String rawPair) {
        int separatorIndex = rawPair.indexOf(':');
        if (separatorIndex <= 0 || separatorIndex >= rawPair.length() - 1) {
            throw new IllegalArgumentException("secretRef credentials must use accessKey:secretKey format");
        }
        String accessKey = rawPair.substring(0, separatorIndex).trim();
        String secretKey = rawPair.substring(separatorIndex + 1).trim();
        if (accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalArgumentException("secretRef credentials must not be blank");
        }
        return new StorageCredentials(accessKey, secretKey);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("secretRef is required");
        }
        return value.trim();
    }
}