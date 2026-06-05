package com.plm.infrastructure.repository.storage;

import com.plm.common.domain.storage.WorkspaceStorageBucket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceStorageBucketRepository extends JpaRepository<WorkspaceStorageBucket, UUID> {
    Optional<WorkspaceStorageBucket> findByWorkspaceId(UUID workspaceId);

    Optional<WorkspaceStorageBucket> findByBucketName(String bucketName);
}