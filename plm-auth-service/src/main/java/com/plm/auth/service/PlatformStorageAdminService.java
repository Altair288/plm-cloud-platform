package com.plm.auth.service;

import com.plm.auth.config.StorageAdminProperties;
import com.plm.auth.exception.AuthBusinessException;
import com.plm.auth.support.AuthDomainConstants;
import com.plm.common.api.dto.auth.AuthAdminSummaryDto;
import com.plm.common.api.dto.auth.AuthStorageClusterResponseDto;
import com.plm.common.api.dto.auth.AuthStorageClusterTestConnectionResponseDto;
import com.plm.common.api.dto.auth.AuthStorageClusterTestDownloadResponseDto;
import com.plm.common.api.dto.auth.AuthStorageClusterTestSessionResponseDto;
import com.plm.common.api.dto.auth.AuthStorageClusterTestUploadRequestDto;
import com.plm.common.api.dto.auth.AuthStorageClusterTestUploadResponseDto;
import com.plm.common.api.dto.auth.AuthStorageClusterUpsertRequestDto;
import com.plm.common.domain.storage.StorageCluster;
import com.plm.common.domain.storage.StorageClusterTestSession;
import com.plm.infrastructure.repository.storage.StorageClusterRepository;
import com.plm.infrastructure.repository.storage.StorageClusterTestSessionRepository;
import com.plm.infrastructure.storage.StorageGateway;
import com.plm.infrastructure.storage.StorageGatewayException;
import com.plm.infrastructure.storage.StorageObjectStat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class PlatformStorageAdminService {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String HEALTH_HEALTHY = "HEALTHY";
    private static final String HEALTH_UNHEALTHY = "UNHEALTHY";
    private static final String HEALTH_UNKNOWN = "UNKNOWN";
    private static final String TEST_STATUS_NOT_TESTED = "NOT_TESTED";
    private static final String TEST_STATUS_PASSED = "PASSED";
    private static final String TEST_STATUS_FAILED = "FAILED";
    private static final String SESSION_INITIATED = "INITIATED";
    private static final String SESSION_COMPLETED = "COMPLETED";
    private static final String SESSION_EXPIRED = "EXPIRED";
    private static final String SESSION_CLEANED = "CLEANED";

    private final PlatformAdminAuthService platformAdminAuthService;
    private final StorageClusterRepository storageClusterRepository;
    private final StorageClusterTestSessionRepository storageClusterTestSessionRepository;
    private final StorageGateway storageGateway;
    private final StorageAdminProperties storageAdminProperties;

    public PlatformStorageAdminService(PlatformAdminAuthService platformAdminAuthService,
                                       StorageClusterRepository storageClusterRepository,
                                       StorageClusterTestSessionRepository storageClusterTestSessionRepository,
                                       StorageGateway storageGateway,
                                       StorageAdminProperties storageAdminProperties) {
        this.platformAdminAuthService = platformAdminAuthService;
        this.storageClusterRepository = storageClusterRepository;
        this.storageClusterTestSessionRepository = storageClusterTestSessionRepository;
        this.storageGateway = storageGateway;
        this.storageAdminProperties = storageAdminProperties;
    }

    @Transactional(readOnly = true)
    public List<AuthStorageClusterResponseDto> listClusters() {
        requireAdminManagePermission();
        return storageClusterRepository.findAllOrderByCreatedAtDesc().stream()
                .map(this::toClusterResponse)
                .toList();
    }

    @Transactional
    public AuthStorageClusterResponseDto createCluster(AuthStorageClusterUpsertRequestDto request) {
        AuthAdminSummaryDto admin = requireAdminManagePermission();
        StorageCluster cluster = new StorageCluster();
        applyClusterRequest(cluster, request, admin, true);
        StorageCluster saved = saveCluster(cluster, admin);
        return toClusterResponse(saved);
    }

    @Transactional
    public AuthStorageClusterResponseDto updateCluster(UUID clusterId, AuthStorageClusterUpsertRequestDto request) {
        AuthAdminSummaryDto admin = requireAdminManagePermission();
        StorageCluster cluster = getClusterOrThrow(clusterId);
        applyClusterRequest(cluster, request, admin, false);
        StorageCluster saved = saveCluster(cluster, admin);
        return toClusterResponse(saved);
    }

    @Transactional
    public AuthStorageClusterTestConnectionResponseDto testConnection(UUID clusterId) {
        AuthAdminSummaryDto admin = requireAdminTestPermission();
        StorageCluster cluster = getClusterOrThrow(clusterId);
        try {
            storageGateway.verifyCluster(cluster);
            storageGateway.ensureBucketExists(cluster, cluster.getTestBucketName());
            OffsetDateTime testedAt = OffsetDateTime.now();
            cluster.setHealthStatus(HEALTH_HEALTHY);
            cluster.setLastTestStatus(TEST_STATUS_PASSED);
            cluster.setLastTestedAt(testedAt);
            cluster.setLastCheckedAt(testedAt);
            cluster.setLastTestErrorCode(null);
            cluster.setLastTestErrorMessage(null);
            cluster.setUpdatedBy(admin.getId().toString());
            storageClusterRepository.save(cluster);
            AuthStorageClusterTestConnectionResponseDto response = new AuthStorageClusterTestConnectionResponseDto();
            response.setClusterId(cluster.getId());
            response.setClusterCode(cluster.getClusterCode());
            response.setTestBucketName(cluster.getTestBucketName());
            response.setHealthStatus(cluster.getHealthStatus());
            response.setLastTestStatus(cluster.getLastTestStatus());
            response.setTestedAt(cluster.getLastTestedAt());
            return response;
        } catch (StorageGatewayException ex) {
            markClusterTestFailure(cluster, admin.getId(), ex.getMessage());
            throw new AuthBusinessException("STORAGE_CLUSTER_TEST_FAILED", HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
    }

    @Transactional
    public AuthStorageClusterTestUploadResponseDto createTestUploadIntent(UUID clusterId,
                                                                          AuthStorageClusterTestUploadRequestDto request) {
        AuthAdminSummaryDto admin = requireAdminTestPermission();
        StorageCluster cluster = getClusterOrThrow(clusterId);
        String originalFileName = normalizeOriginalFileName(request == null ? null : request.getOriginalFileName());
        String contentType = normalizeNullable(request == null ? null : request.getContentType());
        Long fileSize = request == null ? null : request.getFileSize();
        StorageClusterTestSession session = new StorageClusterTestSession();
        session.setClusterId(cluster.getId());
        session.setTestBucketName(cluster.getTestBucketName());
        session.setOriginalFileName(originalFileName);
        session.setContentType(contentType);
        session.setFileSize(fileSize);
        session.setCreatedByUserId(admin.getId());
        session.setCreatedBy(admin.getId().toString());
        session.setUploadToken(UUID.randomUUID().toString().replace("-", ""));
        session.setExpiresAt(OffsetDateTime.now().plusSeconds(storageAdminProperties.getTestSessionExpireSeconds()));
        session.setObjectKey(buildTestObjectKey(cluster.getClusterCode(), session.getUploadToken(), originalFileName));
        try {
            storageGateway.ensureBucketExists(cluster, cluster.getTestBucketName());
            String uploadUrl = storageGateway.createPresignedUploadUrl(
                    cluster,
                    cluster.getTestBucketName(),
                    session.getObjectKey(),
                    Duration.ofSeconds(storageAdminProperties.getUploadUrlExpireSeconds()));
            StorageClusterTestSession saved = storageClusterTestSessionRepository.save(session);
            AuthStorageClusterTestUploadResponseDto response = new AuthStorageClusterTestUploadResponseDto();
            response.setTestSessionId(saved.getId());
            response.setUploadToken(saved.getUploadToken());
            response.setObjectKey(saved.getObjectKey());
            response.setUploadUrl(uploadUrl);
            response.setExpiresAt(saved.getExpiresAt());
            return response;
        } catch (StorageGatewayException ex) {
            markClusterTestFailure(cluster, admin.getId(), ex.getMessage());
            throw new AuthBusinessException("STORAGE_CLUSTER_TEST_FAILED", HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
    }

    @Transactional
    public AuthStorageClusterTestSessionResponseDto completeTestUpload(UUID clusterId, UUID testSessionId) {
        AuthAdminSummaryDto admin = requireAdminTestPermission();
        StorageCluster cluster = getClusterOrThrow(clusterId);
        StorageClusterTestSession session = getTestSessionOrThrow(clusterId, testSessionId);
        assertSessionNotExpired(session, admin.getId().toString());
        try {
            StorageObjectStat stat = storageGateway.statObject(cluster, session.getTestBucketName(), session.getObjectKey());
            session.setSessionStatus(SESSION_COMPLETED);
            session.setCompletedAt(OffsetDateTime.now());
            session.setUpdatedBy(admin.getId().toString());
            session.setEtag(stat.etag());
            session.setFileSize(stat.size());
            StorageClusterTestSession saved = storageClusterTestSessionRepository.save(session);
            cluster.setHealthStatus(HEALTH_HEALTHY);
            cluster.setLastTestStatus(TEST_STATUS_PASSED);
            cluster.setLastTestedAt(OffsetDateTime.now());
            cluster.setLastCheckedAt(cluster.getLastTestedAt());
            cluster.setLastTestErrorCode(null);
            cluster.setLastTestErrorMessage(null);
            cluster.setUpdatedBy(admin.getId().toString());
            storageClusterRepository.save(cluster);
            return toSessionResponse(saved);
        } catch (StorageGatewayException ex) {
            markClusterTestFailure(cluster, admin.getId(), ex.getMessage());
            throw new AuthBusinessException("STORAGE_CLUSTER_TEST_FAILED", HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public AuthStorageClusterTestDownloadResponseDto createTestDownloadUrl(UUID clusterId, UUID testSessionId) {
        requireAdminTestPermission();
        StorageCluster cluster = getClusterOrThrow(clusterId);
        StorageClusterTestSession session = getTestSessionOrThrow(clusterId, testSessionId);
        assertSessionReadyForDownload(session);
        try {
            String downloadUrl = storageGateway.createPresignedDownloadUrl(
                    cluster,
                    session.getTestBucketName(),
                    session.getObjectKey(),
                    Duration.ofSeconds(storageAdminProperties.getDownloadUrlExpireSeconds()));
            AuthStorageClusterTestDownloadResponseDto response = new AuthStorageClusterTestDownloadResponseDto();
            response.setDownloadUrl(downloadUrl);
            response.setExpiresAt(OffsetDateTime.now().plusSeconds(storageAdminProperties.getDownloadUrlExpireSeconds()));
            return response;
        } catch (StorageGatewayException ex) {
            throw new AuthBusinessException("STORAGE_CLUSTER_TEST_FAILED", HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
    }

    @Transactional
    public void cleanupTestSession(UUID clusterId, UUID testSessionId) {
        AuthAdminSummaryDto admin = requireAdminTestPermission();
        StorageCluster cluster = getClusterOrThrow(clusterId);
        StorageClusterTestSession session = getTestSessionOrThrow(clusterId, testSessionId);
        try {
            storageGateway.deleteObject(cluster, session.getTestBucketName(), session.getObjectKey());
        } catch (StorageGatewayException ex) {
            throw new AuthBusinessException("STORAGE_CLUSTER_TEST_FAILED", HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
        session.setSessionStatus(SESSION_CLEANED);
        session.setUpdatedBy(admin.getId().toString());
        storageClusterTestSessionRepository.save(session);
    }

    private AuthAdminSummaryDto requireAdmin() {
        return platformAdminAuthService.requireCurrentAdmin();
    }

    private AuthAdminSummaryDto requireAdminManagePermission() {
        return platformAdminAuthService.requireCurrentAdminPermission(
                AuthDomainConstants.PERMISSION_PLATFORM_STORAGE_CLUSTER_MANAGE).admin();
    }

    private AuthAdminSummaryDto requireAdminTestPermission() {
        return platformAdminAuthService.requireCurrentAdminPermission(
                AuthDomainConstants.PERMISSION_PLATFORM_STORAGE_CLUSTER_TEST).admin();
    }

    private StorageCluster saveCluster(StorageCluster cluster, AuthAdminSummaryDto admin) {
        boolean activateRequested = STATUS_ACTIVE.equalsIgnoreCase(cluster.getStatus());
        if (activateRequested) {
            cluster.setStatus(STATUS_INACTIVE);
        }
        StorageCluster saved = storageClusterRepository.save(cluster);
        if (!activateRequested) {
            return saved;
        }

        storageClusterRepository.deactivateOtherActiveClusters(
                saved.getId(),
                STATUS_ACTIVE,
                STATUS_INACTIVE,
                admin.getId().toString());
        saved.setStatus(STATUS_ACTIVE);
        saved.setUpdatedBy(admin.getId().toString());
        return storageClusterRepository.save(saved);
    }

    private void applyClusterRequest(StorageCluster cluster,
                                     AuthStorageClusterUpsertRequestDto request,
                                     AuthAdminSummaryDto admin,
                                     boolean creating) {
        if (request == null) {
            throw new IllegalArgumentException("storage cluster request is required");
        }
        String normalizedClusterCode = normalizeClusterCode(request.getClusterCode());
        storageClusterRepository.findByClusterCodeIgnoreCase(normalizedClusterCode)
                .filter(existing -> !existing.getId().equals(cluster.getId()))
                .ifPresent(existing -> {
                    throw new AuthBusinessException("STORAGE_CLUSTER_CODE_EXISTS", HttpStatus.CONFLICT,
                            "storage cluster code already exists");
                });

        cluster.setClusterCode(normalizedClusterCode);
        cluster.setProviderType("MINIO");
        cluster.setEndpoint(normalizeEndpoint(request.getEndpoint(), true));
        cluster.setPublicEndpoint(normalizeEndpoint(request.getPublicEndpoint(), false));
        cluster.setRegionName(normalizeNullable(request.getRegionName()));
        cluster.setBucketPrefix(normalizeBucketSegment(request.getBucketPrefix(), "bucketPrefix"));
        cluster.setTestBucketName(resolveTestBucketName(cluster, request.getTestBucketName()));
        cluster.setSecretRef(resolveSecretRef(cluster, request.getSecretRef(), creating));
        cluster.setStatus(resolveClusterStatus(cluster, request.getStatus(), creating));
        if (cluster.getHealthStatus() == null || cluster.getHealthStatus().isBlank()) {
            cluster.setHealthStatus(HEALTH_UNKNOWN);
        }
        if (cluster.getLastTestStatus() == null || cluster.getLastTestStatus().isBlank()) {
            cluster.setLastTestStatus(TEST_STATUS_NOT_TESTED);
        }
        if (creating) {
            cluster.setCreatedBy(admin.getId().toString());
        }
        cluster.setUpdatedBy(admin.getId().toString());
    }

    private String resolveClusterStatus(StorageCluster cluster, String status, boolean creating) {
        String normalized = normalizeNullable(status);
        if (normalized == null) {
            if (!creating && cluster.getStatus() != null && !cluster.getStatus().isBlank()) {
                return cluster.getStatus().toUpperCase(Locale.ROOT);
            }
            return storageClusterRepository.findByStatus(STATUS_ACTIVE).isPresent() ? STATUS_INACTIVE : STATUS_ACTIVE;
        }
        if (!STATUS_ACTIVE.equalsIgnoreCase(normalized) && !STATUS_INACTIVE.equalsIgnoreCase(normalized)) {
            throw new IllegalArgumentException("storage cluster status must be ACTIVE or INACTIVE");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String resolveSecretRef(StorageCluster cluster, String secretRef, boolean creating) {
        String normalized = normalizeNullable(secretRef);
        if (normalized != null) {
            return normalized;
        }
        if (!creating && cluster.getSecretRef() != null && !cluster.getSecretRef().isBlank()) {
            return cluster.getSecretRef();
        }
        throw new IllegalArgumentException("secretRef is required");
    }

    private String resolveTestBucketName(StorageCluster cluster, String requestedTestBucketName) {
        String normalized = normalizeNullable(requestedTestBucketName);
        if (normalized != null) {
            return normalizeBucketName(normalized, "testBucketName");
        }
        if (cluster.getTestBucketName() != null && !cluster.getTestBucketName().isBlank()) {
            return cluster.getTestBucketName();
        }
        return normalizeBucketName(cluster.getBucketPrefix() + "-" + cluster.getClusterCode() + "-admin-test", "testBucketName");
    }

    private void assertSessionNotExpired(StorageClusterTestSession session, String updatedBy) {
        if (session.getExpiresAt().isBefore(OffsetDateTime.now())) {
            session.setSessionStatus(SESSION_EXPIRED);
            session.setUpdatedBy(updatedBy);
            storageClusterTestSessionRepository.save(session);
            throw new AuthBusinessException("STORAGE_TEST_SESSION_EXPIRED", HttpStatus.GONE, "storage test session expired");
        }
    }

    private void assertSessionReadyForDownload(StorageClusterTestSession session) {
        if (session.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new AuthBusinessException("STORAGE_TEST_SESSION_EXPIRED", HttpStatus.GONE, "storage test session expired");
        }
        if (!SESSION_COMPLETED.equalsIgnoreCase(session.getSessionStatus())) {
            throw new AuthBusinessException("STORAGE_TEST_SESSION_NOT_READY", HttpStatus.CONFLICT,
                    "storage test session is not ready for download");
        }
    }

    private void markClusterTestFailure(StorageCluster cluster, UUID operatorId, String errorMessage) {
        cluster.setHealthStatus(HEALTH_UNHEALTHY);
        cluster.setLastTestStatus(TEST_STATUS_FAILED);
        cluster.setLastTestedAt(OffsetDateTime.now());
        cluster.setLastCheckedAt(cluster.getLastTestedAt());
        cluster.setLastTestErrorCode("STORAGE_CLUSTER_TEST_FAILED");
        cluster.setLastTestErrorMessage(truncate(errorMessage, 512));
        cluster.setUpdatedBy(operatorId.toString());
        storageClusterRepository.save(cluster);
    }

    private StorageCluster getClusterOrThrow(UUID clusterId) {
        return storageClusterRepository.findById(clusterId)
                .orElseThrow(() -> new AuthBusinessException("STORAGE_CLUSTER_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "storage cluster not found"));
    }

    private StorageClusterTestSession getTestSessionOrThrow(UUID clusterId, UUID testSessionId) {
        StorageClusterTestSession session = storageClusterTestSessionRepository.findById(testSessionId)
                .orElseThrow(() -> new AuthBusinessException("STORAGE_TEST_SESSION_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "storage test session not found"));
        if (!clusterId.equals(session.getClusterId())) {
            throw new AuthBusinessException("STORAGE_TEST_SESSION_NOT_FOUND", HttpStatus.NOT_FOUND,
                    "storage test session not found");
        }
        return session;
    }

    private AuthStorageClusterResponseDto toClusterResponse(StorageCluster cluster) {
        AuthStorageClusterResponseDto response = new AuthStorageClusterResponseDto();
        response.setId(cluster.getId());
        response.setClusterCode(cluster.getClusterCode());
        response.setProviderType(cluster.getProviderType());
        response.setEndpoint(cluster.getEndpoint());
        response.setPublicEndpoint(cluster.getPublicEndpoint());
        response.setRegionName(cluster.getRegionName());
        response.setBucketPrefix(cluster.getBucketPrefix());
        response.setTestBucketName(cluster.getTestBucketName());
        response.setSecretRefPreview(maskSecretRef(cluster.getSecretRef()));
        response.setStatus(cluster.getStatus());
        response.setHealthStatus(cluster.getHealthStatus());
        response.setLastTestedAt(cluster.getLastTestedAt());
        response.setLastTestStatus(cluster.getLastTestStatus());
        response.setLastTestErrorCode(cluster.getLastTestErrorCode());
        response.setLastTestErrorMessage(cluster.getLastTestErrorMessage());
        response.setLastCheckedAt(cluster.getLastCheckedAt());
        response.setCreatedAt(cluster.getCreatedAt());
        response.setUpdatedAt(cluster.getUpdatedAt());
        return response;
    }

    private AuthStorageClusterTestSessionResponseDto toSessionResponse(StorageClusterTestSession session) {
        AuthStorageClusterTestSessionResponseDto response = new AuthStorageClusterTestSessionResponseDto();
        response.setTestSessionId(session.getId());
        response.setClusterId(session.getClusterId());
        response.setSessionStatus(session.getSessionStatus());
        response.setObjectKey(session.getObjectKey());
        response.setOriginalFileName(session.getOriginalFileName());
        response.setContentType(session.getContentType());
        response.setFileSize(session.getFileSize());
        response.setEtag(session.getEtag());
        response.setExpiresAt(session.getExpiresAt());
        response.setCompletedAt(session.getCompletedAt());
        return response;
    }

    private String normalizeClusterCode(String clusterCode) {
        String normalized = normalizeNullable(clusterCode);
        if (normalized == null) {
            throw new IllegalArgumentException("clusterCode is required");
        }
        String slug = normalized.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("-+", "-");
        slug = trimDash(slug);
        if (slug.length() < 3 || slug.length() > 64) {
            throw new IllegalArgumentException("clusterCode length must be between 3 and 64 after normalization");
        }
        return slug;
    }

    private String normalizeEndpoint(String endpoint, boolean required) {
        String normalized = normalizeNullable(endpoint);
        if (normalized == null) {
            if (required) {
                throw new IllegalArgumentException("endpoint is required");
            }
            return null;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeBucketSegment(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        String slug = normalized.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("-+", "-");
        slug = trimDash(slug);
        if (slug.length() < 3 || slug.length() > 40) {
            throw new IllegalArgumentException(fieldName + " length must be between 3 and 40 after normalization");
        }
        return slug;
    }

    private String normalizeBucketName(String value, String fieldName) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]+", "-").replaceAll("-+", "-");
        normalized = trimDash(normalized.replaceAll("\\.+", "."));
        if (normalized.length() < 3 || normalized.length() > 63) {
            throw new IllegalArgumentException(fieldName + " length must be between 3 and 63 after normalization");
        }
        return normalized;
    }

    private String buildTestObjectKey(String clusterCode, String uploadToken, String originalFileName) {
        return "admin-test/" + clusterCode + "/" + uploadToken + "/" + sanitizeFileName(originalFileName);
    }

    private String normalizeOriginalFileName(String originalFileName) {
        String normalized = normalizeNullable(originalFileName);
        if (normalized == null) {
            throw new IllegalArgumentException("originalFileName is required");
        }
        return normalized;
    }

    private String sanitizeFileName(String originalFileName) {
        String fileName = originalFileName.replace('\\', '/');
        int slashIndex = fileName.lastIndexOf('/');
        if (slashIndex >= 0) {
            fileName = fileName.substring(slashIndex + 1);
        }
        String normalized = fileName.replaceAll("[^A-Za-z0-9._-]+", "-");
        return normalized.isBlank() ? "test-object.bin" : normalized;
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String trimDash(String value) {
        String trimmed = value;
        while (trimmed.startsWith("-")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("-")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String maskSecretRef(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return null;
        }
        String normalized = secretRef.trim();
        if (normalized.length() <= 8) {
            return "****";
        }
        return normalized.substring(0, 4) + "****" + normalized.substring(normalized.length() - 2);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}