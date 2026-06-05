package com.plm.auth.controller;

import cn.dev33.satoken.exception.SaTokenContextException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.auth.config.AuthPlatformAdminBootstrapProperties;
import com.plm.auth.service.RegisterEmailSender;
import com.plm.auth.support.AuthStpKit;
import com.plm.common.api.dto.auth.AuthPasswordEncryptionKeyResponseDto;
import com.plm.common.api.dto.auth.AuthPasswordLoginRequestDto;
import com.plm.common.api.dto.auth.AuthPlatformAdminLoginResponseDto;
import com.plm.common.api.dto.auth.AuthStorageClusterResponseDto;
import com.plm.common.api.dto.auth.AuthStorageClusterTestConnectionResponseDto;
import com.plm.common.api.dto.auth.AuthStorageClusterTestDownloadResponseDto;
import com.plm.common.api.dto.auth.AuthStorageClusterTestSessionResponseDto;
import com.plm.common.api.dto.auth.AuthStorageClusterTestUploadRequestDto;
import com.plm.common.api.dto.auth.AuthStorageClusterTestUploadResponseDto;
import com.plm.common.api.dto.auth.AuthStorageClusterUpsertRequestDto;
import com.plm.common.domain.auth.PlatformRole;
import com.plm.common.domain.auth.Permission;
import com.plm.infrastructure.repository.auth.PermissionRepository;
import com.plm.infrastructure.repository.auth.PlatformRoleRepository;
import com.plm.infrastructure.storage.StorageGateway;
import com.plm.infrastructure.storage.StorageObjectStat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.main.lazy-initialization=true",
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class PlatformAdminStorageControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthPlatformAdminBootstrapProperties authPlatformAdminBootstrapProperties;

        @Autowired
        private PlatformRoleRepository platformRoleRepository;

        @Autowired
        private PermissionRepository permissionRepository;

        @Autowired
        private JdbcTemplate jdbcTemplate;

    @MockBean
    private StorageGateway storageGateway;

    @MockBean
    private RegisterEmailSender registerEmailSender;

    @AfterEach
    void tearDownSessions() {
        reset(storageGateway, registerEmailSender);
        try {
            if (AuthStpKit.WORKSPACE.isLogin()) {
                AuthStpKit.WORKSPACE.logout();
            }
            if (AuthStpKit.PLATFORM.isLogin()) {
                AuthStpKit.PLATFORM.logout();
            }
        } catch (SaTokenContextException ignored) {
            // MockMvc 请求结束后测试线程没有 Sa-Token request context，忽略即可。
        }
    }

    @Test
    void platformAdminStorage_shouldCreateUpdateAndListCluster() throws Exception {
        AuthPlatformAdminLoginResponseDto loginResponse = adminLogin(
                authPlatformAdminBootstrapProperties.getUsername(),
                authPlatformAdminBootstrapProperties.getPassword());

        AuthStorageClusterUpsertRequestDto createRequest = new AuthStorageClusterUpsertRequestDto();
        createRequest.setClusterCode("minio-" + uniqueSuffix());
        createRequest.setEndpoint("http://127.0.0.1:9000");
        createRequest.setPublicEndpoint("http://localhost:9000");
        createRequest.setBucketPrefix("plm-dev");
        createRequest.setSecretRef("inline:minioadmin:minioadmin");
        createRequest.setStatus("ACTIVE");

        AuthStorageClusterResponseDto created = readValue(
                mockMvc.perform(post("/auth/platform-admin/storage/clusters")
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(createRequest)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.clusterCode").value(createRequest.getClusterCode()))
                        .andExpect(jsonPath("$.status").value("ACTIVE"))
                        .andExpect(jsonPath("$.testBucketName").isNotEmpty())
                        .andReturn(),
                AuthStorageClusterResponseDto.class);

        createRequest.setPublicEndpoint("http://minio.example.internal");
        mockMvc.perform(put("/auth/platform-admin/storage/clusters/{clusterId}", created.getId())
                        .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(createRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicEndpoint").value("http://minio.example.internal"));

        mockMvc.perform(get("/auth/platform-admin/storage/clusters")
                        .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(created.getId().toString()))
                .andExpect(jsonPath("$[0].clusterCode").value(created.getClusterCode()));
    }

    @Test
    void platformAdminStorage_shouldRunConnectionUploadDownloadCleanupFlow() throws Exception {
        doNothing().when(storageGateway).verifyCluster(any());
        doNothing().when(storageGateway).ensureBucketExists(any(), anyString());
        doNothing().when(storageGateway).deleteObject(any(), anyString(), anyString());
        when(storageGateway.createPresignedUploadUrl(any(), anyString(), anyString(), any(Duration.class)))
                .thenReturn("http://minio.local/upload");
        when(storageGateway.createPresignedDownloadUrl(any(), anyString(), anyString(), any(Duration.class)))
                .thenReturn("http://minio.local/download");
        when(storageGateway.statObject(any(), anyString(), anyString()))
                .thenReturn(new StorageObjectStat("etag-123", 128L));

        AuthPlatformAdminLoginResponseDto loginResponse = adminLogin(
                authPlatformAdminBootstrapProperties.getUsername(),
                authPlatformAdminBootstrapProperties.getPassword());

        AuthStorageClusterUpsertRequestDto createRequest = new AuthStorageClusterUpsertRequestDto();
        createRequest.setClusterCode("storage-" + uniqueSuffix());
        createRequest.setEndpoint("http://127.0.0.1:9000");
        createRequest.setBucketPrefix("plm-dev");
        createRequest.setSecretRef("inline:minioadmin:minioadmin");
        createRequest.setStatus("ACTIVE");

        AuthStorageClusterResponseDto cluster = readValue(
                mockMvc.perform(post("/auth/platform-admin/storage/clusters")
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(createRequest)))
                        .andExpect(status().isOk())
                        .andReturn(),
                AuthStorageClusterResponseDto.class);

        AuthStorageClusterTestConnectionResponseDto connectionResponse = readValue(
                mockMvc.perform(post("/auth/platform-admin/storage/clusters/{clusterId}/test-connection", cluster.getId())
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.lastTestStatus").value("PASSED"))
                        .andReturn(),
                AuthStorageClusterTestConnectionResponseDto.class);
        Assertions.assertEquals(cluster.getClusterCode(), connectionResponse.getClusterCode());

        AuthStorageClusterTestUploadRequestDto uploadRequest = new AuthStorageClusterTestUploadRequestDto();
        uploadRequest.setOriginalFileName("check-file.txt");
        uploadRequest.setContentType("text/plain");
        uploadRequest.setFileSize(128L);

        AuthStorageClusterTestUploadResponseDto uploadResponse = readValue(
                mockMvc.perform(post("/auth/platform-admin/storage/clusters/{clusterId}/test-upload-intents", cluster.getId())
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(uploadRequest)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.uploadUrl").value("http://minio.local/upload"))
                        .andReturn(),
                AuthStorageClusterTestUploadResponseDto.class);

        AuthStorageClusterTestSessionResponseDto completedResponse = readValue(
                mockMvc.perform(post("/auth/platform-admin/storage/clusters/{clusterId}/test-sessions/{testSessionId}/complete",
                                cluster.getId(), uploadResponse.getTestSessionId())
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.sessionStatus").value("COMPLETED"))
                        .andExpect(jsonPath("$.etag").value("etag-123"))
                        .andReturn(),
                AuthStorageClusterTestSessionResponseDto.class);
        Assertions.assertEquals(128L, completedResponse.getFileSize());

        AuthStorageClusterTestDownloadResponseDto downloadResponse = readValue(
                mockMvc.perform(post("/auth/platform-admin/storage/clusters/{clusterId}/test-sessions/{testSessionId}/download-url",
                                cluster.getId(), uploadResponse.getTestSessionId())
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.downloadUrl").value("http://minio.local/download"))
                        .andReturn(),
                AuthStorageClusterTestDownloadResponseDto.class);
        Assertions.assertNotNull(downloadResponse.getExpiresAt());

        mockMvc.perform(delete("/auth/platform-admin/storage/clusters/{clusterId}/test-sessions/{testSessionId}",
                        cluster.getId(), uploadResponse.getTestSessionId())
                        .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken()))
                .andExpect(status().isNoContent());

        verify(storageGateway).verifyCluster(any());
                verify(storageGateway, times(2)).ensureBucketExists(any(), eq(cluster.getTestBucketName()));
        verify(storageGateway).createPresignedUploadUrl(any(), eq(cluster.getTestBucketName()), anyString(), any(Duration.class));
        verify(storageGateway).createPresignedDownloadUrl(any(), eq(cluster.getTestBucketName()), eq(uploadResponse.getObjectKey()), any(Duration.class));
        verify(storageGateway).deleteObject(any(), eq(cluster.getTestBucketName()), eq(uploadResponse.getObjectKey()));
    }

    @Test
    void platformAdminStorage_shouldRejectClusterManageWhenPermissionMissing() throws Exception {
        AuthPlatformAdminLoginResponseDto loginResponse = adminLogin(
                authPlatformAdminBootstrapProperties.getUsername(),
                authPlatformAdminBootstrapProperties.getPassword());
        revokeBootstrapAdminPermission("platform.storage.cluster.manage");

        AuthStorageClusterUpsertRequestDto createRequest = new AuthStorageClusterUpsertRequestDto();
        createRequest.setClusterCode("deny-manage-" + uniqueSuffix());
        createRequest.setEndpoint("http://127.0.0.1:9000");
        createRequest.setBucketPrefix("plm-dev");
        createRequest.setSecretRef("inline:minioadmin:minioadmin");
        createRequest.setStatus("ACTIVE");

        mockMvc.perform(post("/auth/platform-admin/storage/clusters")
                        .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(createRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_PERMISSION_DENIED"));
    }

    @Test
    void platformAdminStorage_shouldRejectClusterTestWhenPermissionMissing() throws Exception {
        doNothing().when(storageGateway).ensureBucketExists(any(), anyString());
        AuthPlatformAdminLoginResponseDto loginResponse = adminLogin(
                authPlatformAdminBootstrapProperties.getUsername(),
                authPlatformAdminBootstrapProperties.getPassword());

        AuthStorageClusterUpsertRequestDto createRequest = new AuthStorageClusterUpsertRequestDto();
        createRequest.setClusterCode("deny-test-" + uniqueSuffix());
        createRequest.setEndpoint("http://127.0.0.1:9000");
        createRequest.setBucketPrefix("plm-dev");
        createRequest.setSecretRef("inline:minioadmin:minioadmin");
        createRequest.setStatus("ACTIVE");

        AuthStorageClusterResponseDto cluster = readValue(
                mockMvc.perform(post("/auth/platform-admin/storage/clusters")
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(createRequest)))
                        .andExpect(status().isOk())
                        .andReturn(),
                AuthStorageClusterResponseDto.class);

        revokeBootstrapAdminPermission("platform.storage.cluster.test");

        mockMvc.perform(post("/auth/platform-admin/storage/clusters/{clusterId}/test-connection", cluster.getId())
                        .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_PERMISSION_DENIED"));
    }

    private void revokeBootstrapAdminPermission(String permissionCode) {
        PlatformRole bootstrapRole = platformRoleRepository.findByRoleCodeIgnoreCase(
                        authPlatformAdminBootstrapProperties.getRoleCode())
                .orElseThrow(() -> new AssertionError("bootstrap platform role should exist"));
        Permission permission = permissionRepository.findByPermissionCodeIn(List.of(permissionCode)).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("permission should exist: " + permissionCode));
        jdbcTemplate.update(
                "delete from plm_platform.platform_role_permission where role_id = ? and permission_id = ?",
                bootstrapRole.getId(),
                permission.getId());
    }

    private AuthPlatformAdminLoginResponseDto adminLogin(String identifier, String password) throws Exception {
        AuthPasswordLoginRequestDto request = new AuthPasswordLoginRequestDto();
        request.setIdentifier(identifier);
        applyEncryptedLoginPassword(request, password);
        request.setRemember(false);

        MvcResult result = mockMvc.perform(post("/auth/public/platform-admin/login/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn();
        return readValue(result, AuthPlatformAdminLoginResponseDto.class);
    }

    private void applyEncryptedLoginPassword(AuthPasswordLoginRequestDto request, String password) throws Exception {
        AuthPasswordEncryptionKeyResponseDto key = fetchPasswordEncryptionKey();
        request.setEncryptionKeyId(key.getKeyId());
        request.setPasswordCiphertext(encryptWithPublicKey(key, password));
        request.setPassword(null);
    }

    private AuthPasswordEncryptionKeyResponseDto fetchPasswordEncryptionKey() throws Exception {
        MvcResult result = mockMvc.perform(get("/auth/public/security/password-encryption-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyId").isNotEmpty())
                .andExpect(jsonPath("$.publicKeyBase64").isNotEmpty())
                .andReturn();
        return readValue(result, AuthPasswordEncryptionKeyResponseDto.class);
    }

    private String encryptWithPublicKey(AuthPasswordEncryptionKeyResponseDto key, String plainText) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(key.getPublicKeyBase64());
        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(decoded));
        Cipher cipher = Cipher.getInstance(key.getTransformation());
        cipher.init(Cipher.ENCRYPT_MODE,
                publicKey,
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return Base64.getEncoder().encodeToString(cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8)));
    }

    private <T> T readValue(MvcResult result, Class<T> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), type);
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}