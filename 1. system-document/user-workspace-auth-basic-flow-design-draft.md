# 用户注册、登录、创建 Workspace、切换 Workspace 设计草案

更新时间：2026-04-10
阶段：实现前专项流程设计草案

---

## 1. 目标与范围

本稿用于在现有数据库结构设计基础上，明确第一阶段最小可实现用户闭环：

- 用户注册。
- 用户登录。
- 用户创建 workspace。
- 用户切换 workspace 上下文。

本稿直接依赖 [用户、Workspace、角色与权限数据库设计草案](user-workspace-rbac-database-design-draft.md) 中的表结构定义，不重复定义数据库字段。

本稿不覆盖：

- 找回密码。
- 短信验证码。
- 第三方 OAuth / OIDC 登录。
- 企业单点登录。
- 邀请链接完整闭环。
- 平台管理员后台完整操作面板。

---

## 2. 设计原则

### 2.1 先实现最小闭环，不提前引入重型能力

第一阶段只解决：

- 用户如何创建账号。
- 用户如何获得平台登录态。
- 用户如何创建自己的第一个 workspace。
- 用户如何在多个 workspace 间切换当前上下文。

### 2.2 平台身份与 Workspace 上下文分离

当前阶段不建议把“用户已登录”和“当前正在哪个 workspace 中工作”混成同一个概念。

因此建议采用两层会话：

- 平台登录态：证明这个自然人是谁。
- workspace 上下文态：证明当前正在以哪个 workspace_member 身份工作。

### 2.3 数据库职责与会话职责分离

数据库负责保存：

- user_account
- user_credential
- workspace
- workspace_member
- workspace_role
- workspace_member_role

会话层负责保存：

- 当前登录用户。
- 当前选择的 workspace。
- 当前 workspace_member 身份。

---

## 3. 相关表映射

本稿主要使用以下表：

- plm_platform.user_account
- plm_platform.user_credential
- plm_platform.email_verification_code
- plm_platform.workspace
- plm_platform.workspace_member
- plm_platform.workspace_role
- plm_platform.workspace_member_role
- plm_platform.workspace_invitation
- plm_platform.login_audit

其中关键映射关系为：

- 发送注册邮箱验证码：写 email_verification_code。
- 注册：读写 email_verification_code，写 user_account + user_credential。
- 登录：读 user_account + user_credential，写 login_audit，更新 user_account.last_login_at。
- 创建 workspace：写 workspace + workspace_member + workspace_role + workspace_member_role。
- 切换 workspace：读 workspace_member、workspace_role，不强依赖新增表，主要变更会话上下文；可按需更新 is_default_workspace。

---

## 4. 核心决策

### 4.1 注册不自动创建 workspace

第一阶段建议：

- 注册只创建用户账号和密码凭证。
- 不在注册接口里隐式创建默认 workspace。

原因：

- 用户明确要求“创建 workspace”作为独立能力讨论与设计。
- 把注册和建 workspace 拆开，事务边界更清晰。
- 后续如果要支持“受邀加入后无需自建 workspace”，拆分方案更稳定。

### 4.2 登录后先获得平台登录态

登录成功后，系统先建立平台登录态，不强制立即进入某个 workspace。

登录响应中返回：

- 用户基础信息。
- 用户可进入的 workspace 列表摘要。
- 默认 workspace 信息。
- 是否已有当前 workspace 上下文。

若用户只有一个有效 workspace，前端可以自动调用切换接口进入该 workspace。

### 4.3 创建 workspace 后默认自动切换进入

创建 workspace 成功后，建议服务端直接完成两件事：

- 把创建者写成该 workspace 的 owner member。
- 为当前会话自动切换到这个新 workspace。

这样前端不需要再额外调用一次切换接口。

### 4.4 切换 workspace 允许同步设置默认 workspace

切换 workspace 时建议支持一个可选参数：

- rememberAsDefault

当该值为 true 时：

- 更新当前用户在 workspace_member 中的 is_default_workspace。

当该值为 false 时：

- 只切换当前会话上下文，不修改数据库默认空间偏好。

---

## 5. 会话与 Token 模型

### 5.1 Sa-Token loginType 规划

建议使用两类 loginType：

- platform：平台级用户登录态。
- workspace：workspace 上下文登录态。

### 5.2 Platform 登录态

用途：识别“当前是谁”。

建议：

- loginType = platform
- loginId = user_account.id

平台会话中建议保存：

- userId
- username
- displayName

### 5.3 Workspace 上下文态

用途：识别“当前正在以哪个 workspace 成员身份操作”。

建议：

- loginType = workspace
- loginId = workspace_member.id

workspace 会话中建议保存：

- userId
- workspaceId
- workspaceMemberId
- workspaceCode
- workspaceRoleCodes

### 5.4 两层会话的关系

- platform token 是基础身份凭证。
- workspace token 是业务上下文凭证。
- 访问平台侧接口时，只校验 platform token。
- 访问 workspace 侧接口时，同时要求 platform token 与 workspace token 有效，且 userId 必须一致。

### 5.5 注销策略

建议：

- 注销平台登录态时，同时注销当前 workspace 上下文态。
- 单独切换 workspace 时，只替换 workspace token，不影响 platform token。

---

## 6. 接口草案

### 6.1 发送注册邮箱验证码

接口：POST /auth/public/register/email-code

请求体建议：

```json
{
  "email": "alice@example.com"
}
```

处理流程：

1. 标准化邮箱。
2. 校验邮箱尚未被 user_account 占用。
3. 查询同邮箱最近一条 PENDING 验证码。
4. 若已过期，则标记 EXPIRED。
5. 若仍在冷却时间内，则拒绝重复发送。
6. 否则将旧 PENDING 记录标记为 SUPERSEDED。
7. 生成新的 6 位验证码并保存到 email_verification_code。
8. 调用邮件发送器完成验证码投递。

成功响应建议：

```json
{
  "email": "alice@example.com",
  "maskedEmail": "al***@example.com",
  "expiresAt": "2026-04-13T10:00:00Z",
  "expireInSeconds": 600,
  "resendCooldownSeconds": 60
}
```

### 6.2 用户注册

接口：POST /auth/public/register

请求体建议：

```json
{
  "username": "alice",
  "displayName": "Alice",
  "password": "<SECRET>",
  "confirmPassword": "<SECRET>",
  "email": "alice@example.com",
  "emailVerificationCode": "123456",
  "phone": "13800000000"
}
```

校验规则建议：

- username 必填，长度 4-64，按小写唯一。
- displayName 必填，长度 1-128。
- password 必填，长度至少 8。
- confirmPassword 必须一致。
- email 必填，且必须先完成邮箱验证码校验。
- emailVerificationCode 必填，当前要求 6 位数字。
- phone 允许为空，但如果填写必须满足唯一性。

处理流程：

1. 标准化 username、email、phone。
2. 校验 username / email / phone 唯一性。
3. 校验 emailVerificationCode 是否存在、未过期且匹配。
4. 创建 user_account。
5. 创建 user_credential，credential_type 固定为 PASSWORD。
6. 将命中的验证码记录标记为 USED，并回填 consumed_by_user_id。
7. 返回注册成功响应。

事务边界：

- user_account 与 user_credential 必须同一事务提交。

成功响应建议：

```json
{
  "userId": "uuid",
  "username": "alice",
  "displayName": "Alice",
  "registeredAt": "2026-04-10T12:00:00Z"
}
```

### 6.3 用户登录

接口：POST /auth/public/login/password

请求体建议：

```json
{
  "identifier": "alice",
  "password": "<SECRET>"
}
```

identifier 第一阶段建议支持：

- username
- email
- phone

处理流程：

1. 通过 identifier 解析用户。
2. 校验 user_account.status 是否允许登录。
3. 读取 PASSWORD 类型 user_credential。
4. 校验密码摘要。
5. 建立 platform 登录态。
6. 查询该用户全部 ACTIVE workspace_member。
7. 读取默认 workspace。
8. 记录 login_audit(success)。
9. 更新 user_account.last_login_at。

失败流程：

1. 尽量定位用户。
2. 写 login_audit(failed)。
3. 返回统一登录失败信息，不泄露“账号不存在”还是“密码错误”。

成功响应建议：

```json
{
  "platformToken": "token",
  "user": {
    "id": "uuid",
    "username": "alice",
    "displayName": "Alice"
  },
  "defaultWorkspace": {
    "workspaceId": "uuid",
    "workspaceCode": "ws_9e4b810a_alice_workspace_3d8b6370",
    "workspaceName": "Alice Workspace"
  },
  "workspaceOptions": [
    {
      "workspaceId": "uuid",
      "workspaceCode": "ws_9e4b810a_alice_workspace_3d8b6370",
      "workspaceName": "Alice Workspace",
      "memberStatus": "ACTIVE"
    }
  ]
}
```

说明：

- 第一阶段登录成功时不强制立即返回 workspaceToken。
- workspaceToken 在切换 workspace 时建立。

### 6.4 创建 Workspace

接口：POST /auth/workspaces

认证要求：

- 需要有效 platform token。

请求体建议：

```json
{
  "workspaceName": "Alice Workspace",
  "workspaceType": "TEAM",
  "defaultLocale": "zh-CN",
  "defaultTimezone": "Asia/Shanghai",
  "rememberAsDefault": true
}
```

说明：

- `workspaceCode` 不再由前端传入，当前由后端按 `ws_{ownerUserId8}_{workspaceNameSlug}_{workspaceId8}` 规则系统生成。

处理流程：

1. 校验 platform 登录态。
2. 解析并校验 `workspaceType`、`defaultLocale`、`defaultTimezone`。
3. 预生成 workspace 主键，并按规则生成 `workspaceCode`。
4. 创建 workspace，owner_user_id = 当前 userId。
5. 创建 workspace_member：
   - user_id = 当前 userId
   - member_status = ACTIVE
   - join_type = OWNER
   - joined_at = now
6. 初始化该 workspace 的内建角色：
   - workspace_owner
   - workspace_admin
   - workspace_member
   - workspace_viewer
7. 给创建者分配 workspace_owner 角色。
8. 若 rememberAsDefault = true，更新默认 workspace 标记。
9. 建立或替换当前 workspace 上下文态。
10. 返回 workspace 信息与 workspaceToken。

事务边界：

- workspace
- workspace_member
- workspace_role
- workspace_member_role

以上必须同一事务提交。

成功响应建议：

```json
{
  "workspace": {
    "id": "uuid",
    "workspaceCode": "ws_9e4b810a_alice_workspace_3d8b6370",
    "workspaceName": "Alice Workspace"
  },
  "workspaceToken": "token",
  "workspaceMemberId": "uuid",
  "roleCodes": ["workspace_owner"]
}
```

### 6.5 切换 Workspace

接口：POST /auth/workspace-session/switch

认证要求：

- 需要有效 platform token。

请求体建议：

```json
{
  "workspaceId": "uuid",
  "rememberAsDefault": true
}
```

处理流程：

1. 校验 platform 登录态。
2. 查询当前用户在该 workspace 下的 ACTIVE workspace_member。
3. 查询该成员已分配的角色编码。
4. 建立或替换 workspace 登录态。
5. 若 rememberAsDefault = true，则更新默认 workspace 标记。
6. 返回 workspace 上下文信息。

切换失败场景：

- workspace 不存在。
- 当前用户不是该 workspace 成员。
- 成员状态不是 ACTIVE。
- workspace 已冻结或归档。

成功响应建议：

```json
{
  "workspaceToken": "token",
  "workspace": {
    "id": "uuid",
    "workspaceCode": "ws_9e4b810a_alice_workspace_3d8b6370",
    "workspaceName": "Alice Workspace"
  },
  "workspaceMemberId": "uuid",
  "roleCodes": ["workspace_owner"]
}
```

### 6.6 查询当前登录态摘要

接口：GET /auth/me

用途：

- 前端刷新后恢复当前会话信息。
- 同时返回 platform 用户信息和当前 workspace 上下文摘要。

建议响应：

- user
- workspaceOptions
- currentWorkspace
- currentWorkspaceRoleCodes

---

## 7. 默认 Workspace 规则

### 7.1 注册后

- 用户刚注册成功时，不存在默认 workspace。

### 7.2 创建第一个 workspace 时

- 若用户还没有任何默认 workspace，则新创建的 workspace 自动设为默认。

### 7.3 手动切换时

- rememberAsDefault = true 时，更新默认 workspace。
- rememberAsDefault = false 时，只更新当前会话，不更新数据库偏好。

### 7.4 默认 workspace 存储规则

建议使用 workspace_member.is_default_workspace 存储。

更新时必须保证：

- 同一个 user_id 在 ACTIVE 成员中最多只有一个默认 workspace。

这与数据库唯一索引设计保持一致。

---

## 8. 服务层职责建议

建议按以下职责拆分：

- AuthRegistrationService：处理注册。
- AuthLoginService：处理密码登录、审计记录、密码校验。
- WorkspaceCommandService：处理创建 workspace。
- WorkspaceSessionService：处理切换 workspace 上下文。
- AuthQueryService：处理 /auth/me、workspace 列表等查询。

这样可以避免把所有逻辑塞进 Controller 或单个 AuthService。

---

## 9. 事务与一致性要求

### 9.1 注册

- user_account 与 user_credential 必须原子提交。

### 9.2 创建 workspace

- workspace、workspace_member、workspace_role、workspace_member_role 必须原子提交。

### 9.3 切换 workspace

- 切换本身主要是会话更新。
- 若同时更新默认 workspace，则数据库更新与会话建立建议放在同一个应用服务方法中完成。

---

## 10. 安全与校验要求

### 10.1 密码处理

- 不存储明文密码。
- secret_hash 必须使用强哈希算法。
- 第一阶段建议直接使用 Spring Security PasswordEncoder，例如 BCrypt。

### 10.2 登录失败信息

- 前端只收到统一错误提示，例如“用户名或密码错误”。
- 不暴露具体是账号不存在还是密码错误。

### 10.3 状态校验

- DISABLED / LOCKED 用户不得登录。
- 非 ACTIVE 的 workspace_member 不得切换进入 workspace。
- 非 ACTIVE 的 workspace 不得切换进入业务上下文。

### 10.4 权限边界

- 创建 workspace 只要求 platform 登录态，不要求额外平台角色。
- 切换 workspace 的前提是当前用户对目标 workspace 有有效成员身份。

---

## 11. 第一阶段推荐接口清单

- POST /auth/public/register
- POST /auth/public/login/password
- POST /auth/logout
- GET /auth/me
- GET /auth/workspaces
- POST /auth/workspaces
- POST /auth/workspace-session/switch

这是第一阶段足够支撑前端登录、初始化、建空间、切空间的最小接口集合。

---

## 12. 本稿结论

基于当前数据库设计，第一阶段建议采用以下闭环：

- 注册只建账号，不自动建 workspace。
- 登录先建立 platform 登录态。
- 创建 workspace 时同时创建 owner member、内建角色和 owner 角色分配。
- 切换 workspace 时建立独立 workspace 上下文态。
- 默认 workspace 通过 workspace_member.is_default_workspace 持久化。

这样可以先把最小可用用户域闭环跑通，同时保持与后续 platform / workspace 双视角和更完整 SaaS 扩展方向兼容。
