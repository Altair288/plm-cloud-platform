package com.plm.auth.service;

import cn.dev33.satoken.stp.SaLoginModel;
import com.plm.auth.config.AuthLoginProperties;
import com.plm.auth.exception.AuthBusinessException;
import com.plm.auth.support.AuthDomainConstants;
import com.plm.auth.support.AuthStpKit;
import com.plm.auth.util.AuthNormalizer;
import com.plm.common.api.dto.auth.AuthAdminSummaryDto;
import com.plm.common.api.dto.auth.AuthPasswordLoginRequestDto;
import com.plm.common.api.dto.auth.AuthPlatformAdminLoginResponseDto;
import com.plm.common.api.dto.auth.AuthPlatformAdminSessionResponseDto;
import com.plm.common.domain.auth.LoginAudit;
import com.plm.common.domain.auth.UserAccount;
import com.plm.common.domain.auth.UserCredential;
import com.plm.infrastructure.repository.auth.LoginAuditRepository;
import com.plm.infrastructure.repository.auth.PlatformUserRoleRepository;
import com.plm.infrastructure.repository.auth.UserAccountRepository;
import com.plm.infrastructure.repository.auth.UserCredentialRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PlatformAdminAuthService {
    private final AuthLoginProperties authLoginProperties;
    private final PasswordTransportSecurityService passwordTransportSecurityService;
    private final UserAccountRepository userAccountRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final PlatformUserRoleRepository platformUserRoleRepository;
    private final LoginAuditRepository loginAuditRepository;
    private final PasswordEncoder passwordEncoder;

    public PlatformAdminAuthService(AuthLoginProperties authLoginProperties,
                                    PasswordTransportSecurityService passwordTransportSecurityService,
                                    UserAccountRepository userAccountRepository,
                                    UserCredentialRepository userCredentialRepository,
                                    PlatformUserRoleRepository platformUserRoleRepository,
                                    LoginAuditRepository loginAuditRepository,
                                    PasswordEncoder passwordEncoder) {
        this.authLoginProperties = authLoginProperties;
        this.passwordTransportSecurityService = passwordTransportSecurityService;
        this.userAccountRepository = userAccountRepository;
        this.userCredentialRepository = userCredentialRepository;
        this.platformUserRoleRepository = platformUserRoleRepository;
        this.loginAuditRepository = loginAuditRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AuthPlatformAdminLoginResponseDto login(AuthPasswordLoginRequestDto request, HttpServletRequest servletRequest) {
        String identifier = AuthNormalizer.normalizeIdentifier(request.getIdentifier());
        String password = passwordTransportSecurityService.resolvePassword(
                request.getPassword(),
                request.getPasswordCiphertext(),
                request.getEncryptionKeyId(),
                "password");
        if (identifier == null) {
            throw new IllegalArgumentException("identifier is required");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password is required");
        }

        UserAccount user = userAccountRepository.findByIdentifier(identifier).orElse(null);
        if (user == null) {
            recordLoginAudit(null, AuthDomainConstants.LOGIN_TYPE_PLATFORM_ADMIN,
                    AuthDomainConstants.LOGIN_RESULT_FAILED, servletRequest, "INVALID_CREDENTIALS");
            throw new AuthBusinessException("AUTH_INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!AuthDomainConstants.USER_STATUS_ACTIVE.equalsIgnoreCase(user.getStatus())) {
            recordLoginAudit(user, AuthDomainConstants.LOGIN_TYPE_PLATFORM_ADMIN,
                    AuthDomainConstants.LOGIN_RESULT_FAILED, servletRequest, "ACCOUNT_NOT_ACTIVE");
            throw new AuthBusinessException("ACCOUNT_NOT_ACTIVE", HttpStatus.FORBIDDEN, "account is not active");
        }

        UserCredential credential = userCredentialRepository
                .findByUserIdAndCredentialType(user.getId(), AuthDomainConstants.CREDENTIAL_TYPE_PASSWORD)
                .orElse(null);
        if (credential == null || !AuthDomainConstants.USER_STATUS_ACTIVE.equalsIgnoreCase(credential.getStatus())) {
            recordLoginAudit(user, AuthDomainConstants.LOGIN_TYPE_PLATFORM_ADMIN,
                    AuthDomainConstants.LOGIN_RESULT_FAILED, servletRequest, "CREDENTIAL_NOT_ACTIVE");
            throw new AuthBusinessException("AUTH_INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(password, credential.getSecretHash())) {
            recordLoginAudit(user, AuthDomainConstants.LOGIN_TYPE_PLATFORM_ADMIN,
                    AuthDomainConstants.LOGIN_RESULT_FAILED, servletRequest, "INVALID_CREDENTIALS");
            throw new AuthBusinessException("AUTH_INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }

        List<String> roleCodes = loadActivePlatformRoleCodes(user.getId());
        if (roleCodes.isEmpty()) {
            recordLoginAudit(user, AuthDomainConstants.LOGIN_TYPE_PLATFORM_ADMIN,
                    AuthDomainConstants.LOGIN_RESULT_FAILED, servletRequest, "PLATFORM_ADMIN_REQUIRED");
            throw new AuthBusinessException("PLATFORM_ADMIN_REQUIRED", HttpStatus.FORBIDDEN, "platform admin role is required");
        }

        boolean remember = Boolean.TRUE.equals(request.getRemember());
        long tokenExpireInSeconds = resolveTokenExpireInSeconds(remember);

        AuthStpKit.PLATFORM.login(user.getId(), new SaLoginModel()
                .setTimeout(tokenExpireInSeconds)
                .setIsLastingCookie(remember));
        AuthStpKit.clearCurrentWorkspaceMemberId();
        if (AuthStpKit.WORKSPACE.isLogin()) {
            AuthStpKit.WORKSPACE.logout();
        }

        user.setLastLoginAt(OffsetDateTime.now());
        user.setUpdatedBy(user.getId().toString());
        userAccountRepository.save(user);
        credential.setLastVerifiedAt(OffsetDateTime.now());
        credential.setUpdatedBy(user.getId().toString());
        userCredentialRepository.save(credential);
        recordLoginAudit(user, AuthDomainConstants.LOGIN_TYPE_PLATFORM_ADMIN,
                AuthDomainConstants.LOGIN_RESULT_SUCCESS, servletRequest, null);

        AuthPlatformAdminLoginResponseDto response = new AuthPlatformAdminLoginResponseDto();
        response.setPlatformToken(AuthStpKit.PLATFORM.getTokenValue());
        response.setPlatformTokenName(AuthStpKit.PLATFORM.getTokenName());
        response.setRemember(remember);
        response.setPlatformTokenExpireInSeconds(tokenExpireInSeconds);
        response.setAdmin(toAdminSummary(user, roleCodes));
        return response;
    }

    @Transactional(readOnly = true)
    public AuthPlatformAdminSessionResponseDto getCurrentAdminSession() {
        UUID userId = AuthStpKit.requirePlatformUserId();
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new AuthBusinessException("AUTH_NOT_LOGGED_IN", HttpStatus.UNAUTHORIZED, "not logged in"));
        if (!AuthDomainConstants.USER_STATUS_ACTIVE.equalsIgnoreCase(user.getStatus())) {
            throw new AuthBusinessException("ACCOUNT_NOT_ACTIVE", HttpStatus.FORBIDDEN, "account is not active");
        }
        List<String> roleCodes = loadActivePlatformRoleCodes(user.getId());
        if (roleCodes.isEmpty()) {
            throw new AuthBusinessException("PLATFORM_ADMIN_REQUIRED", HttpStatus.FORBIDDEN, "platform admin role is required");
        }

        AuthPlatformAdminSessionResponseDto response = new AuthPlatformAdminSessionResponseDto();
        response.setAdmin(toAdminSummary(user, roleCodes));
        return response;
    }

    private List<String> loadActivePlatformRoleCodes(UUID userId) {
        return platformUserRoleRepository.findRoleCodesByUserIdAndRoleStatus(
                userId,
                AuthDomainConstants.PLATFORM_ROLE_STATUS_ACTIVE);
    }

    private AuthAdminSummaryDto toAdminSummary(UserAccount user, List<String> roleCodes) {
        AuthAdminSummaryDto summary = new AuthAdminSummaryDto();
        summary.setId(user.getId());
        summary.setUsername(user.getUsername());
        summary.setDisplayName(user.getDisplayName());
        summary.setEmail(user.getEmail());
        summary.setPhone(user.getPhone());
        summary.setStatus(user.getStatus());
        summary.setRoleCodes(roleCodes);
        summary.setSuperAdmin(roleCodes.stream()
                .anyMatch(AuthDomainConstants.ROLE_CODE_PLATFORM_SUPER_ADMIN::equalsIgnoreCase));
        return summary;
    }

    private void recordLoginAudit(UserAccount user,
                                  String loginType,
                                  String result,
                                  HttpServletRequest servletRequest,
                                  String failureReason) {
        LoginAudit audit = new LoginAudit();
        if (user != null) {
            audit.setUserId(user.getId());
        }
        audit.setLoginType(loginType);
        audit.setLoginResult(result);
        audit.setLoginIp(resolveClientIp(servletRequest));
        audit.setUserAgent(servletRequest == null ? null : servletRequest.getHeader("User-Agent"));
        audit.setFailureReason(failureReason);
        loginAuditRepository.save(audit);
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int commaIndex = forwarded.indexOf(',');
            return commaIndex >= 0 ? forwarded.substring(0, commaIndex).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    private long resolveTokenExpireInSeconds(boolean remember) {
        long tokenExpireInSeconds = remember
                ? authLoginProperties.getRememberExpireInSeconds()
                : authLoginProperties.getExpireInSeconds();
        if (tokenExpireInSeconds <= 0) {
            throw new IllegalStateException("login token expireInSeconds must be > 0");
        }
        return tokenExpireInSeconds;
    }
}