package com.plm.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.auth.config.AuthEmailVerificationProperties;
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
    private final RegisterEmailTemplateRenderer templateRenderer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ResendRegisterEmailSender(AuthEmailVerificationProperties properties,
                                     RegisterEmailTemplateRenderer templateRenderer,
                                     ObjectMapper objectMapper) {
        this.properties = properties;
        this.templateRenderer = templateRenderer;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public void sendRegisterVerificationEmail(String email, String verificationCode, OffsetDateTime expiresAt) {
        if (!properties.isEnabled()) {
            throw new AuthBusinessException("EMAIL_VERIFICATION_DISABLED", HttpStatus.SERVICE_UNAVAILABLE, "email verification is disabled");
        }

        String apiKey = AuthNormalizer.trimToNull(properties.getApiKey());
        String fromEmail = AuthNormalizer.trimToNull(properties.getFromEmail());
        if (apiKey == null || fromEmail == null) {
            throw new AuthBusinessException("EMAIL_VERIFICATION_NOT_CONFIGURED", HttpStatus.SERVICE_UNAVAILABLE, "resend email sender is not configured");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", formatFrom(properties.getFromName(), fromEmail));
        payload.put("to", List.of(email));
        payload.put("subject", properties.getSubject());
        payload.put("html", templateRenderer.render(email, verificationCode, expiresAt));
        payload.put("text", templateRenderer.renderText(verificationCode, expiresAt));

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getApiUrl()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(serializePayload(payload), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AuthBusinessException("EMAIL_VERIFICATION_SEND_FAILED", HttpStatus.BAD_GATEWAY, "failed to send verification email");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AuthBusinessException("EMAIL_VERIFICATION_SEND_FAILED", HttpStatus.BAD_GATEWAY, "failed to send verification email");
        } catch (IOException ex) {
            throw new AuthBusinessException("EMAIL_VERIFICATION_SEND_FAILED", HttpStatus.BAD_GATEWAY, "failed to send verification email");
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