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
import com.plm.common.api.dto.auth.AuthRegisterResponseDto;
import com.plm.common.api.dto.auth.AuthSendRegisterEmailCodeRequestDto;
import com.plm.common.api.dto.auth.AuthSendRegisterEmailCodeResponseDto;
import com.plm.common.api.dto.auth.AuthWorkspaceSessionResponseDto;
import com.plm.common.domain.storage.StorageCluster;
import com.plm.common.domain.storage.WorkspaceStorageBucket;
import com.plm.infrastructure.repository.storage.StorageClusterRepository;
import com.plm.infrastructure.repository.storage.WorkspaceStorageBucketRepository;
import com.plm.infrastructure.storage.StorageGateway;
import com.plm.infrastructure.storage.StorageGatewayException;
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
import java.util.Base64;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
class WorkspaceStorageProvisioningIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StorageClusterRepository storageClusterRepository;

    @Autowired
    private WorkspaceStorageBucketRepository workspaceStorageBucketRepository;

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
    void createWorkspace_shouldProvisionBucketWhenActiveClusterExists() throws Exception {
        deactivateActiveCluster();
        doNothing().when(storageGateway).ensureBucketExists(any(), anyString());
        StorageCluster cluster = saveActiveCluster("ready-" + uniqueSuffix(), "plm-dev");

        AuthPasswordLoginResponseDto loginResponse = registerAndLogin(uniqueSuffix());
        AuthWorkspaceSessionResponseDto workspaceSession = createWorkspace(loginResponse, "Ready Workspace " + uniqueSuffix());

        WorkspaceStorageBucket bucket = workspaceStorageBucketRepository.findByWorkspaceId(workspaceSession.getWorkspaceId())
                .orElseThrow(() -> new AssertionError("workspace bucket should exist"));

        Assertions.assertEquals(cluster.getId(), bucket.getClusterId());
        Assertions.assertEquals("READY", bucket.getBucketStatus());
        Assertions.assertEquals(expectedBucketName("plm-dev", workspaceSession.getWorkspaceId()), bucket.getBucketName());
        Assertions.assertNotNull(bucket.getProvisionedAt());
        verify(storageGateway).ensureBucketExists(any(), eq(bucket.getBucketName()));
    }

    @Test
    void createWorkspace_shouldMarkBucketFailedWhenNoActiveClusterConfigured() throws Exception {
        deactivateActiveCluster();
        AuthPasswordLoginResponseDto loginResponse = registerAndLogin(uniqueSuffix());
        AuthWorkspaceSessionResponseDto workspaceSession = createWorkspace(loginResponse, "No Cluster Workspace " + uniqueSuffix());

        WorkspaceStorageBucket bucket = workspaceStorageBucketRepository.findByWorkspaceId(workspaceSession.getWorkspaceId())
                .orElseThrow(() -> new AssertionError("workspace bucket should exist"));

        Assertions.assertEquals("FAILED", bucket.getBucketStatus());
        Assertions.assertEquals("STORAGE_CLUSTER_NOT_CONFIGURED", bucket.getProvisionErrorCode());
        Assertions.assertNull(bucket.getClusterId());
        Assertions.assertNull(bucket.getBucketName());
    }

    @Test
    void createWorkspace_shouldMarkBucketFailedWhenGatewayProvisionFails() throws Exception {
        deactivateActiveCluster();
        doThrow(new StorageGatewayException("minio bucket create failed"))
            .when(storageGateway)
            .ensureBucketExists(any(), anyString());
        StorageCluster cluster = saveActiveCluster("failed-" + uniqueSuffix(), "plm-fail");

        AuthPasswordLoginResponseDto loginResponse = registerAndLogin(uniqueSuffix());
        AuthWorkspaceSessionResponseDto workspaceSession = createWorkspace(loginResponse, "Failed Workspace " + uniqueSuffix());

        WorkspaceStorageBucket bucket = workspaceStorageBucketRepository.findByWorkspaceId(workspaceSession.getWorkspaceId())
                .orElseThrow(() -> new AssertionError("workspace bucket should exist"));

        Assertions.assertEquals(cluster.getId(), bucket.getClusterId());
        Assertions.assertEquals(expectedBucketName("plm-fail", workspaceSession.getWorkspaceId()), bucket.getBucketName());
        Assertions.assertEquals("FAILED", bucket.getBucketStatus());
        Assertions.assertEquals("WORKSPACE_BUCKET_PROVISION_FAILED", bucket.getProvisionErrorCode());
        Assertions.assertTrue(bucket.getProvisionErrorMessage().contains("minio bucket create failed"));
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
        request.setUsername("storage_user_" + suffix);
        request.setDisplayName("Storage User " + suffix);
        applyEncryptedRegisterPasswords(request, "Password123!", "Password123!");
        String email = suffix + "@example.com";
        request.setEmail(email);
        request.setEmailVerificationCode(sendRegisterEmailCode(email));
        request.setPhone("1390000" + suffix.substring(0, 4));

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

    private String expectedBucketName(String bucketPrefix, UUID workspaceId) {
        return bucketPrefix + "-ws-" + workspaceId.toString().replace("-", "").toLowerCase();
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}