package com.plm.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.auth.service.RegisterEmailSender;
import com.plm.auth.support.AuthDomainConstants;
import com.plm.auth.support.AuthStpKit;
import com.plm.common.api.dto.auth.AuthCreateWorkspaceRequestDto;
import com.plm.common.api.dto.auth.AuthMeResponseDto;
import com.plm.common.api.dto.auth.AuthPasswordLoginRequestDto;
import com.plm.common.api.dto.auth.AuthPasswordLoginResponseDto;
import com.plm.common.api.dto.auth.AuthRegisterRequestDto;
import com.plm.common.api.dto.auth.AuthRegisterResponseDto;
import com.plm.common.api.dto.auth.AuthSendRegisterEmailCodeRequestDto;
import com.plm.common.api.dto.auth.AuthSendRegisterEmailCodeResponseDto;
import com.plm.common.api.dto.auth.AuthSwitchWorkspaceRequestDto;
import com.plm.common.api.dto.auth.AuthWorkspaceSessionResponseDto;
import com.plm.common.domain.auth.EmailVerificationCode;
import com.plm.common.domain.auth.UserCredential;
import com.plm.common.domain.auth.UserAccount;
import com.plm.common.domain.auth.Workspace;
import com.plm.common.domain.auth.WorkspaceMember;
import com.plm.infrastructure.repository.auth.EmailVerificationCodeRepository;
import com.plm.infrastructure.repository.auth.UserAccountRepository;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.main.lazy-initialization=true",
                "spring.main.allow-bean-definition-overriding=true"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class AuthFlowControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
        request.setPassword("Password123!");
        request.setConfirmPassword("Password123!");
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
                .findTopByTargetEmailAndVerificationPurposeOrderByCreatedAtDesc(email, AuthDomainConstants.VERIFICATION_PURPOSE_REGISTER)
                .orElseThrow();
        Assertions.assertEquals(AuthDomainConstants.VERIFICATION_CODE_STATUS_USED, verificationCode.getCodeStatus());
        Assertions.assertEquals(response.getUserId(), verificationCode.getConsumedByUserId());
    }

    @Test
    void login_createWorkspace_switch_and_me_shouldWork() throws Exception {
        AuthRegisterResponseDto registered = registerUser(uniqueSuffix());

        AuthPasswordLoginResponseDto loginResponse = login(registered.getUsername(), "Password123!");
        Assertions.assertNotNull(loginResponse.getPlatformToken());
        Assertions.assertTrue(loginResponse.getWorkspaceOptions().isEmpty());
        Assertions.assertNull(loginResponse.getCurrentWorkspace());

        String platformTokenName = loginResponse.getPlatformTokenName();
        String platformToken = loginResponse.getPlatformToken();

        AuthWorkspaceSessionResponseDto firstWorkspace = createWorkspace(
                platformTokenName,
                platformToken,
                "alpha_" + uniqueSuffix(),
                "Alpha Workspace",
                true
        );
        Assertions.assertNotNull(firstWorkspace.getWorkspaceToken());
        Assertions.assertEquals(List.of("workspace_owner"), firstWorkspace.getRoleCodes());

        WorkspaceMember firstMember = workspaceMemberRepository.findById(firstWorkspace.getWorkspaceMemberId()).orElseThrow();
        Assertions.assertTrue(Boolean.TRUE.equals(firstMember.getIsDefaultWorkspace()));
        List<String> ownerPermissionCodes = workspaceRolePermissionRepository.findPermissionCodesByWorkspaceRoleId(
                workspaceRoleRepository.findByWorkspaceIdAndRoleCode(firstWorkspace.getWorkspaceId(), "workspace_owner")
                        .orElseThrow()
                        .getId()
        );
        Assertions.assertTrue(ownerPermissionCodes.contains("workspace.config.update"));
        Assertions.assertTrue(ownerPermissionCodes.contains("runtime.export.execute"));

        AuthWorkspaceSessionResponseDto secondWorkspace = createWorkspace(
                platformTokenName,
                platformToken,
                "beta_" + uniqueSuffix(),
                "Beta Workspace",
                false
        );
        Assertions.assertNotNull(secondWorkspace.getWorkspaceToken());

        MvcResult meAfterSecondCreateResult = mockMvc.perform(get("/auth/me")
                        .header(platformTokenName, platformToken)
                        .header(secondWorkspace.getWorkspaceTokenName(), secondWorkspace.getWorkspaceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceOptions.length()").value(2))
                .andExpect(jsonPath("$.defaultWorkspace.workspaceId").value(firstWorkspace.getWorkspaceId().toString()))
                .andExpect(jsonPath("$.currentWorkspace.workspaceId").value(secondWorkspace.getWorkspaceId().toString()))
                .andReturn();

        AuthMeResponseDto meAfterSecondCreate = readValue(meAfterSecondCreateResult, AuthMeResponseDto.class);
        Assertions.assertEquals(2, meAfterSecondCreate.getWorkspaceOptions().size());

        AuthWorkspaceSessionResponseDto switched = switchWorkspace(
                platformTokenName,
                platformToken,
                firstWorkspace.getWorkspaceId(),
                true
        );
        Assertions.assertEquals(firstWorkspace.getWorkspaceId(), switched.getWorkspaceId());
        Assertions.assertEquals(firstWorkspace.getWorkspaceMemberId(), switched.getWorkspaceMemberId());

        MvcResult meAfterSwitchResult = mockMvc.perform(get("/auth/me")
                        .header(platformTokenName, platformToken)
                        .header(switched.getWorkspaceTokenName(), switched.getWorkspaceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultWorkspace.workspaceId").value(firstWorkspace.getWorkspaceId().toString()))
                .andExpect(jsonPath("$.currentWorkspace.workspaceId").value(firstWorkspace.getWorkspaceId().toString()))
                .andReturn();

        AuthMeResponseDto meAfterSwitch = readValue(meAfterSwitchResult, AuthMeResponseDto.class);
        Assertions.assertEquals(firstWorkspace.getWorkspaceId(), meAfterSwitch.getCurrentWorkspace().getWorkspaceId());
        Assertions.assertEquals(List.of("workspace_owner"), meAfterSwitch.getCurrentWorkspace().getRoleCodes());

        mockMvc.perform(get("/auth/workspace-session/current")
                        .header(platformTokenName, platformToken)
                        .header(switched.getWorkspaceTokenName(), switched.getWorkspaceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(firstWorkspace.getWorkspaceId().toString()));

        mockMvc.perform(delete("/auth/workspace-session/current")
                        .header(platformTokenName, platformToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/auth/workspace-session/current")
                        .header(platformTokenName, platformToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void register_shouldRejectDuplicateUsername() throws Exception {
        String suffix = uniqueSuffix();
        registerUser(suffix);

        AuthRegisterRequestDto duplicate = new AuthRegisterRequestDto();
        duplicate.setUsername("user_" + suffix);
        duplicate.setDisplayName("Duplicate User");
        duplicate.setPassword("Password123!");
        duplicate.setConfirmPassword("Password123!");
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
                request.setPassword("Password123!");
                request.setConfirmPassword("Password123!");
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
        request.setPassword("WrongPassword123!");

        mockMvc.perform(post("/auth/public/login/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void switchWorkspace_shouldRejectNonMember() throws Exception {
        AuthRegisterResponseDto owner = registerUser(uniqueSuffix());
        AuthPasswordLoginResponseDto ownerLogin = login(owner.getUsername(), "Password123!");
        AuthWorkspaceSessionResponseDto ownerWorkspace = createWorkspace(
                ownerLogin.getPlatformTokenName(),
                ownerLogin.getPlatformToken(),
                "gamma_" + uniqueSuffix(),
                "Gamma Workspace",
                true
        );

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
        request.setPassword("Password123!");

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
                "inactive_" + uniqueSuffix(),
                "Inactive Member Workspace",
                true
        );

        WorkspaceMember member = workspaceMemberRepository.findById(workspace.getWorkspaceMemberId()).orElseThrow();
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
                "frozen_" + uniqueSuffix(),
                "Frozen Workspace",
                true
        );

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
        AuthRegisterRequestDto request = new AuthRegisterRequestDto();
        request.setUsername("user_" + suffix);
        request.setDisplayName("User " + suffix);
        request.setPassword("Password123!");
        request.setConfirmPassword("Password123!");
        String email = suffix + "@example.com";
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
        AuthPasswordLoginRequestDto request = new AuthPasswordLoginRequestDto();
        request.setIdentifier(identifier);
        request.setPassword(password);

        MvcResult result = mockMvc.perform(post("/auth/public/login/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn();
        return readValue(result, AuthPasswordLoginResponseDto.class);
    }

    private AuthWorkspaceSessionResponseDto createWorkspace(String platformTokenName,
                                                            String platformToken,
                                                            String workspaceCode,
                                                            String workspaceName,
                                                            boolean rememberAsDefault) throws Exception {
        AuthCreateWorkspaceRequestDto request = new AuthCreateWorkspaceRequestDto();
        request.setWorkspaceCode(workspaceCode);
        request.setWorkspaceName(workspaceName);
        request.setWorkspaceType("DEFAULT");
        request.setDefaultLocale("zh-CN");
        request.setDefaultTimezone("Asia/Shanghai");
        request.setRememberAsDefault(rememberAsDefault);

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

    private <T> T readValue(MvcResult result, Class<T> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), type);
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
                verify(registerEmailSender).sendRegisterVerificationEmail(emailCaptor.capture(), codeCaptor.capture(), any());
                Assertions.assertEquals(email, emailCaptor.getValue());
                Assertions.assertTrue(codeCaptor.getValue().matches("\\d{6}"));

                AuthSendRegisterEmailCodeResponseDto response = readValue(result, AuthSendRegisterEmailCodeResponseDto.class);
                Assertions.assertTrue(response.getExpireInSeconds() > 0);
                return codeCaptor.getValue();
        }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}