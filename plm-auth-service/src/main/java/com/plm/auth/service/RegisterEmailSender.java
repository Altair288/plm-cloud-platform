package com.plm.auth.service;

import java.time.OffsetDateTime;

public interface RegisterEmailSender {
    void sendRegisterVerificationEmail(String email, String verificationCode, OffsetDateTime expiresAt);
}