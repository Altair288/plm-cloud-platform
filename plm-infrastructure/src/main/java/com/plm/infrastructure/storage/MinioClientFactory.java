package com.plm.infrastructure.storage;

import com.plm.common.domain.storage.StorageCluster;
import io.minio.MinioClient;

public interface MinioClientFactory {
    MinioClient create(StorageCluster cluster, boolean usePublicEndpoint);
}