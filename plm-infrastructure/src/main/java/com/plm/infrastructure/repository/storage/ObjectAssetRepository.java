package com.plm.infrastructure.repository.storage;

import com.plm.common.domain.storage.ObjectAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ObjectAssetRepository extends JpaRepository<ObjectAsset, UUID> {
    Optional<ObjectAsset> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Optional<ObjectAsset> findByWorkspaceIdAndObjectKey(UUID workspaceId, String objectKey);
}