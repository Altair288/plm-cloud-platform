package com.plm.infrastructure.repository.auth;

import com.plm.common.domain.auth.UserCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserCredentialRepository extends JpaRepository<UserCredential, UUID> {
    Optional<UserCredential> findByUserIdAndCredentialType(UUID userId, String credentialType);
}