package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, UUID> {
        Optional<EmailVerificationCode> findTopByTargetEmailAndVerificationPurposeOrderByCreatedAtDesc(
                        String targetEmail,
                        String verificationPurpose
        );

    Optional<EmailVerificationCode> findTopByTargetEmailAndVerificationPurposeAndCodeStatusOrderByCreatedAtDesc(
            String targetEmail,
            String verificationPurpose,
            String codeStatus
    );

    List<EmailVerificationCode> findAllByTargetEmailAndVerificationPurposeAndCodeStatusOrderByCreatedAtDesc(
            String targetEmail,
            String verificationPurpose,
            String codeStatus
    );

    long countByTargetEmailAndVerificationPurposeAndCreatedAtAfter(
            String targetEmail,
            String verificationPurpose,
            OffsetDateTime createdAt
    );
}