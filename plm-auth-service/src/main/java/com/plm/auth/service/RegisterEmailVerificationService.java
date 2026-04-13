package com.plm.auth.service;

import com.plm.auth.config.AuthEmailVerificationProperties;
import com.plm.auth.exception.AuthBusinessException;
import com.plm.auth.support.AuthDomainConstants;
import com.plm.auth.util.AuthNormalizer;
import com.plm.common.api.dto.auth.AuthSendRegisterEmailCodeRequestDto;
import com.plm.common.api.dto.auth.AuthSendRegisterEmailCodeResponseDto;
import com.plm.common.domain.auth.EmailVerificationCode;
import com.plm.infrastructure.repository.auth.EmailVerificationCodeRepository;
import com.plm.infrastructure.repository.auth.UserAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class RegisterEmailVerificationService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final EmailVerificationCodeRepository emailVerificationCodeRepository;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final RegisterEmailSender registerEmailSender;
    private final AuthEmailVerificationProperties properties;

    public RegisterEmailVerificationService(EmailVerificationCodeRepository emailVerificationCodeRepository,
                                            UserAccountRepository userAccountRepository,
                                            PasswordEncoder passwordEncoder,
                                            RegisterEmailSender registerEmailSender,
                                            AuthEmailVerificationProperties properties) {
        this.emailVerificationCodeRepository = emailVerificationCodeRepository;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.registerEmailSender = registerEmailSender;
        this.properties = properties;
    }

    @Transactional
    public AuthSendRegisterEmailCodeResponseDto sendCode(AuthSendRegisterEmailCodeRequestDto request) {
        String email = AuthNormalizer.normalizeEmail(request.getEmail());
        if (email == null) {
            throw new IllegalArgumentException("email is required");
        }
        if (userAccountRepository.existsByEmailIgnoreCase(email)) {
            throw new AuthBusinessException("EMAIL_ALREADY_EXISTS", HttpStatus.CONFLICT, "email already exists");
        }

        OffsetDateTime now = OffsetDateTime.now();
        EmailVerificationCode latestPending = emailVerificationCodeRepository
                .findTopByTargetEmailAndVerificationPurposeAndCodeStatusOrderByCreatedAtDesc(
                        email,
                        AuthDomainConstants.VERIFICATION_PURPOSE_REGISTER,
                        AuthDomainConstants.VERIFICATION_CODE_STATUS_PENDING
                )
                .orElse(null);

        if (latestPending != null) {
            if (!latestPending.getExpiresAt().isAfter(now)) {
                latestPending.setCodeStatus(AuthDomainConstants.VERIFICATION_CODE_STATUS_EXPIRED);
                latestPending.setUpdatedBy("AUTH_REGISTER_EMAIL_CODE_EXPIRE");
            } else if (latestPending.getCreatedAt().plusSeconds(properties.getResendCooldownSeconds()).isAfter(now)) {
                throw new AuthBusinessException(
                        "EMAIL_VERIFICATION_SEND_TOO_FREQUENT",
                        HttpStatus.TOO_MANY_REQUESTS,
                        "email verification code was sent too frequently"
                );
            } else {
                latestPending.setCodeStatus(AuthDomainConstants.VERIFICATION_CODE_STATUS_SUPERSEDED);
                latestPending.setUpdatedBy("AUTH_REGISTER_EMAIL_CODE_SUPERSEDE");
            }
            emailVerificationCodeRepository.save(latestPending);
        }

        String verificationCode = generateVerificationCode();
        EmailVerificationCode record = new EmailVerificationCode();
        record.setTargetEmail(email);
        record.setVerificationPurpose(AuthDomainConstants.VERIFICATION_PURPOSE_REGISTER);
        record.setCodeHash(passwordEncoder.encode(verificationCode));
        record.setCodeStatus(AuthDomainConstants.VERIFICATION_CODE_STATUS_PENDING);
        record.setExpiresAt(now.plusSeconds(properties.getExpireInSeconds()));
        record.setCreatedBy("AUTH_REGISTER_EMAIL_CODE_SEND");
        emailVerificationCodeRepository.save(record);

        registerEmailSender.sendRegisterVerificationEmail(email, verificationCode, record.getExpiresAt());

        AuthSendRegisterEmailCodeResponseDto response = new AuthSendRegisterEmailCodeResponseDto();
        response.setEmail(email);
        response.setMaskedEmail(maskEmail(email));
        response.setExpiresAt(record.getExpiresAt());
        response.setExpireInSeconds(properties.getExpireInSeconds());
        response.setResendCooldownSeconds(properties.getResendCooldownSeconds());
        return response;
    }

    @Transactional
    public void verifyRegisterCode(String email, String rawCode, String consumedBy, UUID consumedByUserId) {
        String verificationCode = AuthNormalizer.normalizeVerificationCode(rawCode);
        if (email == null) {
            throw new IllegalArgumentException("email is required");
        }
        if (verificationCode == null) {
            throw new IllegalArgumentException("emailVerificationCode is required");
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<EmailVerificationCode> pendingCodes = emailVerificationCodeRepository
                .findAllByTargetEmailAndVerificationPurposeAndCodeStatusOrderByCreatedAtDesc(
                        email,
                        AuthDomainConstants.VERIFICATION_PURPOSE_REGISTER,
                        AuthDomainConstants.VERIFICATION_CODE_STATUS_PENDING
                );

        EmailVerificationCode activeCode = null;
        for (EmailVerificationCode pendingCode : pendingCodes) {
            if (pendingCode.getExpiresAt().isAfter(now)) {
                activeCode = pendingCode;
                break;
            }
            pendingCode.setCodeStatus(AuthDomainConstants.VERIFICATION_CODE_STATUS_EXPIRED);
            pendingCode.setUpdatedBy(consumedBy);
            emailVerificationCodeRepository.save(pendingCode);
        }

        if (activeCode == null) {
            throw new AuthBusinessException(
                    "EMAIL_VERIFICATION_CODE_EXPIRED",
                    HttpStatus.BAD_REQUEST,
                    "email verification code has expired"
            );
        }
        if (!passwordEncoder.matches(verificationCode, activeCode.getCodeHash())) {
            throw new AuthBusinessException(
                    "EMAIL_VERIFICATION_CODE_INVALID",
                    HttpStatus.BAD_REQUEST,
                    "email verification code is invalid"
            );
        }

        activeCode.setCodeStatus(AuthDomainConstants.VERIFICATION_CODE_STATUS_USED);
        activeCode.setConsumedAt(now);
        activeCode.setConsumedByUserId(consumedByUserId);
        activeCode.setUpdatedBy(consumedBy);
        emailVerificationCodeRepository.save(activeCode);
    }

    private String generateVerificationCode() {
        int value = SECURE_RANDOM.nextInt(900000) + 100000;
        return Integer.toString(value);
    }

    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return "***" + email.substring(Math.max(0, atIndex));
        }
        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (local.length() <= 2) {
            return local.charAt(0) + "***" + domain;
        }
        return local.substring(0, 2) + "***" + domain;
    }
}