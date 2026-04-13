package com.plm.auth.controller;

import com.plm.auth.service.AuthLoginService;
import com.plm.auth.service.AuthQueryService;
import com.plm.auth.service.WorkspaceCommandService;
import com.plm.auth.service.WorkspaceSessionService;
import com.plm.auth.support.AuthStpKit;
import com.plm.common.api.dto.auth.AuthCreateWorkspaceRequestDto;
import com.plm.common.api.dto.auth.AuthMeResponseDto;
import com.plm.common.api.dto.auth.AuthWorkspaceOptionDto;
import com.plm.common.api.dto.auth.AuthWorkspaceSessionResponseDto;
import com.plm.common.api.dto.auth.AuthSwitchWorkspaceRequestDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class AuthSessionController {
    private final AuthLoginService authLoginService;
    private final AuthQueryService authQueryService;
    private final WorkspaceCommandService workspaceCommandService;
    private final WorkspaceSessionService workspaceSessionService;

    public AuthSessionController(AuthLoginService authLoginService,
                                 AuthQueryService authQueryService,
                                 WorkspaceCommandService workspaceCommandService,
                                 WorkspaceSessionService workspaceSessionService) {
        this.authLoginService = authLoginService;
        this.authQueryService = authQueryService;
        this.workspaceCommandService = workspaceCommandService;
        this.workspaceSessionService = workspaceSessionService;
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout() {
        authLoginService.logout();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/me")
    public ResponseEntity<AuthMeResponseDto> me() {
        UUID userId = AuthStpKit.requirePlatformUserId();
        return ResponseEntity.ok(authQueryService.getCurrentSession(userId));
    }

    @GetMapping("/auth/workspaces")
    public ResponseEntity<List<AuthWorkspaceOptionDto>> listWorkspaces() {
        UUID userId = AuthStpKit.requirePlatformUserId();
        return ResponseEntity.ok(authQueryService.listWorkspaceOptions(userId));
    }

    @PostMapping("/auth/workspaces")
    public ResponseEntity<AuthWorkspaceSessionResponseDto> createWorkspace(@RequestBody AuthCreateWorkspaceRequestDto request) {
        UUID userId = AuthStpKit.requirePlatformUserId();
        return ResponseEntity.ok(workspaceCommandService.createWorkspace(userId, request));
    }

    @PostMapping("/auth/workspace-session/switch")
    public ResponseEntity<AuthWorkspaceSessionResponseDto> switchWorkspace(@RequestBody AuthSwitchWorkspaceRequestDto request) {
        UUID userId = AuthStpKit.requirePlatformUserId();
        return ResponseEntity.ok(workspaceSessionService.switchWorkspace(userId, request.getWorkspaceId(), request.getRememberAsDefault()));
    }

    @GetMapping("/auth/workspace-session/current")
    public ResponseEntity<AuthWorkspaceSessionResponseDto> currentWorkspaceSession() {
        UUID userId = AuthStpKit.requirePlatformUserId();
        AuthWorkspaceSessionResponseDto current = workspaceSessionService.getCurrentWorkspaceSession(userId);
        if (current == null) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        return ResponseEntity.ok(current);
    }

    @DeleteMapping("/auth/workspace-session/current")
    public ResponseEntity<Void> clearWorkspaceSession() {
        AuthStpKit.requirePlatformUserId();
        workspaceSessionService.clearCurrentWorkspaceSession();
        return ResponseEntity.noContent().build();
    }
}