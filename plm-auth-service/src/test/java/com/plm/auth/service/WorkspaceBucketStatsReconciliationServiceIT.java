package com.plm.auth.service;

import com.plm.common.domain.auth.UserAccount;
import com.plm.common.domain.auth.Workspace;
import com.plm.common.domain.storage.ObjectAsset;
import com.plm.common.domain.storage.StorageCluster;
import com.plm.common.domain.storage.WorkspaceStorageBucket;
import com.plm.infrastructure.repository.auth.UserAccountRepository;
import com.plm.infrastructure.repository.auth.WorkspaceRepository;
import com.plm.infrastructure.repository.storage.ObjectAssetRepository;
import com.plm.infrastructure.repository.storage.StorageClusterRepository;
import com.plm.infrastructure.repository.storage.WorkspaceStorageBucketRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.main.lazy-initialization=true",
        "spring.main.allow-bean-definition-overriding=true"
})
@ActiveProfiles("dev")
@Transactional
class WorkspaceBucketStatsReconciliationServiceIT {

    @Autowired
    private WorkspaceBucketStatsReconciliationService workspaceBucketStatsReconciliationService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private StorageClusterRepository storageClusterRepository;

    @Autowired
    private WorkspaceStorageBucketRepository workspaceStorageBucketRepository;

    @Autowired
    private ObjectAssetRepository objectAssetRepository;

    @Test
    void reconcileBucketStats_shouldRefreshReadyAndFrozenBucketsFromActiveObjects() {
        UserAccount bootstrapAdmin = bootstrapAdmin();
        StorageCluster cluster = saveCluster("stats-" + uniqueSuffix());

        Workspace readyWorkspace = saveWorkspace(bootstrapAdmin, "stats-ready-" + uniqueSuffix());
        WorkspaceStorageBucket readyBucket = saveBucket(cluster, readyWorkspace, "READY", 999L, 99L);
        saveObject(readyWorkspace, readyBucket, bootstrapAdmin, "ACTIVE", 100L);
        saveObject(readyWorkspace, readyBucket, bootstrapAdmin, "ACTIVE", 60L);
        saveObject(readyWorkspace, readyBucket, bootstrapAdmin, "DELETED", 999L);

        Workspace frozenWorkspace = saveWorkspace(bootstrapAdmin, "stats-frozen-" + uniqueSuffix());
        WorkspaceStorageBucket frozenBucket = saveBucket(cluster, frozenWorkspace, "FROZEN", 777L, 77L);
        saveObject(frozenWorkspace, frozenBucket, bootstrapAdmin, "ACTIVE", 40L);

        workspaceBucketStatsReconciliationService.reconcileBucketStats();

        WorkspaceStorageBucket refreshedReadyBucket = workspaceStorageBucketRepository.findById(readyBucket.getId())
                .orElseThrow();
        WorkspaceStorageBucket refreshedFrozenBucket = workspaceStorageBucketRepository.findById(frozenBucket.getId())
                .orElseThrow();

        Assertions.assertEquals(160L, refreshedReadyBucket.getUsedBytes());
        Assertions.assertEquals(2L, refreshedReadyBucket.getObjectCount());
        Assertions.assertEquals("SYSTEM_BUCKET_STATS_RECONCILIATION", refreshedReadyBucket.getUpdatedBy());
        Assertions.assertEquals(40L, refreshedFrozenBucket.getUsedBytes());
        Assertions.assertEquals(1L, refreshedFrozenBucket.getObjectCount());
    }

    @Test
    void reconcileBucketStats_shouldZeroStaleStatsWhenBucketHasNoActiveObjects() {
        UserAccount bootstrapAdmin = bootstrapAdmin();
        StorageCluster cluster = saveCluster("stats-zero-" + uniqueSuffix());
        Workspace workspace = saveWorkspace(bootstrapAdmin, "stats-zero-workspace-" + uniqueSuffix());
        WorkspaceStorageBucket bucket = saveBucket(cluster, workspace, "READY", 500L, 5L);
        saveObject(workspace, bucket, bootstrapAdmin, "DELETED", 300L);
        saveObject(workspace, bucket, bootstrapAdmin, "UPLOAD_FAILED", 200L);

        workspaceBucketStatsReconciliationService.reconcileBucketStats();

        WorkspaceStorageBucket refreshedBucket = workspaceStorageBucketRepository.findById(bucket.getId())
                .orElseThrow();
        Assertions.assertEquals(0L, refreshedBucket.getUsedBytes());
        Assertions.assertEquals(0L, refreshedBucket.getObjectCount());
        Assertions.assertEquals("SYSTEM_BUCKET_STATS_RECONCILIATION", refreshedBucket.getUpdatedBy());
    }

    private UserAccount bootstrapAdmin() {
        return userAccountRepository.findByUsernameIgnoreCase("plm_admin")
                .orElseThrow(() -> new AssertionError("bootstrap admin should exist"));
    }

    private StorageCluster saveCluster(String clusterCode) {
        StorageCluster cluster = new StorageCluster();
        cluster.setClusterCode(clusterCode);
        cluster.setProviderType("MINIO");
        cluster.setEndpoint("http://127.0.0.1:9000");
        cluster.setPublicEndpoint("http://localhost:9000");
        cluster.setBucketPrefix("plm-stats");
        cluster.setTestBucketName("plm-stats-" + clusterCode + "-admin-test");
        cluster.setSecretRef("inline:minioadmin:minioadmin");
        cluster.setStatus("INACTIVE");
        cluster.setHealthStatus("HEALTHY");
        cluster.setLastTestStatus("PASSED");
        return storageClusterRepository.save(cluster);
    }

    private Workspace saveWorkspace(UserAccount owner, String workspaceCode) {
        Workspace workspace = new Workspace();
        workspace.setWorkspaceCode(workspaceCode);
        workspace.setWorkspaceName(workspaceCode);
        workspace.setWorkspaceStatus("ACTIVE");
        workspace.setOwnerUserId(owner.getId());
        workspace.setWorkspaceType("TEAM");
        workspace.setLifecycleStage("ACTIVE");
        workspace.setDefaultLocale("zh-CN");
        workspace.setDefaultTimezone("Asia/Shanghai");
        workspace.setCreatedBy(owner.getId().toString());
        return workspaceRepository.save(workspace);
    }

    private WorkspaceStorageBucket saveBucket(StorageCluster cluster,
                                             Workspace workspace,
                                             String bucketStatus,
                                             long usedBytes,
                                             long objectCount) {
        WorkspaceStorageBucket bucket = new WorkspaceStorageBucket();
        bucket.setWorkspaceId(workspace.getId());
        bucket.setClusterId(cluster.getId());
        bucket.setBucketName("plm-stats-ws-" + workspace.getId().toString().replace("-", ""));
        bucket.setBucketStatus(bucketStatus);
        bucket.setUsedBytes(usedBytes);
        bucket.setObjectCount(objectCount);
        bucket.setCreatedBy("TEST");
        return workspaceStorageBucketRepository.save(bucket);
    }

    private ObjectAsset saveObject(Workspace workspace,
                                   WorkspaceStorageBucket bucket,
                                   UserAccount uploader,
                                   String objectStatus,
                                   Long fileSize) {
        ObjectAsset objectAsset = new ObjectAsset();
        objectAsset.setWorkspaceId(workspace.getId());
        objectAsset.setBucketId(bucket.getId());
        objectAsset.setObjectKey("objects/document/2026/06/08/" + UUID.randomUUID());
        objectAsset.setObjectStatus(objectStatus);
        objectAsset.setBizType("DOCUMENT");
        objectAsset.setOriginalFileName("stats.txt");
        objectAsset.setContentType("text/plain");
        objectAsset.setFileSize(fileSize);
        objectAsset.setUploadedByUserId(uploader.getId());
        objectAsset.setCreatedBy(uploader.getId().toString());
        return objectAssetRepository.save(objectAsset);
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}