package com.plm.infrastructure.repository.storage;

import com.plm.common.domain.storage.ObjectUploadSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ObjectUploadSessionRepository extends JpaRepository<ObjectUploadSession, UUID> {
  List<ObjectUploadSession> findByWorkspaceId(UUID workspaceId);

    Optional<ObjectUploadSession> findByUploadToken(String uploadToken);

    Optional<ObjectUploadSession> findByUploadTokenAndObjectId(String uploadToken, UUID objectId);
}