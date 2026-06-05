package com.plm.auth.service;

import com.plm.auth.exception.AuthBusinessException;
import com.plm.auth.support.AuthDomainConstants;
import com.plm.common.api.dto.storage.StorageObjectCompleteUploadRequestDto;
import com.plm.common.api.dto.storage.StorageObjectDownloadUrlResponseDto;
import com.plm.common.api.dto.storage.StorageObjectResponseDto;
import com.plm.common.api.dto.storage.StorageObjectUploadIntentRequestDto;
import com.plm.common.api.dto.storage.StorageObjectUploadIntentResponseDto;
import com.plm.common.domain.storage.ObjectAsset;
import com.plm.common.domain.storage.ObjectUploadSession;
import com.plm.common.domain.storage.WorkspaceStorageBucket;
import com.plm.infrastructure.repository.storage.ObjectAssetRepository;
import com.plm.infrastructure.repository.storage.ObjectUploadSessionRepository;
import com.plm.infrastructure.repository.storage.WorkspaceStorageBucketRepository;
import com.plm.infrastructure.storage.StorageGateway;
import com.plm.infrastructure.storage.StorageGatewayException;
import com.plm.infrastructure.storage.StorageObjectStat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class WorkspaceObjectStorageService {
    private static final String OBJECT_STATUS_PENDING_UPLOAD = "PENDING_UPLOAD";
    private static final String OBJECT_STATUS_ACTIVE = "ACTIVE";
    private static final String OBJECT_STATUS_UPLOAD_FAILED = "UPLOAD_FAILED";
    private static final String OBJECT_STATUS_DELETING = "DELETING";
    private static final String OBJECT_STATUS_DELETED = "DELETED";
    private static final String SESSION_STATUS_INITIATED = "INITIATED";
    private static final String SESSION_STATUS_UPLOADED = "UPLOADED";
    private static final String SESSION_STATUS_COMPLETED = "COMPLETED";
    private static final String SESSION_STATUS_EXPIRED = "EXPIRED";
    private static final long UPLOAD_URL_EXPIRE_SECONDS = 900L;
    private static final long DOWNLOAD_URL_EXPIRE_SECONDS = 300L;

    private final WorkspaceStorageContextResolver workspaceStorageContextResolver;
    private final ObjectAssetRepository objectAssetRepository;
    private final ObjectUploadSessionRepository objectUploadSessionRepository;
    private final WorkspaceStorageBucketRepository workspaceStorageBucketRepository;
    private final StorageObjectKeyNamingPolicy storageObjectKeyNamingPolicy;
    private final StorageGateway storageGateway;

    public WorkspaceObjectStorageService(WorkspaceStorageContextResolver workspaceStorageContextResolver,
                                         ObjectAssetRepository objectAssetRepository,
                                         ObjectUploadSessionRepository objectUploadSessionRepository,
                                         WorkspaceStorageBucketRepository workspaceStorageBucketRepository,
                                         StorageObjectKeyNamingPolicy storageObjectKeyNamingPolicy,
                                         StorageGateway storageGateway) {
        this.workspaceStorageContextResolver = workspaceStorageContextResolver;
        this.objectAssetRepository = objectAssetRepository;
        this.objectUploadSessionRepository = objectUploadSessionRepository;
        this.workspaceStorageBucketRepository = workspaceStorageBucketRepository;
        this.storageObjectKeyNamingPolicy = storageObjectKeyNamingPolicy;
        this.storageGateway = storageGateway;
    }

    @Transactional
    public StorageObjectUploadIntentResponseDto createUploadIntent(UUID userId,
                                                                   UUID workspaceId,
                                                                   StorageObjectUploadIntentRequestDto request) {
        WorkspaceStorageContextResolver.ResolvedWorkspaceStorage storage = workspaceStorageContextResolver.requireReadyStorage(
                userId,
                workspaceId,
                AuthDomainConstants.PERMISSION_STORAGE_OBJECT_UPLOAD);
        UploadIntentCommand command = normalizeUploadIntent(request, userId, workspaceId, storage.bucket().getId());
        ObjectAsset objectAsset = objectAssetRepository.save(newObjectAsset(command));
        ObjectUploadSession uploadSession = objectUploadSessionRepository.save(newUploadSession(command, objectAsset.getId()));
        String uploadUrl = createUploadUrl(storage, objectAsset.getObjectKey());
        return toUploadIntentResponse(uploadSession, storage.bucket(), objectAsset.getObjectKey(), uploadUrl);
    }

    @Transactional
    public StorageObjectResponseDto completeUpload(UUID userId,
                                                   UUID workspaceId,
                                                   UUID objectId,
                                                   StorageObjectCompleteUploadRequestDto request) {
        WorkspaceStorageContextResolver.ResolvedWorkspaceStorage storage = workspaceStorageContextResolver.requireReadyStorage(
                userId,
                workspaceId,
                AuthDomainConstants.PERMISSION_STORAGE_OBJECT_UPLOAD);
        ObjectAsset objectAsset = getObjectAssetOrThrow(workspaceId, objectId);
        ObjectUploadSession uploadSession = getUploadSessionOrThrow(objectAsset.getId(), request == null ? null : request.getUploadToken());
        assertUploadCompletable(objectAsset, uploadSession, userId);
        try {
            StorageObjectStat objectStat = storageGateway.statObject(storage.cluster(), storage.bucket().getBucketName(), objectAsset.getObjectKey());
            uploadSession.setSessionStatus(SESSION_STATUS_UPLOADED);
            validateUploadedObject(uploadSession, objectStat, objectAsset, userId);
            uploadSession.setSessionStatus(SESSION_STATUS_COMPLETED);
            uploadSession.setCompletedAt(OffsetDateTime.now());
            uploadSession.setUpdatedBy(userId.toString());
            objectAsset.setObjectStatus(OBJECT_STATUS_ACTIVE);
            objectAsset.setFileSize(objectStat.size());
            objectAsset.setEtag(objectStat.etag());
            objectAsset.setUpdatedBy(userId.toString());
            incrementBucketStats(storage.bucket(), objectStat.size(), userId);
            objectUploadSessionRepository.save(uploadSession);
            return toObjectResponse(objectAssetRepository.save(objectAsset));
        } catch (StorageGatewayException ex) {
            markUploadFailed(objectAsset, uploadSession, userId, ex.getMessage());
            throw new AuthBusinessException("OBJECT_UPLOAD_NOT_COMPLETED", HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public StorageObjectDownloadUrlResponseDto createDownloadUrl(UUID userId, UUID workspaceId, UUID objectId) {
        WorkspaceStorageContextResolver.ResolvedWorkspaceStorage storage = workspaceStorageContextResolver.requireReadyStorage(
                userId,
                workspaceId,
                AuthDomainConstants.PERMISSION_STORAGE_OBJECT_DOWNLOAD);
        ObjectAsset objectAsset = getObjectAssetOrThrow(workspaceId, objectId);
        assertObjectActive(objectAsset);
        try {
            StorageObjectDownloadUrlResponseDto response = new StorageObjectDownloadUrlResponseDto();
            response.setObjectId(objectAsset.getId());
            response.setDownloadUrl(storageGateway.createPresignedDownloadUrl(
                    storage.cluster(),
                    storage.bucket().getBucketName(),
                    objectAsset.getObjectKey(),
                    Duration.ofSeconds(DOWNLOAD_URL_EXPIRE_SECONDS)));
            response.setExpireAt(OffsetDateTime.now().plusSeconds(DOWNLOAD_URL_EXPIRE_SECONDS));
            return response;
        } catch (StorageGatewayException ex) {
            throw new AuthBusinessException("OBJECT_ACCESS_DENIED", HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
    }

    @Transactional
    public void deleteObject(UUID userId, UUID workspaceId, UUID objectId) {
        WorkspaceStorageContextResolver.ResolvedWorkspaceStorage storage = workspaceStorageContextResolver.requireReadyStorage(
                userId,
                workspaceId,
                AuthDomainConstants.PERMISSION_STORAGE_OBJECT_DELETE);
        ObjectAsset objectAsset = getObjectAssetOrThrow(workspaceId, objectId);
        assertObjectDeletable(objectAsset);
        objectAsset.setObjectStatus(OBJECT_STATUS_DELETING);
        objectAsset.setUpdatedBy(userId.toString());
        objectAssetRepository.save(objectAsset);
        try {
            storageGateway.deleteObject(storage.cluster(), storage.bucket().getBucketName(), objectAsset.getObjectKey());
        } catch (StorageGatewayException ex) {
            objectAsset.setObjectStatus(OBJECT_STATUS_ACTIVE);
            objectAsset.setUpdatedBy(userId.toString());
            objectAssetRepository.save(objectAsset);
            throw new AuthBusinessException("OBJECT_ACCESS_DENIED", HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
        objectAsset.setObjectStatus(OBJECT_STATUS_DELETED);
        objectAsset.setUpdatedBy(userId.toString());
        decrementBucketStats(storage.bucket(), objectAsset.getFileSize(), userId);
        objectAssetRepository.save(objectAsset);
    }

    private UploadIntentCommand normalizeUploadIntent(StorageObjectUploadIntentRequestDto request,
                                                      UUID userId,
                                                      UUID workspaceId,
                                                      UUID bucketId) {
        if (request == null) {
            throw new IllegalArgumentException("storage object upload request is required");
        }
        String bizType = requireText(request.getBizType(), "bizType is required");
        String fileName = requireText(request.getFileName(), "fileName is required");
        String contentType = normalizeNullable(request.getContentType());
        Long expectedSize = request.getExpectedSize();
        if (expectedSize != null && expectedSize <= 0L) {
            throw new IllegalArgumentException("expectedSize must be > 0");
        }
        UUID objectId = UUID.randomUUID();
        String objectKey = storageObjectKeyNamingPolicy.generateObjectKey(bizType, objectId, OffsetDateTime.now());
        OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(UPLOAD_URL_EXPIRE_SECONDS);
        return new UploadIntentCommand(
                objectId,
                workspaceId,
                bucketId,
                bizType,
                request.getBizRefId(),
                fileName,
                contentType,
                expectedSize,
                userId,
                objectKey,
                UUID.randomUUID().toString().replace("-", ""),
                expiresAt);
    }

    private ObjectAsset newObjectAsset(UploadIntentCommand command) {
        ObjectAsset objectAsset = new ObjectAsset();
        objectAsset.setId(command.objectId());
        objectAsset.setWorkspaceId(command.workspaceId());
        objectAsset.setBucketId(command.bucketId());
        objectAsset.setObjectKey(command.objectKey());
        objectAsset.setObjectStatus(OBJECT_STATUS_PENDING_UPLOAD);
        objectAsset.setBizType(command.bizType());
        objectAsset.setBizRefId(command.bizRefId());
        objectAsset.setOriginalFileName(command.fileName());
        objectAsset.setContentType(command.contentType());
        objectAsset.setUploadedByUserId(command.createdByUserId());
        objectAsset.setVisibilityScope("WORKSPACE");
        objectAsset.setCreatedBy(command.createdByUserId().toString());
        return objectAsset;
    }

    private ObjectUploadSession newUploadSession(UploadIntentCommand command, UUID objectId) {
        ObjectUploadSession uploadSession = new ObjectUploadSession();
        uploadSession.setObjectId(objectId);
        uploadSession.setWorkspaceId(command.workspaceId());
        uploadSession.setSessionStatus(SESSION_STATUS_INITIATED);
        uploadSession.setUploadToken(command.uploadToken());
        uploadSession.setPresignedMethod("PUT");
        uploadSession.setExpiresAt(command.expiresAt());
        uploadSession.setExpectedContentType(command.contentType());
        uploadSession.setExpectedMaxSize(command.expectedSize());
        uploadSession.setCreatedByUserId(command.createdByUserId());
        uploadSession.setCreatedBy(command.createdByUserId().toString());
        return uploadSession;
    }

    private String createUploadUrl(WorkspaceStorageContextResolver.ResolvedWorkspaceStorage storage, String objectKey) {
        try {
            return storageGateway.createPresignedUploadUrl(
                    storage.cluster(),
                    storage.bucket().getBucketName(),
                    objectKey,
                    Duration.ofSeconds(UPLOAD_URL_EXPIRE_SECONDS));
        } catch (StorageGatewayException ex) {
            throw new AuthBusinessException("WORKSPACE_BUCKET_NOT_READY", HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
    }

    private ObjectAsset getObjectAssetOrThrow(UUID workspaceId, UUID objectId) {
        return objectAssetRepository.findByIdAndWorkspaceId(objectId, workspaceId)
                .orElseThrow(() -> new AuthBusinessException("OBJECT_NOT_FOUND", HttpStatus.NOT_FOUND, "object not found"));
    }

    private ObjectUploadSession getUploadSessionOrThrow(UUID objectId, String uploadToken) {
        String normalizedUploadToken = requireText(uploadToken, "uploadToken is required");
        return objectUploadSessionRepository.findByUploadTokenAndObjectId(normalizedUploadToken, objectId)
                .orElseThrow(() -> new AuthBusinessException("OBJECT_UPLOAD_NOT_COMPLETED", HttpStatus.CONFLICT,
                        "object upload session not found"));
    }

    private void assertUploadCompletable(ObjectAsset objectAsset, ObjectUploadSession uploadSession, UUID userId) {
        if (uploadSession.getExpiresAt().isBefore(OffsetDateTime.now())) {
            uploadSession.setSessionStatus(SESSION_STATUS_EXPIRED);
            uploadSession.setUpdatedBy(userId.toString());
            objectUploadSessionRepository.save(uploadSession);
            objectAsset.setObjectStatus(OBJECT_STATUS_UPLOAD_FAILED);
            objectAsset.setUpdatedBy(userId.toString());
            objectAssetRepository.save(objectAsset);
            throw new AuthBusinessException("OBJECT_UPLOAD_SESSION_EXPIRED", HttpStatus.GONE,
                    "object upload session expired");
        }
        if (OBJECT_STATUS_DELETED.equalsIgnoreCase(objectAsset.getObjectStatus())) {
            throw new AuthBusinessException("OBJECT_ALREADY_DELETED", HttpStatus.CONFLICT, "object already deleted");
        }
    }

    private void validateUploadedObject(ObjectUploadSession uploadSession,
                                        StorageObjectStat objectStat,
                                        ObjectAsset objectAsset,
                                        UUID userId) {
        if (uploadSession.getExpectedMaxSize() != null && !uploadSession.getExpectedMaxSize().equals(objectStat.size())) {
            markUploadFailed(objectAsset, uploadSession, userId, "uploaded object size mismatch");
            throw new AuthBusinessException("OBJECT_UPLOAD_NOT_COMPLETED", HttpStatus.CONFLICT,
                    "uploaded object size mismatch");
        }
    }

    private void markUploadFailed(ObjectAsset objectAsset,
                                  ObjectUploadSession uploadSession,
                                  UUID userId,
                                  String errorMessage) {
        uploadSession.setUpdatedBy(userId.toString());
        objectUploadSessionRepository.save(uploadSession);
        objectAsset.setObjectStatus(OBJECT_STATUS_UPLOAD_FAILED);
        objectAsset.setUpdatedBy(userId.toString());
        objectAssetRepository.save(objectAsset);
    }

    private void assertObjectActive(ObjectAsset objectAsset) {
        if (!OBJECT_STATUS_ACTIVE.equalsIgnoreCase(objectAsset.getObjectStatus())) {
            throw new AuthBusinessException("OBJECT_ACCESS_DENIED", HttpStatus.CONFLICT,
                    "object is not active");
        }
    }

    private void assertObjectDeletable(ObjectAsset objectAsset) {
        if (OBJECT_STATUS_DELETED.equalsIgnoreCase(objectAsset.getObjectStatus())) {
            throw new AuthBusinessException("OBJECT_ALREADY_DELETED", HttpStatus.CONFLICT,
                    "object already deleted");
        }
    }

    private void incrementBucketStats(WorkspaceStorageBucket bucket, long fileSize, UUID userId) {
        long currentUsedBytes = bucket.getUsedBytes() == null ? 0L : bucket.getUsedBytes();
        long currentObjectCount = bucket.getObjectCount() == null ? 0L : bucket.getObjectCount();
        bucket.setUsedBytes(currentUsedBytes + fileSize);
        bucket.setObjectCount(currentObjectCount + 1L);
        bucket.setUpdatedBy(userId.toString());
        workspaceStorageBucketRepository.save(bucket);
    }

    private void decrementBucketStats(WorkspaceStorageBucket bucket, Long fileSize, UUID userId) {
        long currentUsedBytes = bucket.getUsedBytes() == null ? 0L : bucket.getUsedBytes();
        long currentObjectCount = bucket.getObjectCount() == null ? 0L : bucket.getObjectCount();
        long deleteBytes = fileSize == null ? 0L : fileSize;
        bucket.setUsedBytes(Math.max(0L, currentUsedBytes - deleteBytes));
        bucket.setObjectCount(Math.max(0L, currentObjectCount - 1L));
        bucket.setUpdatedBy(userId.toString());
        workspaceStorageBucketRepository.save(bucket);
    }

    private StorageObjectUploadIntentResponseDto toUploadIntentResponse(ObjectUploadSession uploadSession,
                                                                        WorkspaceStorageBucket bucket,
                                                                        String objectKey,
                                                                        String uploadUrl) {
        StorageObjectUploadIntentResponseDto response = new StorageObjectUploadIntentResponseDto();
        response.setObjectId(uploadSession.getObjectId());
        response.setUploadToken(uploadSession.getUploadToken());
        response.setBucketName(bucket.getBucketName());
        response.setObjectKey(objectKey);
        response.setPresignedUploadUrl(uploadUrl);
        response.setExpireAt(uploadSession.getExpiresAt());
        return response;
    }

    private StorageObjectResponseDto toObjectResponse(ObjectAsset objectAsset) {
        StorageObjectResponseDto response = new StorageObjectResponseDto();
        response.setObjectId(objectAsset.getId());
        response.setWorkspaceId(objectAsset.getWorkspaceId());
        response.setObjectKey(objectAsset.getObjectKey());
        response.setObjectStatus(objectAsset.getObjectStatus());
        response.setBizType(objectAsset.getBizType());
        response.setBizRefId(objectAsset.getBizRefId());
        response.setOriginalFileName(objectAsset.getOriginalFileName());
        response.setContentType(objectAsset.getContentType());
        response.setFileSize(objectAsset.getFileSize());
        response.setEtag(objectAsset.getEtag());
        response.setVisibilityScope(objectAsset.getVisibilityScope());
        response.setCreatedAt(objectAsset.getCreatedAt());
        response.setUpdatedAt(objectAsset.getUpdatedAt());
        return response;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private record UploadIntentCommand(UUID objectId,
                                       UUID workspaceId,
                                       UUID bucketId,
                                       String bizType,
                                       UUID bizRefId,
                                       String fileName,
                                       String contentType,
                                       Long expectedSize,
                                       UUID createdByUserId,
                                       String objectKey,
                                       String uploadToken,
                                       OffsetDateTime expiresAt) {
    }
}