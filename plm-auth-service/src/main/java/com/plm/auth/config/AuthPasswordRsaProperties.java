package com.plm.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "plm.auth.password-rsa")
public class AuthPasswordRsaProperties {
    private boolean enabled = true;
    private boolean allowPlaintextFallback = false;
    private String keyId = "auth-password-rsa-v1";
    private String redisKeyPrefix = "plm:auth:password-rsa";
    private long redisTtlSeconds = 86400;
    private String transformation = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private int keySize = 2048;
    private String publicKeyPem;
    private String privateKeyPem;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowPlaintextFallback() {
        return allowPlaintextFallback;
    }

    public void setAllowPlaintextFallback(boolean allowPlaintextFallback) {
        this.allowPlaintextFallback = allowPlaintextFallback;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getRedisKeyPrefix() {
        return redisKeyPrefix;
    }

    public void setRedisKeyPrefix(String redisKeyPrefix) {
        this.redisKeyPrefix = redisKeyPrefix;
    }

    public long getRedisTtlSeconds() {
        return redisTtlSeconds;
    }

    public void setRedisTtlSeconds(long redisTtlSeconds) {
        this.redisTtlSeconds = redisTtlSeconds;
    }

    public String getTransformation() {
        return transformation;
    }

    public void setTransformation(String transformation) {
        this.transformation = transformation;
    }

    public int getKeySize() {
        return keySize;
    }

    public void setKeySize(int keySize) {
        this.keySize = keySize;
    }

    public String getPublicKeyPem() {
        return publicKeyPem;
    }

    public void setPublicKeyPem(String publicKeyPem) {
        this.publicKeyPem = publicKeyPem;
    }

    public String getPrivateKeyPem() {
        return privateKeyPem;
    }

    public void setPrivateKeyPem(String privateKeyPem) {
        this.privateKeyPem = privateKeyPem;
    }
}