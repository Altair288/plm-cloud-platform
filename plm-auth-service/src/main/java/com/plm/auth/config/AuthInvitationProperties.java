package com.plm.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "plm.auth.invitation")
public class AuthInvitationProperties {
    private String emailSubject = "PLM Cloud Workspace Invitation";
    private long emailExpireInSeconds = 604800;
    private int linkExpireInHours = 168;
    private int maxBatchSize = 50;
    private String emailAcceptUrlTemplate = "http://localhost:8080/invite/email?token=%s";
    private String linkAcceptUrlTemplate = "http://localhost:8080/invite/link?token=%s";

    public String getEmailSubject() {
        return emailSubject;
    }

    public void setEmailSubject(String emailSubject) {
        this.emailSubject = emailSubject;
    }

    public long getEmailExpireInSeconds() {
        return emailExpireInSeconds;
    }

    public void setEmailExpireInSeconds(long emailExpireInSeconds) {
        this.emailExpireInSeconds = emailExpireInSeconds;
    }

    public int getLinkExpireInHours() {
        return linkExpireInHours;
    }

    public void setLinkExpireInHours(int linkExpireInHours) {
        this.linkExpireInHours = linkExpireInHours;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public String getEmailAcceptUrlTemplate() {
        return emailAcceptUrlTemplate;
    }

    public void setEmailAcceptUrlTemplate(String emailAcceptUrlTemplate) {
        this.emailAcceptUrlTemplate = emailAcceptUrlTemplate;
    }

    public String getLinkAcceptUrlTemplate() {
        return linkAcceptUrlTemplate;
    }

    public void setLinkAcceptUrlTemplate(String linkAcceptUrlTemplate) {
        this.linkAcceptUrlTemplate = linkAcceptUrlTemplate;
    }
}