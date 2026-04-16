package com.plm.auth.service;

import com.plm.auth.util.AuthNormalizer;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

@Service
public class WorkspaceCodeGenerationService {

    private static final String WORKSPACE_CODE_PREFIX = "ws";
    private static final int UUID_PREFIX_LENGTH = 8;
    private static final int MAX_WORKSPACE_CODE_LENGTH = 64;
    private static final int FIXED_PART_LENGTH = WORKSPACE_CODE_PREFIX.length() + 1 + UUID_PREFIX_LENGTH + 1 + 1 + UUID_PREFIX_LENGTH;
    private static final int MAX_SLUG_LENGTH = MAX_WORKSPACE_CODE_LENGTH - FIXED_PART_LENGTH;

    public String generateWorkspaceCode(UUID ownerUserId, UUID workspaceId, String workspaceName, String workspaceType) {
        String ownerPrefix = toUuidPrefix(ownerUserId);
        String workspacePrefix = toUuidPrefix(workspaceId);

        String slug = AuthNormalizer.normalizeWorkspaceCodeSlug(workspaceName);
        if (slug == null) {
            slug = AuthNormalizer.normalizeWorkspaceCodeSlug(workspaceType);
        }
        if (slug == null) {
            slug = "workspace";
        }
        if (slug.length() > MAX_SLUG_LENGTH) {
            slug = slug.substring(0, MAX_SLUG_LENGTH);
        }

        return AuthNormalizer.normalizeWorkspaceCode(
                WORKSPACE_CODE_PREFIX + "_" + ownerPrefix + "_" + slug + "_" + workspacePrefix
        );
    }

    private String toUuidPrefix(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("uuid prefix source is required");
        }
        return value.toString().replace("-", "").substring(0, UUID_PREFIX_LENGTH).toLowerCase(Locale.ROOT);
    }
}