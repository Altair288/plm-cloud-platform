package com.plm.auth.service;

import com.plm.common.domain.storage.StorageCluster;
import com.plm.common.domain.storage.StorageClusterTestSession;
import com.plm.infrastructure.repository.storage.StorageClusterRepository;
import com.plm.infrastructure.repository.storage.StorageClusterTestSessionRepository;
import com.plm.infrastructure.storage.StorageGateway;
import com.plm.infrastructure.storage.StorageGatewayException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

@Service
public class PlatformStorageAdminCleanupService {
    private static final String SESSION_EXPIRED = "EXPIRED";
    private static final String SESSION_CLEANED = "CLEANED";

    private final StorageClusterTestSessionRepository storageClusterTestSessionRepository;
    private final StorageClusterRepository storageClusterRepository;
    private final StorageGateway storageGateway;

    public PlatformStorageAdminCleanupService(StorageClusterTestSessionRepository storageClusterTestSessionRepository,
                                              StorageClusterRepository storageClusterRepository,
                                              StorageGateway storageGateway) {
        this.storageClusterTestSessionRepository = storageClusterTestSessionRepository;
        this.storageClusterRepository = storageClusterRepository;
        this.storageGateway = storageGateway;
    }

    @Scheduled(fixedDelayString = "${plm.storage.admin.test-session-cleanup-interval-ms:300000}")
    @Transactional
    public void cleanupExpiredTestSessions() {
        List<StorageClusterTestSession> expiredSessions = storageClusterTestSessionRepository
                .findByExpiresAtBeforeAndSessionStatusNotIn(
                        OffsetDateTime.now(),
                        Set.of(SESSION_EXPIRED, SESSION_CLEANED));

        for (StorageClusterTestSession session : expiredSessions) {
            cleanupExpiredSession(session);
        }
    }

    private void cleanupExpiredSession(StorageClusterTestSession session) {
        storageClusterRepository.findById(session.getClusterId())
                .ifPresentOrElse(
                        cluster -> deleteExpiredObject(cluster, session),
                        () -> markExpired(session));
    }

    private void deleteExpiredObject(StorageCluster cluster, StorageClusterTestSession session) {
        try {
            storageGateway.deleteObject(cluster, session.getTestBucketName(), session.getObjectKey());
        } catch (StorageGatewayException ignored) {
            // 测试对象清理走 best-effort；对象缺失或网关失败不阻断会话过期回写。
        }
        markExpired(session);
    }

    private void markExpired(StorageClusterTestSession session) {
        session.setSessionStatus(SESSION_EXPIRED);
        session.setUpdatedBy("SYSTEM_STORAGE_TEST_SESSION_CLEANUP");
        storageClusterTestSessionRepository.save(session);
    }
}