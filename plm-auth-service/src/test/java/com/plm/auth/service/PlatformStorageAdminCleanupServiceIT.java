package com.plm.auth.service;

import com.plm.common.domain.storage.StorageCluster;
import com.plm.common.domain.storage.StorageClusterTestSession;
import com.plm.infrastructure.repository.auth.UserAccountRepository;
import com.plm.infrastructure.repository.storage.StorageClusterRepository;
import com.plm.infrastructure.repository.storage.StorageClusterTestSessionRepository;
import com.plm.infrastructure.storage.StorageGateway;
import com.plm.infrastructure.storage.StorageGatewayException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.main.lazy-initialization=true",
        "spring.main.allow-bean-definition-overriding=true"
})
@ActiveProfiles("dev")
@Transactional
class PlatformStorageAdminCleanupServiceIT {

    @Autowired
    private PlatformStorageAdminCleanupService platformStorageAdminCleanupService;

    @Autowired
    private StorageClusterRepository storageClusterRepository;

    @Autowired
    private StorageClusterTestSessionRepository storageClusterTestSessionRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @MockBean
    private StorageGateway storageGateway;

    @Test
    void cleanupExpiredTestSessions_shouldExpireSessionAndDeleteObject() {
        StorageCluster cluster = saveCluster("cleanup-" + uniqueSuffix());
        StorageClusterTestSession expiredSession = saveSession(cluster.getId(), OffsetDateTime.now().minusMinutes(5));
        StorageClusterTestSession activeSession = saveSession(cluster.getId(), OffsetDateTime.now().plusMinutes(5));

        doNothing().when(storageGateway).deleteObject(any(), eq(expiredSession.getTestBucketName()), eq(expiredSession.getObjectKey()));

        platformStorageAdminCleanupService.cleanupExpiredTestSessions();

        StorageClusterTestSession refreshedExpired = storageClusterTestSessionRepository.findById(expiredSession.getId())
                .orElseThrow(() -> new AssertionError("expired session should exist"));
        StorageClusterTestSession refreshedActive = storageClusterTestSessionRepository.findById(activeSession.getId())
                .orElseThrow(() -> new AssertionError("active session should exist"));

        Assertions.assertEquals("EXPIRED", refreshedExpired.getSessionStatus());
        Assertions.assertEquals("SYSTEM_STORAGE_TEST_SESSION_CLEANUP", refreshedExpired.getUpdatedBy());
        Assertions.assertEquals("INITIATED", refreshedActive.getSessionStatus());
        verify(storageGateway).deleteObject(any(), eq(expiredSession.getTestBucketName()), eq(expiredSession.getObjectKey()));
        verifyNoMoreInteractions(storageGateway);
    }

    @Test
    void cleanupExpiredTestSessions_shouldStillExpireSessionWhenDeleteFails() {
        StorageCluster cluster = saveCluster("cleanup-fail-" + uniqueSuffix());
        StorageClusterTestSession expiredSession = saveSession(cluster.getId(), OffsetDateTime.now().minusMinutes(5));

        doThrow(new StorageGatewayException("delete failed"))
                .when(storageGateway)
                .deleteObject(any(), eq(expiredSession.getTestBucketName()), eq(expiredSession.getObjectKey()));

        platformStorageAdminCleanupService.cleanupExpiredTestSessions();

        StorageClusterTestSession refreshedExpired = storageClusterTestSessionRepository.findById(expiredSession.getId())
                .orElseThrow(() -> new AssertionError("expired session should exist"));
        Assertions.assertEquals("EXPIRED", refreshedExpired.getSessionStatus());
        verify(storageGateway).deleteObject(any(), eq(expiredSession.getTestBucketName()), eq(expiredSession.getObjectKey()));
    }

    private StorageCluster saveCluster(String clusterCode) {
        StorageCluster cluster = new StorageCluster();
        cluster.setClusterCode(clusterCode);
        cluster.setProviderType("MINIO");
        cluster.setEndpoint("http://127.0.0.1:9000");
        cluster.setPublicEndpoint("http://localhost:9000");
        cluster.setBucketPrefix("plm-cleanup");
        cluster.setTestBucketName("plm-cleanup-" + clusterCode + "-admin-test");
        cluster.setSecretRef("inline:minioadmin:minioadmin");
        cluster.setStatus("INACTIVE");
        cluster.setHealthStatus("HEALTHY");
        cluster.setLastTestStatus("PASSED");
        return storageClusterRepository.save(cluster);
    }

    private StorageClusterTestSession saveSession(UUID clusterId, OffsetDateTime expiresAt) {
        UUID createdByUserId = userAccountRepository.findByUsernameIgnoreCase("plm_admin")
                .orElseThrow(() -> new AssertionError("bootstrap admin should exist"))
                .getId();
        StorageClusterTestSession session = new StorageClusterTestSession();
        session.setClusterId(clusterId);
        session.setTestBucketName("plm-cleanup-test-bucket");
        session.setObjectKey("__cluster_test__/2026/06/05/" + UUID.randomUUID());
        session.setSessionStatus("INITIATED");
        session.setUploadToken(UUID.randomUUID().toString().replace("-", ""));
        session.setOriginalFileName("cleanup.txt");
        session.setCreatedByUserId(createdByUserId);
        session.setCreatedBy("TEST");
        session.setExpiresAt(expiresAt);
        return storageClusterTestSessionRepository.save(session);
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}