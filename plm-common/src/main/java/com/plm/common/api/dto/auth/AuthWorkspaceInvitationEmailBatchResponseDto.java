package com.plm.common.api.dto.auth;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class AuthWorkspaceInvitationEmailBatchResponseDto {
    private UUID workspaceId;
    private UUID batchId;
    private int successCount;
    private int skippedCount;
    private List<AuthWorkspaceInvitationEmailBatchItemDto> results;
}