package com.plm.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "plm.storage.governance")
public class StorageBucketGovernanceProperties {
    private boolean enforcePrivateBucketPolicy = true;
    private int abortIncompleteMultipartAfterDays = 1;
    private int testObjectExpireDays = 1;
    private String testObjectPrefix = "__cluster_test__/";
    private boolean enableServerSideEncryption = false;

    public boolean isEnforcePrivateBucketPolicy() {
        return enforcePrivateBucketPolicy;
    }

    public void setEnforcePrivateBucketPolicy(boolean enforcePrivateBucketPolicy) {
        this.enforcePrivateBucketPolicy = enforcePrivateBucketPolicy;
    }

    public int getAbortIncompleteMultipartAfterDays() {
        return abortIncompleteMultipartAfterDays;
    }

    public void setAbortIncompleteMultipartAfterDays(int abortIncompleteMultipartAfterDays) {
        this.abortIncompleteMultipartAfterDays = abortIncompleteMultipartAfterDays;
    }

    public int getTestObjectExpireDays() {
        return testObjectExpireDays;
    }

    public void setTestObjectExpireDays(int testObjectExpireDays) {
        this.testObjectExpireDays = testObjectExpireDays;
    }

    public String getTestObjectPrefix() {
        return testObjectPrefix;
    }

    public void setTestObjectPrefix(String testObjectPrefix) {
        this.testObjectPrefix = testObjectPrefix;
    }

    public boolean isEnableServerSideEncryption() {
        return enableServerSideEncryption;
    }

    public void setEnableServerSideEncryption(boolean enableServerSideEncryption) {
        this.enableServerSideEncryption = enableServerSideEncryption;
    }
}