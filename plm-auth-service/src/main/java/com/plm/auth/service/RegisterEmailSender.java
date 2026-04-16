package com.plm.auth.service;

import java.time.OffsetDateTime;

public interface RegisterEmailSender {
    void sendRegisterVerificationEmail(String email, String verificationCode, OffsetDateTime expiresAt);

    void sendWorkspaceInvitationEmail(String email,
                                      String workspaceName,
                                      String inviterDisplayName,
                                      String acceptUrl,
                                      OffsetDateTime expiresAt);

    void sendTestEmail(String email, OffsetDateTime sentAt);
}