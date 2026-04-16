package com.plm.auth.service;

import com.plm.auth.exception.AuthBusinessException;
import com.plm.auth.support.AuthDomainConstants;
import com.plm.auth.util.AuthNormalizer;
import com.plm.common.api.dto.auth.AuthRegisterRequestDto;
import com.plm.common.api.dto.auth.AuthRegisterResponseDto;
import com.plm.common.domain.auth.UserAccount;
import com.plm.common.domain.auth.UserCredential;
import com.plm.infrastructure.repository.auth.UserAccountRepository;
import com.plm.infrastructure.repository.auth.UserCredentialRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class AuthRegistrationService {
    private final UserAccountRepository userAccountRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final RegisterEmailVerificationService registerEmailVerificationService;

    public AuthRegistrationService(UserAccountRepository userAccountRepository,
                                   UserCredentialRepository userCredentialRepository,
                                   PasswordEncoder passwordEncoder,
                                   RegisterEmailVerificationService registerEmailVerificationService) {
        this.userAccountRepository = userAccountRepository;
        this.userCredentialRepository = userCredentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.registerEmailVerificationService = registerEmailVerificationService;
    }

    @Transactional
    public AuthRegisterResponseDto register(AuthRegisterRequestDto request) {
        String username = AuthNormalizer.normalizeUsername(request.getUsername());
        String displayName = AuthNormalizer.trimToNull(request.getDisplayName());
        String password = request.getPassword();
        String confirmPassword = request.getConfirmPassword();
        String email = AuthNormalizer.normalizeEmail(request.getEmail());
        String emailVerificationCode = AuthNormalizer.normalizeVerificationCode(request.getEmailVerificationCode());
        String phone = AuthNormalizer.normalizePhone(request.getPhone());

        if (email == null) {
            throw new IllegalArgumentException("email is required");
        }
        if (displayName == null || displayName.length() > 128) {
            throw new IllegalArgumentException("displayName is required and must be <= 128 chars");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("password must be at least 8 chars");
        }
        if (!Objects.equals(password, confirmPassword)) {
            throw new IllegalArgumentException("confirmPassword does not match password");
        }
        if (userAccountRepository.existsByUsernameIgnoreCase(username)) {
            throw new AuthBusinessException("USERNAME_ALREADY_EXISTS", HttpStatus.CONFLICT, "username already exists");
        }
        if (email != null && userAccountRepository.existsByEmailIgnoreCase(email)) {
            throw new AuthBusinessException("EMAIL_ALREADY_EXISTS", HttpStatus.CONFLICT, "email already exists");
        }
        if (phone != null && userAccountRepository.existsByPhone(phone)) {
            throw new AuthBusinessException("PHONE_ALREADY_EXISTS", HttpStatus.CONFLICT, "phone already exists");
        }

        UUID userId = UUID.randomUUID();
        registerEmailVerificationService.verifyRegisterCode(email, emailVerificationCode, "SELF_REGISTER", userId);

        UserAccount user = new UserAccount();
        user.setId(userId);
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setPhone(phone);
        user.setStatus(AuthDomainConstants.USER_STATUS_ACTIVE);
        user.setSourceType("LOCAL");
        user.setIsFirstLogin(Boolean.TRUE);
        user.setWorkspaceCount(0);
        user.setCreatedBy("SELF_REGISTER");
        user = userAccountRepository.save(user);

        UserCredential credential = new UserCredential();
        credential.setUserId(user.getId());
        credential.setCredentialType(AuthDomainConstants.CREDENTIAL_TYPE_PASSWORD);
        credential.setSecretHash(passwordEncoder.encode(password));
        credential.setStatus(AuthDomainConstants.USER_STATUS_ACTIVE);
        credential.setCreatedBy(user.getId().toString());
        userCredentialRepository.save(credential);

        AuthRegisterResponseDto response = new AuthRegisterResponseDto();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setDisplayName(user.getDisplayName());
        response.setRegisteredAt(user.getRegisteredAt());
        return response;
    }
}