package com.plm.auth.service;

import com.plm.auth.support.AuthDomainConstants;
import com.plm.common.domain.auth.UserAccount;
import com.plm.infrastructure.repository.auth.UserAccountRepository;
import com.plm.infrastructure.repository.auth.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class UserWorkspaceStateService {

    private static final String UPDATED_BY_SYNC = "AUTH_USER_WORKSPACE_STATE_SYNC";

    private final UserAccountRepository userAccountRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    public UserWorkspaceStateService(UserAccountRepository userAccountRepository,
                                     WorkspaceMemberRepository workspaceMemberRepository) {
        this.userAccountRepository = userAccountRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
    }

    @Transactional
    public UserAccount syncUserWorkspaceState(UserAccount user) {
        int actualWorkspaceCount = Math.toIntExact(
                workspaceMemberRepository.countByUserIdAndMemberStatus(
                        user.getId(),
                        AuthDomainConstants.WORKSPACE_MEMBER_STATUS_ACTIVE
                )
        );

        boolean persistedFirstLogin = !Boolean.FALSE.equals(user.getIsFirstLogin());
        boolean effectiveFirstLogin = actualWorkspaceCount > 0 ? false : persistedFirstLogin;

        boolean changed = !Objects.equals(user.getWorkspaceCount(), actualWorkspaceCount)
                || !Objects.equals(user.getIsFirstLogin(), effectiveFirstLogin);
        if (!changed) {
            return user;
        }

        user.setWorkspaceCount(actualWorkspaceCount);
        user.setIsFirstLogin(effectiveFirstLogin);
        user.setUpdatedBy(UPDATED_BY_SYNC);
        return userAccountRepository.save(user);
    }
}