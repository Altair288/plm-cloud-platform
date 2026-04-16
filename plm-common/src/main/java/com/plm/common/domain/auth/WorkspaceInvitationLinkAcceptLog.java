package com.plm.common.domain.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "workspace_invitation_link_accept_log", schema = "plm_platform")
@Getter
@Setter
public class WorkspaceInvitationLinkAcceptLog {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invitation_link_id", nullable = false)
    private UUID invitationLinkId;

    @Column(name = "accepted_by_user_id", nullable = false)
    private UUID acceptedByUserId;

    @Column(name = "workspace_member_id", nullable = false)
    private UUID workspaceMemberId;

    @Column(name = "accepted_at", nullable = false)
    private OffsetDateTime acceptedAt;

    @Column(name = "accept_ip", length = 64)
    private String acceptIp;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (acceptedAt == null) {
            acceptedAt = OffsetDateTime.now();
        }
    }
}