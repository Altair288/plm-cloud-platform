package com.plm.auth.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RegisterEmailTemplateRenderer {
    private static final String VERIFICATION_TEMPLATE_PATH = "templates/email-checkcode-preview.html";
    private static final String INVITATION_TEMPLATE_PATH = "templates/email-invite-preview.html";
  private static final String TEST_TEMPLATE_PATH = "templates/email-test-preview.html";

    private final String verificationHtmlTemplate;
    private final String invitationHtmlTemplate;
  private final String testHtmlTemplate;

    public RegisterEmailTemplateRenderer() {
        this.verificationHtmlTemplate = loadTemplate(VERIFICATION_TEMPLATE_PATH);
        this.invitationHtmlTemplate = loadTemplate(INVITATION_TEMPLATE_PATH);
    this.testHtmlTemplate = loadTemplate(TEST_TEMPLATE_PATH);
    }

    public String render(String email, String verificationCode, OffsetDateTime expiresAt) {
        long minutes = Math.max(1, ChronoUnit.MINUTES.between(OffsetDateTime.now(), expiresAt));
        String safeEmail = escapeHtml(email);
        String safeCode = escapeHtml(verificationCode);
        return replacePlaceholder(
            replacePlaceholder(
                replacePlaceholder(verificationHtmlTemplate, "%s", safeEmail),
                "%s",
                safeCode),
            "%d",
            String.valueOf(minutes));
    }

    public String renderText(String verificationCode, OffsetDateTime expiresAt) {
        long minutes = Math.max(1, ChronoUnit.MINUTES.between(OffsetDateTime.now(), expiresAt));
        return "Your PLM Cloud verification code is " + verificationCode + ". It will expire in " + minutes + " minutes.";
    }

    public String renderWorkspaceInvitationEmail(String workspaceName,
                                                 String inviterDisplayName,
                                                 String acceptUrl,
                                                 OffsetDateTime expiresAt) {
        long hours = Math.max(1, ChronoUnit.HOURS.between(OffsetDateTime.now(), expiresAt));
        String safeWorkspaceName = escapeHtml(workspaceName);
        String safeInviterDisplayName = escapeHtml(inviterDisplayName == null ? "PLM Cloud" : inviterDisplayName);
        String safeAcceptUrl = escapeHtml(acceptUrl);
        return replacePlaceholder(
            replacePlaceholder(
                replacePlaceholder(
                    replacePlaceholder(invitationHtmlTemplate, "%s", safeInviterDisplayName),
                    "%s",
                    safeWorkspaceName),
                "%s",
                safeAcceptUrl),
            "%d",
            String.valueOf(hours));
    }

    public String renderWorkspaceInvitationEmailText(String workspaceName,
                                                     String inviterDisplayName,
                                                     String acceptUrl,
                                                     OffsetDateTime expiresAt) {
        long hours = Math.max(1, ChronoUnit.HOURS.between(OffsetDateTime.now(), expiresAt));
        return (inviterDisplayName == null ? "PLM Cloud" : inviterDisplayName)
                + " invited you to join workspace " + workspaceName
                + ". Open " + acceptUrl
                + " within " + hours + " hours.";
    }

    public String renderTestEmail(String email, OffsetDateTime sentAt) {
        String safeEmail = escapeHtml(email);
        String safeSentAt = escapeHtml(sentAt.toString());
        return replacePlaceholder(
                replacePlaceholder(testHtmlTemplate, "%s", safeEmail),
                "%s",
                safeSentAt);
    }

    public String renderTestEmailText(String email, OffsetDateTime sentAt) {
        return "PLM Cloud test email delivered to " + email + " at " + sentAt + ".";
    }

    private String loadTemplate(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to load email template: " + path, ex);
        }
    }

    private String replacePlaceholder(String template, String placeholder, String value) {
        return template.replaceFirst(Pattern.quote(placeholder), Matcher.quoteReplacement(value));
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
              .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}