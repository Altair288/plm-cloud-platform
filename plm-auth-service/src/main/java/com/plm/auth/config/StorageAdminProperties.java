package com.plm.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "plm.storage.admin")
public class StorageAdminProperties {
    private long uploadUrlExpireSeconds = 900;
    private long downloadUrlExpireSeconds = 900;
    private long testSessionExpireSeconds = 1800;

    public long getUploadUrlExpireSeconds() {
        return uploadUrlExpireSeconds;
    }

    public void setUploadUrlExpireSeconds(long uploadUrlExpireSeconds) {
        this.uploadUrlExpireSeconds = uploadUrlExpireSeconds;
    }

    public long getDownloadUrlExpireSeconds() {
        return downloadUrlExpireSeconds;
    }

    public void setDownloadUrlExpireSeconds(long downloadUrlExpireSeconds) {
        this.downloadUrlExpireSeconds = downloadUrlExpireSeconds;
    }

    public long getTestSessionExpireSeconds() {
        return testSessionExpireSeconds;
    }

    public void setTestSessionExpireSeconds(long testSessionExpireSeconds) {
        this.testSessionExpireSeconds = testSessionExpireSeconds;
    }
}