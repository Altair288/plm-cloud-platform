package com.plm.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.auth.config.AuthLoginProperties;
import com.plm.auth.config.AuthPlatformAdminBootstrapProperties;
import com.plm.auth.service.RegisterEmailSender;
import com.plm.auth.support.AuthDomainConstants;
import com.plm.auth.support.AuthStpKit;
import com.plm.auth.util.AuthNormalizer;
import com.plm.common.api.dto.auth.AuthCreateWorkspaceRequestDto;
import com.plm.common.api.dto.auth.AuthPlatformAdminLoginResponseDto;
import com.plm.common.api.dto.auth.AuthPlatformAdminSessionResponseDto;
import com.plm.common.api.dto.auth.AuthMeResponseDto;
import com.plm.common.api.dto.auth.AuthPasswordEncryptionKeyResponseDto;
import com.plm.common.api.dto.auth.AuthPasswordLoginRequestDto;
import com.plm.common.api.dto.auth.AuthPasswordLoginResponseDto;
import com.plm.common.api.dto.auth.AuthRegisterRequestDto;
import com.plm.common.api.dto.auth.AuthRegisterResponseDto;
import com.plm.common.api.dto.auth.AuthSendRegisterEmailCodeRequestDto;
import com.plm.common.api.dto.auth.AuthSendRegisterEmailCodeResponseDto;
import com.plm.common.api.dto.auth.AuthSwitchWorkspaceRequestDto;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationDto;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationEmailBatchRequestDto;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationEmailBatchResponseDto;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationLinkPreviewResponseDto;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationLinkRequestDto;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationLinkResponseDto;
import com.plm.common.api.dto.auth.AuthWorkspaceInvitationPreviewResponseDto;
import com.plm.common.api.dto.auth.AuthWorkspaceBootstrapOptionsResponseDto;
import com.plm.common.api.dto.auth.AuthWorkspaceSessionResponseDto;
import com.plm.common.domain.auth.EmailVerificationCode;
import com.plm.common.domain.auth.UserCredential;
import com.plm.common.domain.auth.UserAccount;
import com.plm.common.domain.auth.Workspace;
import com.plm.common.domain.auth.WorkspaceInvitation;
import com.plm.common.domain.auth.WorkspaceInvitationLinkAcceptLog;
import com.plm.common.domain.auth.WorkspaceMember;
import com.plm.infrastructure.repository.auth.EmailVerificationCodeRepository;
import com.plm.infrastructure.repository.auth.UserAccountRepository;
import com.plm.infrastructure.repository.auth.WorkspaceInvitationLinkAcceptLogRepository;
import com.plm.infrastructure.repository.auth.WorkspaceInvitationLinkRepository;
import com.plm.infrastructure.repository.auth.WorkspaceInvitationRepository;
import com.plm.infrastructure.repository.auth.UserCredentialRepository;
import com.plm.infrastructure.repository.auth.WorkspaceMemberRepository;
import com.plm.infrastructure.repository.auth.WorkspaceRolePermissionRepository;
import com.plm.infrastructure.repository.auth.WorkspaceRoleRepository;
import com.plm.infrastructure.repository.auth.WorkspaceRepository;
import cn.dev33.satoken.exception.SaTokenContextException;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
                "spring.main.lazy-initialization=true",
                "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class AuthFlowControllerIT {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private AuthLoginProperties authLoginProperties;

        @Autowired
        private AuthPlatformAdminBootstrapProperties authPlatformAdminBootstrapProperties;

        @Autowired
        private UserAccountRepository userAccountRepository;

        @Autowired
        private UserCredentialRepository userCredentialRepository;

        @Autowired
        private EmailVerificationCodeRepository emailVerificationCodeRepository;

        @Autowired
        private WorkspaceMemberRepository workspaceMemberRepository;

        @Autowired
        private WorkspaceRepository workspaceRepository;

        @Autowired
        private WorkspaceInvitationRepository workspaceInvitationRepository;

        @Autowired
        private WorkspaceInvitationLinkRepository workspaceInvitationLinkRepository;

        @Autowired
        private WorkspaceInvitationLinkAcceptLogRepository workspaceInvitationLinkAcceptLogRepository;

        @Autowired
        private WorkspaceRoleRepository workspaceRoleRepository;

        @Autowired
        private WorkspaceRolePermissionRepository workspaceRolePermissionRepository;

        @MockBean
        private RegisterEmailSender registerEmailSender;

        @AfterEach
        void tearDownSessions() {
                reset(registerEmailSender);
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
        void register_shouldCreateUserAndPasswordCredential() throws Exception {
                String suffix = uniqueSuffix();
                AuthRegisterRequestDto request = new AuthRegisterRequestDto();
                request.setUsername("user_" + suffix);
                request.setDisplayName("User " + suffix);
                applyEncryptedRegisterPasswords(request, "Password123!", "Password123!");
                String email = suffix + "@example.com";
                request.setEmail(email);
                request.setEmailVerificationCode(sendRegisterEmailCode(email));
                request.setPhone("1380000" + suffix.substring(0, 4));

                MvcResult result = mockMvc.perform(post("/auth/public/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.userId").isNotEmpty())
                                .andExpect(jsonPath("$.username").value("user_" + suffix))
                                .andReturn();

                AuthRegisterResponseDto response = readValue(result, AuthRegisterResponseDto.class);
                Assertions.assertTrue(userAccountRepository.existsById(response.getUserId()));

                UserCredential credential = userCredentialRepository
                                .findByUserIdAndCredentialType(response.getUserId(), "PASSWORD")
                                .orElseThrow();
                Assertions.assertNotEquals("Password123!", credential.getSecretHash());
                Assertions.assertTrue(credential.getSecretHash().startsWith("$2"));

                EmailVerificationCode verificationCode = emailVerificationCodeRepository
                                .findTopByTargetEmailAndVerificationPurposeOrderByCreatedAtDesc(email,
                                                AuthDomainConstants.VERIFICATION_PURPOSE_REGISTER)
                                .orElseThrow();
                Assertions.assertEquals(AuthDomainConstants.VERIFICATION_CODE_STATUS_USED,
                                verificationCode.getCodeStatus());
                Assertions.assertEquals(response.getUserId(), verificationCode.getConsumedByUserId());
        }

        @Test
        void workspaceBootstrapOptions_shouldReturnSeededCatalogs() throws Exception {
                MvcResult result = mockMvc.perform(get("/auth/public/workspace-bootstrap-options"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.workspaceTypes.length()").value(3))
                                .andExpect(jsonPath("$.locales.length()").value(2))
                                .andExpect(jsonPath("$.timezones.length()").value(3))
                                .andReturn();

                AuthWorkspaceBootstrapOptionsResponseDto response = readValue(result,
                                AuthWorkspaceBootstrapOptionsResponseDto.class);
                Assertions.assertEquals(List.of("TEAM", "PERSONAL", "LEARNING"),
                                response.getWorkspaceTypes().stream().map(option -> option.getCode()).toList());
                Assertions.assertEquals(List.of("zh-CN", "en-US"),
                                response.getLocales().stream().map(option -> option.getCode()).toList());
                Assertions.assertEquals(List.of("Asia/Shanghai", "UTC", "America/Los_Angeles"),
                                response.getTimezones().stream().map(option -> option.getCode()).toList());
                Assertions.assertEquals(Boolean.TRUE, response.getWorkspaceTypes().get(0).getIsDefault());
                Assertions.assertEquals(Boolean.TRUE, response.getLocales().get(0).getIsDefault());
                Assertions.assertEquals(Boolean.TRUE, response.getTimezones().get(0).getIsDefault());
                Assertions.assertTrue(response.getWorkspaceTypes().stream()
                                .allMatch(option -> option.getLabel() != null && !option.getLabel().isBlank()));
                Assertions.assertTrue(response.getLocales().stream()
                                .allMatch(option -> option.getLabel() != null && !option.getLabel().isBlank()));
                Assertions.assertTrue(response.getTimezones().stream()
                                .allMatch(option -> option.getLabel() != null && !option.getLabel().isBlank()));
        }

        @Test
        void passwordEncryptionKey_shouldExposeCurrentPublicKey() throws Exception {
                AuthPasswordEncryptionKeyResponseDto response = fetchPasswordEncryptionKey();

                Assertions.assertNotNull(response.getKeyId());
                Assertions.assertEquals("RSA", response.getAlgorithm());
                Assertions.assertFalse(response.getPublicKeyBase64().contains("BEGIN PUBLIC KEY"));
        }

        @Test
        void passwordEncryptionKey_shouldReuseSameRedisKeyWithinTtl() throws Exception {
                AuthPasswordEncryptionKeyResponseDto first = fetchPasswordEncryptionKey();
                AuthPasswordEncryptionKeyResponseDto second = fetchPasswordEncryptionKey();

                Assertions.assertEquals(first.getKeyId(), second.getKeyId());
                Assertions.assertEquals(first.getPublicKeyBase64(), second.getPublicKeyBase64());
        }

        @Test
        void devPlatformAdmin_shouldBootstrapAndLoginWithoutWorkspace() throws Exception {
                String username = authPlatformAdminBootstrapProperties.getUsername();
                String password = authPlatformAdminBootstrapProperties.getPassword();
                String roleCode = authPlatformAdminBootstrapProperties.getRoleCode();

                UserAccount adminUser = userAccountRepository.findByUsernameIgnoreCase(username).orElseThrow();
                Assertions.assertEquals(Boolean.FALSE, adminUser.getIsFirstLogin());
                Assertions.assertEquals(0, adminUser.getWorkspaceCount());

                AuthPlatformAdminLoginResponseDto loginResponse = adminLogin(username, password, false);
                Assertions.assertNotNull(loginResponse.getPlatformToken());
                Assertions.assertEquals(username, loginResponse.getAdmin().getUsername());
                Assertions.assertEquals(List.of(roleCode), loginResponse.getAdmin().getRoleCodes());
                Assertions.assertEquals(
                                AuthDomainConstants.ROLE_CODE_PLATFORM_SUPER_ADMIN.equalsIgnoreCase(roleCode),
                                loginResponse.getAdmin().getSuperAdmin());

                MvcResult meResult = mockMvc.perform(get("/auth/platform-admin/me")
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.admin.username").value(username))
                                .andExpect(jsonPath("$.admin.roleCodes[0]").value(roleCode))
                                .andReturn();

                AuthPlatformAdminSessionResponseDto meResponse = readValue(meResult,
                                AuthPlatformAdminSessionResponseDto.class);
                Assertions.assertEquals(username, meResponse.getAdmin().getUsername());
                Assertions.assertEquals(List.of(roleCode), meResponse.getAdmin().getRoleCodes());
        }

        @Test
        void login_createWorkspace_switch_and_me_shouldWork() throws Exception {
                AuthRegisterResponseDto registered = registerUser(uniqueSuffix());

                AuthPasswordLoginResponseDto loginResponse = login(registered.getUsername(), "Password123!");
                Assertions.assertNotNull(loginResponse.getPlatformToken());
                Assertions.assertEquals(Boolean.FALSE, loginResponse.getRemember());
                Assertions.assertEquals(authLoginProperties.getExpireInSeconds(),
                                loginResponse.getPlatformTokenExpireInSeconds());
                Assertions.assertTrue(Boolean.TRUE.equals(loginResponse.getUser().getIsFirstLogin()));
                Assertions.assertEquals(0, loginResponse.getUser().getWorkspaceCount());
                Assertions.assertTrue(loginResponse.getWorkspaceOptions().isEmpty());
                Assertions.assertNull(loginResponse.getCurrentWorkspace());

                String platformTokenName = loginResponse.getPlatformTokenName();
                String platformToken = loginResponse.getPlatformToken();

                AuthWorkspaceSessionResponseDto firstWorkspace = createWorkspace(
                                platformTokenName,
                                platformToken,
                                "Alpha Workspace",
                                true);
                Assertions.assertNotNull(firstWorkspace.getWorkspaceToken());
                Assertions.assertEquals(List.of("workspace_owner"), firstWorkspace.getRoleCodes());
                Assertions.assertEquals("TEAM", firstWorkspace.getWorkspaceType());
                Assertions.assertEquals("zh-CN", firstWorkspace.getDefaultLocale());
                Assertions.assertEquals("Asia/Shanghai", firstWorkspace.getDefaultTimezone());
                assertGeneratedWorkspaceCode(firstWorkspace.getWorkspaceCode(), registered.getUserId(),
                                firstWorkspace.getWorkspaceId(), "Alpha Workspace", "TEAM");

                WorkspaceMember firstMember = workspaceMemberRepository.findById(firstWorkspace.getWorkspaceMemberId())
                                .orElseThrow();
                Assertions.assertTrue(Boolean.TRUE.equals(firstMember.getIsDefaultWorkspace()));
                List<String> ownerPermissionCodes = workspaceRolePermissionRepository
                                .findPermissionCodesByWorkspaceRoleId(
                                                workspaceRoleRepository
                                                                .findByWorkspaceIdAndRoleCode(
                                                                                firstWorkspace.getWorkspaceId(),
                                                                                "workspace_owner")
                                                                .orElseThrow()
                                                                .getId());
                Assertions.assertTrue(ownerPermissionCodes.contains("workspace.config.update"));
                Assertions.assertTrue(ownerPermissionCodes.contains("runtime.export.execute"));

                AuthWorkspaceSessionResponseDto secondWorkspace = createWorkspace(
                                platformTokenName,
                                platformToken,
                                "Beta Workspace",
                                false);
                Assertions.assertNotNull(secondWorkspace.getWorkspaceToken());
                Assertions.assertEquals("TEAM", secondWorkspace.getWorkspaceType());
                Assertions.assertEquals("zh-CN", secondWorkspace.getDefaultLocale());
                Assertions.assertEquals("Asia/Shanghai", secondWorkspace.getDefaultTimezone());
                assertGeneratedWorkspaceCode(secondWorkspace.getWorkspaceCode(), registered.getUserId(),
                                secondWorkspace.getWorkspaceId(), "Beta Workspace", "TEAM");

                MvcResult meAfterSecondCreateResult = mockMvc.perform(get("/auth/me")
                                .header(platformTokenName, platformToken)
                                .header(secondWorkspace.getWorkspaceTokenName(), secondWorkspace.getWorkspaceToken()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.workspaceOptions.length()").value(2))
                                .andExpect(jsonPath("$.defaultWorkspace.workspaceId")
                                                .value(firstWorkspace.getWorkspaceId().toString()))
                                .andExpect(jsonPath("$.currentWorkspace.workspaceId")
                                                .value(secondWorkspace.getWorkspaceId().toString()))
                                .andReturn();

                AuthMeResponseDto meAfterSecondCreate = readValue(meAfterSecondCreateResult, AuthMeResponseDto.class);
                Assertions.assertTrue(Boolean.FALSE.equals(meAfterSecondCreate.getUser().getIsFirstLogin()));
                Assertions.assertEquals(2, meAfterSecondCreate.getUser().getWorkspaceCount());
                Assertions.assertEquals(2, meAfterSecondCreate.getWorkspaceOptions().size());
                Assertions.assertEquals("TEAM", meAfterSecondCreate.getDefaultWorkspace().getWorkspaceType());
                Assertions.assertEquals("zh-CN", meAfterSecondCreate.getDefaultWorkspace().getDefaultLocale());
                Assertions.assertEquals("Asia/Shanghai",
                                meAfterSecondCreate.getDefaultWorkspace().getDefaultTimezone());
                Assertions.assertEquals("TEAM", meAfterSecondCreate.getCurrentWorkspace().getWorkspaceType());
                Assertions.assertEquals("zh-CN", meAfterSecondCreate.getCurrentWorkspace().getDefaultLocale());
                Assertions.assertEquals("Asia/Shanghai",
                                meAfterSecondCreate.getCurrentWorkspace().getDefaultTimezone());
                Assertions.assertTrue(meAfterSecondCreate.getWorkspaceOptions().stream()
                                .allMatch(option -> "TEAM".equals(option.getWorkspaceType())));

                AuthWorkspaceSessionResponseDto switched = switchWorkspace(
                                platformTokenName,
                                platformToken,
                                firstWorkspace.getWorkspaceId(),
                                true);
                Assertions.assertEquals(firstWorkspace.getWorkspaceId(), switched.getWorkspaceId());
                Assertions.assertEquals(firstWorkspace.getWorkspaceMemberId(), switched.getWorkspaceMemberId());
                Assertions.assertEquals("TEAM", switched.getWorkspaceType());
                Assertions.assertEquals("zh-CN", switched.getDefaultLocale());
                Assertions.assertEquals("Asia/Shanghai", switched.getDefaultTimezone());

                MvcResult meAfterSwitchResult = mockMvc.perform(get("/auth/me")
                                .header(platformTokenName, platformToken)
                                .header(switched.getWorkspaceTokenName(), switched.getWorkspaceToken()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.defaultWorkspace.workspaceId")
                                                .value(firstWorkspace.getWorkspaceId().toString()))
                                .andExpect(jsonPath("$.currentWorkspace.workspaceId")
                                                .value(firstWorkspace.getWorkspaceId().toString()))
                                .andReturn();

                AuthMeResponseDto meAfterSwitch = readValue(meAfterSwitchResult, AuthMeResponseDto.class);
                Assertions.assertEquals(firstWorkspace.getWorkspaceId(),
                                meAfterSwitch.getCurrentWorkspace().getWorkspaceId());
                Assertions.assertEquals(List.of("workspace_owner"), meAfterSwitch.getCurrentWorkspace().getRoleCodes());
                Assertions.assertEquals("TEAM", meAfterSwitch.getCurrentWorkspace().getWorkspaceType());
                Assertions.assertEquals("zh-CN", meAfterSwitch.getCurrentWorkspace().getDefaultLocale());
                Assertions.assertEquals("Asia/Shanghai", meAfterSwitch.getCurrentWorkspace().getDefaultTimezone());

                MvcResult currentWorkspaceResult = mockMvc.perform(get("/auth/workspace-session/current")
                                .header(platformTokenName, platformToken)
                                .header(switched.getWorkspaceTokenName(), switched.getWorkspaceToken()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.workspaceId").value(firstWorkspace.getWorkspaceId().toString()))
                                .andReturn();

                AuthWorkspaceSessionResponseDto currentWorkspace = readValue(currentWorkspaceResult,
                                AuthWorkspaceSessionResponseDto.class);
                Assertions.assertEquals("TEAM", currentWorkspace.getWorkspaceType());
                Assertions.assertEquals("zh-CN", currentWorkspace.getDefaultLocale());
                Assertions.assertEquals("Asia/Shanghai", currentWorkspace.getDefaultTimezone());

                mockMvc.perform(delete("/auth/workspace-session/current")
                                .header(platformTokenName, platformToken))
                                .andExpect(status().isNoContent());

                mockMvc.perform(get("/auth/workspace-session/current")
                                .header(platformTokenName, platformToken))
                                .andExpect(status().isNoContent());
        }

        @Test
        void createWorkspace_shouldGenerateSystemWorkspaceCodeAndUseDictionaryDefaults() throws Exception {
                AuthRegisterResponseDto registered = registerUser(uniqueSuffix());
                AuthPasswordLoginResponseDto loginResponse = login(registered.getUsername(), "Password123!");

                AuthCreateWorkspaceRequestDto request = newCreateWorkspaceRequest(
                                "Default Dictionary Workspace",
                                null,
                                null,
                                null,
                                true);

                MvcResult result = mockMvc.perform(post("/auth/workspaces")
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.workspaceType").value("TEAM"))
                                .andExpect(jsonPath("$.defaultLocale").value("zh-CN"))
                                .andExpect(jsonPath("$.defaultTimezone").value("Asia/Shanghai"))
                                .andReturn();

                AuthWorkspaceSessionResponseDto response = readValue(result, AuthWorkspaceSessionResponseDto.class);
                Assertions.assertEquals("TEAM", response.getWorkspaceType());
                Assertions.assertEquals("zh-CN", response.getDefaultLocale());
                Assertions.assertEquals("Asia/Shanghai", response.getDefaultTimezone());
                assertGeneratedWorkspaceCode(response.getWorkspaceCode(), registered.getUserId(),
                                response.getWorkspaceId(), "Default Dictionary Workspace", "TEAM");
        }

        @Test
        void createWorkspace_shouldRejectInvalidDictionaryCodes() throws Exception {
                AuthRegisterResponseDto registered = registerUser(uniqueSuffix());
                AuthPasswordLoginResponseDto loginResponse = login(registered.getUsername(), "Password123!");

                AuthCreateWorkspaceRequestDto invalidWorkspaceType = newCreateWorkspaceRequest(
                                "Invalid Workspace Type",
                                "UNKNOWN",
                                "zh-CN",
                                "Asia/Shanghai",
                                true);

                mockMvc.perform(post("/auth/workspaces")
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(invalidWorkspaceType)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                                .andExpect(jsonPath("$.message").value("workspaceType is invalid"));

                AuthCreateWorkspaceRequestDto invalidLocale = newCreateWorkspaceRequest(
                                "Invalid Locale",
                                "TEAM",
                                "zh-XX",
                                "Asia/Shanghai",
                                true);

                mockMvc.perform(post("/auth/workspaces")
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(invalidLocale)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                                .andExpect(jsonPath("$.message").value("defaultLocale is invalid"));

                AuthCreateWorkspaceRequestDto invalidTimezone = newCreateWorkspaceRequest(
                                "Invalid Timezone",
                                "TEAM",
                                "zh-CN",
                                "Mars/Olympus",
                                true);

                mockMvc.perform(post("/auth/workspaces")
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(invalidTimezone)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                                .andExpect(jsonPath("$.message").value("defaultTimezone is invalid"));
        }

        @Test
        void createWorkspace_shouldGenerateDifferentCodesForSameName() throws Exception {
                AuthRegisterResponseDto registered = registerUser(uniqueSuffix());
                AuthPasswordLoginResponseDto loginResponse = login(registered.getUsername(), "Password123!");

                AuthWorkspaceSessionResponseDto firstWorkspace = createWorkspace(
                                loginResponse.getPlatformTokenName(),
                                loginResponse.getPlatformToken(),
                                "Alpha Workspace",
                                true);
                AuthWorkspaceSessionResponseDto secondWorkspace = createWorkspace(
                                loginResponse.getPlatformTokenName(),
                                loginResponse.getPlatformToken(),
                                "Alpha Workspace",
                                false);

                Assertions.assertNotEquals(firstWorkspace.getWorkspaceId(), secondWorkspace.getWorkspaceId());
                Assertions.assertNotEquals(firstWorkspace.getWorkspaceCode(), secondWorkspace.getWorkspaceCode());
                assertGeneratedWorkspaceCode(firstWorkspace.getWorkspaceCode(), registered.getUserId(),
                                firstWorkspace.getWorkspaceId(), "Alpha Workspace", "TEAM");
                assertGeneratedWorkspaceCode(secondWorkspace.getWorkspaceCode(), registered.getUserId(),
                                secondWorkspace.getWorkspaceId(), "Alpha Workspace", "TEAM");
        }

        @Test
        void emailInvitation_shouldPreviewAndAcceptIntoWorkspace() throws Exception {
                AuthRegisterResponseDto owner = registerUser(uniqueSuffix());
                AuthPasswordLoginResponseDto ownerLogin = login(owner.getUsername(), "Password123!");
                AuthWorkspaceSessionResponseDto workspace = createWorkspace(
                                ownerLogin.getPlatformTokenName(),
                                ownerLogin.getPlatformToken(),
                                "Invite Mail Workspace",
                                true);

                String inviteeEmail = uniqueSuffix() + "@example.com";
                AuthRegisterResponseDto invitee = registerUserWithEmail(uniqueSuffix(), inviteeEmail);

                doNothing().when(registerEmailSender).sendWorkspaceInvitationEmail(anyString(), anyString(), anyString(),
                                anyString(), any());

                AuthWorkspaceInvitationEmailBatchRequestDto inviteRequest = new AuthWorkspaceInvitationEmailBatchRequestDto();
                inviteRequest.setWorkspaceId(workspace.getWorkspaceId());
                inviteRequest.setEmails(List.of(inviteeEmail));
                inviteRequest.setSourceScene("WORKSPACE");
                inviteRequest.setTargetRoleCode("workspace_member");

                MvcResult sendResult = mockMvc.perform(post("/auth/workspace-invitations/email-batch")
                                .header(ownerLogin.getPlatformTokenName(), ownerLogin.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(inviteRequest)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.successCount").value(1))
                                .andReturn();

                AuthWorkspaceInvitationEmailBatchResponseDto sendResponse = readValue(sendResult,
                                AuthWorkspaceInvitationEmailBatchResponseDto.class);
                UUID invitationId = sendResponse.getResults().get(0).getInvitationId();
                WorkspaceInvitation invitation = workspaceInvitationRepository.findById(invitationId).orElseThrow();

                mockMvc.perform(get("/auth/public/workspace-invitations/email/{token}", invitation.getInvitationToken()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.workspaceId").value(workspace.getWorkspaceId().toString()))
                                .andExpect(jsonPath("$.invitationStatus").value("PENDING"))
                                .andExpect(jsonPath("$.canAccept").value(true));

                AuthPasswordLoginResponseDto inviteeLogin = login(invitee.getUsername(), "Password123!");
                MvcResult acceptResult = mockMvc.perform(post("/auth/workspace-invitations/email/{token}/accept",
                                invitation.getInvitationToken())
                                .header(inviteeLogin.getPlatformTokenName(), inviteeLogin.getPlatformToken()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.workspaceId").value(workspace.getWorkspaceId().toString()))
                                .andExpect(jsonPath("$.roleCodes[0]").value("workspace_member"))
                                .andReturn();

                AuthWorkspaceSessionResponseDto acceptedSession = readValue(acceptResult,
                                AuthWorkspaceSessionResponseDto.class);
                WorkspaceMember member = workspaceMemberRepository.findById(acceptedSession.getWorkspaceMemberId())
                                .orElseThrow();
                Assertions.assertEquals(AuthDomainConstants.WORKSPACE_JOIN_TYPE_INVITE, member.getJoinType());

                WorkspaceInvitation updatedInvitation = workspaceInvitationRepository.findById(invitationId).orElseThrow();
                Assertions.assertEquals(AuthDomainConstants.INVITATION_STATUS_ACCEPTED,
                                updatedInvitation.getInvitationStatus());
                Assertions.assertEquals(invitee.getUserId(), updatedInvitation.getAcceptedByUserId());
        }

        @Test
        void emailBatchInvitation_shouldReturnPendingExistsAndDuplicateInput() throws Exception {
                AuthRegisterResponseDto owner = registerUser(uniqueSuffix());
                AuthPasswordLoginResponseDto ownerLogin = login(owner.getUsername(), "Password123!");
                AuthWorkspaceSessionResponseDto workspace = createWorkspace(
                                ownerLogin.getPlatformTokenName(),
                                ownerLogin.getPlatformToken(),
                                "Batch Invite Workspace",
                                true);

                String email = uniqueSuffix() + "@example.com";
                doNothing().when(registerEmailSender).sendWorkspaceInvitationEmail(anyString(), anyString(), anyString(),
                                anyString(), any());

                AuthWorkspaceInvitationEmailBatchRequestDto firstRequest = new AuthWorkspaceInvitationEmailBatchRequestDto();
                firstRequest.setWorkspaceId(workspace.getWorkspaceId());
                firstRequest.setEmails(List.of(email));
                firstRequest.setSourceScene("ONBOARDING");

                mockMvc.perform(post("/auth/workspace-invitations/email-batch")
                                .header(ownerLogin.getPlatformTokenName(), ownerLogin.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(firstRequest)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.successCount").value(1));

                AuthWorkspaceInvitationEmailBatchRequestDto secondRequest = new AuthWorkspaceInvitationEmailBatchRequestDto();
                secondRequest.setWorkspaceId(workspace.getWorkspaceId());
                secondRequest.setEmails(List.of(email, email));
                secondRequest.setSourceScene("ONBOARDING");

                MvcResult secondResult = mockMvc.perform(post("/auth/workspace-invitations/email-batch")
                                .header(ownerLogin.getPlatformTokenName(), ownerLogin.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(secondRequest)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.successCount").value(0))
                                .andExpect(jsonPath("$.skippedCount").value(2))
                                .andReturn();

                AuthWorkspaceInvitationEmailBatchResponseDto secondResponse = readValue(secondResult,
                                AuthWorkspaceInvitationEmailBatchResponseDto.class);
                Assertions.assertEquals("PENDING_EXISTS", secondResponse.getResults().get(0).getResult());
                Assertions.assertEquals("DUPLICATE_INPUT", secondResponse.getResults().get(1).getResult());

                MvcResult listResult = mockMvc.perform(get("/auth/workspace-invitations")
                                .header(ownerLogin.getPlatformTokenName(), ownerLogin.getPlatformToken())
                                .param("workspaceId", workspace.getWorkspaceId().toString())
                                .param("status", "PENDING"))
                                .andExpect(status().isOk())
                                .andReturn();

                AuthWorkspaceInvitationDto[] invitations = readValue(listResult, AuthWorkspaceInvitationDto[].class);
                Assertions.assertEquals(1, invitations.length);
                Assertions.assertEquals("PENDING", invitations[0].getInvitationStatus());
        }

        @Test
        void invitationLink_shouldPreviewAndAcceptIntoWorkspace() throws Exception {
                AuthRegisterResponseDto owner = registerUser(uniqueSuffix());
                AuthPasswordLoginResponseDto ownerLogin = login(owner.getUsername(), "Password123!");
                AuthWorkspaceSessionResponseDto workspace = createWorkspace(
                                ownerLogin.getPlatformTokenName(),
                                ownerLogin.getPlatformToken(),
                                "Invite Link Workspace",
                                true);

                AuthRegisterResponseDto invitee = registerUser(uniqueSuffix());
                AuthWorkspaceInvitationLinkRequestDto request = new AuthWorkspaceInvitationLinkRequestDto();
                request.setWorkspaceId(workspace.getWorkspaceId());
                request.setSourceScene("ONBOARDING");
                request.setTargetRoleCode("workspace_member");
                request.setExpiresInHours(24);

                MvcResult createResult = mockMvc.perform(post("/auth/workspace-invitation-links")
                                .header(ownerLogin.getPlatformTokenName(), ownerLogin.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("ACTIVE"))
                                .andReturn();

                AuthWorkspaceInvitationLinkResponseDto linkResponse = readValue(createResult,
                                AuthWorkspaceInvitationLinkResponseDto.class);
                String token = linkResponse.getShareUrl().substring(linkResponse.getShareUrl().indexOf("token=") + 6);

                MvcResult previewResult = mockMvc.perform(get("/auth/public/workspace-invitation-links/{token}", token))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.workspaceId").value(workspace.getWorkspaceId().toString()))
                                .andExpect(jsonPath("$.status").value("ACTIVE"))
                                .andReturn();
                AuthWorkspaceInvitationLinkPreviewResponseDto preview = readValue(previewResult,
                                AuthWorkspaceInvitationLinkPreviewResponseDto.class);
                Assertions.assertTrue(preview.isCanAccept());

                AuthPasswordLoginResponseDto inviteeLogin = login(invitee.getUsername(), "Password123!");
                MvcResult acceptResult = mockMvc.perform(post("/auth/workspace-invitation-links/{token}/accept", token)
                                .header(inviteeLogin.getPlatformTokenName(), inviteeLogin.getPlatformToken()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.workspaceId").value(workspace.getWorkspaceId().toString()))
                                .andReturn();

                AuthWorkspaceSessionResponseDto acceptedSession = readValue(acceptResult,
                                AuthWorkspaceSessionResponseDto.class);
                WorkspaceMember member = workspaceMemberRepository.findById(acceptedSession.getWorkspaceMemberId())
                                .orElseThrow();
                Assertions.assertEquals(AuthDomainConstants.WORKSPACE_JOIN_TYPE_INVITE_LINK, member.getJoinType());

                List<WorkspaceInvitationLinkAcceptLog> logs = workspaceInvitationLinkAcceptLogRepository.findAll();
                Assertions.assertEquals(1, logs.size());
                Assertions.assertEquals(invitee.getUserId(), logs.get(0).getAcceptedByUserId());
        }

        @Test
        void emailInvitation_shouldRejectWhenCurrentUserEmailDoesNotMatch() throws Exception {
                AuthRegisterResponseDto owner = registerUser(uniqueSuffix());
                AuthPasswordLoginResponseDto ownerLogin = login(owner.getUsername(), "Password123!");
                AuthWorkspaceSessionResponseDto workspace = createWorkspace(
                                ownerLogin.getPlatformTokenName(),
                                ownerLogin.getPlatformToken(),
                                "Mismatch Invite Workspace",
                                true);

                String targetEmail = uniqueSuffix() + "@example.com";
                doNothing().when(registerEmailSender).sendWorkspaceInvitationEmail(anyString(), anyString(), anyString(),
                                anyString(), any());

                AuthWorkspaceInvitationEmailBatchRequestDto inviteRequest = new AuthWorkspaceInvitationEmailBatchRequestDto();
                inviteRequest.setWorkspaceId(workspace.getWorkspaceId());
                inviteRequest.setEmails(List.of(targetEmail));
                inviteRequest.setSourceScene("WORKSPACE");
                inviteRequest.setTargetRoleCode("workspace_member");

                MvcResult sendResult = mockMvc.perform(post("/auth/workspace-invitations/email-batch")
                                .header(ownerLogin.getPlatformTokenName(), ownerLogin.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(inviteRequest)))
                                .andExpect(status().isOk())
                                .andReturn();

                AuthWorkspaceInvitationEmailBatchResponseDto sendResponse = readValue(sendResult,
                                AuthWorkspaceInvitationEmailBatchResponseDto.class);
                WorkspaceInvitation invitation = workspaceInvitationRepository
                                .findById(sendResponse.getResults().get(0).getInvitationId())
                                .orElseThrow();

                AuthRegisterResponseDto outsider = registerUser(uniqueSuffix());
                AuthPasswordLoginResponseDto outsiderLogin = login(outsider.getUsername(), "Password123!");

                mockMvc.perform(post("/auth/workspace-invitations/email/{token}/accept", invitation.getInvitationToken())
                                .header(outsiderLogin.getPlatformTokenName(), outsiderLogin.getPlatformToken()))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("INVITATION_EMAIL_MISMATCH"));
        }

        @Test
        void canceledEmailInvitation_shouldRejectAcceptanceAndPreviewCanAcceptFalse() throws Exception {
                AuthRegisterResponseDto owner = registerUser(uniqueSuffix());
                AuthPasswordLoginResponseDto ownerLogin = login(owner.getUsername(), "Password123!");
                AuthWorkspaceSessionResponseDto workspace = createWorkspace(
                                ownerLogin.getPlatformTokenName(),
                                ownerLogin.getPlatformToken(),
                                "Canceled Invite Workspace",
                                true);

                String inviteeEmail = uniqueSuffix() + "@example.com";
                AuthRegisterResponseDto invitee = registerUserWithEmail(uniqueSuffix(), inviteeEmail);
                doNothing().when(registerEmailSender).sendWorkspaceInvitationEmail(anyString(), anyString(), anyString(),
                                anyString(), any());

                AuthWorkspaceInvitationEmailBatchRequestDto inviteRequest = new AuthWorkspaceInvitationEmailBatchRequestDto();
                inviteRequest.setWorkspaceId(workspace.getWorkspaceId());
                inviteRequest.setEmails(List.of(inviteeEmail));
                inviteRequest.setSourceScene("WORKSPACE");

                MvcResult sendResult = mockMvc.perform(post("/auth/workspace-invitations/email-batch")
                                .header(ownerLogin.getPlatformTokenName(), ownerLogin.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(inviteRequest)))
                                .andExpect(status().isOk())
                                .andReturn();

                AuthWorkspaceInvitationEmailBatchResponseDto sendResponse = readValue(sendResult,
                                AuthWorkspaceInvitationEmailBatchResponseDto.class);
                UUID invitationId = sendResponse.getResults().get(0).getInvitationId();
                WorkspaceInvitation invitation = workspaceInvitationRepository.findById(invitationId).orElseThrow();

                mockMvc.perform(post("/auth/workspace-invitations/{id}/cancel", invitationId)
                                .header(ownerLogin.getPlatformTokenName(), ownerLogin.getPlatformToken()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.invitationStatus").value("CANCELED"));

                mockMvc.perform(get("/auth/public/workspace-invitations/email/{token}", invitation.getInvitationToken()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.invitationStatus").value("CANCELED"))
                                .andExpect(jsonPath("$.canAccept").value(false));

                AuthPasswordLoginResponseDto inviteeLogin = login(invitee.getUsername(), "Password123!");
                mockMvc.perform(post("/auth/workspace-invitations/email/{token}/accept", invitation.getInvitationToken())
                                .header(inviteeLogin.getPlatformTokenName(), inviteeLogin.getPlatformToken()))
                                .andExpect(status().isGone())
                                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_CANCELED"));
        }

        @Test
        void disabledInvitationLink_shouldRejectAcceptanceAndPreviewCanAcceptFalse() throws Exception {
                AuthRegisterResponseDto owner = registerUser(uniqueSuffix());
                AuthPasswordLoginResponseDto ownerLogin = login(owner.getUsername(), "Password123!");
                AuthWorkspaceSessionResponseDto workspace = createWorkspace(
                                ownerLogin.getPlatformTokenName(),
                                ownerLogin.getPlatformToken(),
                                "Disabled Link Workspace",
                                true);

                AuthRegisterResponseDto invitee = registerUser(uniqueSuffix());
                AuthWorkspaceInvitationLinkRequestDto request = new AuthWorkspaceInvitationLinkRequestDto();
                request.setWorkspaceId(workspace.getWorkspaceId());
                request.setSourceScene("WORKSPACE");
                request.setTargetRoleCode("workspace_member");
                request.setExpiresInHours(24);

                MvcResult createResult = mockMvc.perform(post("/auth/workspace-invitation-links")
                                .header(ownerLogin.getPlatformTokenName(), ownerLogin.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isOk())
                                .andReturn();

                AuthWorkspaceInvitationLinkResponseDto linkResponse = readValue(createResult,
                                AuthWorkspaceInvitationLinkResponseDto.class);
                String token = linkResponse.getShareUrl().substring(linkResponse.getShareUrl().indexOf("token=") + 6);

                mockMvc.perform(post("/auth/workspace-invitation-links/{id}/disable", linkResponse.getLinkId())
                                .header(ownerLogin.getPlatformTokenName(), ownerLogin.getPlatformToken()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("DISABLED"));

                mockMvc.perform(get("/auth/public/workspace-invitation-links/{token}", token))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("DISABLED"))
                                .andExpect(jsonPath("$.canAccept").value(false));

                AuthPasswordLoginResponseDto inviteeLogin = login(invitee.getUsername(), "Password123!");
                mockMvc.perform(post("/auth/workspace-invitation-links/{token}/accept", token)
                                .header(inviteeLogin.getPlatformTokenName(), inviteeLogin.getPlatformToken()))
                                .andExpect(status().isGone())
                                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_LINK_DISABLED"));
        }

        @Test
        void singleUseInvitationLink_shouldExpireAfterFirstAcceptance() throws Exception {
                AuthRegisterResponseDto owner = registerUser(uniqueSuffix());
                AuthPasswordLoginResponseDto ownerLogin = login(owner.getUsername(), "Password123!");
                AuthWorkspaceSessionResponseDto workspace = createWorkspace(
                                ownerLogin.getPlatformTokenName(),
                                ownerLogin.getPlatformToken(),
                                "Single Use Link Workspace",
                                true);

                AuthRegisterResponseDto firstInvitee = registerUser(uniqueSuffix());
                AuthRegisterResponseDto secondInvitee = registerUser(uniqueSuffix());

                AuthWorkspaceInvitationLinkRequestDto request = new AuthWorkspaceInvitationLinkRequestDto();
                request.setWorkspaceId(workspace.getWorkspaceId());
                request.setSourceScene("ONBOARDING");
                request.setTargetRoleCode("workspace_member");
                request.setExpiresInHours(24);
                request.setMaxUseCount(1);

                MvcResult createResult = mockMvc.perform(post("/auth/workspace-invitation-links")
                                .header(ownerLogin.getPlatformTokenName(), ownerLogin.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isOk())
                                .andReturn();

                AuthWorkspaceInvitationLinkResponseDto linkResponse = readValue(createResult,
                                AuthWorkspaceInvitationLinkResponseDto.class);
                String token = linkResponse.getShareUrl().substring(linkResponse.getShareUrl().indexOf("token=") + 6);

                AuthPasswordLoginResponseDto firstInviteeLogin = login(firstInvitee.getUsername(), "Password123!");
                mockMvc.perform(post("/auth/workspace-invitation-links/{token}/accept", token)
                                .header(firstInviteeLogin.getPlatformTokenName(), firstInviteeLogin.getPlatformToken()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.workspaceId").value(workspace.getWorkspaceId().toString()));

                mockMvc.perform(get("/auth/public/workspace-invitation-links/{token}", token))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("EXPIRED"))
                                .andExpect(jsonPath("$.usedCount").value(1))
                                .andExpect(jsonPath("$.maxUseCount").value(1))
                                .andExpect(jsonPath("$.canAccept").value(false));

                AuthPasswordLoginResponseDto secondInviteeLogin = login(secondInvitee.getUsername(), "Password123!");
                mockMvc.perform(post("/auth/workspace-invitation-links/{token}/accept", token)
                                .header(secondInviteeLogin.getPlatformTokenName(), secondInviteeLogin.getPlatformToken()))
                                .andExpect(status().isGone())
                                .andExpect(jsonPath("$.code").value("WORKSPACE_INVITATION_LINK_EXPIRED"));
        }

        @Test
        void register_shouldRejectDuplicateUsername() throws Exception {
                String suffix = uniqueSuffix();
                registerUser(suffix);

                AuthRegisterRequestDto duplicate = new AuthRegisterRequestDto();
                duplicate.setUsername("user_" + suffix);
                duplicate.setDisplayName("Duplicate User");
                applyEncryptedRegisterPasswords(duplicate, "Password123!", "Password123!");
                String email = "duplicate-" + suffix + "@example.com";
                duplicate.setEmail(email);
                duplicate.setEmailVerificationCode(sendRegisterEmailCode(email));
                duplicate.setPhone("1370000" + suffix.substring(0, 4));

                mockMvc.perform(post("/auth/public/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(duplicate)))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"));
        }

        @Test
        void sendRegisterEmailCode_shouldRejectTooFrequent() throws Exception {
                String email = uniqueSuffix() + "@example.com";
                sendRegisterEmailCode(email);

                AuthSendRegisterEmailCodeRequestDto request = new AuthSendRegisterEmailCodeRequestDto();
                request.setEmail(email);

                mockMvc.perform(post("/auth/public/register/email-code")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isTooManyRequests())
                                .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_SEND_TOO_FREQUENT"));
        }

        @Test
        void register_shouldRejectInvalidEmailVerificationCode() throws Exception {
                String suffix = uniqueSuffix();
                String email = suffix + "@example.com";
                sendRegisterEmailCode(email);

                AuthRegisterRequestDto request = new AuthRegisterRequestDto();
                request.setUsername("user_" + suffix);
                request.setDisplayName("User " + suffix);
                applyEncryptedRegisterPasswords(request, "Password123!", "Password123!");
                request.setEmail(email);
                request.setEmailVerificationCode("123456");
                request.setPhone("1360000" + suffix.substring(0, 4));

                mockMvc.perform(post("/auth/public/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_CODE_INVALID"));
        }

        @Test
        void login_shouldRejectWrongPassword() throws Exception {
                AuthRegisterResponseDto registered = registerUser(uniqueSuffix());

                AuthPasswordLoginRequestDto request = new AuthPasswordLoginRequestDto();
                request.setIdentifier(registered.getUsername());
                applyEncryptedLoginPassword(request, "WrongPassword123!");

                mockMvc.perform(post("/auth/public/login/password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isUnauthorized())
                                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
        }

        @Test
        void login_shouldRejectPlaintextPasswordWhenRsaTransportIsRequired() throws Exception {
                AuthRegisterResponseDto registered = registerUser(uniqueSuffix());

                AuthPasswordLoginRequestDto request = new AuthPasswordLoginRequestDto();
                request.setIdentifier(registered.getUsername());
                request.setPassword("Password123!");

                mockMvc.perform(post("/auth/public/login/password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                                .andExpect(jsonPath("$.message").value("passwordCiphertext is required"));
        }

        @Test
        void login_shouldRejectInvalidEncryptionKeyId() throws Exception {
                AuthRegisterResponseDto registered = registerUser(uniqueSuffix());
                AuthPasswordEncryptionKeyResponseDto key = fetchPasswordEncryptionKey();

                AuthPasswordLoginRequestDto request = new AuthPasswordLoginRequestDto();
                request.setIdentifier(registered.getUsername());
                request.setEncryptionKeyId(key.getKeyId() + "-expired");
                request.setPasswordCiphertext(encryptWithPublicKey(key, "Password123!"));

                mockMvc.perform(post("/auth/public/login/password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                                .andExpect(jsonPath("$.message").value("encryptionKeyId is invalid or expired"));
        }

        @Test
        void platformAdminEndpoints_shouldRejectUserWithoutPlatformRole() throws Exception {
                AuthRegisterResponseDto registered = registerUser(uniqueSuffix());

                AuthPasswordLoginRequestDto adminRequest = new AuthPasswordLoginRequestDto();
                adminRequest.setIdentifier(registered.getUsername());
                applyEncryptedLoginPassword(adminRequest, "Password123!");

                mockMvc.perform(post("/auth/public/platform-admin/login/password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(adminRequest)))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("PLATFORM_ADMIN_REQUIRED"));

                AuthPasswordLoginResponseDto userLogin = login(registered.getUsername(), "Password123!");
                mockMvc.perform(get("/auth/platform-admin/me")
                                .header(userLogin.getPlatformTokenName(), userLogin.getPlatformToken()))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("PLATFORM_ADMIN_REQUIRED"));
        }

        @Test
        void login_shouldSupportRememberOptionAndReturnTokenLifetime() throws Exception {
                AuthRegisterResponseDto registered = registerUser(uniqueSuffix());

                AuthPasswordLoginResponseDto defaultLogin = login(registered.getUsername(), "Password123!", false);
                Assertions.assertEquals(Boolean.FALSE, defaultLogin.getRemember());
                Assertions.assertEquals(authLoginProperties.getExpireInSeconds(),
                                defaultLogin.getPlatformTokenExpireInSeconds());
                long defaultTokenTimeout = AuthStpKit.PLATFORM.getTokenTimeout(defaultLogin.getPlatformToken());
                Assertions.assertTrue(defaultTokenTimeout <= authLoginProperties.getExpireInSeconds());
                Assertions.assertTrue(defaultTokenTimeout > authLoginProperties.getExpireInSeconds() - 10);

                mockMvc.perform(post("/auth/logout")
                                .header(defaultLogin.getPlatformTokenName(), defaultLogin.getPlatformToken()))
                                .andExpect(status().isNoContent());

                AuthPasswordLoginResponseDto rememberedLogin = login(registered.getUsername(), "Password123!", true);
                Assertions.assertEquals(Boolean.TRUE, rememberedLogin.getRemember());
                Assertions.assertEquals(authLoginProperties.getRememberExpireInSeconds(),
                                rememberedLogin.getPlatformTokenExpireInSeconds());
                long rememberedTokenTimeout = AuthStpKit.PLATFORM.getTokenTimeout(rememberedLogin.getPlatformToken());
                Assertions.assertTrue(rememberedTokenTimeout <= authLoginProperties.getRememberExpireInSeconds());
                Assertions.assertTrue(rememberedTokenTimeout > authLoginProperties.getRememberExpireInSeconds() - 10);
        }

        @Test
        void switchWorkspace_shouldRejectNonMember() throws Exception {
                AuthRegisterResponseDto owner = registerUser(uniqueSuffix());
                AuthPasswordLoginResponseDto ownerLogin = login(owner.getUsername(), "Password123!");
                AuthWorkspaceSessionResponseDto ownerWorkspace = createWorkspace(
                                ownerLogin.getPlatformTokenName(),
                                ownerLogin.getPlatformToken(),
                                "Gamma Workspace",
                                true);

                AuthRegisterResponseDto outsider = registerUser(uniqueSuffix());
                AuthPasswordLoginResponseDto outsiderLogin = login(outsider.getUsername(), "Password123!");

                AuthSwitchWorkspaceRequestDto request = new AuthSwitchWorkspaceRequestDto();
                request.setWorkspaceId(ownerWorkspace.getWorkspaceId());
                request.setRememberAsDefault(Boolean.FALSE);

                mockMvc.perform(post("/auth/workspace-session/switch")
                                .header(outsiderLogin.getPlatformTokenName(), outsiderLogin.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBER_NOT_FOUND"));
        }

        @Test
        void login_shouldRejectDisabledAccount() throws Exception {
                AuthRegisterResponseDto registered = registerUser(uniqueSuffix());
                UserAccount user = userAccountRepository.findById(registered.getUserId()).orElseThrow();
                user.setStatus("DISABLED");
                userAccountRepository.save(user);

                AuthPasswordLoginRequestDto request = new AuthPasswordLoginRequestDto();
                request.setIdentifier(registered.getUsername());
                applyEncryptedLoginPassword(request, "Password123!");

                mockMvc.perform(post("/auth/public/login/password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"))
                                .andExpect(jsonPath("$.message", containsString("not active")));
        }

        @Test
        void protectedEndpoint_shouldRejectWhenNotLoggedIn() throws Exception {
                mockMvc.perform(get("/auth/me"))
                                .andExpect(status().isUnauthorized())
                                .andExpect(jsonPath("$.code").value("AUTH_NOT_LOGGED_IN"));
        }

        @Test
        void switchWorkspace_shouldRejectInactiveMember() throws Exception {
                AuthRegisterResponseDto registered = registerUser(uniqueSuffix());
                AuthPasswordLoginResponseDto loginResponse = login(registered.getUsername(), "Password123!");
                AuthWorkspaceSessionResponseDto workspace = createWorkspace(
                                loginResponse.getPlatformTokenName(),
                                loginResponse.getPlatformToken(),
                                "Inactive Member Workspace",
                                true);

                WorkspaceMember member = workspaceMemberRepository.findById(workspace.getWorkspaceMemberId())
                                .orElseThrow();
                member.setMemberStatus("DISABLED");
                workspaceMemberRepository.save(member);

                AuthSwitchWorkspaceRequestDto request = new AuthSwitchWorkspaceRequestDto();
                request.setWorkspaceId(workspace.getWorkspaceId());
                request.setRememberAsDefault(Boolean.FALSE);

                mockMvc.perform(post("/auth/workspace-session/switch")
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBER_INACTIVE"));
        }

        @Test
        void switchWorkspace_shouldRejectFrozenWorkspace() throws Exception {
                AuthRegisterResponseDto registered = registerUser(uniqueSuffix());
                AuthPasswordLoginResponseDto loginResponse = login(registered.getUsername(), "Password123!");
                AuthWorkspaceSessionResponseDto workspaceSession = createWorkspace(
                                loginResponse.getPlatformTokenName(),
                                loginResponse.getPlatformToken(),
                                "Frozen Workspace",
                                true);

                Workspace workspace = workspaceRepository.findById(workspaceSession.getWorkspaceId()).orElseThrow();
                workspace.setWorkspaceStatus("FROZEN");
                workspaceRepository.save(workspace);

                AuthSwitchWorkspaceRequestDto request = new AuthSwitchWorkspaceRequestDto();
                request.setWorkspaceId(workspaceSession.getWorkspaceId());
                request.setRememberAsDefault(Boolean.FALSE);

                mockMvc.perform(post("/auth/workspace-session/switch")
                                .header(loginResponse.getPlatformTokenName(), loginResponse.getPlatformToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_ACTIVE"));
        }

        private AuthRegisterResponseDto registerUser(String suffix) throws Exception {
                return registerUserWithEmail(suffix, suffix + "@example.com");
        }

        private AuthRegisterResponseDto registerUserWithEmail(String suffix, String email) throws Exception {
                AuthRegisterRequestDto request = new AuthRegisterRequestDto();
                request.setUsername("user_" + suffix);
                request.setDisplayName("User " + suffix);
                applyEncryptedRegisterPasswords(request, "Password123!", "Password123!");
                request.setEmail(email);
                request.setEmailVerificationCode(sendRegisterEmailCode(email));
                request.setPhone("1390000" + suffix.substring(0, 4));

                MvcResult result = mockMvc.perform(post("/auth/public/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isOk())
                                .andReturn();
                return readValue(result, AuthRegisterResponseDto.class);
        }

        private AuthPasswordLoginResponseDto login(String identifier, String password) throws Exception {
                return login(identifier, password, false);
        }

        private AuthPasswordLoginResponseDto login(String identifier, String password, boolean remember) throws Exception {
                AuthPasswordLoginRequestDto request = new AuthPasswordLoginRequestDto();
                request.setIdentifier(identifier);
                applyEncryptedLoginPassword(request, password);
                request.setRemember(remember);

                MvcResult result = mockMvc.perform(post("/auth/public/login/password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isOk())
                                .andReturn();
                return readValue(result, AuthPasswordLoginResponseDto.class);
        }

        private AuthPlatformAdminLoginResponseDto adminLogin(String identifier, String password, boolean remember)
                        throws Exception {
                AuthPasswordLoginRequestDto request = new AuthPasswordLoginRequestDto();
                request.setIdentifier(identifier);
                applyEncryptedLoginPassword(request, password);
                request.setRemember(remember);

                MvcResult result = mockMvc.perform(post("/auth/public/platform-admin/login/password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isOk())
                                .andReturn();
                return readValue(result, AuthPlatformAdminLoginResponseDto.class);
        }

        private AuthWorkspaceSessionResponseDto createWorkspace(String platformTokenName,
                        String platformToken,
                        String workspaceName,
                        boolean rememberAsDefault) throws Exception {
                AuthCreateWorkspaceRequestDto request = newCreateWorkspaceRequest(
                                workspaceName,
                                "TEAM",
                                "zh-CN",
                                "Asia/Shanghai",
                                rememberAsDefault);

                MvcResult result = mockMvc.perform(post("/auth/workspaces")
                                .header(platformTokenName, platformToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.workspaceId").isNotEmpty())
                                .andReturn();
                return readValue(result, AuthWorkspaceSessionResponseDto.class);
        }

        private AuthWorkspaceSessionResponseDto switchWorkspace(String platformTokenName,
                        String platformToken,
                        UUID workspaceId,
                        boolean rememberAsDefault) throws Exception {
                AuthSwitchWorkspaceRequestDto request = new AuthSwitchWorkspaceRequestDto();
                request.setWorkspaceId(workspaceId);
                request.setRememberAsDefault(rememberAsDefault);

                MvcResult result = mockMvc.perform(post("/auth/workspace-session/switch")
                                .header(platformTokenName, platformToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request)))
                                .andExpect(status().isOk())
                                .andReturn();
                return readValue(result, AuthWorkspaceSessionResponseDto.class);
        }

        private AuthCreateWorkspaceRequestDto newCreateWorkspaceRequest(String workspaceName,
                        String workspaceType,
                        String defaultLocale,
                        String defaultTimezone,
                        boolean rememberAsDefault) {
                AuthCreateWorkspaceRequestDto request = new AuthCreateWorkspaceRequestDto();
                request.setWorkspaceName(workspaceName);
                request.setWorkspaceType(workspaceType);
                request.setDefaultLocale(defaultLocale);
                request.setDefaultTimezone(defaultTimezone);
                request.setRememberAsDefault(rememberAsDefault);
                return request;
        }

        private void assertGeneratedWorkspaceCode(String actualCode,
                        UUID ownerUserId,
                        UUID workspaceId,
                        String workspaceName,
                        String workspaceType) {
                String ownerPrefix = ownerUserId.toString().replace("-", "").substring(0, 8);
                String workspacePrefix = workspaceId.toString().replace("-", "").substring(0, 8);
                String slug = AuthNormalizer.normalizeWorkspaceCodeSlug(workspaceName);
                if (slug == null) {
                        slug = AuthNormalizer.normalizeWorkspaceCodeSlug(workspaceType);
                }
                if (slug == null) {
                        slug = "workspace";
                }
                if (slug.length() > 43) {
                        slug = slug.substring(0, 43);
                }
                Assertions.assertEquals("ws_" + ownerPrefix + "_" + slug + "_" + workspacePrefix, actualCode);
        }

        private <T> T readValue(MvcResult result, Class<T> type) throws Exception {
                return objectMapper.readValue(result.getResponse().getContentAsByteArray(), type);
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

        private String encryptWithPublicKey(AuthPasswordEncryptionKeyResponseDto key, String plainText) throws Exception {
                byte[] decoded = Base64.getDecoder().decode(key.getPublicKeyBase64());
                PublicKey publicKey = KeyFactory.getInstance("RSA")
                                .generatePublic(new X509EncodedKeySpec(decoded));
                Cipher cipher = Cipher.getInstance(key.getTransformation());
                cipher.init(Cipher.ENCRYPT_MODE,
                                publicKey,
                                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                                                PSource.PSpecified.DEFAULT));
                return Base64.getEncoder().encodeToString(cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8)));
        }

        private String sendRegisterEmailCode(String email) throws Exception {
                reset(registerEmailSender);
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
                verify(registerEmailSender).sendRegisterVerificationEmail(emailCaptor.capture(), codeCaptor.capture(),
                                any());
                Assertions.assertEquals(email, emailCaptor.getValue());
                Assertions.assertTrue(codeCaptor.getValue().matches("\\d{6}"));

                AuthSendRegisterEmailCodeResponseDto response = readValue(result,
                                AuthSendRegisterEmailCodeResponseDto.class);
                Assertions.assertTrue(response.getExpireInSeconds() > 0);
                return codeCaptor.getValue();
        }

        private String uniqueSuffix() {
                return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
}