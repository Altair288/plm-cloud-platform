package com.plm.infrastructure.storage;

import com.plm.common.domain.storage.StorageCluster;
import io.minio.BucketExistsArgs;
import io.minio.DeleteBucketEncryptionArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketEncryptionArgs;
import io.minio.SetBucketLifecycleArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.SseConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioStorageGatewayTest {

    @Test
    void ensureBucketExists_shouldCreateBucketAndApplyGovernance() throws Exception {
        StorageBucketGovernanceProperties properties = new StorageBucketGovernanceProperties();
        properties.setEnableServerSideEncryption(false);
        MinioClientFactory minioClientFactory = mock(MinioClientFactory.class);
        MinioClient minioClient = mock(MinioClient.class);
        when(minioClientFactory.create(any(), any(Boolean.class))).thenReturn(minioClient);
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        MinioStorageGateway gateway = new MinioStorageGateway(minioClientFactory, properties);
        StorageCluster cluster = newCluster("plm-test-admin-test");

        gateway.ensureBucketExists(cluster, cluster.getTestBucketName());

        verify(minioClient).bucketExists(any(BucketExistsArgs.class));
        verify(minioClient).makeBucket(any(MakeBucketArgs.class));
        verify(minioClient).setBucketPolicy(any(SetBucketPolicyArgs.class));
        verify(minioClient).setBucketLifecycle(any(SetBucketLifecycleArgs.class));
        verify(minioClient).deleteBucketEncryption(any(DeleteBucketEncryptionArgs.class));
        verify(minioClient, never()).setBucketEncryption(any(SetBucketEncryptionArgs.class));
    }

    @Test
    void buildLifecycleConfiguration_shouldIncludeAbortMultipartAndTestObjectExpiryForTestBucket() {
        StorageBucketGovernanceProperties properties = new StorageBucketGovernanceProperties();
        properties.setAbortIncompleteMultipartAfterDays(2);
        properties.setTestObjectExpireDays(1);
        MinioStorageGateway gateway = new MinioStorageGateway(mock(MinioClientFactory.class), properties);

        LifecycleConfiguration lifecycleConfiguration = gateway.buildLifecycleConfiguration(true);

        Assertions.assertNotNull(lifecycleConfiguration);
        Assertions.assertEquals(2, lifecycleConfiguration.rules().size());
        Assertions.assertEquals("abort-incomplete-multipart", lifecycleConfiguration.rules().get(0).id());
        Assertions.assertEquals("expire-cluster-test-objects", lifecycleConfiguration.rules().get(1).id());
    }

    @Test
    void buildLifecycleConfiguration_shouldOnlyIncludeAbortMultipartForWorkspaceBucket() {
        StorageBucketGovernanceProperties properties = new StorageBucketGovernanceProperties();
        properties.setAbortIncompleteMultipartAfterDays(1);
        properties.setTestObjectExpireDays(1);
        MinioStorageGateway gateway = new MinioStorageGateway(mock(MinioClientFactory.class), properties);

        LifecycleConfiguration lifecycleConfiguration = gateway.buildLifecycleConfiguration(false);

        Assertions.assertNotNull(lifecycleConfiguration);
        Assertions.assertEquals(1, lifecycleConfiguration.rules().size());
        Assertions.assertEquals("abort-incomplete-multipart", lifecycleConfiguration.rules().get(0).id());
    }

    @Test
    void applyBucketGovernance_shouldApplySseWhenEnabled() throws Exception {
        StorageBucketGovernanceProperties properties = new StorageBucketGovernanceProperties();
        properties.setEnableServerSideEncryption(true);
        MinioStorageGateway gateway = new MinioStorageGateway(mock(MinioClientFactory.class), properties);
        MinioClient minioClient = mock(MinioClient.class);
        StorageCluster cluster = newCluster("plm-test-admin-test");

        gateway.applyBucketGovernance(minioClient, cluster, cluster.getTestBucketName());

        ArgumentCaptor<SetBucketEncryptionArgs> encryptionArgsCaptor = ArgumentCaptor.forClass(SetBucketEncryptionArgs.class);
        verify(minioClient).setBucketEncryption(encryptionArgsCaptor.capture());
        verify(minioClient, never()).deleteBucketEncryption(any(DeleteBucketEncryptionArgs.class));
        SseConfiguration sseConfiguration = gateway.buildSseConfiguration();
        Assertions.assertNotNull(sseConfiguration);
        Assertions.assertNotNull(encryptionArgsCaptor.getValue());
    }

    private StorageCluster newCluster(String testBucketName) {
        StorageCluster cluster = new StorageCluster();
        cluster.setClusterCode("minio-dev");
        cluster.setProviderType("MINIO");
        cluster.setEndpoint("http://127.0.0.1:9000");
        cluster.setPublicEndpoint("http://localhost:9000");
        cluster.setBucketPrefix("plm-dev");
        cluster.setTestBucketName(testBucketName);
        cluster.setSecretRef("inline:minioadmin:minioadmin");
        cluster.setStatus("ACTIVE");
        cluster.setHealthStatus("HEALTHY");
        cluster.setLastTestStatus("PASSED");
        return cluster;
    }
}