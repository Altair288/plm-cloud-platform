package com.plm.auth.controller;

import cn.dev33.satoken.exception.SaTokenContextException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.auth.service.RegisterEmailSender;
import com.plm.auth.support.AuthStpKit;
import com.plm.common.api.dto.auth.AuthCreateWorkspaceRequestDto;
import com.plm.common.api.dto.auth.AuthPasswordEncryptionKeyResponseDto;
import com.plm.common.api.dto.auth.AuthPasswordLoginRequestDto;
import com.plm.common.api.dto.auth.AuthPasswordLoginResponseDto;
import com.plm.common.api.dto.auth.AuthRegisterRequestDto;
import com.plm.common.api.dto.auth.AuthSendRegisterEmailCodeRequestDto;
import com.plm.common.api.dto.auth.AuthSendRegisterEmailCodeResponseDto;
import com.plm.common.api.dto.auth.AuthWorkspaceSessionResponseDto;
import com.plm.common.api.dto.storage.StorageObjectCompleteUploadRequestDto;
import com.plm.common.api.dto.storage.StorageObjectDownloadUrlResponseDto;
import com.plm.common.api.dto.storage.StorageObjectResponseDto;
import com.plm.common.api.dto.storage.StorageObjectUploadIntentRequestDto;
import com.plm.common.api.dto.storage.StorageObjectUploadIntentResponseDto;
import com.plm.common.domain.storage.ObjectAsset;
import com.plm.common.domain.storage.ObjectUploadSession;
import com.plm.common.domain.storage.StorageCluster;
import com.plm.common.domain.storage.WorkspaceStorageBucket;
import com.plm.infrastructure.repository.storage.ObjectAssetRepository;
import com.plm.infrastructure.repository.storage.ObjectUploadSessionRepository;
import com.plm.infrastructure.repository.storage.StorageClusterRepository;
import com.plm.infrastructure.repository.storage.WorkspaceStorageBucketRepository;
import com.plm.infrastructure.storage.StorageGateway;
import com.plm.infrastructure.storage.StorageObjectStat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.main.lazy-initialization=true",
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class WorkspaceObjectStorageControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StorageClusterRepository storageClusterRepository;

    @Autowired
    private WorkspaceStorageBucketRepository workspaceStorageBucketRepository;

    @Autowired
    private ObjectAssetRepository objectAssetRepository;

    @Autowired
    private ObjectUploadSessionRepository objectUploadSessionRepository;

    @MockBean
    private RegisterEmailSender registerEmailSender;

    @MockBean
    private StorageGateway storageGateway;

    @AfterEach
    void tearDownSessions() {
        reset(registerEmailSender, storageGateway);
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
    void workspaceObjectStorage_shouldCompleteUploadDownloadAndDeleteFlow() throws Exception {
        deactivateActiveCluster();
        doNothing().when(storageGateway).ensureBucketExists(any(), anyString());
        doNothing().when(storageGateway).deleteObject(any(), anyString(), anyString());
        when(storageGateway.createPresignedUploadUrl(any(), anyString(), anyString(), any(Duration.class)))
                .thenReturn("http://minio.local/workspace-upload");
        when(storageGateway.createPresignedDownloadUrl(any(), anyString(), anyString(), any(Duration.class)))
                .thenReturn("http://minio.local/workspace-download");
        when(storageGateway.statObject(any(), anyString(), anyString()))
                .thenReturn(new StorageObjectStat("etag-workspace-1", 128L));
        saveActiveCluster("objects-" + uniqueSuffix(), "plm-obj");

        AuthPasswordLoginResponseDto loginResponse = registerAndLogin(uniqueSuffix());
        AuthWorkspaceSessionResponseDto workspaceSession = createWorkspace(loginResponse, "Object Workspace " + uniqueSuffix());

        StorageObjectUploadIntentRequestDto uploadRequest = new StorageObjectUploadIntentRequestDto();
        uploadRequest.setBizType("DOCUMENT");
        uploadRequest.setFileName("manual.pdf");
        uploadRequest.setContentType("application/pdf");
        uploadRequest.setExpectedSize(128L);

        StorageObjectUploadIntentResponseDto uploadIntent = readValue(
                mockMvc.perform(post("/api/storage/workspaces/{workspaceId}/objects/upload-intents", workspaceSession.getWorkspaceId())
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(uploadRequest)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.presignedUploadUrl").value("http://minio.local/workspace-upload"))
                        .andExpect(jsonPath("$.bucketName").isNotEmpty())
                        .andReturn(),
                StorageObjectUploadIntentResponseDto.class);

        ObjectAsset createdObject = objectAssetRepository.findById(uploadIntent.getObjectId()).orElseThrow();
        Assertions.assertEquals("PENDING_UPLOAD", createdObject.getObjectStatus());
        Assertions.assertEquals("DOCUMENT", createdObject.getBizType());
        ObjectUploadSession uploadSession = objectUploadSessionRepository.findByUploadToken(uploadIntent.getUploadToken())
                .orElseThrow();
        Assertions.assertEquals(createdObject.getId(), uploadSession.getObjectId());

        StorageObjectCompleteUploadRequestDto completeRequest = new StorageObjectCompleteUploadRequestDto();
        completeRequest.setUploadToken(uploadIntent.getUploadToken());

        StorageObjectResponseDto completedObject = readValue(
                mockMvc.perform(post("/api/storage/workspaces/{workspaceId}/objects/{objectId}/complete",
                                workspaceSession.getWorkspaceId(),
                                uploadIntent.getObjectId())
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(completeRequest)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.objectStatus").value("ACTIVE"))
                        .andExpect(jsonPath("$.etag").value("etag-workspace-1"))
                        .andReturn(),
                StorageObjectResponseDto.class);
        Assertions.assertEquals(128L, completedObject.getFileSize());

        WorkspaceStorageBucket bucket = workspaceStorageBucketRepository.findByWorkspaceId(workspaceSession.getWorkspaceId())
                .orElseThrow();
        Assertions.assertEquals(128L, bucket.getUsedBytes());
        Assertions.assertEquals(1L, bucket.getObjectCount());

        StorageObjectDownloadUrlResponseDto downloadUrl = readValue(
                mockMvc.perform(post("/api/storage/workspaces/{workspaceId}/objects/{objectId}/download-url",
                                workspaceSession.getWorkspaceId(),
                                uploadIntent.getObjectId())
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.downloadUrl").value("http://minio.local/workspace-download"))
                        .andReturn(),
                StorageObjectDownloadUrlResponseDto.class);
        Assertions.assertNotNull(downloadUrl.getExpireAt());

        mockMvc.perform(delete("/api/storage/workspaces/{workspaceId}/objects/{objectId}",
                        workspaceSession.getWorkspaceId(),
                        uploadIntent.getObjectId())
                        .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken()))
                .andExpect(status().isNoContent());

        ObjectAsset deletedObject = objectAssetRepository.findById(uploadIntent.getObjectId()).orElseThrow();
        Assertions.assertEquals("DELETED", deletedObject.getObjectStatus());
        WorkspaceStorageBucket bucketAfterDelete = workspaceStorageBucketRepository.findByWorkspaceId(workspaceSession.getWorkspaceId())
                .orElseThrow();
        Assertions.assertEquals(0L, bucketAfterDelete.getUsedBytes());
        Assertions.assertEquals(0L, bucketAfterDelete.getObjectCount());

        verify(storageGateway).createPresignedUploadUrl(any(), eq(bucket.getBucketName()), eq(uploadIntent.getObjectKey()), any(Duration.class));
        verify(storageGateway).statObject(any(), eq(bucket.getBucketName()), eq(uploadIntent.getObjectKey()));
        verify(storageGateway).createPresignedDownloadUrl(any(), eq(bucket.getBucketName()), eq(uploadIntent.getObjectKey()), any(Duration.class));
        verify(storageGateway).deleteObject(any(), eq(bucket.getBucketName()), eq(uploadIntent.getObjectKey()));
    }

    @Test
    void workspaceObjectStorage_shouldRejectUploadIntentWhenBucketProvisionFailed() throws Exception {
        deactivateActiveCluster();
        AuthPasswordLoginResponseDto loginResponse = registerAndLogin(uniqueSuffix());
        AuthWorkspaceSessionResponseDto workspaceSession = createWorkspace(loginResponse, "Failed Bucket Upload " + uniqueSuffix());

        StorageObjectUploadIntentRequestDto uploadRequest = new StorageObjectUploadIntentRequestDto();
        uploadRequest.setBizType("DOCUMENT");
        uploadRequest.setFileName("manual.pdf");
        uploadRequest.setExpectedSize(128L);

        mockMvc.perform(post("/api/storage/workspaces/{workspaceId}/objects/upload-intents", workspaceSession.getWorkspaceId())
                        .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(uploadRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_BUCKET_PROVISION_FAILED"));
    }

    private StorageCluster saveActiveCluster(String clusterCode, String bucketPrefix) {
        StorageCluster cluster = new StorageCluster();
        cluster.setClusterCode(clusterCode);
        cluster.setProviderType("MINIO");
        cluster.setEndpoint("http://127.0.0.1:9000");
        cluster.setPublicEndpoint("http://localhost:9000");
        cluster.setBucketPrefix(bucketPrefix);
        cluster.setTestBucketName(bucketPrefix + "-" + clusterCode + "-admin-test");
        cluster.setSecretRef("inline:minioadmin:minioadmin");
        cluster.setStatus("ACTIVE");
        cluster.setHealthStatus("HEALTHY");
        cluster.setLastTestStatus("PASSED");
        return storageClusterRepository.save(cluster);
    }

    private void deactivateActiveCluster() {
        storageClusterRepository.findByStatus("ACTIVE")
                .ifPresent(cluster -> {
                    cluster.setStatus("INACTIVE");
                    cluster.setUpdatedBy("TEST");
                    storageClusterRepository.save(cluster);
                });
    }

    private AuthPasswordLoginResponseDto registerAndLogin(String suffix) throws Exception {
        AuthRegisterRequestDto request = new AuthRegisterRequestDto();
        request.setUsername("object_user_" + suffix);
        request.setDisplayName("Object User " + suffix);
        applyEncryptedRegisterPasswords(request, "Password123!", "Password123!");
        String email = suffix + "@example.com";
        request.setEmail(email);
        request.setEmailVerificationCode(sendRegisterEmailCode(email));
        request.setPhone("1380000" + suffix.substring(0, 4));

        mockMvc.perform(post("/auth/public/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isNotEmpty());

        return login(email, "Password123!");
    }

    private AuthWorkspaceSessionResponseDto createWorkspace(AuthPasswordLoginResponseDto loginResponse, String workspaceName)
            throws Exception {
        AuthCreateWorkspaceRequestDto request = new AuthCreateWorkspaceRequestDto();
        request.setWorkspaceName(workspaceName);
        request.setWorkspaceType("TEAM");
        request.setDefaultLocale("zh-CN");
        request.setDefaultTimezone("Asia/Shanghai");
        request.setRememberAsDefault(Boolean.TRUE);

        MvcResult result = mockMvc.perform(post("/auth/workspaces")
                        .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").isNotEmpty())
                .andReturn();
        return readValue(result, AuthWorkspaceSessionResponseDto.class);
    }

    private AuthPasswordLoginResponseDto login(String identifier, String password) throws Exception {
        AuthPasswordLoginRequestDto request = new AuthPasswordLoginRequestDto();
        request.setIdentifier(identifier);
        applyEncryptedLoginPassword(request, password);
        request.setRemember(false);

        MvcResult result = mockMvc.perform(post("/auth/public/login/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn();
        return readValue(result, AuthPasswordLoginResponseDto.class);
    }

    private void applyEncryptedRegisterPasswords(AuthRegisterRequestDto request,
                                                 String password,
                                                 String confirmPassword) throws Exception {
        AuthPasswordEncryptionKeyResponseDto key = fetchPasswordEncryptionKey();
        request.setEncryptionKeyId(key.getKeyId());
        request.setPasswordCiphertext(encryptWithPublicKey(key, password));
        request.setConfirmPasswordCiphertext(encryptWithPublicKey(key, confirmPassword));
        request.setPassword(null);
        request.setConfirmPassword(null);
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

    private String sendRegisterEmailCode(String email) throws Exception {
        doNothing().when(registerEmailSender).sendRegisterVerificationEmail(anyString(), anyString(), any());

        AuthSendRegisterEmailCodeRequestDto request = new AuthSendRegisterEmailCodeRequestDto();
        request.setEmail(email);

        MvcResult result = mockMvc.perform(post("/auth/public/register/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.maskedEmail").isNotEmpty())
                .andReturn();

        ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(registerEmailSender).sendRegisterVerificationEmail(emailCaptor.capture(), codeCaptor.capture(), any());
        Assertions.assertEquals(email, emailCaptor.getValue());
        Assertions.assertTrue(codeCaptor.getValue().matches("\\d{6}"));
        AuthSendRegisterEmailCodeResponseDto response = readValue(result, AuthSendRegisterEmailCodeResponseDto.class);
        Assertions.assertTrue(response.getExpireInSeconds() > 0);
        return codeCaptor.getValue();
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