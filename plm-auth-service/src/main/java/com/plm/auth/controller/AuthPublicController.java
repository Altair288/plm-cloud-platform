package com.plm.auth.controller;

import com.plm.auth.service.AuthLoginService;
import com.plm.auth.service.AuthRegistrationService;
import com.plm.auth.service.RegisterEmailVerificationService;
import com.plm.common.api.dto.auth.AuthPasswordLoginRequestDto;
import com.plm.common.api.dto.auth.AuthPasswordLoginResponseDto;
import com.plm.common.api.dto.auth.AuthRegisterRequestDto;
import com.plm.common.api.dto.auth.AuthRegisterResponseDto;
import com.plm.common.api.dto.auth.AuthSendRegisterEmailCodeRequestDto;
import com.plm.common.api.dto.auth.AuthSendRegisterEmailCodeResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthPublicController {
    private final AuthRegistrationService authRegistrationService;
    private final AuthLoginService authLoginService;
    private final RegisterEmailVerificationService registerEmailVerificationService;

    public AuthPublicController(AuthRegistrationService authRegistrationService,
                                AuthLoginService authLoginService,
                                RegisterEmailVerificationService registerEmailVerificationService) {
        this.authRegistrationService = authRegistrationService;
        this.authLoginService = authLoginService;
        this.registerEmailVerificationService = registerEmailVerificationService;
    }

    @PostMapping("/auth/public/register/email-code")
    public ResponseEntity<AuthSendRegisterEmailCodeResponseDto> sendRegisterEmailCode(
            @RequestBody AuthSendRegisterEmailCodeRequestDto request
    ) {
        return ResponseEntity.ok(registerEmailVerificationService.sendCode(request));
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
}