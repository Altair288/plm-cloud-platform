# 用户、Workspace、角色与权限数据库设计草案

更新时间：2026-04-16
阶段：数据库设计草案（当前阶段服务于 user -> workspace 结构，不一步到位实现完整 SaaS tenant 模型）

---

## 1. 目标与范围

本稿用于承接 [用户、Workspace、角色与权限基础设计草案](user-tenant-rbac-foundation-design-draft.md) 中与数据库相关的内容，提供更具体的：

- schema 划分。
- 表名称。
- 表字段。
- 主键、唯一约束与外键关系。
- 索引建议。

围绕这些表的注册、登录、创建 workspace、切换 workspace 流程设计，详见 [用户注册、登录、创建 Workspace、切换 Workspace 设计草案](user-workspace-auth-basic-flow-design-draft.md)。

本稿当前只覆盖用户域第一阶段核心数据模型，不覆盖：

- 完整 tenant / subscription / billing 体系。
- 元数据回写审批流。
- 复杂组织树。
- 业务运行态实体的完整重建。

当前稿已纳入注册邮箱验证码的最小落地建模。

---

## 2. Schema 划分建议

### 2.1 plm_platform

用途：平台控制面与身份权限域。

放置内容：

- 用户账号。
- 用户凭证。
- workspace。
- workspace 成员。
- 平台角色。
- workspace 角色。
- 权限字典。
- 角色权限关系。
- 登录审计与 workspace 邀请等控制面辅助表。

结论：

- 当前阶段用户域核心表建议全部放在 plm_platform。
- 不建议放入 plm_runtime。
- 也不建议直接混入 plm_meta。

### 2.2 plm_meta

用途：平台标准元数据。

放置内容：

- 标准分类。
- 标准属性。
- 标准 LOV。
- 标准规则模板。
- 平台标准字典。

说明：

- 本稿不向 plm_meta 增加用户、workspace、成员、角色表。

### 2.3 plm_runtime

用途：workspace 运行态业务数据。

放置内容：

- 产品、分类实例、属性值、文档、BOM 等真正的业务运行态实体。

说明：

- 本阶段只约定后续运行态表统一向 workspace_id 收敛。
- 本稿不展开运行态业务表完整设计。

### 2.4 plm_runtime 存储架构定位

当前对 plm_runtime 的定位，不是“每个 workspace 独立数据库”模式，而是：

- 共享同一个 PostgreSQL 实例。
- 共享同一个应用库。
- 共享同一组运行态 schema / 表结构。
- 通过 workspace_id 进行逻辑隔离与权限收敛。

因此，它更接近“共享数据库、共享表结构、逻辑隔离数据”的模式。

需要明确：

- 这里的共享，不等于不同 workspace 共享同一批业务数据。
- 每条运行态业务数据仍应明确归属于某个 workspace。
- 查询、唯一约束、权限判定、审计追踪，都应以 workspace_id 为核心隔离维度。
- 当前阶段不采用“独库独 schema 独表”的重型多租户部署模型。

---

## 3. 命名与通用字段约定

### 3.1 主键约定

- 所有主键统一使用 UUID。
- 主键列统一命名为 id。

### 3.2 时间字段约定

- 时间字段统一使用 TIMESTAMPTZ。
- 常用字段包括 created_at、updated_at、registered_at、joined_at、assigned_at、accepted_at、last_login_at。

### 3.3 状态字段约定

- 状态字段统一使用 VARCHAR，避免过早绑定数据库枚举类型。
- 具体状态值由应用层字典与约束控制。

### 3.4 审计字段约定

建议通用审计字段：

- created_at
- created_by
- updated_at
- updated_by

说明：

- created_by / updated_by 建议先使用 VARCHAR(64) 记录操作者标识，兼容 SYSTEM、MIGRATION、用户 ID 字符串等场景。
- 需要强关系的业务动作，如 assigned_by_user_id、invited_by_user_id，则单独使用 UUID 外键。

### 3.5 大小写唯一性约定

- username、email 这类人输入标识建议按小写规范唯一。
- PostgreSQL 层建议通过 UNIQUE INDEX ON lower(column) 实现。

### 3.6 Workspace 引导字典约定

- workspace 的 `workspace_type`、`default_locale`、`default_timezone` 当前不再建议写死常量，而是由平台控制面字典表统一维护。
- 业务表仍保留字符串 code 列，便于接口传输与前端联调，但数据库层建议通过外键约束将其收敛到字典表。
- 当前阶段 workspace 类型固定落地三种 code：`TEAM`、`PERSONAL`、`LEARNING`。
- 历史数据若使用过旧值 `DEFAULT`，迁移时应先回填映射到 `TEAM`，再补外键约束。

---

## 4. 核心表设计

### 4.1 UserAccount

Schema：plm_platform  
表名：user_account

用途：全局用户主体。

#### UserAccount 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| username | VARCHAR(64) | Y | 登录用户名 |
| display_name | VARCHAR(128) | Y | 显示名称 |
| email | VARCHAR(128) | N | 邮箱 |
| phone | VARCHAR(32) | N | 手机号 |
| status | VARCHAR(20) | Y | 账号状态，如 ACTIVE、DISABLED、LOCKED |
| source_type | VARCHAR(20) | Y | 账号来源，如 LOCAL、INVITED、SYSTEM |
| is_first_login | BOOLEAN | Y | 是否尚未完成首次 workspace 创建 |
| workspace_count | INTEGER | Y | 当前活跃 workspace 数 |
| registered_at | TIMESTAMPTZ | Y | 注册时间 |
| last_login_at | TIMESTAMPTZ | N | 最近登录时间 |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |
| updated_at | TIMESTAMPTZ | N | 更新时间 |
| updated_by | VARCHAR(64) | N | 更新者 |

#### UserAccount 约束

- PRIMARY KEY (id)

#### UserAccount 索引

- UNIQUE INDEX uidx_user_account_username_ci ON plm_platform.user_account (lower(username))
- UNIQUE INDEX uidx_user_account_email_ci ON plm_platform.user_account (lower(email)) WHERE email IS NOT NULL
- UNIQUE INDEX uidx_user_account_phone ON plm_platform.user_account (phone) WHERE phone IS NOT NULL
- INDEX idx_user_account_status ON plm_platform.user_account (status)
- INDEX idx_user_account_registered_at ON plm_platform.user_account (registered_at)

---

### 4.2 UserCredential

Schema：plm_platform  
表名：user_credential

用途：用户认证凭证。

#### UserCredential 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| user_id | UUID | Y | 对应用户 |
| credential_type | VARCHAR(20) | Y | 凭证类型，当前可先支持 PASSWORD |
| secret_hash | VARCHAR(255) | Y | 凭证摘要 |
| secret_salt | VARCHAR(255) | N | 凭证盐值 |
| status | VARCHAR(20) | Y | 凭证状态 |
| expires_at | TIMESTAMPTZ | N | 过期时间 |
| last_rotated_at | TIMESTAMPTZ | N | 最近轮换时间 |
| last_verified_at | TIMESTAMPTZ | N | 最近校验成功时间 |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |
| updated_at | TIMESTAMPTZ | N | 更新时间 |
| updated_by | VARCHAR(64) | N | 更新者 |

#### UserCredential 约束

- PRIMARY KEY (id)
- FOREIGN KEY (user_id) REFERENCES plm_platform.user_account(id) ON DELETE CASCADE
- UNIQUE (user_id, credential_type)

#### UserCredential 索引

- INDEX idx_user_credential_user_status ON plm_platform.user_credential (user_id, status)
- INDEX idx_user_credential_expires_at ON plm_platform.user_credential (expires_at) WHERE expires_at IS NOT NULL

---

### 4.3 Workspace

Schema：plm_platform  
表名：workspace

用途：当前阶段最小业务空间。

#### Workspace 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| workspace_code | VARCHAR(64) | Y | 系统生成的稳定标识，当前默认规则为 `ws_{ownerUserId8}_{workspaceNameSlug}_{workspaceId8}` |
| workspace_name | VARCHAR(128) | Y | 空间名称 |
| workspace_status | VARCHAR(20) | Y | 状态，如 ACTIVE、FROZEN、ARCHIVED |
| owner_user_id | UUID | Y | 默认拥有者 |
| workspace_type | VARCHAR(20) | Y | 类型 code，引用 `plm_platform.workspace_type`，当前值为 TEAM / PERSONAL / LEARNING |
| lifecycle_stage | VARCHAR(20) | Y | 生命周期阶段 |
| default_locale | VARCHAR(16) | Y | 默认语言环境 code，引用 `plm_platform.workspace_locale` |
| default_timezone | VARCHAR(64) | Y | 默认时区 code，引用 `plm_platform.workspace_timezone` |
| config_json | JSONB | N | 空间级扩展配置 |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |
| updated_at | TIMESTAMPTZ | N | 更新时间 |
| updated_by | VARCHAR(64) | N | 更新者 |

#### Workspace 约束

- PRIMARY KEY (id)
- FOREIGN KEY (owner_user_id) REFERENCES plm_platform.user_account(id)
- FOREIGN KEY (workspace_type) REFERENCES plm_platform.workspace_type(code)
- FOREIGN KEY (default_locale) REFERENCES plm_platform.workspace_locale(code)
- FOREIGN KEY (default_timezone) REFERENCES plm_platform.workspace_timezone(code)
- UNIQUE (workspace_code)

#### Workspace 索引

- INDEX idx_workspace_owner ON plm_platform.workspace (owner_user_id)
- INDEX idx_workspace_status ON plm_platform.workspace (workspace_status)
- INDEX idx_workspace_type ON plm_platform.workspace (workspace_type)
- INDEX idx_workspace_created_at ON plm_platform.workspace (created_at)
- GIN INDEX idx_workspace_config_gin ON plm_platform.workspace USING gin (config_json jsonb_path_ops)

---

### 4.3.1 WorkspaceTypeDefinition

Schema：plm_platform  
表名：workspace_type

用途：workspace 类型字典，供创建引导、表单校验和后续 UI 分类展示使用。

#### WorkspaceTypeDefinition 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| code | VARCHAR(20) | Y | 主键，类型编码，如 TEAM、PERSONAL、LEARNING |
| name | VARCHAR(64) | Y | 类型名称 |
| description | VARCHAR(255) | N | 类型说明 |
| sort_order | INTEGER | Y | 排序号 |
| enabled | BOOLEAN | Y | 是否可用 |
| is_default | BOOLEAN | Y | 是否默认选项 |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |
| updated_at | TIMESTAMPTZ | N | 更新时间 |
| updated_by | VARCHAR(64) | N | 更新者 |

#### WorkspaceTypeDefinition 约束

- PRIMARY KEY (code)

#### WorkspaceTypeDefinition 索引

- INDEX idx_workspace_type_enabled_sort ON plm_platform.workspace_type (enabled, sort_order)
- UNIQUE INDEX uidx_workspace_type_default ON plm_platform.workspace_type (is_default) WHERE is_default = TRUE

说明：当前初始化数据固定为 `TEAM`、`PERSONAL`、`LEARNING`，其中 `TEAM` 为默认项。

---

### 4.3.2 WorkspaceLocaleDefinition

Schema：plm_platform  
表名：workspace_locale

用途：workspace 默认语言环境字典。

#### WorkspaceLocaleDefinition 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| code | VARCHAR(16) | Y | 主键，如 zh-CN、en-US |
| name | VARCHAR(64) | Y | 显示名称 |
| description | VARCHAR(255) | N | 说明 |
| sort_order | INTEGER | Y | 排序号 |
| enabled | BOOLEAN | Y | 是否可用 |
| is_default | BOOLEAN | Y | 是否默认选项 |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |
| updated_at | TIMESTAMPTZ | N | 更新时间 |
| updated_by | VARCHAR(64) | N | 更新者 |

#### WorkspaceLocaleDefinition 约束

- PRIMARY KEY (code)

#### WorkspaceLocaleDefinition 索引

- INDEX idx_workspace_locale_enabled_sort ON plm_platform.workspace_locale (enabled, sort_order)
- UNIQUE INDEX uidx_workspace_locale_default ON plm_platform.workspace_locale (is_default) WHERE is_default = TRUE

说明：当前初始化数据为 `zh-CN`、`en-US`，其中 `zh-CN` 为默认项。

---

### 4.3.3 WorkspaceTimezoneDefinition

Schema：plm_platform  
表名：workspace_timezone

用途：workspace 默认时区字典。

#### WorkspaceTimezoneDefinition 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| code | VARCHAR(64) | Y | 主键，如 Asia/Shanghai、UTC |
| name | VARCHAR(64) | Y | 显示名称 |
| description | VARCHAR(255) | N | 说明 |
| sort_order | INTEGER | Y | 排序号 |
| enabled | BOOLEAN | Y | 是否可用 |
| is_default | BOOLEAN | Y | 是否默认选项 |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |
| updated_at | TIMESTAMPTZ | N | 更新时间 |
| updated_by | VARCHAR(64) | N | 更新者 |

#### WorkspaceTimezoneDefinition 约束

- PRIMARY KEY (code)

#### WorkspaceTimezoneDefinition 索引

- INDEX idx_workspace_timezone_enabled_sort ON plm_platform.workspace_timezone (enabled, sort_order)
- UNIQUE INDEX uidx_workspace_timezone_default ON plm_platform.workspace_timezone (is_default) WHERE is_default = TRUE

说明：当前初始化数据为 `Asia/Shanghai`、`UTC`、`America/Los_Angeles`，其中 `Asia/Shanghai` 为默认项。

---

### 4.4 WorkspaceMember

Schema：plm_platform  
表名：workspace_member

用途：用户在 workspace 中的成员身份。

#### WorkspaceMember 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| workspace_id | UUID | Y | 所属 workspace |
| user_id | UUID | Y | 所属用户 |
| member_status | VARCHAR(20) | Y | 成员状态，如 ACTIVE、INVITED、DISABLED、LEFT |
| join_type | VARCHAR(20) | Y | 加入方式，如 OWNER、INVITE、DIRECT |
| joined_at | TIMESTAMPTZ | N | 正式加入时间 |
| invited_by_user_id | UUID | N | 邀请人 |
| is_default_workspace | BOOLEAN | Y | 是否为默认 workspace |
| remark | VARCHAR(255) | N | 备注 |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |
| updated_at | TIMESTAMPTZ | N | 更新时间 |
| updated_by | VARCHAR(64) | N | 更新者 |

#### WorkspaceMember 约束

- PRIMARY KEY (id)
- FOREIGN KEY (workspace_id) REFERENCES plm_platform.workspace(id) ON DELETE CASCADE
- FOREIGN KEY (user_id) REFERENCES plm_platform.user_account(id) ON DELETE CASCADE
- FOREIGN KEY (invited_by_user_id) REFERENCES plm_platform.user_account(id)
- UNIQUE (workspace_id, user_id)

#### WorkspaceMember 索引

- INDEX idx_workspace_member_workspace_status ON plm_platform.workspace_member (workspace_id, member_status)
- INDEX idx_workspace_member_user_status ON plm_platform.workspace_member (user_id, member_status)
- UNIQUE INDEX uidx_workspace_member_default_workspace ON plm_platform.workspace_member (user_id) WHERE is_default_workspace = TRUE

---

### 4.5 Permission

Schema：plm_platform  
表名：permission

用途：统一权限字典。

#### Permission 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| permission_code | VARCHAR(96) | Y | 权限编码 |
| permission_name | VARCHAR(128) | Y | 权限名称 |
| scope_type | VARCHAR(20) | Y | 作用域，如 GLOBAL、WORKSPACE |
| module_code | VARCHAR(64) | Y | 模块编码 |
| description | TEXT | N | 描述 |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |

#### Permission 约束

- PRIMARY KEY (id)
- UNIQUE (permission_code)

#### Permission 索引

- INDEX idx_permission_scope_module ON plm_platform.permission (scope_type, module_code)

---

### 4.6 PlatformRole

Schema：plm_platform  
表名：platform_role

用途：平台视角角色。

#### PlatformRole 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| role_code | VARCHAR(64) | Y | 角色编码 |
| role_name | VARCHAR(128) | Y | 角色名称 |
| role_status | VARCHAR(20) | Y | 角色状态 |
| built_in_flag | BOOLEAN | Y | 是否内建 |
| description | TEXT | N | 描述 |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |

#### PlatformRole 约束

- PRIMARY KEY (id)
- UNIQUE (role_code)

#### PlatformRole 索引

- INDEX idx_platform_role_status ON plm_platform.platform_role (role_status)

---

### 4.7 PlatformUserRole

Schema：plm_platform  
表名：platform_user_role

用途：平台用户角色关联。

#### PlatformUserRole 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| user_id | UUID | Y | 用户 |
| role_id | UUID | Y | 角色 |
| assigned_at | TIMESTAMPTZ | Y | 分配时间 |
| assigned_by_user_id | UUID | N | 分配人 |

#### PlatformUserRole 约束

- PRIMARY KEY (user_id, role_id)
- FOREIGN KEY (user_id) REFERENCES plm_platform.user_account(id) ON DELETE CASCADE
- FOREIGN KEY (role_id) REFERENCES plm_platform.platform_role(id) ON DELETE CASCADE
- FOREIGN KEY (assigned_by_user_id) REFERENCES plm_platform.user_account(id)

#### PlatformUserRole 索引

- INDEX idx_platform_user_role_role ON plm_platform.platform_user_role (role_id)

---

### 4.8 PlatformRolePermission

Schema：plm_platform  
表名：platform_role_permission

用途：平台角色权限关联。

#### PlatformRolePermission 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| role_id | UUID | Y | 平台角色 |
| permission_id | UUID | Y | 权限 |

#### PlatformRolePermission 约束

- PRIMARY KEY (role_id, permission_id)
- FOREIGN KEY (role_id) REFERENCES plm_platform.platform_role(id) ON DELETE CASCADE
- FOREIGN KEY (permission_id) REFERENCES plm_platform.permission(id) ON DELETE CASCADE

#### PlatformRolePermission 索引

- INDEX idx_platform_role_permission_permission ON plm_platform.platform_role_permission (permission_id)

---

### 4.9 WorkspaceRole

Schema：plm_platform  
表名：workspace_role

用途：workspace 内实际生效的角色模板。

#### WorkspaceRole 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| workspace_id | UUID | Y | 所属 workspace |
| role_code | VARCHAR(64) | Y | 角色编码 |
| role_name | VARCHAR(128) | Y | 角色名称 |
| role_type | VARCHAR(20) | Y | SYSTEM / CUSTOM |
| role_status | VARCHAR(20) | Y | 角色状态 |
| built_in_flag | BOOLEAN | Y | 是否内建 |
| description | TEXT | N | 描述 |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |
| updated_at | TIMESTAMPTZ | N | 更新时间 |
| updated_by | VARCHAR(64) | N | 更新者 |

#### WorkspaceRole 约束

- PRIMARY KEY (id)
- FOREIGN KEY (workspace_id) REFERENCES plm_platform.workspace(id) ON DELETE CASCADE
- UNIQUE (workspace_id, role_code)

#### WorkspaceRole 索引

- INDEX idx_workspace_role_workspace_status ON plm_platform.workspace_role (workspace_id, role_status)
- INDEX idx_workspace_role_workspace_type ON plm_platform.workspace_role (workspace_id, role_type)

---

### 4.10 WorkspaceMemberRole

Schema：plm_platform  
表名：workspace_member_role

用途：workspace 成员角色关联。

#### WorkspaceMemberRole 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| workspace_member_id | UUID | Y | 空间成员 |
| workspace_role_id | UUID | Y | 空间角色 |
| assigned_at | TIMESTAMPTZ | Y | 分配时间 |
| assigned_by_user_id | UUID | N | 分配人 |

#### WorkspaceMemberRole 约束

- PRIMARY KEY (workspace_member_id, workspace_role_id)
- FOREIGN KEY (workspace_member_id) REFERENCES plm_platform.workspace_member(id) ON DELETE CASCADE
- FOREIGN KEY (workspace_role_id) REFERENCES plm_platform.workspace_role(id) ON DELETE CASCADE
- FOREIGN KEY (assigned_by_user_id) REFERENCES plm_platform.user_account(id)

#### WorkspaceMemberRole 索引

- INDEX idx_workspace_member_role_role ON plm_platform.workspace_member_role (workspace_role_id)

说明：

- workspace_member_role 无法仅靠两个单列外键保证“成员与角色属于同一 workspace”。
- 第一阶段建议由应用层校验这一点。
- 若后续要做数据库层强约束，可在 workspace_member 与 workspace_role 上增加复合唯一键，并在关联表中显式携带 workspace_id 形成复合外键。

---

### 4.11 WorkspaceRolePermission

Schema：plm_platform  
表名：workspace_role_permission

用途：workspace 角色权限关联。

#### WorkspaceRolePermission 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| workspace_role_id | UUID | Y | 空间角色 |
| permission_id | UUID | Y | 权限 |

#### WorkspaceRolePermission 约束

- PRIMARY KEY (workspace_role_id, permission_id)
- FOREIGN KEY (workspace_role_id) REFERENCES plm_platform.workspace_role(id) ON DELETE CASCADE
- FOREIGN KEY (permission_id) REFERENCES plm_platform.permission(id) ON DELETE CASCADE

#### WorkspaceRolePermission 索引

- INDEX idx_workspace_role_permission_permission ON plm_platform.workspace_role_permission (permission_id)

---

## 5. 推荐辅助表

以下表不是“无它不可”，但根据当前需求很快会变成必要辅助表。

### 5.1 WorkspaceInvitation

Schema：plm_platform  
表名：workspace_invitation

用途：支持成员邀请。

#### WorkspaceInvitation 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| workspace_id | UUID | Y | 所属 workspace |
| invitee_email | VARCHAR(128) | Y | 被邀请邮箱 |
| invitee_display_name | VARCHAR(128) | N | 被邀请显示名 |
| invited_by_user_id | UUID | Y | 邀请人 |
| invitation_status | VARCHAR(20) | Y | PENDING、ACCEPTED、EXPIRED、CANCELED |
| invitation_token | VARCHAR(128) | Y | 邀请令牌 |
| expires_at | TIMESTAMPTZ | Y | 失效时间 |
| accepted_by_user_id | UUID | N | 接受邀请的用户 |
| accepted_at | TIMESTAMPTZ | N | 接受时间 |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |

#### WorkspaceInvitation 约束

- PRIMARY KEY (id)
- FOREIGN KEY (workspace_id) REFERENCES plm_platform.workspace(id) ON DELETE CASCADE
- FOREIGN KEY (invited_by_user_id) REFERENCES plm_platform.user_account(id)
- FOREIGN KEY (accepted_by_user_id) REFERENCES plm_platform.user_account(id)
- UNIQUE (invitation_token)

#### WorkspaceInvitation 索引

- INDEX idx_workspace_invitation_workspace_status ON plm_platform.workspace_invitation (workspace_id, invitation_status)
- INDEX idx_workspace_invitation_email_status ON plm_platform.workspace_invitation (lower(invitee_email), invitation_status)
- INDEX idx_workspace_invitation_expires_at ON plm_platform.workspace_invitation (expires_at)

---

### 5.2 LoginAudit

Schema：plm_platform  
表名：login_audit

用途：记录平台端与 workspace 端登录行为。

#### LoginAudit 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| user_id | UUID | N | 登录用户 |
| login_type | VARCHAR(20) | Y | platform / workspace |
| login_result | VARCHAR(20) | Y | SUCCESS / FAILED |
| login_ip | VARCHAR(64) | N | 登录 IP |
| user_agent | VARCHAR(512) | N | 终端信息 |
| failure_reason | VARCHAR(255) | N | 失败原因 |
| created_at | TIMESTAMPTZ | Y | 发生时间 |

#### LoginAudit 约束

- PRIMARY KEY (id)
- FOREIGN KEY (user_id) REFERENCES plm_platform.user_account(id)

#### LoginAudit 索引

- INDEX idx_login_audit_user_created_at ON plm_platform.login_audit (user_id, created_at DESC)
- INDEX idx_login_audit_type_result_created_at ON plm_platform.login_audit (login_type, login_result, created_at DESC)

---

### 5.3 EmailVerificationCode

Schema：plm_platform  
表名：email_verification_code

用途：保存注册邮箱验证码发送、过期、覆盖、消费轨迹。

#### EmailVerificationCode 字段

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| target_email | VARCHAR(128) | Y | 目标邮箱，注册前的业务锚点 |
| verification_purpose | VARCHAR(32) | Y | 验证用途，当前为 REGISTER |
| code_hash | VARCHAR(255) | Y | 验证码哈希 |
| code_status | VARCHAR(20) | Y | PENDING、USED、EXPIRED、SUPERSEDED |
| expires_at | TIMESTAMPTZ | Y | 过期时间 |
| consumed_at | TIMESTAMPTZ | N | 消费时间 |
| consumed_by_user_id | UUID | N | 成功注册后回填的用户 ID |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |
| updated_at | TIMESTAMPTZ | N | 更新时间 |
| updated_by | VARCHAR(64) | N | 更新者 |

#### EmailVerificationCode 约束

- PRIMARY KEY (id)
- FOREIGN KEY (consumed_by_user_id) REFERENCES plm_platform.user_account(id)

#### EmailVerificationCode 索引

- INDEX idx_email_verification_code_target_purpose_status_created ON plm_platform.email_verification_code (lower(target_email), verification_purpose, code_status, created_at DESC)
- INDEX idx_email_verification_code_expires_at ON plm_platform.email_verification_code (expires_at)
- INDEX idx_email_verification_code_consumed_by_user ON plm_platform.email_verification_code (consumed_by_user_id) WHERE consumed_by_user_id IS NOT NULL

设计说明：

- 注册邮箱验证码发生在 user_account 正式创建之前，因此不能把 user_id 作为发送阶段的必填外键。
- 当前阶段采用 target_email 作为注册前业务锚点，这是符合注册前置校验场景的。
- 为了补足与现有用户域模型的关联，验证码在成功消费后会回填 consumed_by_user_id，形成与 user_account 的弱前置、强后置关联。
- 这个设计既避免了“注册前没有 user 却强行要求外键”的建模冲突，也保留了注册后追溯验证码消费归属的能力。

---

## 6. 种子数据建议

### 6.1 平台内建角色

建议初始化：

- platform_super_admin
- platform_admin
- platform_operator
- platform_auditor

### 6.2 Workspace 内建角色

建议在 workspace 创建时自动初始化：

- workspace_owner
- workspace_admin
- workspace_member
- workspace_viewer

### 6.3 权限字典

建议初始化：

- platform.workspace.read
- platform.workspace.create
- platform.workspace.update
- platform.workspace.freeze
- platform.user.read
- platform.user.assign-role
- platform.audit.read
- workspace.member.read
- workspace.member.invite
- workspace.member.disable
- workspace.member.assign-role
- workspace.profile.read
- workspace.profile.update
- workspace.config.read
- workspace.config.update
- runtime.import.execute
- runtime.export.execute

---

## 7. 与 plm_runtime 的衔接约定

虽然本稿不设计运行态业务表，但需要先明确一个统一约定：

- 自第一阶段起，后续新增的 workspace 侧运行态业务表应优先携带 workspace_id。
- 不建议把 user_id 直接作为运行态业务隔离主轴。
- 任何运行态业务查询最终都应能收敛为按 workspace_id 过滤。
- 若某张运行态表存在业务唯一性约束，应优先评估是否需要做成 (workspace_id, business_key) 形式，而不是全局唯一。
- 运行态表之间的外键链路，应尽量保持在同一 workspace 隔离上下文中闭合，避免出现跨 workspace 关联。
- 服务层读取运行态数据时，不允许脱离 workspace 上下文做裸查询。

这保证当前阶段采用 workspace 结构后，未来不会在运行态隔离维度上再次返工。

---

## 8. 与未来 SaaS / Tenant 扩展的兼容性

当前数据库模型虽然不实现 Tenant，但已预留足够扩展空间：

- Workspace 是独立实体，而不是用户字段附属品。
- WorkspaceMember 是独立关系表，而不是 user_account 上的单列 workspace_id。
- WorkspaceRole 是空间级角色，而不是全局角色强行复用。
- 运行态隔离字段预期收敛到 workspace_id，而不是 user_id。

未来如果进入完整 SaaS 模型，可考虑新增：

- tenant
- tenant_workspace
- tenant_subscription
- tenant_billing_profile

而无需推翻当前 user / workspace / member / role 结构。

---

## 9. 本稿结论

基于当前需求，用户域第一阶段数据库设计建议如下：

- 用户、workspace、成员、角色、权限全部落在 plm_platform。
- plm_meta 继续只放标准元数据。
- plm_runtime 继续只放真正的业务运行态数据。
- 当前主结构采用 user -> workspace，而不是一步到位实现 tenant。
- 但通过独立 Workspace 实体、成员关系表、空间角色表和 workspace_id 隔离约定，保留未来向 SaaS / tenant 演进的完整扩展能力。
