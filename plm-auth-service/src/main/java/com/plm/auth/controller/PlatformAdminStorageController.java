package com.plm.auth.controller;

import com.plm.auth.service.PlatformStorageAdminService;
import com.plm.common.api.dto.auth.AuthStorageClusterResponseDto;
import com.plm.common.api.dto.auth.AuthStorageClusterTestConnectionResponseDto;
import com.plm.common.api.dto.auth.AuthStorageClusterTestDownloadResponseDto;
import com.plm.common.api.dto.auth.AuthStorageClusterTestSessionResponseDto;
import com.plm.common.api.dto.auth.AuthStorageClusterTestUploadRequestDto;
import com.plm.common.api.dto.auth.AuthStorageClusterTestUploadResponseDto;
import com.plm.common.api.dto.auth.AuthStorageClusterUpsertRequestDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class PlatformAdminStorageController {
    private final PlatformStorageAdminService platformStorageAdminService;

    public PlatformAdminStorageController(PlatformStorageAdminService platformStorageAdminService) {
        this.platformStorageAdminService = platformStorageAdminService;
    }

    @GetMapping("/auth/platform-admin/storage/clusters")
    public ResponseEntity<List<AuthStorageClusterResponseDto>> listClusters() {
        return ResponseEntity.ok(platformStorageAdminService.listClusters());
    }

    @PostMapping("/auth/platform-admin/storage/clusters")
    public ResponseEntity<AuthStorageClusterResponseDto> createCluster(
            @RequestBody AuthStorageClusterUpsertRequestDto request
    ) {
        return ResponseEntity.ok(platformStorageAdminService.createCluster(request));
    }

    @PutMapping("/auth/platform-admin/storage/clusters/{clusterId}")
    public ResponseEntity<AuthStorageClusterResponseDto> updateCluster(
            @PathVariable("clusterId") UUID clusterId,
            @RequestBody AuthStorageClusterUpsertRequestDto request
    ) {
        return ResponseEntity.ok(platformStorageAdminService.updateCluster(clusterId, request));
    }

    @PostMapping("/auth/platform-admin/storage/clusters/{clusterId}/test-connection")
    public ResponseEntity<AuthStorageClusterTestConnectionResponseDto> testConnection(
            @PathVariable("clusterId") UUID clusterId
    ) {
        return ResponseEntity.ok(platformStorageAdminService.testConnection(clusterId));
    }

    @PostMapping("/auth/platform-admin/storage/clusters/{clusterId}/test-upload-intents")
    public ResponseEntity<AuthStorageClusterTestUploadResponseDto> createTestUploadIntent(
            @PathVariable("clusterId") UUID clusterId,
            @RequestBody AuthStorageClusterTestUploadRequestDto request
    ) {
        return ResponseEntity.ok(platformStorageAdminService.createTestUploadIntent(clusterId, request));
    }

    @PostMapping("/auth/platform-admin/storage/clusters/{clusterId}/test-sessions/{testSessionId}/complete")
    public ResponseEntity<AuthStorageClusterTestSessionResponseDto> completeTestUpload(
            @PathVariable("clusterId") UUID clusterId,
            @PathVariable("testSessionId") UUID testSessionId
    ) {
        return ResponseEntity.ok(platformStorageAdminService.completeTestUpload(clusterId, testSessionId));
    }

    @PostMapping("/auth/platform-admin/storage/clusters/{clusterId}/test-sessions/{testSessionId}/download-url")
    public ResponseEntity<AuthStorageClusterTestDownloadResponseDto> createTestDownloadUrl(
            @PathVariable("clusterId") UUID clusterId,
            @PathVariable("testSessionId") UUID testSessionId
    ) {
        return ResponseEntity.ok(platformStorageAdminService.createTestDownloadUrl(clusterId, testSessionId));
    }

    @DeleteMapping("/auth/platform-admin/storage/clusters/{clusterId}/test-sessions/{testSessionId}")
    public ResponseEntity<Void> cleanupTestSession(
            @PathVariable("clusterId") UUID clusterId,
            @PathVariable("testSessionId") UUID testSessionId
    ) {
        platformStorageAdminService.cleanupTestSession(clusterId, testSessionId);
        return ResponseEntity.noContent().build();
    }
}