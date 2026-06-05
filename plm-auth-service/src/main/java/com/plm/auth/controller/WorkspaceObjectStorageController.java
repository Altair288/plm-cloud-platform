package com.plm.auth.controller;

import com.plm.auth.service.WorkspaceObjectStorageService;
import com.plm.auth.support.AuthStpKit;
import com.plm.common.api.dto.storage.StorageObjectCompleteUploadRequestDto;
import com.plm.common.api.dto.storage.StorageObjectDownloadUrlResponseDto;
import com.plm.common.api.dto.storage.StorageObjectResponseDto;
import com.plm.common.api.dto.storage.StorageObjectUploadIntentRequestDto;
import com.plm.common.api.dto.storage.StorageObjectUploadIntentResponseDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class WorkspaceObjectStorageController {
    private final WorkspaceObjectStorageService workspaceObjectStorageService;

    public WorkspaceObjectStorageController(WorkspaceObjectStorageService workspaceObjectStorageService) {
        this.workspaceObjectStorageService = workspaceObjectStorageService;
    }

    @PostMapping("/api/storage/workspaces/{workspaceId}/objects/upload-intents")
    public ResponseEntity<StorageObjectUploadIntentResponseDto> createUploadIntent(
            @PathVariable("workspaceId") UUID workspaceId,
            @RequestBody StorageObjectUploadIntentRequestDto request
    ) {
        UUID userId = AuthStpKit.requirePlatformUserId();
        return ResponseEntity.ok(workspaceObjectStorageService.createUploadIntent(userId, workspaceId, request));
    }

    @PostMapping("/api/storage/workspaces/{workspaceId}/objects/{objectId}/complete")
    public ResponseEntity<StorageObjectResponseDto> completeUpload(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("objectId") UUID objectId,
            @RequestBody StorageObjectCompleteUploadRequestDto request
    ) {
        UUID userId = AuthStpKit.requirePlatformUserId();
        return ResponseEntity.ok(workspaceObjectStorageService.completeUpload(userId, workspaceId, objectId, request));
    }

    @PostMapping("/api/storage/workspaces/{workspaceId}/objects/{objectId}/download-url")
    public ResponseEntity<StorageObjectDownloadUrlResponseDto> createDownloadUrl(
            @PathVariable("workspaceId") UUID workspaceId,
            @PathVariable("objectId") UUID objectId
    ) {
        UUID userId = AuthStpKit.requirePlatformUserId();
        return ResponseEntity.ok(workspaceObjectStorageService.createDownloadUrl(userId, workspaceId, objectId));
    }

    @DeleteMapping("/api/storage/workspaces/{workspaceId}/objects/{objectId}")
    public ResponseEntity<Void> deleteObject(@PathVariable("workspaceId") UUID workspaceId,
                                             @PathVariable("objectId") UUID objectId) {
        UUID userId = AuthStpKit.requirePlatformUserId();
        workspaceObjectStorageService.deleteObject(userId, workspaceId, objectId);
        return ResponseEntity.noContent().build();
    }
}