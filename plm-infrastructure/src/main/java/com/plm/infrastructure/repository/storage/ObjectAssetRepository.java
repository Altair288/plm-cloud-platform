package com.plm.infrastructure.repository.storage;

import com.plm.common.domain.storage.ObjectAsset;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ObjectAssetRepository extends JpaRepository<ObjectAsset, UUID> {
    Optional<ObjectAsset> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Optional<ObjectAsset> findByWorkspaceIdAndObjectKey(UUID workspaceId, String objectKey);

    List<ObjectAsset> findByBucketIdAndObjectStatusNot(UUID bucketId, String objectStatus);

    @Query("""
            select oa.bucketId as bucketId,
                   count(oa.id) as objectCount,
                   coalesce(sum(coalesce(oa.fileSize, 0)), 0) as usedBytes
            from ObjectAsset oa
            where oa.objectStatus = 'ACTIVE'
            group by oa.bucketId
            """)
    List<BucketUsageAggregate> summarizeActiveUsageByBucketId();

    interface BucketUsageAggregate {
        UUID getBucketId();

        long getObjectCount();

        long getUsedBytes();
    }
}