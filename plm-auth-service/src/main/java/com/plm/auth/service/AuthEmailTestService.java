package com.plm.auth.service;

import com.plm.auth.config.AuthEmailVerificationProperties;
import com.plm.auth.exception.AuthBusinessException;
import com.plm.auth.util.AuthNormalizer;
import com.plm.common.api.dto.auth.AuthSendTestEmailRequestDto;
import com.plm.common.api.dto.auth.AuthSendTestEmailResponseDto;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class AuthEmailTestService {

    private static final String TEST_EMAIL_SUBJECT = "PLM Cloud Email Delivery Test";

    private final RegisterEmailSender registerEmailSender;
    private final Environment environment;
    private final AuthEmailVerificationProperties properties;

    public AuthEmailTestService(RegisterEmailSender registerEmailSender,
                                Environment environment,
                                AuthEmailVerificationProperties properties) {
        this.registerEmailSender = registerEmailSender;
        this.environment = environment;
        this.properties = properties;
    }

    public AuthSendTestEmailResponseDto sendTestEmail(AuthSendTestEmailRequestDto request) {
        if (!environment.acceptsProfiles(Profiles.of("dev"))) {
            throw new AuthBusinessException(
                    "EMAIL_TEST_ENDPOINT_DISABLED",
                    HttpStatus.FORBIDDEN,
                    "test email endpoint is only available in dev profile"
            );
        }

        String email = AuthNormalizer.normalizeEmail(request.getEmail());
        if (email == null) {
            throw new IllegalArgumentException("email is required");
        }

        OffsetDateTime sentAt = OffsetDateTime.now();
        registerEmailSender.sendTestEmail(email, sentAt);

        AuthSendTestEmailResponseDto response = new AuthSendTestEmailResponseDto();
        response.setEmail(email);
        response.setFromEmail(AuthNormalizer.trimToNull(properties.getFromEmail()));
        response.setSubject(TEST_EMAIL_SUBJECT);
        response.setSentAt(sentAt);
        return response;
    }
}