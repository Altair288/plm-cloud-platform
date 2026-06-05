package com.plm.infrastructure.repository.storage;

import com.plm.common.domain.storage.StorageClusterTestSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StorageClusterTestSessionRepository extends JpaRepository<StorageClusterTestSession, UUID> {
    Optional<StorageClusterTestSession> findByUploadToken(String uploadToken);

    List<StorageClusterTestSession> findByClusterIdOrderByCreatedAtDesc(UUID clusterId);

    List<StorageClusterTestSession> findByExpiresAtBefore(OffsetDateTime expiresAt);

    List<StorageClusterTestSession> findByExpiresAtBeforeAndSessionStatusNotIn(OffsetDateTime expiresAt,
                                                                               Collection<String> sessionStatuses);
}