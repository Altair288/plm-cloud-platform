package com.plm.common.domain.auth;

import lombok.Data;

import java.io.Serializable;
import java.util.UUID;

@Data
public class PlatformUserRoleId implements Serializable {
    private UUID userId;
    private UUID roleId;
}