package com.plm.common.api.dto.auth;

import lombok.Data;

import java.util.UUID;

@Data
public class AuthWorkspaceInvitationEmailBatchItemDto {
    private String email;
    private String result;
    private UUID invitationId;
    private String message;
}