package com.plm.auth.service;

import com.plm.auth.config.AuthPlatformAdminBootstrapProperties;
import com.plm.auth.support.AuthDomainConstants;
import com.plm.auth.util.AuthNormalizer;
import com.plm.common.domain.auth.PlatformRole;
import com.plm.common.domain.auth.PlatformUserRole;
import com.plm.common.domain.auth.UserAccount;
import com.plm.common.domain.auth.UserCredential;
import com.plm.infrastructure.repository.auth.PlatformRoleRepository;
import com.plm.infrastructure.repository.auth.PlatformUserRoleRepository;
import com.plm.infrastructure.repository.auth.UserAccountRepository;
import com.plm.infrastructure.repository.auth.UserCredentialRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@Profile("dev")
public class DevPlatformAdminInitializer implements ApplicationRunner {
    private final AuthPlatformAdminBootstrapProperties properties;
    private final UserAccountRepository userAccountRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final PlatformRoleRepository platformRoleRepository;
    private final PlatformUserRoleRepository platformUserRoleRepository;
    private final PasswordEncoder passwordEncoder;

    public DevPlatformAdminInitializer(AuthPlatformAdminBootstrapProperties properties,
                                       UserAccountRepository userAccountRepository,
                                       UserCredentialRepository userCredentialRepository,
                                       PlatformRoleRepository platformRoleRepository,
                                       PlatformUserRoleRepository platformUserRoleRepository,
                                       PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userAccountRepository = userAccountRepository;
        this.userCredentialRepository = userCredentialRepository;
        this.platformRoleRepository = platformRoleRepository;
        this.platformUserRoleRepository = platformUserRoleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }

        String username = AuthNormalizer.normalizeIdentifier(properties.getUsername());
        String email = AuthNormalizer.normalizeEmail(properties.getEmail());
        String password = properties.getPassword();
        String displayName = properties.getDisplayName();
        String roleCode = properties.getRoleCode();

        if (username == null) {
            throw new IllegalStateException("plm.auth.platform-admin.bootstrap.username is required");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("plm.auth.platform-admin.bootstrap.password is required");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalStateException("plm.auth.platform-admin.bootstrap.display-name is required");
        }
        if (roleCode == null || roleCode.isBlank()) {
            throw new IllegalStateException("plm.auth.platform-admin.bootstrap.role-code is required");
        }

        UserAccount user = userAccountRepository.findByUsernameIgnoreCase(username).orElse(null);
        if (user == null) {
            if (email != null) {
                userAccountRepository.findByEmailIgnoreCase(email).ifPresent(existing -> {
                    throw new IllegalStateException("platform admin bootstrap email already exists: " + email);
                });
            }
            user = new UserAccount();
            user.setUsername(username);
            user.setDisplayName(displayName);
            user.setEmail(email);
            user.setStatus(AuthDomainConstants.USER_STATUS_ACTIVE);
            user.setSourceType("SYSTEM");
            user.setIsFirstLogin(Boolean.FALSE);
            user.setWorkspaceCount(0);
            user.setCreatedBy("DEV_PLATFORM_ADMIN_BOOTSTRAP");
            user.setUpdatedBy("DEV_PLATFORM_ADMIN_BOOTSTRAP");
            user = userAccountRepository.save(user);
        }

        UserCredential credential = userCredentialRepository
                .findByUserIdAndCredentialType(user.getId(), AuthDomainConstants.CREDENTIAL_TYPE_PASSWORD)
                .orElse(null);
        if (credential == null) {
            credential = new UserCredential();
            credential.setUserId(user.getId());
            credential.setCredentialType(AuthDomainConstants.CREDENTIAL_TYPE_PASSWORD);
            credential.setSecretHash(passwordEncoder.encode(password));
            credential.setStatus(AuthDomainConstants.USER_STATUS_ACTIVE);
            credential.setCreatedBy("DEV_PLATFORM_ADMIN_BOOTSTRAP");
            credential.setUpdatedBy("DEV_PLATFORM_ADMIN_BOOTSTRAP");
            userCredentialRepository.save(credential);
        }

        PlatformRole role = platformRoleRepository.findByRoleCodeIgnoreCase(roleCode)
                .orElseThrow(() -> new IllegalStateException("platform role not found: " + roleCode));
        if (!platformUserRoleRepository.existsByUserIdAndRoleId(user.getId(), role.getId())) {
            PlatformUserRole userRole = new PlatformUserRole();
            userRole.setUserId(user.getId());
            userRole.setRoleId(role.getId());
            userRole.setAssignedByUserId(user.getId());
            platformUserRoleRepository.save(userRole);
        }
    }
}