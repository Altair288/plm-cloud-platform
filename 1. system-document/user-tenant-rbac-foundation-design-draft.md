# 用户、Workspace、角色与权限基础设计草案

更新时间：2026-04-10
阶段：基础设计草案（当前阶段以 user -> workspace 为核心，保留未来向 SaaS / tenant 模型扩展的结构余量）

---

## 1. 背景

经过进一步收敛，当前阶段的目标不再是一步到位实现完整 SaaS 租户体系，而是先建立一套更贴近现阶段业务的基础结构：

- 用户注册与登录。
- 用户拥有一个或多个 workspace。
- workspace 内存在成员、角色、权限与上下文切换。
- 平台管理员视角与 workspace 业务视角隔离。

这样做的原因是：

- 当前最紧迫的问题是用户域、工作空间域与权限域的落地，而不是完整的租户运营模型。
- 直接上完整 tenant / subscription / billing / governance 体系，复杂度过高，且会把当前真正要解决的问题稀释掉。
- 但如果当前结构设计得太轻，又会让后续向 SaaS 扩展时产生大规模返工。

因此，本草案采用“workspace 优先、tenant 预留扩展位”的思路。

数据库层面的具体 schema、表、字段与索引设计，详见 [用户、Workspace、角色与权限数据库设计草案](user-workspace-rbac-database-design-draft.md)。

---

## 2. 当前约束与前提

### 2.1 当前实施前提

- runtime 旧数据已准备全部舍弃，运行态表允许重新设计。
- 运行态正式命名统一按 plm_runtime 讨论。
- 当前阶段只讨论用户、workspace、成员、角色、权限、登录态与上下文切换。
- 元数据回写、审批流、复杂组织树、平台计费与订阅不在本阶段范围内。

### 2.2 当前仓库现状

- auth 服务仍是骨架，只有 Sa-Token 最薄接入，还没有真正的用户域实现。
- 旧运行态 V1 中的 user_account、role、permission、user_role 只是全局 RBAC 雏形。
- 当前基础设施仍偏单数据源思路，本草案不把多数据源路由作为第一阶段前置条件。

### 2.3 本草案采用的术语

- 用户：全局账号主体，对应一个自然人或系统主体。
- Workspace：当前阶段的最小业务空间与协作边界。
- 成员：某个全局用户在某个 workspace 中的身份实例。
- 平台角色：只在平台视角下生效的角色。
- Workspace 角色：只在 workspace 视角下生效的角色。
- 权限：细粒度操作能力标识。
- 作用域：权限生效范围，当前至少区分 GLOBAL 与 WORKSPACE。

说明：

- business_domain 继续保留为元数据/业务领域维度。
- workspace 不等于 business_domain。
- permission scope 也不等于 business_domain。

这三个概念必须严格分离。

---

## 3. 设计目标

### 3.1 当前阶段必须实现的目标

- 支持用户注册、登录、登出。
- 支持用户创建与拥有 workspace。
- 支持一个用户加入多个 workspace，并在不同 workspace 中拥有不同身份。
- 支持 workspace 内成员管理与角色分配。
- 支持平台管理员视角与 workspace 用户视角隔离。
- 支持权限按 workspace 作用域生效，而不是全局平铺。
- 支持后续运行态业务数据逐步按 workspace_id 隔离。

### 3.2 当前阶段必须避免的问题

- 不能把“全局用户角色”错误地当成“workspace 成员角色”。
- 不能让平台管理员与 workspace 用户共用一套模糊 token 语义。
- 不能继续让运行态业务表完全缺失可收敛的空间隔离字段。
- 不能为了未来 SaaS 扩展而在当前阶段提前引入过多无实际使用价值的租户结构。

---

## 4. 非目标

以下内容明确不在本阶段基础设计范围内：

- 完整 SaaS tenant 体系。
- 订阅、套餐、计费、配额。
- 元数据回写审批流。
- 工作流引擎接入。
- workspace 内复杂组织架构树。
- 属性级、字段级、行级高级权限控制。
- 跨 workspace 共享与协作。
- OAuth2、OIDC、LDAP、企业单点登录等外部身份源集成。

这些能力未来可以建立在本草案之上继续演进，但不作为第一阶段设计前提。

---

## 5. 总体设计思路

### 5.1 当前采用 user -> workspace 结构

当前阶段的核心关系是：

- UserAccount 是全局主体。
- Workspace 是当前最小业务空间。
- WorkspaceMember 是用户进入某个 workspace 后的身份实例。
- WorkspaceRole 是成员在该 workspace 内的权限模板。

这比完整 tenant 模型更轻，但仍能覆盖当前最需要的：

- 用户注册。
- 空间创建。
- 成员协作。
- 权限隔离。
- 上下文切换。

### 5.2 为未来 SaaS 扩展预留路径

虽然当前阶段不实现 tenant，但结构上必须允许后续平滑扩展。推荐的扩展路径是：

- 当前先落地 workspace 作为业务空间。
- 后续若进入完整 SaaS，可以在 workspace 之上增加 Tenant 层。
- 未来可演进为 Tenant 1 - N Workspace，或 Tenant 1 - 1 Workspace，具体取决于业务形态。

因此，本阶段不直接实现 Tenant，但要避免把结构写死成“永远只有单个 workspace、且不能被上层归属”。

### 5.3 控制面与运行态分离

本阶段建议将数据分成三类：

- 平台控制面数据：用户、workspace、成员、角色、权限、邀请、登录审计。
- 平台标准元数据：分类、属性、LOV、标准规则模板。
- 运行态业务数据：workspace 真正使用的业务对象与实例数据。

其中：

- 用户与 workspace 相关表，不应放入 plm_runtime。
- plm_runtime 应只承载真正的业务运行态数据。
- plm_meta 应继续只承载标准元数据。

如果当前要落控制面，建议单独使用平台控制面 schema，例如：

- plm_platform
- 或 plm_iam
- 或 plm_auth

而不是直接把账号与 workspace 表塞进 plm_runtime。

本草案后续所有数据库落点讨论，统一以 [用户、Workspace、角色与权限数据库设计草案](user-workspace-rbac-database-design-draft.md) 为准。

---

## 6. 核心设计原则

### 6.1 全局账号与空间成员分离

系统中“账号”与“成员身份”不是一回事。

- 全局用户只负责认证主体、凭证、基础资料。
- workspace 成员才负责空间内身份、状态、角色、上下文。

同一个人可以：

- 在 Workspace A 是 workspace_admin。
- 在 Workspace B 是 workspace_member。
- 同时还具备平台 operator 或 platform_admin 视角。

### 6.2 角色按视角分层

角色至少要区分：

- 平台角色：面向平台管理端。
- Workspace 角色：面向 workspace 工作台。

如果不做这层拆分，后续菜单、接口、数据范围、审计责任都会越来越混乱。

### 6.3 权限按作用域生效

权限校验必须带有“当前在哪个作用域下操作”的上下文信息。

- GLOBAL：平台管理作用域。
- WORKSPACE：工作空间作用域。

同一个权限码是否可用，不仅取决于用户是谁，也取决于当前上下文是否绑定了合法 workspace 成员关系。

### 6.4 运行态数据最终统一走 workspace_id 隔离

本阶段虽然先聚焦用户域，但目标必须明确：

- plm_runtime 中的运行态核心业务表最终都应带 workspace_id。
- 任何 workspace 侧接口都不能依赖前端显式传入任意 workspace_id 作为最终信任来源。
- 当前操作 workspace 必须来自已认证上下文与成员关系校验结果。

### 6.5 平台视角与 workspace 视角必须强隔离

平台与 workspace 不是简单菜单不同，而是边界完全不同的两类身份域：

- 平台视角：全局治理、账号治理、workspace 管理、平台审计、标准元数据管理。
- Workspace 视角：本 workspace 成员管理、规则配置、业务执行。

---

## 7. 视角模型

### 7.1 平台视角

平台视角职责包括：

- 管理平台管理员账号。
- 管理 workspace 生命周期。
- 审计全局运行情况。
- 维护 plm_meta 标准资源。

平台视角特点：

- 不依赖当前 workspace_id 才能访问核心平台接口。
- 使用平台侧登录体系。
- 权限来自平台角色，而不是 workspace 角色。

### 7.2 Workspace 视角

Workspace 视角职责包括：

- 管理本 workspace 成员。
- 分配本 workspace 角色。
- 配置本 workspace 规则。
- 执行本 workspace 业务。

Workspace 视角特点：

- 必须绑定当前 workspace_id。
- 必须能在运行时确认当前用户与当前 workspace 存在有效成员关系。
- 权限来自 workspace 角色，而不是平台角色。

### 7.3 双视角关系

系统必须允许：

- 平台管理员单独登录平台端。
- 普通用户单独登录 workspace 端。
- 后续如业务需要，再扩展“平台管理员进入 workspace 观察态/代理态”的能力。

但第一阶段不要求实现复杂代理登录，只需先把双视角边界定义清楚。

---

## 8. 核心模型草案

说明：

- 本节只保留领域模型摘要。
- 表级字段、约束与索引以 [用户、Workspace、角色与权限数据库设计草案](user-workspace-rbac-database-design-draft.md) 为准。

### 8.1 全局用户 UserAccount

职责：

- 存储全局登录凭证。
- 存储账号级基础信息。
- 作为平台角色与 workspace 成员的共同主体。

建议关键字段：

- id
- username
- display_name
- email
- phone
- status
- registered_at
- last_login_at
- source_type
- created_at
- created_by
- updated_at
- updated_by

说明：

- 这是全局主体，不携带 workspace_id。
- 一个 UserAccount 可以对应多个 WorkspaceMember。

### 8.2 用户凭证 UserCredential

职责：

- 存储可替换的认证凭证信息。

建议关键字段：

- id
- user_id
- credential_type
- password_hash
- password_salt
- status
- expires_at
- last_rotated_at
- created_at

说明：

- 当前如果只做用户名密码，也建议在模型上预留凭证表，而不是把所有认证信息永远焊死在 user_account 中。

### 8.3 工作空间 Workspace

职责：

- 表示当前阶段的最小业务空间。
- 承载成员协作、空间级配置与业务上下文。

建议关键字段：

- id
- workspace_code
- workspace_name
- workspace_status
- owner_user_id
- default_locale
- default_timezone
- config_json
- created_at
- created_by
- updated_at
- updated_by

扩展预留字段建议：

- parent_tenant_id，可为空。
- workspace_type。
- lifecycle_stage。

说明：

- 当前阶段不实现 Tenant，但可以预留 parent_tenant_id 为空位，或后续通过独立映射表扩展。
- 若你希望当前更干净，也可以不落 parent_tenant_id，只在文档与命名层面保留未来扩展约定。

### 8.4 工作空间成员 WorkspaceMember

职责：

- 表示某用户在某个 workspace 中的成员身份。
- 承担 workspace 上下文绑定的核心职责。

建议关键字段：

- id
- workspace_id
- user_id
- member_status
- join_type
- joined_at
- invited_by
- is_default_workspace
- remark
- created_at
- created_by
- updated_at
- updated_by

建议约束：

- workspace_id + user_id 唯一。

说明：

- 是否属于某个 workspace，看 WorkspaceMember，不看 UserAccount。
- 后续切换当前 workspace 时，本质上是切换当前使用的 WorkspaceMember。

### 8.5 平台角色 PlatformRole

职责：

- 定义平台视角权限集合。

建议角色示例：

- platform_super_admin
- platform_admin
- platform_operator
- platform_auditor

### 8.6 平台用户角色 PlatformUserRole

职责：

- 给全局用户分配平台角色。

说明：

- 这是 UserAccount 到 PlatformRole 的关联。
- 不绑定 workspace_id。

### 8.7 工作空间角色 WorkspaceRole

职责：

- 定义 workspace 内权限模板。

建议角色示例：

- workspace_owner
- workspace_admin
- workspace_member
- workspace_viewer

建议关键字段：

- id
- workspace_id
- role_code
- role_name
- role_type（SYSTEM / CUSTOM）
- role_status
- built_in_flag
- created_at
- created_by

说明：

- 第一阶段如果不开放 workspace 自定义角色，也可以先以内建角色模板实现。
- 若希望保留后续空间内自定义能力，则 WorkspaceRole 可以直接设计成 workspace 级表。

### 8.8 工作空间成员角色 WorkspaceMemberRole

职责：

- 给某个 workspace 成员分配一个或多个 workspace 角色。

建议关键字段：

- workspace_member_id
- workspace_role_id
- assigned_at
- assigned_by

### 8.9 权限 Permission

职责：

- 定义最小能力单元。

建议关键字段：

- id
- permission_code
- permission_name
- scope_type
- module_code
- description
- created_at

建议权限示例：

- platform.workspace.read
- platform.workspace.create
- platform.workspace.freeze
- platform.user.read
- platform.user.assign-role
- workspace.member.read
- workspace.member.invite
- workspace.member.disable
- workspace.role.assign
- workspace.role.read
- runtime.import.execute
- runtime.export.execute
- runtime.config.manage

### 8.10 角色权限关系

建议拆分为：

- platform_role_permission
- workspace_role_permission

不建议继续使用无作用域语义的一张 role_permission 统包全部关系。

---

## 9. 推荐关系模型

### 9.1 用户与工作空间

- 一个 UserAccount 可以加入多个 Workspace。
- 一个 Workspace 可以有多个 WorkspaceMember。

关系表达：

- UserAccount 1 - N WorkspaceMember
- Workspace 1 - N WorkspaceMember

### 9.2 用户与平台角色

- 一个 UserAccount 可以拥有多个 PlatformRole。
- 平台角色只在平台视角下生效。

### 9.3 成员与工作空间角色

- 一个 WorkspaceMember 可以拥有多个 WorkspaceRole。
- Workspace 角色只在该成员所属 workspace 下生效。

### 9.4 权限生效规则

平台接口：

- 用户已登录平台端。
- 用户拥有匹配的平台角色。
- 平台角色映射出所需 GLOBAL 权限。

Workspace 接口：

- 用户已登录 workspace 端。
- 当前上下文已绑定 workspace_id。
- 当前用户在该 workspace_id 下存在有效 WorkspaceMember。
- WorkspaceMember 拥有对应 WorkspaceRole。
- WorkspaceRole 映射出所需 WORKSPACE 权限。

---

## 10. Sa-Token 基础设计草案

### 10.1 双登录体系

第一阶段建议直接采用双 loginType，而不是单 StpUtil 混用：

- 平台端 loginType：platform
- Workspace 端 loginType：workspace

设计目的：

- 平台 token 与 workspace token 语义隔离。
- 平台接口与 workspace 接口可分别做鉴权拦截。
- 避免后期接口边界越来越模糊。

### 10.2 平台端 token 载荷建议

- loginId
- loginType = platform
- userId
- platformRoleSnapshot 或 roleVersion

平台端 token 不要求强绑定 workspace_id。

### 10.3 Workspace 端 token 载荷建议

- loginId
- loginType = workspace
- userId
- currentWorkspaceId
- currentWorkspaceMemberId
- workspaceRoleSnapshot 或 roleVersion

说明：

- currentWorkspaceId 应作为当前 workspace 上下文的可信来源之一。
- 但服务端仍应校验该用户与该 workspace 成员关系是否有效，不能只信任前端传值。

### 10.4 鉴权分层建议

建议至少分成三层：

- 认证：是否已登录对应 loginType。
- 作用域：当前接口要求的是 platform 还是 workspace 作用域。
- 权限：当前主体在当前作用域下是否具备指定 permission。

### 10.5 当前 workspace 切换

由于同一用户可加入多个 workspace，workspace 端必须支持“切换当前 workspace 上下文”。

第一阶段建议：

- 显式提供 switch workspace 接口。
- 切换后更新会话中的 currentWorkspaceId 与 currentWorkspaceMemberId。
- 所有 workspace 接口默认从会话中读取当前 workspace，而不是依赖每个接口都传 workspace_id。

---

## 11. 数据隔离原则

数据库落点与 schema 划分，详见 [用户、Workspace、角色与权限数据库设计草案](user-workspace-rbac-database-design-draft.md)。

### 11.1 用户域表的隔离方式

建议如下：

- UserAccount：全局表，不带 workspace_id。
- UserCredential：全局表。
- Workspace：全局表。
- WorkspaceMember：天然带 workspace_id。
- WorkspaceRole：若做空间自定义角色，则天然带 workspace_id。

### 11.2 运行态业务表的隔离方式

第一阶段虽不改全业务域，但设计上必须统一目标：

- plm_runtime 中所有 workspace 侧业务核心表应逐步补齐 workspace_id。
- 所有查询最终必须收敛到按 workspace_id 过滤。

### 11.3 平台元数据与运行态数据边界

- plm_meta 不接受 workspace 端直接写入。
- plm_runtime 是 workspace 使用与扩展的落点。
- 本阶段不涉及回写审批能力，因此不定义跨库写入策略。

---

## 12. 第一阶段范围定义

### 12.1 In Scope

- 全局用户注册。
- 注册后创建默认 workspace。
- 创建默认 workspace 拥有者或管理员成员。
- 平台端与 workspace 端双登录体系。
- 平台角色、workspace 角色、权限基础模型。
- workspace 成员管理。
- 当前 workspace 上下文切换。
- 基础的 workspace 级权限校验。
- 运行态后续 workspace_id 隔离的统一约束定义。

### 12.2 Out of Scope

- 完整 tenant 层。
- 回写审批。
- 工作流引擎。
- 元数据版本升级通知。
- 组织树与部门层级授权。
- 字段级敏感权限。
- 数据共享与协作。
- 复杂套餐、订阅、计费能力。

---

## 13. 第一阶段建议内建角色

### 13.1 平台侧内建角色

- platform_super_admin：拥有全部平台能力。
- platform_admin：具备大部分平台管理能力，不含超级危险操作。
- platform_operator：偏运营与 workspace 处理。
- platform_auditor：只读审计视角。

### 13.2 Workspace 侧内建角色

- workspace_owner：空间拥有者，最高空间权限。
- workspace_admin：空间管理员，可管理成员与空间配置。
- workspace_member：标准成员，可执行常规业务。
- workspace_viewer：只读成员。

说明：

- 第一阶段建议先以内建角色为主。
- 是否开放 workspace 自定义角色，可以作为第二阶段能力评估项。

---

## 14. 第一阶段建议权限分组

### 14.1 平台权限

- platform.workspace.read
- platform.workspace.create
- platform.workspace.update
- platform.workspace.freeze
- platform.user.read
- platform.user.assign-role
- platform.audit.read

### 14.2 Workspace 权限

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

说明：

- 第一阶段先做到页面级、菜单级、接口级能力控制。
- 更细粒度的资源级授权后续再扩展。

---

## 15. 对数据库草案的直接影响

本节具体内容已拆分到独立文档 [用户、Workspace、角色与权限数据库设计草案](user-workspace-rbac-database-design-draft.md)。

当前只保留结论性约束：

- 用户域核心表统一落在平台控制面 schema，而不是 plm_runtime。
- workspace 侧运行态业务表后续统一向 workspace_id 隔离收敛。
- 旧 V1 中无作用域的全局 RBAC 表不建议原样沿用为最终设计。

---

## 16. 向未来 SaaS 扩展的兼容路径

当前不实现 Tenant，但设计上建议遵守以下兼容原则：

- 不把 workspace 命名成 project、team 这类更难上提的概念。
- 不把 workspace 写死成“每个用户只能有一个”。
- 不把角色权限关系直接焊死在 user_account 上。
- 不把运行态业务隔离字段设计成 user_id，而应设计成 workspace_id。

未来若进入 SaaS，可以有两种常见扩展方向：

### 16.1 Tenant 在 Workspace 之上

- Tenant 负责计费、套餐、企业级治理。
- Workspace 负责实际协作与业务空间。

关系可扩展为：

- Tenant 1 - N Workspace。

### 16.2 Workspace 直接平滑升级为 Tenant 轻量实现

- 若后续发现一个企业只需要一个 workspace，则可把当前 workspace 逐步升级为轻量 tenant。
- 再引入 tenant_profile、tenant_subscription 等补充结构。

两条路径都要求当前阶段不要把模型写死成不可演进的单层用户结构。

---

## 17. 未决问题

以下问题需要在后续评审中逐项明确：

### 17.1 注册模型

- 是否所有自注册都必须自动创建一个默认 workspace。
- 是否允许“仅注册账号，等待他人邀请加入 workspace”。

### 17.2 Workspace 角色开放程度

- 第一阶段是否只允许内建角色。
- 是否允许 workspace 管理员自定义角色。

### 17.3 平台管理员进入 Workspace 观察态

- 第一阶段是否支持平台管理员切换到 workspace 视角排障。
- 若支持，是代理登录还是只读观察态。

### 17.4 登录入口与前端视图拆分方式

- 平台端与 workspace 端是否采用不同路由前缀。
- 是否采用独立前端入口或同前端双工作台。

### 17.5 历史运行态表重建顺序

- workspace_id 补齐的优先级如何安排。
- 哪些运行态表第一阶段必须同步改造，哪些可以后置。

---

## 18. 本草案结论

本阶段用户域的正确起点不是“在旧 RBAC 表上小修小补”，也不是“一步到位上完整 SaaS 租户体系”，而是明确建立以下四层：

- 全局账号层
- workspace 层
- workspace 成员层
- 分视角角色权限层

只有先把这四层立稳，后续的：

- workspace 内成员管理
- 元数据抽取后的 workspace 化使用
- 运行态业务数据隔离
- 未来向 SaaS / tenant 演进

才有稳定的上下文边界与权限基础。

因此，第一阶段的实现建议应围绕“用户、workspace、成员、角色、权限、双视角登录、当前 workspace 上下文”展开，而不是过早进入完整 tenant、流程与治理能力。
