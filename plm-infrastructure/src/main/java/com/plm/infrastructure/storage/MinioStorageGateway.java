package com.plm.infrastructure.storage;

import com.plm.common.domain.storage.StorageCluster;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class MinioStorageGateway implements StorageGateway {
    private final StorageSecretResolver storageSecretResolver;

    public MinioStorageGateway(StorageSecretResolver storageSecretResolver) {
        this.storageSecretResolver = storageSecretResolver;
    }

    @Override
    public void verifyCluster(StorageCluster cluster) {
        execute(cluster, false, client -> {
            client.listBuckets();
            return null;
        }, "failed to verify storage cluster connection");
    }

    @Override
    public void ensureBucketExists(StorageCluster cluster, String bucketName) {
        execute(cluster, false, client -> {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
            return null;
        }, "failed to ensure bucket exists");
    }

    @Override
    public String createPresignedUploadUrl(StorageCluster cluster,
                                           String bucketName,
                                           String objectKey,
                                           Duration expiry) {
        return createPresignedUrl(cluster, bucketName, objectKey, expiry, Method.PUT);
    }

    @Override
    public String createPresignedDownloadUrl(StorageCluster cluster,
                                             String bucketName,
                                             String objectKey,
                                             Duration expiry) {
        return createPresignedUrl(cluster, bucketName, objectKey, expiry, Method.GET);
    }

    @Override
    public StorageObjectStat statObject(StorageCluster cluster, String bucketName, String objectKey) {
        return execute(cluster, false, client -> {
            StatObjectResponse response = client.statObject(
                    StatObjectArgs.builder().bucket(bucketName).object(objectKey).build());
            return new StorageObjectStat(response.etag(), response.size());
        }, "failed to stat storage object");
    }

    @Override
    public void deleteObject(StorageCluster cluster, String bucketName, String objectKey) {
        execute(cluster, false, client -> {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucketName).object(objectKey).build());
            return null;
        }, "failed to delete storage object");
    }

    private String createPresignedUrl(StorageCluster cluster,
                                      String bucketName,
                                      String objectKey,
                                      Duration expiry,
                                      Method method) {
        return execute(cluster, true, client -> client.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(method)
                        .bucket(bucketName)
                        .object(objectKey)
                        .expiry(toExpirySeconds(expiry))
                        .build()), "failed to create presigned object url");
    }

    private int toExpirySeconds(Duration expiry) {
        long expirySeconds = expiry == null ? 900L : expiry.getSeconds();
        if (expirySeconds <= 0 || expirySeconds > 604800) {
            throw new IllegalArgumentException("presigned url expiry must be between 1 and 604800 seconds");
        }
        return (int) expirySeconds;
    }

    private MinioClient buildClient(StorageCluster cluster, boolean usePublicEndpoint) {
        StorageCredentials credentials = storageSecretResolver.resolve(cluster.getSecretRef());
        String endpoint = usePublicEndpoint && cluster.getPublicEndpoint() != null && !cluster.getPublicEndpoint().isBlank()
                ? cluster.getPublicEndpoint().trim()
                : cluster.getEndpoint().trim();
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(credentials.accessKey(), credentials.secretKey());
        if (cluster.getRegionName() != null && !cluster.getRegionName().isBlank()) {
            builder.region(cluster.getRegionName().trim());
        }
        return builder.build();
    }

    private <T> T execute(StorageCluster cluster,
                          boolean usePublicEndpoint,
                          StorageClientOperation<T> operation,
                          String failureMessage) {
        try {
            return operation.apply(buildClient(cluster, usePublicEndpoint));
        } catch (Exception ex) {
            throw new StorageGatewayException(failureMessage + ": " + ex.getMessage(), ex);
        }
    }

    @FunctionalInterface
    private interface StorageClientOperation<T> {
        T apply(MinioClient client) throws Exception;
    }
}