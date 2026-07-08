package com.plm.infrastructure.storage;

import com.plm.common.domain.storage.StorageCluster;
import io.minio.MinioClient;
import org.springframework.stereotype.Component;

@Component
public class DefaultMinioClientFactory implements MinioClientFactory {
    private final StorageSecretResolver storageSecretResolver;

    public DefaultMinioClientFactory(StorageSecretResolver storageSecretResolver) {
        this.storageSecretResolver = storageSecretResolver;
    }

    @Override
    public MinioClient create(StorageCluster cluster, boolean usePublicEndpoint) {
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
}