package com.plm.auth.controller;

import com.plm.auth.service.AuthLoginService;
import com.plm.auth.service.AuthEmailTestService;
import com.plm.auth.service.PlatformAdminAuthService;
import com.plm.auth.service.AuthRegistrationService;
import com.plm.auth.service.PasswordTransportSecurityService;
import com.plm.auth.service.RegisterEmailVerificationService;
import com.plm.auth.service.WorkspaceInvitationService;
import com.plm.auth.service.WorkspaceDictionaryService;
import com.plm.common.api.dto.auth.AuthPasswordEncryptionKeyResponseDto;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationLinkPreviewResponseDto;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationPreviewResponseDto;
import com.plm.common.api.dto.auth.AuthWorkspaceBootstrapOptionsResponseDto;
import com.plm.common.api.dto.auth.AuthPasswordLoginRequestDto;
import com.plm.common.api.dto.auth.AuthPasswordLoginResponseDto;
import com.plm.common.api.dto.auth.AuthPlatformAdminLoginResponseDto;
import com.plm.common.api.dto.auth.AuthRegisterRequestDto;
import com.plm.common.api.dto.auth.AuthRegisterResponseDto;
import com.plm.common.api.dto.auth.AuthSendRegisterEmailCodeRequestDto;
import com.plm.common.api.dto.auth.AuthSendRegisterEmailCodeResponseDto;
import com.plm.common.api.dto.auth.AuthSendTestEmailRequestDto;
import com.plm.common.api.dto.auth.AuthSendTestEmailResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthPublicController {
    private final AuthRegistrationService authRegistrationService;
    private final AuthLoginService authLoginService;
    private final PlatformAdminAuthService platformAdminAuthService;
    private final RegisterEmailVerificationService registerEmailVerificationService;
    private final AuthEmailTestService authEmailTestService;
    private final WorkspaceDictionaryService workspaceDictionaryService;
    private final WorkspaceInvitationService workspaceInvitationService;
    private final PasswordTransportSecurityService passwordTransportSecurityService;

    public AuthPublicController(AuthRegistrationService authRegistrationService,
                                AuthLoginService authLoginService,
                                PlatformAdminAuthService platformAdminAuthService,
                                RegisterEmailVerificationService registerEmailVerificationService,
                                AuthEmailTestService authEmailTestService,
                                WorkspaceDictionaryService workspaceDictionaryService,
                                WorkspaceInvitationService workspaceInvitationService,
                                PasswordTransportSecurityService passwordTransportSecurityService) {
        this.authRegistrationService = authRegistrationService;
        this.authLoginService = authLoginService;
        this.platformAdminAuthService = platformAdminAuthService;
        this.registerEmailVerificationService = registerEmailVerificationService;
        this.authEmailTestService = authEmailTestService;
        this.workspaceDictionaryService = workspaceDictionaryService;
        this.workspaceInvitationService = workspaceInvitationService;
        this.passwordTransportSecurityService = passwordTransportSecurityService;
    }

    @GetMapping("/auth/public/security/password-encryption-key")
    public ResponseEntity<AuthPasswordEncryptionKeyResponseDto> getPasswordEncryptionKey() {
        return ResponseEntity.ok(passwordTransportSecurityService.getPasswordEncryptionKey());
    }

    @GetMapping("/auth/public/workspace-bootstrap-options")
    public ResponseEntity<AuthWorkspaceBootstrapOptionsResponseDto> getWorkspaceBootstrapOptions() {
        return ResponseEntity.ok(workspaceDictionaryService.getWorkspaceBootstrapOptions());
    }

    @GetMapping("/auth/public/workspace-invitations/email/{token}")
    public ResponseEntity<AuthWorkspaceInvitationPreviewResponseDto> getEmailInvitationPreview(@PathVariable("token") String token) {
        return ResponseEntity.ok(workspaceInvitationService.getEmailInvitationPreview(token));
    }

    @GetMapping("/auth/public/workspace-invitation-links/{token}")
    public ResponseEntity<AuthWorkspaceInvitationLinkPreviewResponseDto> getInvitationLinkPreview(@PathVariable("token") String token) {
        return ResponseEntity.ok(workspaceInvitationService.getInvitationLinkPreview(token));
    }

    @PostMapping("/auth/public/register/email-code")
    public ResponseEntity<AuthSendRegisterEmailCodeResponseDto> sendRegisterEmailCode(
            @RequestBody AuthSendRegisterEmailCodeRequestDto request
    ) {
        return ResponseEntity.ok(registerEmailVerificationService.sendCode(request));
    }

    @PostMapping("/auth/public/test/email-send")
    public ResponseEntity<AuthSendTestEmailResponseDto> sendTestEmail(
            @RequestBody AuthSendTestEmailRequestDto request
    ) {
        return ResponseEntity.ok(authEmailTestService.sendTestEmail(request));
    }

    @PostMapping("/auth/public/register")
    public ResponseEntity<AuthRegisterResponseDto> register(@RequestBody AuthRegisterRequestDto request) {
        return ResponseEntity.ok(authRegistrationService.register(request));
    }

    @PostMapping("/auth/public/login/password")
    public ResponseEntity<AuthPasswordLoginResponseDto> login(@RequestBody AuthPasswordLoginRequestDto request,
                                                              HttpServletRequest servletRequest) {
        return ResponseEntity.ok(authLoginService.login(request, servletRequest));
    }

    @PostMapping("/auth/public/platform-admin/login/password")
    public ResponseEntity<AuthPlatformAdminLoginResponseDto> platformAdminLogin(@RequestBody AuthPasswordLoginRequestDto request,
                                                                                HttpServletRequest servletRequest) {
        return ResponseEntity.ok(platformAdminAuthService.login(request, servletRequest));
    }
}