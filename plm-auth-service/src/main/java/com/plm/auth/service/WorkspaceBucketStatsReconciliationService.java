package com.plm.auth.service;

import com.plm.common.domain.storage.WorkspaceStorageBucket;
import com.plm.infrastructure.repository.storage.ObjectAssetRepository;
import com.plm.infrastructure.repository.storage.WorkspaceStorageBucketRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WorkspaceBucketStatsReconciliationService {
    private static final String BUCKET_STATUS_READY = "READY";
    private static final String BUCKET_STATUS_FROZEN = "FROZEN";
    private static final String UPDATED_BY = "SYSTEM_BUCKET_STATS_RECONCILIATION";

    private final WorkspaceStorageBucketRepository workspaceStorageBucketRepository;
    private final ObjectAssetRepository objectAssetRepository;

    public WorkspaceBucketStatsReconciliationService(WorkspaceStorageBucketRepository workspaceStorageBucketRepository,
                                                    ObjectAssetRepository objectAssetRepository) {
        this.workspaceStorageBucketRepository = workspaceStorageBucketRepository;
        this.objectAssetRepository = objectAssetRepository;
    }

    @Scheduled(fixedDelayString = "${plm.storage.workspace.stats-reconciliation-interval-ms:900000}")
    @Transactional
    public void reconcileBucketStats() {
        List<WorkspaceStorageBucket> trackedBuckets = workspaceStorageBucketRepository.findByBucketStatusIn(
                Set.of(BUCKET_STATUS_READY, BUCKET_STATUS_FROZEN));
        if (trackedBuckets.isEmpty()) {
            return;
        }

        Map<UUID, ObjectAssetRepository.BucketUsageAggregate> usageByBucketId = objectAssetRepository
                .summarizeActiveUsageByBucketId().stream()
                .collect(Collectors.toMap(ObjectAssetRepository.BucketUsageAggregate::getBucketId, Function.identity()));

        List<WorkspaceStorageBucket> changedBuckets = trackedBuckets.stream()
                .filter(bucket -> applyUsage(bucket, usageByBucketId.get(bucket.getId())))
                .toList();
        if (!changedBuckets.isEmpty()) {
            workspaceStorageBucketRepository.saveAll(changedBuckets);
        }
    }

    private boolean applyUsage(WorkspaceStorageBucket bucket, ObjectAssetRepository.BucketUsageAggregate usage) {
        long expectedUsedBytes = usage == null ? 0L : usage.getUsedBytes();
        long expectedObjectCount = usage == null ? 0L : usage.getObjectCount();
        long currentUsedBytes = bucket.getUsedBytes() == null ? 0L : bucket.getUsedBytes();
        long currentObjectCount = bucket.getObjectCount() == null ? 0L : bucket.getObjectCount();
        if (currentUsedBytes == expectedUsedBytes && currentObjectCount == expectedObjectCount) {
            return false;
        }
        bucket.setUsedBytes(expectedUsedBytes);
        bucket.setObjectCount(expectedObjectCount);
        bucket.setUpdatedBy(UPDATED_BY);
        return true;
    }
}