package com.plm.infrastructure.storage;

import com.plm.common.domain.storage.StorageCluster;

import java.time.Duration;

public interface StorageGateway {
    void verifyCluster(StorageCluster cluster);

    void ensureBucketExists(StorageCluster cluster, String bucketName);

    String createPresignedUploadUrl(StorageCluster cluster,
                                    String bucketName,
                                    String objectKey,
                                    Duration expiry);

    String createPresignedDownloadUrl(StorageCluster cluster,
                                      String bucketName,
                                      String objectKey,
                                      Duration expiry);

    StorageObjectStat statObject(StorageCluster cluster, String bucketName, String objectKey);

    void deleteObject(StorageCluster cluster, String bucketName, String objectKey);
}