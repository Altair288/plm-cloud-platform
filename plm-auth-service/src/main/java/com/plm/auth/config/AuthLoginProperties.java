package com.plm.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "plm.auth.login")
public class AuthLoginProperties {
    private long expireInSeconds = 43200;
    private long rememberExpireInSeconds = 2592000;

    public long getExpireInSeconds() {
        return expireInSeconds;
    }

    public void setExpireInSeconds(long expireInSeconds) {
        this.expireInSeconds = expireInSeconds;
    }

    public long getRememberExpireInSeconds() {
        return rememberExpireInSeconds;
    }

    public void setRememberExpireInSeconds(long rememberExpireInSeconds) {
        this.rememberExpireInSeconds = rememberExpireInSeconds;
    }
}