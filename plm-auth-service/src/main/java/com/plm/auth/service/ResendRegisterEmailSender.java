package com.plm.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.auth.config.AuthEmailVerificationProperties;
import com.plm.auth.config.AuthInvitationProperties;
import com.plm.auth.exception.AuthBusinessException;
import com.plm.auth.util.AuthNormalizer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ResendRegisterEmailSender implements RegisterEmailSender {
    private final AuthEmailVerificationProperties properties;
    private final AuthInvitationProperties invitationProperties;
    private final RegisterEmailTemplateRenderer templateRenderer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ResendRegisterEmailSender(AuthEmailVerificationProperties properties,
                                     AuthInvitationProperties invitationProperties,
                                     RegisterEmailTemplateRenderer templateRenderer,
                                     ObjectMapper objectMapper) {
        this.properties = properties;
        this.invitationProperties = invitationProperties;
        this.templateRenderer = templateRenderer;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public void sendRegisterVerificationEmail(String email, String verificationCode, OffsetDateTime expiresAt) {
        sendEmail(
                email,
                properties.getSubject(),
                templateRenderer.render(email, verificationCode, expiresAt),
                templateRenderer.renderText(verificationCode, expiresAt),
                "EMAIL_VERIFICATION_DISABLED",
                "EMAIL_VERIFICATION_NOT_CONFIGURED",
                "EMAIL_VERIFICATION_SEND_FAILED",
                "email verification is disabled",
                "resend email sender is not configured",
                "failed to send verification email"
        );
    }

    @Override
    public void sendWorkspaceInvitationEmail(String email,
                                             String workspaceName,
                                             String inviterDisplayName,
                                             String acceptUrl,
                                             OffsetDateTime expiresAt) {
        sendEmail(
                email,
                invitationProperties.getEmailSubject() + " - " + workspaceName,
                templateRenderer.renderWorkspaceInvitationEmail(workspaceName, inviterDisplayName, acceptUrl, expiresAt),
                templateRenderer.renderWorkspaceInvitationEmailText(workspaceName, inviterDisplayName, acceptUrl, expiresAt),
                "WORKSPACE_INVITATION_EMAIL_DISABLED",
                "WORKSPACE_INVITATION_EMAIL_NOT_CONFIGURED",
                "WORKSPACE_INVITATION_EMAIL_SEND_FAILED",
                "workspace invitation email sending is disabled",
                "resend email sender is not configured",
                "failed to send workspace invitation email"
        );
    }

    @Override
    public void sendTestEmail(String email, OffsetDateTime sentAt) {
        sendEmail(
                email,
                "PLM Cloud Email Delivery Test",
                templateRenderer.renderTestEmail(email, sentAt),
                templateRenderer.renderTestEmailText(email, sentAt),
                "EMAIL_TEST_SEND_DISABLED",
                "EMAIL_TEST_SEND_NOT_CONFIGURED",
                "EMAIL_TEST_SEND_FAILED",
                "email test sending is disabled",
                "resend email sender is not configured",
                "failed to send test email"
        );
    }

    private void sendEmail(String email,
                           String subject,
                           String html,
                           String text,
                           String disabledCode,
                           String notConfiguredCode,
                           String sendFailedCode,
                           String disabledMessage,
                           String notConfiguredMessage,
                           String sendFailedMessage) {
        if (!properties.isEnabled()) {
            throw new AuthBusinessException(disabledCode, HttpStatus.SERVICE_UNAVAILABLE, disabledMessage);
        }

        String apiKey = AuthNormalizer.trimToNull(properties.getApiKey());
        String fromEmail = AuthNormalizer.trimToNull(properties.getFromEmail());
        if (apiKey == null || fromEmail == null) {
            throw new AuthBusinessException(notConfiguredCode, HttpStatus.SERVICE_UNAVAILABLE, notConfiguredMessage);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", formatFrom(properties.getFromName(), fromEmail));
        payload.put("to", List.of(email));
        payload.put("subject", subject);
        payload.put("html", html);
        payload.put("text", text);

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getApiUrl()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(serializePayload(payload), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AuthBusinessException(sendFailedCode, HttpStatus.BAD_GATEWAY, sendFailedMessage);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AuthBusinessException(sendFailedCode, HttpStatus.BAD_GATEWAY, sendFailedMessage);
        } catch (IOException ex) {
            throw new AuthBusinessException(sendFailedCode, HttpStatus.BAD_GATEWAY, sendFailedMessage);
        }
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize resend request body", ex);
        }
    }

    private String formatFrom(String fromName, String fromEmail) {
        String trimmedName = AuthNormalizer.trimToNull(fromName);
        if (trimmedName == null) {
            return fromEmail;
        }
        return trimmedName + " <" + fromEmail + ">";
    }
}