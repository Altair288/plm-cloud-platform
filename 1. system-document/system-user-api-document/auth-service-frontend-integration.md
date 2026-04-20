# PLM Cloud Auth-Service 前端对接说明

## 1. 文档范围

本文档面向前端联调用途，覆盖当前 auth-service 已实现并通过集成测试验证的注册邮箱验证码发送、账号注册、普通账号密码登录、平台管理员密码登录、workspace 创建、workspace 切换、当前会话读取与退出能力、workspace 类型 / 语言 / 时区引导项获取能力，以及 workspace 邀请能力。

本地联调时，前端统一通过 gateway 访问 auth 接口：

- base URL：`http://localhost:8080`
- auth 路径前缀：`/auth/**`
- 不再建议前端直接请求 `http://localhost:8081`

当前接口语义有 6 个前置约束，前端必须按此理解：

1. 注册前必须先发送并校验邮箱验证码。
2. 密码登录只建立平台登录态，不自动建立当前 workspace 会话。
3. 创建 workspace 会自动创建 owner member、内建角色、默认权限绑定，并自动切入该 workspace。
4. 当前 workspace 上下文以后端 platform session 中保存的 currentWorkspaceMemberId 为准，前端本地缓存只用于恢复请求头，不可替代后端真实会话。
5. 邮箱邀请预览与链接预览可以匿名访问，但真正接受邀请时必须先登录；其中邮箱邀请要求当前登录账号邮箱与被邀请邮箱一致。
6. 注册与密码登录中的密码字段当前按 RSA 非对称加密传输；前端必须先读取公钥，再提交密文密码，明文密码在当前配置下会被后端拒绝；当前公私钥默认按 Redis 24 小时窗口缓存，`keyId` 在窗口内通常保持稳定，但到期后必须重新获取。

对于平台管理员前端，还需要额外理解 3 个约束：

1. 平台管理员必须走独立接口 POST /auth/public/platform-admin/login/password，不复用普通用户登录后的 workspace 恢复分支。
2. 平台管理员登录成功后只返回平台 token 与管理员摘要，不返回 `defaultWorkspace`、`workspaceOptions`、`currentWorkspace`。
3. 平台管理员页面刷新恢复时，应调用 GET /auth/platform-admin/me 做管理员身份校验，而不是依赖 GET /auth/me 的 workspace 语义。

另外，`user` 摘要中新增两个与 workspace 引导直接相关的字段：

1. `isFirstLogin`：表示用户尚未完成首次 workspace 建立；首次成功创建 workspace 后会永久变为 `false`。
2. `workspaceCount`：表示用户当前可用的活跃 workspace 数，用于处理“用户后来删光了所有 workspace”的空态分支。

workspace 创建表单中的 `workspaceType`、`defaultLocale`、`defaultTimezone` 不允许前端硬编码，必须优先读取 GET /auth/public/workspace-bootstrap-options 返回的字典选项。

workspace 创建接口中的 `workspaceCode` 已改为后端系统生成字段，前端不再传入，也不应提供手工填写入口。

## 2. 鉴权与 Header 约定

### 2.1 平台登录态

- 来源接口：POST /auth/public/login/password
- 返回字段：platformToken、platformTokenName、remember、platformTokenExpireInSeconds
- 用途：所有受保护的 /auth 接口都必须携带平台 token

发送规则：

```http
{platformTokenName}: {platformToken}
```

前端不要写死 header 名称，必须以后端返回的 platformTokenName 为准。

### 2.2 Workspace 会话态

- 来源接口：POST /auth/workspaces
- 来源接口：POST /auth/workspace-session/switch
- 返回字段：workspaceToken、workspaceTokenName
- 用途：表示当前正在操作哪个 workspace

推荐发送规则：

```http
{platformTokenName}: {platformToken}
{workspaceTokenName}: {workspaceToken}
```

说明：

1. platform token 负责证明当前是谁。
2. workspace token 负责证明当前正在操作哪个 workspace。
3. 登录成功后通常只有 platform token，没有 workspace token。
4. 切换 workspace 成功后必须覆盖旧 workspace token，不能继续复用旧值。

### 2.3 前端状态建议

前端状态建议拆成两层，而不是只维护一个统一 token 对象：

- platformAuth：platformToken、platformTokenName、remember、platformTokenExpireInSeconds、user
- currentWorkspaceSession：workspaceToken、workspaceTokenName、workspaceId、workspaceMemberId、roleCodes

这样可以避免切换 workspace 时误覆盖平台登录态。

## 3. 统一响应约定

### 3.1 成功响应

- 200：请求成功并返回业务数据
- 204：请求成功但无响应体，例如退出登录、清理当前 workspace、当前没有 workspace 会话

### 3.2 错误响应结构

错误响应统一为：

```json
{
  "timestamp": "2026-04-10T21:00:00+08:00",
  "status": 401,
  "error": "Unauthorized",
  "code": "AUTH_NOT_LOGGED_IN",
  "message": "not logged in",
  "path": "/auth/me"
}
```

前端处理建议：

1. code 作为分支判断主依据。
2. message 用于兜底提示。
3. status 用于统一登录失效、权限失败、冲突态跳转。

通过 gateway 调用时，auth-service 返回的 4xx/5xx 业务错误会原样透传；只有 gateway 自己生成的错误会额外出现以下 code：

- `GATEWAY_ROUTE_NOT_FOUND`：前端请求了未被 gateway 配置的路径，通常对应 404
- `GATEWAY_DOWNSTREAM_UNAVAILABLE`：gateway 已命中路由，但下游 auth-service 未启动或不可达，通常对应 502
- `GATEWAY_INTERNAL_ERROR`：gateway 自身内部异常，通常对应 500

## 4. 核心流程

### 4.1 注册流程

1. 先调用 POST /auth/public/register/email-code
2. 用户收到邮件后输入 emailVerificationCode
3. 再调用 GET /auth/public/security/password-encryption-key 获取当前 RSA 公钥与 keyId
4. 前端使用公钥加密 password 与 confirmPassword
5. 再调用 POST /auth/public/register 提交密文字段
6. 注册成功后跳转登录页或回填登录表单
7. 不要假设已登录
8. 不要假设已有 defaultWorkspace 或 currentWorkspace

### 4.2 登录后初始化流程

推荐顺序：

1. 调用 POST /auth/public/login/password
2. 在提交登录表单前先调用 GET /auth/public/security/password-encryption-key 获取当前 RSA 公钥与 keyId
3. 前端使用公钥加密 password，并连同 remember 一起提交给 POST /auth/public/login/password
4. 保存 platformToken、platformTokenName、remember、platformTokenExpireInSeconds、user、defaultWorkspace、workspaceOptions
5. 如果 `user.isFirstLogin = true`，直接进入“创建第一个 workspace”页面
6. 如果 `user.isFirstLogin = false` 且 `user.workspaceCount = 0`，进入“无 workspace 空态页”或“恢复创建 workspace”页面，不要停留在空白页
7. 如果 `user.workspaceCount > 0` 且 `defaultWorkspace` 存在，优先调用 POST /auth/workspace-session/switch 切入 defaultWorkspace
8. switch 成功后保存 workspaceToken、workspaceTokenName、currentWorkspace、roleCodes
9. 进入依赖 workspace 上下文的业务页面

注意：登录成功不代表可以直接访问 workspace 业务页面，因为此时通常还没有 workspace token。

### 4.3 创建 Workspace 流程

1. 前提是已登录并持有 platform token
2. 先调用 GET /auth/public/workspace-bootstrap-options，获取可选 `workspaceType`、`defaultLocale`、`defaultTimezone`
3. 调用 POST /auth/workspaces，并且请求体中的这三个字段必须使用 bootstrap 返回值
4. 成功后直接使用返回值刷新 currentWorkspaceSession
5. 不需要额外再调用一次 switch
6. 如果需要同步 defaultWorkspace 与 workspaceOptions，可以追加调用 GET /auth/me

### 4.3.1 平台管理员登录流程

推荐顺序：

1. 调用 GET /auth/public/security/password-encryption-key 获取当前 RSA 公钥与 keyId。
2. 前端使用公钥加密管理员密码。
3. 调用 POST /auth/public/platform-admin/login/password。
4. 保存 `platformToken`、`platformTokenName`、`remember`、`platformTokenExpireInSeconds`、`admin`。
5. 立即调用 GET /auth/platform-admin/me 做一次管理员会话校验，确认平台角色仍然有效。
6. 校验通过后跳转到管理员页面。

注意：平台管理员登录不要求 workspace，上述流程不应再调用 workspace switch 或 workspace create。

### 4.4 切换 Workspace 流程

1. 展示 workspaceOptions
2. 用户选择目标 workspace
3. 调用 POST /auth/workspace-session/switch
4. 成功后覆盖本地 workspaceToken、workspaceTokenName、workspaceId、workspaceMemberId、roleCodes
5. 按需再调用 GET /auth/me，刷新 defaultWorkspace 与 workspaceOptions

### 4.5 刷新后的会话恢复流程

1. 先用本地 platform token 调用 GET /auth/me
2. 如果 currentWorkspace 存在且本地 workspace token 也存在，可恢复当前 workspace UI 状态
3. 如果 `user.isFirstLogin = true`，进入首次创建 workspace 流程
4. 如果 `user.isFirstLogin = false` 且 `user.workspaceCount = 0`，进入无 workspace 空态页或创建页
5. 如果 currentWorkspace 为 null 且 `user.workspaceCount > 0`，进入“选空间 / 建空间”流程
6. 如果 currentWorkspace 存在但本地 workspace token 已丢失，应使用 currentWorkspace.workspaceId 再调一次 POST /auth/workspace-session/switch，重新获取 workspace token

### 4.6 邀请协作流程

推荐顺序：

1. workspace owner 或具备 `workspace.member.invite` 权限的成员在当前 workspace 下发起邮箱邀请或创建分享链接。
2. 邮箱邀请场景下，前端可以先访问 GET /auth/public/workspace-invitations/email/{token} 做匿名预览，再引导用户登录目标邮箱对应账号。
3. 分享链接场景下，前端可以先访问 GET /auth/public/workspace-invitation-links/{token} 展示 workspace 名称、角色、剩余次数与可接受状态。
4. 用户登录成功后再调用对应 accept 接口，不要在未登录时直接发起接受请求。
5. 接受成功后，后端会直接返回新的 workspace session，前端应立即覆盖本地 workspaceToken、workspaceTokenName、workspaceId、workspaceMemberId、roleCodes。
6. 对于邮箱批量邀请，前端必须逐项消费 `results`，不能只看 `successCount`；同一批请求允许部分成功、部分跳过。

## 5. 接口明细

## 5.1 POST /auth/public/register/email-code

用途：向注册邮箱发送验证码。

请求体：

```json
{
  "email": "alice@example.com"
}
```

成功响应示例：

```json
{
  "email": "alice@example.com",
  "maskedEmail": "al***@example.com",
  "expiresAt": "2026-04-13T10:00:00+08:00",
  "expireInSeconds": 600,
  "resendCooldownSeconds": 60
}
```

成功状态码：200

典型失败：

- 400 INVALID_ARGUMENT：email 为空或格式非法
- 409 EMAIL_ALREADY_EXISTS
- 429 EMAIL_VERIFICATION_SEND_TOO_FREQUENT
- 502 EMAIL_VERIFICATION_SEND_FAILED
- 503 EMAIL_VERIFICATION_DISABLED / EMAIL_VERIFICATION_NOT_CONFIGURED

前端约束：

1. 发送成功后应展示 maskedEmail、剩余有效期和重发倒计时。
2. 重发前应至少等待 resendCooldownSeconds。

## 5.2 GET /auth/public/security/password-encryption-key

用途：获取当前用于注册和密码登录的 RSA 公钥。

请求头：无

成功响应示例：

```json
{
  "keyId": "auth-password-rsa-v1-6f1d7d0d-4e7e-4b63-9f35-7e2cb93f8a31",
  "algorithm": "RSA",
  "transformation": "RSA/ECB/OAEPWithSHA-256AndMGF1Padding",
  "publicKeyBase64": "MIIBIjANBgkq...",
  "plaintextFallbackAllowed": false
}
```

字段说明：

- `keyId`：本次加密提交时必须原样回传到注册或登录请求的 `encryptionKeyId`
- `algorithm`：当前公钥算法，固定为 `RSA`
- `transformation`：前端加密时必须匹配的 RSA 填充模式
- `publicKeyBase64`：X.509 SPKI 公钥的纯 Base64 文本，不包含 PEM header/footer
- `plaintextFallbackAllowed`：当前后端是否允许明文密码回退；当前实现默认是 `false`

当前实现补充说明：

1. auth-service 在未显式配置固定 PEM 公私钥时，会优先把动态生成的 RSA 密钥对存入 Redis，默认 TTL 为 24 小时。
2. 前端可以把同一个 `keyId` 视为一个 24 小时内有效的临时加密版本号；一旦收到新的 `keyId`，必须立即切换为新的公钥重新加密。
3. 当前开发环境已要求 Redis 可用并完成认证；如果 Redis 不可达或密码错误，公钥接口与解密流程会直接失败，不再回退到进程内临时密钥。
4. 后端对 `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` 已显式固定为 `OAEP digest = SHA-256`、`MGF1 digest = SHA-256`、`PSource = default(empty)`，不会使用部分 JCE provider 的 `MGF1 = SHA-1` 默认值。

成功状态码：200

前端约束：

1. 登录和注册提交前都应先读取这个接口，使用最新公钥加密密码。
2. `keyId` 默认有 24 小时有效窗口；不要跨天、跨长时间会话继续复用旧 keyId。
3. 如果登录或注册返回 `encryptionKeyId is invalid or expired`，前端应重新拉取公钥后重试，而不是继续重放旧密文。

## 5.2.1 POST /auth/public/register

用途：创建平台账号，不创建 workspace，不返回 token。

请求体：

```json
{
  "username": "alice_001",
  "displayName": "Alice",
  "passwordCiphertext": "base64-rsa-ciphertext",
  "confirmPasswordCiphertext": "base64-rsa-ciphertext",
  "encryptionKeyId": "auth-password-rsa-v1-6f1d7d0d-4e7e-4b63-9f35-7e2cb93f8a31",
  "email": "alice@example.com",
  "emailVerificationCode": "123456",
  "phone": "13800001234"
}
```

字段说明：

- username：用户名
- displayName：展示名
- passwordCiphertext：使用当前 RSA 公钥加密后的密码密文，Base64 编码
- confirmPasswordCiphertext：使用当前 RSA 公钥加密后的确认密码密文，Base64 编码
- encryptionKeyId：公钥接口返回的 keyId
- email：邮箱，必填且必须唯一
- emailVerificationCode：邮箱验证码，必填且当前要求 6 位数字
- phone：手机号，可选，但如果传入必须唯一

成功响应示例：

```json
{
  "userId": "d955f1bd-b3f4-4d5f-90f4-fccf27baaf72",
  "username": "alice_001",
  "displayName": "Alice",
  "registeredAt": "2026-04-10T20:50:10.121+08:00"
}
```

成功状态码：200

典型失败：

- 400 INVALID_ARGUMENT：字段为空、格式非法、密码确认不一致、验证码格式非法、密文字段缺失、keyId 非法、解密失败
- 400 EMAIL_VERIFICATION_CODE_INVALID
- 400 EMAIL_VERIFICATION_CODE_EXPIRED
- 409 USERNAME_ALREADY_EXISTS
- 409 EMAIL_ALREADY_EXISTS
- 409 PHONE_ALREADY_EXISTS

前端约束：注册成功后不要调用业务首页初始化逻辑，先进入登录流程。验证码成功消费后不能复用同一条验证码再次注册。当前配置下不应再提交明文 `password` 与 `confirmPassword`。

## 5.2.2 GET /auth/public/workspace-bootstrap-options

用途：获取 workspace 创建页的后端引导选项，包括 workspace 类型、默认语言和默认时区。

请求头：无

成功响应示例：

```json
{
  "workspaceTypes": [
    {
      "code": "TEAM",
      "label": "Team Workspace",
      "description": "适用于团队协作和共享模型管理",
      "sortOrder": 10,
      "isDefault": true
    },
    {
      "code": "PERSONAL",
      "label": "Personal Workspace",
      "description": "适用于个人试验、草稿和私有整理",
      "sortOrder": 20,
      "isDefault": false
    },
    {
      "code": "LEARNING",
      "label": "Learning Workspace",
      "description": "适用于培训、演示和学习数据集",
      "sortOrder": 30,
      "isDefault": false
    }
  ],
  "locales": [
    {
      "code": "zh-CN",
      "label": "简体中文",
      "description": "默认简体中文界面",
      "sortOrder": 10,
      "isDefault": true
    },
    {
      "code": "en-US",
      "label": "English (United States)",
      "description": "默认英文界面",
      "sortOrder": 20,
      "isDefault": false
    }
  ],
  "timezones": [
    {
      "code": "Asia/Shanghai",
      "label": "Asia/Shanghai",
      "description": "中国标准时间",
      "sortOrder": 10,
      "isDefault": true
    },
    {
      "code": "UTC",
      "label": "UTC",
      "description": "协调世界时",
      "sortOrder": 20,
      "isDefault": false
    },
    {
      "code": "America/Los_Angeles",
      "label": "America/Los_Angeles",
      "description": "太平洋时间",
      "sortOrder": 30,
      "isDefault": false
    }
  ]
}
```

成功状态码：200

字段说明：

- `code`：提交给创建 workspace 接口的真实 code 值
- `label`：前端展示文案
- `description`：辅助说明文案
- `sortOrder`：展示排序
- `isDefault`：是否为默认推荐项

前端约束：

1. 创建 workspace 前必须先读取该接口，不要在前端本地硬编码 `TEAM`、`PERSONAL`、`LEARNING` 或 locale / timezone 列表。
2. 建议将 `isDefault = true` 的选项作为表单初始值。
3. 如果后端后续扩容字典项，前端页面应自动兼容，不应因为硬编码枚举而发版。

## 5.3 POST /auth/public/login/password

用途：建立平台登录态，返回平台 token、用户摘要、默认 workspace 与可选 workspace 列表。

请求体：

```json
{
  "identifier": "alice_001",
  "passwordCiphertext": "base64-rsa-ciphertext",
  "encryptionKeyId": "auth-password-rsa-v1-6f1d7d0d-4e7e-4b63-9f35-7e2cb93f8a31",
  "remember": true
}
```

说明：

- `identifier` 支持用户名、邮箱或手机号语义
- `passwordCiphertext` 为使用当前 RSA 公钥加密后的密码密文，Base64 编码
- `encryptionKeyId` 为公钥接口返回的 keyId
- `remember` 可选；`true` 表示使用更长的平台 token 有效期，`false` 或不传表示使用普通有效期

成功响应示例：

```json
{
  "platformToken": "e2f8d7...",
  "platformTokenName": "satoken-platform",
  "remember": true,
  "platformTokenExpireInSeconds": 2592000,
  "user": {
    "id": "9e4b810a-f1b1-4d18-bf97-f5f16111ad95",
    "username": "alice_001",
    "displayName": "Alice",
    "email": "alice@example.com",
    "phone": "13800001234",
    "status": "ACTIVE",
    "isFirstLogin": true,
    "workspaceCount": 0
  },
  "defaultWorkspace": null,
  "workspaceOptions": [],
  "currentWorkspace": null
}
```

字段说明：

- platformToken：平台登录 token
- platformTokenName：平台 token 对应 header 名称
- remember：本次登录是否使用“记住登录”模式
- platformTokenExpireInSeconds：平台 token 的后端有效期，单位秒；前端应按该值管理本地 token 生命周期
- user：当前用户摘要，其中 `isFirstLogin` 与 `workspaceCount` 是前端进入首次创建页或无 workspace 空态页的主判断依据
- defaultWorkspace：默认 workspace 摘要，不等于当前 workspace
- workspaceOptions：可选 workspace 摘要列表
- currentWorkspace：当前 workspace 会话，登录成功后通常为空

前端建议判断顺序：

1. `user.isFirstLogin = true`：进入首次创建 workspace 页面
2. `user.isFirstLogin = false && user.workspaceCount = 0`：进入无 workspace 空态页或恢复创建页
3. `user.workspaceCount > 0`：继续按 defaultWorkspace / currentWorkspace 正常恢复业务上下文

成功状态码：200

典型失败：

- 400 INVALID_ARGUMENT：identifier 为空、passwordCiphertext 缺失、encryptionKeyId 缺失或非法或已过期、密文解密失败
- 401 AUTH_INVALID_CREDENTIALS：用户名不存在或密码错误
- 403 ACCOUNT_NOT_ACTIVE：账号被禁用或不处于可登录状态

前端约束：

1. 登录成功后如果要进入业务页面，必须继续执行 create workspace 或 switch workspace。
2. 前端登录表单如果提供“记住登录”复选框，应把该值透传到 `remember`。
3. 前端不应本地写死 token TTL，必须以后端返回的 `platformTokenExpireInSeconds` 为准。
4. 当前配置下不应再提交明文 `password`。

## 5.3.1 POST /auth/public/platform-admin/login/password

用途：建立平台管理员登录态，返回平台 token 与管理员摘要，不携带任何 workspace 信息。

请求体：

```json
{
  "identifier": "plm_admin",
  "passwordCiphertext": "base64-rsa-ciphertext",
  "encryptionKeyId": "auth-password-rsa-v1-6f1d7d0d-4e7e-4b63-9f35-7e2cb93f8a31",
  "remember": true
}
```

说明：

- `identifier` 支持用户名、邮箱或手机号语义
- `passwordCiphertext` 为使用当前 RSA 公钥加密后的密码密文，Base64 编码
- `encryptionKeyId` 为公钥接口返回的 keyId
- `remember` 可选；`true` 表示使用更长的平台 token 有效期

成功响应示例：

```json
{
  "platformToken": "e2f8d7...",
  "platformTokenName": "satoken-platform",
  "remember": true,
  "platformTokenExpireInSeconds": 2592000,
  "admin": {
    "id": "9e4b810a-f1b1-4d18-bf97-f5f16111ad95",
    "username": "plm_admin",
    "displayName": "Platform Admin",
    "email": "admin@plm.local",
    "phone": null,
    "status": "ACTIVE",
    "roleCodes": [
      "platform_super_admin"
    ],
    "superAdmin": true
  }
}
```

字段说明：

- `admin.roleCodes`：当前管理员拥有的活跃平台角色列表
- `admin.superAdmin`：是否包含 `platform_super_admin`

成功状态码：200

典型失败：

- 400 INVALID_ARGUMENT：identifier 为空、passwordCiphertext 缺失、encryptionKeyId 缺失或非法或已过期、密文解密失败
- 401 AUTH_INVALID_CREDENTIALS：用户名不存在或密码错误
- 403 ACCOUNT_NOT_ACTIVE：账号被禁用或不处于可登录状态
- 403 PLATFORM_ADMIN_REQUIRED：当前账号没有任何活跃平台角色，不能进入平台管理员端

前端约束：

1. 管理员登录页必须使用这个接口，不要继续调用普通用户登录接口后自行猜测是否管理员。
2. 返回中没有 workspace 字段属于正常行为，不要因此触发 workspace 创建或恢复逻辑。

## 5.4 POST /auth/logout

用途：退出平台登录态，同时清理当前 workspace 会话。

请求头：

```http
{platformTokenName}: {platformToken}
```

成功状态码：204

典型失败：

- 401 AUTH_NOT_LOGGED_IN

前端约束：退出时必须同时清空 platform token、本地 currentWorkspace 和 workspace token。

## 5.4.1 GET /auth/platform-admin/me

用途：读取当前平台管理员会话摘要，用于管理员页面刷新后的登录恢复与权限校验。

请求头：

```http
{platformTokenName}: {platformToken}
```

成功响应示例：

```json
{
  "admin": {
    "id": "9e4b810a-f1b1-4d18-bf97-f5f16111ad95",
    "username": "plm_admin",
    "displayName": "Platform Admin",
    "email": "admin@plm.local",
    "phone": null,
    "status": "ACTIVE",
    "roleCodes": [
      "platform_super_admin"
    ],
    "superAdmin": true
  }
}
```

成功状态码：200

典型失败：

- 401 AUTH_NOT_LOGGED_IN
- 403 ACCOUNT_NOT_ACTIVE
- 403 PLATFORM_ADMIN_REQUIRED

前端约束：

1. 管理员页面刷新恢复时，应优先调用该接口做身份确认。
2. 如果返回 `PLATFORM_ADMIN_REQUIRED`，应清空平台 token 并回到管理员登录页，而不是跳普通业务页。

## 5.5 GET /auth/me

用途：获取当前登录用户、默认 workspace、可选 workspace 列表、当前 workspace 会话。

请求头：

```http
{platformTokenName}: {platformToken}
{workspaceTokenName}: {workspaceToken}
```

workspace token 在已有 currentWorkspace 时推荐一起带上，但平台 token 是必需项。

成功响应示例：

```json
{
  "user": {
    "id": "9e4b810a-f1b1-4d18-bf97-f5f16111ad95",
    "username": "alice_001",
    "displayName": "Alice",
    "email": "alice@example.com",
    "phone": "13800001234",
    "status": "ACTIVE",
    "isFirstLogin": false,
    "workspaceCount": 1
  },
  "defaultWorkspace": {
    "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
    "workspaceCode": "ws_9e4b810a_alpha_team_3d8b6370",
    "workspaceName": "Alpha Team",
    "workspaceStatus": "ACTIVE",
    "workspaceType": "TEAM",
    "defaultLocale": "zh-CN",
    "defaultTimezone": "Asia/Shanghai",
    "workspaceMemberId": "ad97145a-3aa4-42f7-8f65-714b48a4d2a2",
    "memberStatus": "ACTIVE",
    "isDefaultWorkspace": true
  },
  "workspaceOptions": [
    {
      "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
      "workspaceCode": "ws_9e4b810a_alpha_team_3d8b6370",
      "workspaceName": "Alpha Team",
      "workspaceStatus": "ACTIVE",
      "workspaceType": "TEAM",
      "defaultLocale": "zh-CN",
      "defaultTimezone": "Asia/Shanghai",
      "workspaceMemberId": "ad97145a-3aa4-42f7-8f65-714b48a4d2a2",
      "memberStatus": "ACTIVE",
      "isDefaultWorkspace": true
    }
  ],
  "currentWorkspace": {
    "workspaceToken": "7a8b9c...",
    "workspaceTokenName": "satoken-workspace",
    "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
    "workspaceCode": "ws_9e4b810a_alpha_team_3d8b6370",
    "workspaceName": "Alpha Team",
    "workspaceType": "TEAM",
    "defaultLocale": "zh-CN",
    "defaultTimezone": "Asia/Shanghai",
    "workspaceMemberId": "ad97145a-3aa4-42f7-8f65-714b48a4d2a2",
    "roleCodes": [
      "workspace_owner"
    ]
  }
}
```

成功状态码：200

典型失败：

- 401 AUTH_NOT_LOGGED_IN
- 403 WORKSPACE_MEMBER_INACTIVE
- 404 WORKSPACE_NOT_FOUND
- 409 WORKSPACE_NOT_ACTIVE

前端处理建议：如果是 workspace 类错误，不要直接跳登录页，优先清空 currentWorkspace 与 workspace token，然后重新进入选空间流程。

## 5.6 GET /auth/workspaces

用途：获取当前用户可见的 workspace 摘要列表。

请求头：

```http
{platformTokenName}: {platformToken}
```

成功响应示例：

```json
[
  {
    "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
    "workspaceCode": "ws_9e4b810a_alpha_team_3d8b6370",
    "workspaceName": "Alpha Team",
    "workspaceStatus": "ACTIVE",
    "workspaceType": "TEAM",
    "defaultLocale": "zh-CN",
    "defaultTimezone": "Asia/Shanghai",
    "workspaceMemberId": "ad97145a-3aa4-42f7-8f65-714b48a4d2a2",
    "memberStatus": "ACTIVE",
    "isDefaultWorkspace": true
  }
]
```

成功状态码：200

典型失败：

- 401 AUTH_NOT_LOGGED_IN

前端约束：该列表不是无条件可切换列表，仍需结合 workspaceStatus 和 memberStatus 判断是否允许自动切换。

## 5.7 POST /auth/workspaces

用途：创建 workspace，并自动建立当前 workspace 会话。

请求体：

```json
{
  "workspaceName": "Alpha Team",
  "workspaceType": "TEAM",
  "defaultLocale": "zh-CN",
  "defaultTimezone": "Asia/Shanghai",
  "rememberAsDefault": true
}
```

字段说明：

- workspaceName：workspace 名称
- workspaceType：workspace 类型，当前固定由后端字典表维护，首批可选值为 `TEAM`、`PERSONAL`、`LEARNING`
- defaultLocale：默认语言，当前必须使用 bootstrap 接口返回的 locale code
- defaultTimezone：默认时区，当前必须使用 bootstrap 接口返回的 timezone code
- rememberAsDefault：是否设置为默认 workspace

前端约束：`workspaceType`、`defaultLocale`、`defaultTimezone` 必须来自 GET /auth/public/workspace-bootstrap-options；`workspaceCode` 已不属于请求体字段。

成功响应示例：

```json
{
  "workspaceToken": "7a8b9c...",
  "workspaceTokenName": "satoken-workspace",
  "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
  "workspaceCode": "ws_9e4b810a_alpha_team_3d8b6370",
  "workspaceName": "Alpha Team",
  "workspaceType": "TEAM",
  "defaultLocale": "zh-CN",
  "defaultTimezone": "Asia/Shanghai",
  "workspaceMemberId": "ad97145a-3aa4-42f7-8f65-714b48a4d2a2",
  "roleCodes": [
    "workspace_owner"
  ]
}
```

成功状态码：200

典型失败：

- 400 INVALID_ARGUMENT：workspaceName 等字段不合法
- 401 AUTH_NOT_LOGGED_IN
- 403 USER_NOT_ACTIVE

前端约束：该接口成功后已经自动切入 workspace，不要再额外调用 switch。

## 5.8 POST /auth/workspace-session/switch

用途：切换当前 workspace，并返回新的 workspace 会话信息。

请求体：

```json
{
  "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
  "rememberAsDefault": false
}
```

成功响应：响应结构与 POST /auth/workspaces 一致。

成功状态码：200

典型失败：

- 401 AUTH_NOT_LOGGED_IN
- 403 WORKSPACE_MEMBER_NOT_FOUND：当前用户不是目标 workspace 成员
- 403 WORKSPACE_MEMBER_INACTIVE：成员状态不可用
- 404 WORKSPACE_NOT_FOUND：workspace 不存在
- 409 WORKSPACE_NOT_ACTIVE：workspace 冻结或不可用

前端约束：切换成功后必须替换旧 workspace token。rememberAsDefault 为 false 时，只切换当前会话，不修改默认 workspace。

## 5.9 GET /auth/workspace-session/current

用途：读取当前 workspace 会话。

请求头：

```http
{platformTokenName}: {platformToken}
{workspaceTokenName}: {workspaceToken}
```

成功响应：响应结构与 POST /auth/workspaces 一致。

成功状态码：

- 200：存在当前 workspace 会话
- 204：当前没有 workspace 会话

典型失败：

- 401 AUTH_NOT_LOGGED_IN
- 403 WORKSPACE_MEMBER_INACTIVE
- 404 WORKSPACE_NOT_FOUND
- 409 WORKSPACE_NOT_ACTIVE

前端约束：204 是合法业务状态，不应弹系统异常，应直接进入选空间或建空间分支。

## 5.10 DELETE /auth/workspace-session/current

用途：清理当前 workspace 会话，但不退出平台登录态。

请求头：

```http
{platformTokenName}: {platformToken}
```

成功状态码：204

典型失败：

- 401 AUTH_NOT_LOGGED_IN

前端约束：该接口不是 logout。调用后用户仍处于登录状态，只是 currentWorkspace 被清空。

## 5.11 Workspace 邀请接口统一版块

本版块统一收口当前邀请相关接口，覆盖邮箱批量邀请、邀请预览、邀请接受、邀请列表、邀请取消、分享链接创建、链接预览、链接接受、链接禁用。

通用前置约束：

1. 除两个 public 预览接口外，其余邀请接口都必须携带 platform token。
2. 邀请创建、列表、取消、链接创建、链接禁用都要求当前用户在目标 workspace 中具备 `workspace.member.invite` 权限。
3. 邮箱邀请接受时，当前登录用户邮箱必须与 invitation 目标邮箱完全一致，否则返回 `403 INVITATION_EMAIL_MISMATCH`。
4. 预览接口返回 200 不代表一定还能接受，前端必须同时检查 `invitationStatus` 或 `status` 以及 `canAccept`。
5. 分享链接如果达到 `maxUseCount` 或过期时间，会自动转为 `EXPIRED`；禁用后状态为 `DISABLED`。

### 5.11.1 POST /auth/workspace-invitations/email-batch

用途：向一批邮箱发送 workspace 邀请邮件。

请求头：

```http
{platformTokenName}: {platformToken}
```

请求体：

```json
{
  "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
  "emails": [
    "alice@example.com",
    "bob@example.com"
  ],
  "targetRoleCode": "workspace_member",
  "sourceScene": "ONBOARDING"
}
```

字段说明：

- `workspaceId`：目标 workspace ID
- `emails`：邮箱数组；服务端会做去空格、大小写归一和逐项合法性校验
- `targetRoleCode`：邀请后授予的 workspace 角色；未传时由后端默认处理为成员角色
- `sourceScene`：来源场景，当前支持 `WORKSPACE`、`ONBOARDING`；未传默认 `WORKSPACE`

成功响应示例：

```json
{
  "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
  "batchId": "0d2c2690-c4d5-4c9f-8a53-1d9cf8b8a530",
  "successCount": 1,
  "skippedCount": 2,
  "results": [
    {
      "email": "alice@example.com",
      "result": "CREATED",
      "invitationId": "4e0f7394-d704-4463-b3cb-830a4f7d9917",
      "message": null
    },
    {
      "email": "owner@example.com",
      "result": "SELF_SKIPPED",
      "invitationId": null,
      "message": "cannot invite current user email"
    },
    {
      "email": "alice@example.com",
      "result": "DUPLICATE_INPUT",
      "invitationId": null,
      "message": "duplicate email in request"
    }
  ]
}
```

`results[].result` 当前可能值：

- `CREATED`：本次已成功创建并发送邀请
- `INVALID_EMAIL`：邮箱格式非法
- `DUPLICATE_INPUT`：同一批请求中重复邮箱
- `SELF_SKIPPED`：邀请目标等于当前登录用户邮箱
- `ALREADY_MEMBER`：目标邮箱已对应当前 workspace 的活跃成员
- `PENDING_EXISTS`：已有同 workspace、同邮箱的待接受邀请

成功状态码：200

典型失败：

- 400 INVALID_ARGUMENT：emails 为空、超出批量上限、targetRoleCode 非法、sourceScene 非法
- 401 AUTH_NOT_LOGGED_IN
- 403 USER_NOT_ACTIVE / WORKSPACE_MEMBER_NOT_FOUND / WORKSPACE_MEMBER_INACTIVE / WORKSPACE_PERMISSION_DENIED
- 404 WORKSPACE_NOT_FOUND / WORKSPACE_ROLE_NOT_FOUND
- 409 WORKSPACE_NOT_ACTIVE / WORKSPACE_ROLE_NOT_ACTIVE

前端约束：

1. 这是“部分成功”接口，必须逐项读取 `results` 展示处理结果。
2. 不要把 `successCount = 0` 直接等同于接口失败，可能只是全部命中跳过态。

### 5.11.2 GET /auth/public/workspace-invitations/email/{token}

用途：匿名预览邮箱邀请内容。

请求头：无

成功响应示例：

```json
{
  "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
  "workspaceName": "Alpha Team",
  "workspaceType": "TEAM",
  "inviterDisplayName": "Alice",
  "inviteeEmailMasked": "al***@example.com",
  "invitationStatus": "PENDING",
  "sourceScene": "WORKSPACE",
  "targetRoleCode": "workspace_member",
  "expiresAt": "2026-04-16T23:59:59+08:00",
  "canAccept": true
}
```

成功状态码：200

典型失败：

- 400 INVALID_ARGUMENT：token 为空
- 404 WORKSPACE_INVITATION_NOT_FOUND / WORKSPACE_NOT_FOUND

前端约束：如果 `canAccept = false`，应直接切为失效态说明页，不要继续提示用户点击接受。

### 5.11.3 POST /auth/workspace-invitations/email/{token}/accept

用途：登录后接受邮箱邀请，并直接建立当前 workspace session。

请求头：

```http
{platformTokenName}: {platformToken}
```

成功响应：响应结构与 POST /auth/workspaces 一致。

成功状态码：200

典型失败：

- 401 AUTH_NOT_LOGGED_IN
- 403 USER_NOT_ACTIVE / INVITATION_EMAIL_MISMATCH
- 404 WORKSPACE_INVITATION_NOT_FOUND / WORKSPACE_NOT_FOUND / WORKSPACE_ROLE_NOT_FOUND
- 409 INVITATION_ACCEPT_EMAIL_REQUIRED / WORKSPACE_MEMBER_INACTIVE / WORKSPACE_INVITATION_ALREADY_ACCEPTED / WORKSPACE_NOT_ACTIVE / WORKSPACE_ROLE_NOT_ACTIVE
- 410 WORKSPACE_INVITATION_CANCELED / WORKSPACE_INVITATION_EXPIRED

前端约束：

1. 邮箱邀请 accept 成功后，后端已经切入目标 workspace，不需要额外再调 switch。
2. 如果返回 `INVITATION_EMAIL_MISMATCH`，应明确提示“请使用收到邀请的邮箱对应账号登录”。

### 5.11.4 GET /auth/workspace-invitations

用途：按 workspace 读取邀请记录，支持按状态筛选。

请求头：

```http
{platformTokenName}: {platformToken}
```

查询参数：

- `workspaceId`：必填
- `status`：可选；当前支持 `PENDING`、`ACCEPTED`、`EXPIRED`、`CANCELED`

成功响应示例：

```json
[
  {
    "id": "4e0f7394-d704-4463-b3cb-830a4f7d9917",
    "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
    "workspaceName": "Alpha Team",
    "inviteeEmail": "alice@example.com",
    "inviteeDisplayName": null,
    "inviterDisplayName": "Alice",
    "invitationStatus": "PENDING",
    "sourceScene": "ONBOARDING",
    "invitationChannel": "EMAIL",
    "targetRoleCode": "workspace_member",
    "batchId": "0d2c2690-c4d5-4c9f-8a53-1d9cf8b8a530",
    "expiresAt": "2026-04-16T23:59:59+08:00",
    "sentAt": "2026-04-16T12:00:00+08:00",
    "acceptedAt": null,
    "acceptedByUserId": null,
    "canceledAt": null,
    "canceledByUserId": null,
    "cancelReason": null,
    "createdAt": "2026-04-16T11:59:58+08:00"
  }
]
```

成功状态码：200

典型失败：

- 400 INVALID_ARGUMENT：workspaceId 缺失或 status 非法
- 401 AUTH_NOT_LOGGED_IN
- 403 USER_NOT_ACTIVE / WORKSPACE_MEMBER_NOT_FOUND / WORKSPACE_MEMBER_INACTIVE / WORKSPACE_PERMISSION_DENIED
- 404 WORKSPACE_NOT_FOUND
- 409 WORKSPACE_NOT_ACTIVE

### 5.11.5 POST /auth/workspace-invitations/{id}/cancel

用途：取消一条邮箱邀请。

请求头：

```http
{platformTokenName}: {platformToken}
```

成功响应：响应结构与 GET /auth/workspace-invitations 的单项对象一致。

成功状态码：200

典型失败：

- 401 AUTH_NOT_LOGGED_IN
- 403 USER_NOT_ACTIVE / WORKSPACE_MEMBER_NOT_FOUND / WORKSPACE_MEMBER_INACTIVE / WORKSPACE_PERMISSION_DENIED
- 404 WORKSPACE_INVITATION_NOT_FOUND / WORKSPACE_NOT_FOUND
- 409 WORKSPACE_NOT_ACTIVE / WORKSPACE_INVITATION_ALREADY_ACCEPTED

前端约束：取消成功后，公开预览页仍可能返回 200，但 `invitationStatus = CANCELED` 且 `canAccept = false`。

### 5.11.6 POST /auth/workspace-invitation-links

用途：创建一个可分享的 workspace 邀请链接。

请求头：

```http
{platformTokenName}: {platformToken}
```

请求体：

```json
{
  "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
  "sourceScene": "ONBOARDING",
  "targetRoleCode": "workspace_member",
  "expiresInHours": 24,
  "maxUseCount": 10
}
```

字段说明：

- `expiresInHours`：链接有效小时数；未传时走后端默认配置
- `maxUseCount`：最多可接受次数；未传表示不限制使用次数

成功响应示例：

```json
{
  "linkId": "2d59c6d7-b2ab-44b1-9634-b2558f4caa05",
  "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
  "workspaceName": "Alpha Team",
  "shareUrl": "http://localhost:3000/invite-link?token=6c6c1e...",
  "sourceScene": "ONBOARDING",
  "targetRoleCode": "workspace_member",
  "expiresAt": "2026-04-17T12:00:00+08:00",
  "usedCount": 0,
  "maxUseCount": 10,
  "status": "ACTIVE",
  "createdAt": "2026-04-16T12:00:00+08:00"
}
```

成功状态码：200

典型失败：

- 400 INVALID_ARGUMENT：expiresInHours <= 0、maxUseCount <= 0、sourceScene 非法、targetRoleCode 非法
- 401 AUTH_NOT_LOGGED_IN
- 403 USER_NOT_ACTIVE / WORKSPACE_MEMBER_NOT_FOUND / WORKSPACE_MEMBER_INACTIVE / WORKSPACE_PERMISSION_DENIED
- 404 WORKSPACE_NOT_FOUND / WORKSPACE_ROLE_NOT_FOUND
- 409 WORKSPACE_NOT_ACTIVE / WORKSPACE_ROLE_NOT_ACTIVE

### 5.11.7 GET /auth/public/workspace-invitation-links/{token}

用途：匿名预览分享链接邀请。

请求头：无

成功响应示例：

```json
{
  "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
  "workspaceName": "Alpha Team",
  "workspaceType": "TEAM",
  "inviterDisplayName": "Alice",
  "sourceScene": "ONBOARDING",
  "targetRoleCode": "workspace_member",
  "expiresAt": "2026-04-17T12:00:00+08:00",
  "usedCount": 0,
  "maxUseCount": 10,
  "status": "ACTIVE",
  "canAccept": true
}
```

成功状态码：200

典型失败：

- 400 INVALID_ARGUMENT：token 为空
- 404 WORKSPACE_INVITATION_LINK_NOT_FOUND / WORKSPACE_NOT_FOUND

前端约束：如果 `status = DISABLED` 或 `status = EXPIRED`，即使页面仍能打开，也只能展示失效说明，不应再触发 accept。

### 5.11.8 POST /auth/workspace-invitation-links/{token}/accept

用途：登录后接受分享链接邀请，并直接建立当前 workspace session。

请求头：

```http
{platformTokenName}: {platformToken}
```

成功响应：响应结构与 POST /auth/workspaces 一致。

成功状态码：200

典型失败：

- 401 AUTH_NOT_LOGGED_IN
- 403 USER_NOT_ACTIVE
- 404 WORKSPACE_INVITATION_LINK_NOT_FOUND / WORKSPACE_NOT_FOUND / WORKSPACE_ROLE_NOT_FOUND
- 409 WORKSPACE_MEMBER_INACTIVE / WORKSPACE_NOT_ACTIVE / WORKSPACE_ROLE_NOT_ACTIVE
- 410 WORKSPACE_INVITATION_LINK_DISABLED / WORKSPACE_INVITATION_LINK_EXPIRED

前端约束：

1. 分享链接 accept 成功后同样不需要再调 switch。
2. 单次链接在第一个新成员接受后会立即转为 `EXPIRED`，后续用户再次提交同一 token 会收到 410。

### 5.11.9 POST /auth/workspace-invitation-links/{id}/disable

用途：禁用一条分享链接。

请求头：

```http
{platformTokenName}: {platformToken}
```

成功响应：响应结构与 POST /auth/workspace-invitation-links 一致。

成功状态码：200

典型失败：

- 401 AUTH_NOT_LOGGED_IN
- 403 USER_NOT_ACTIVE / WORKSPACE_MEMBER_NOT_FOUND / WORKSPACE_MEMBER_INACTIVE / WORKSPACE_PERMISSION_DENIED
- 404 WORKSPACE_INVITATION_LINK_NOT_FOUND / WORKSPACE_NOT_FOUND
- 409 WORKSPACE_NOT_ACTIVE

前端约束：链接禁用后，公开预览页会显示 `status = DISABLED` 且 `canAccept = false`。

## 6. 前端状态管理建议

建议至少维护以下状态：

```ts
type PlatformAuthState = {
  platformToken: string | null;
  platformTokenName: string | null;
  remember: boolean;
  platformTokenExpireInSeconds: number | null;
  user: {
    id: string;
    username: string;
    displayName: string;
    email: string | null;
    phone: string | null;
    status: string;
    isFirstLogin: boolean;
    workspaceCount: number;
  } | null;
};

type WorkspaceSessionState = {
  workspaceToken: string | null;
  workspaceTokenName: string | null;
  workspaceId: string | null;
  workspaceCode: string | null;
  workspaceName: string | null;
  workspaceType: string | null;
  defaultLocale: string | null;
  defaultTimezone: string | null;
  workspaceMemberId: string | null;
  roleCodes: string[];
};
```

本地状态刷新建议：

1. login 成功只更新 PlatformAuthState。
2. create workspace 或 switch workspace 成功后再更新 WorkspaceSessionState。
3. logout 时同时清空两层状态。
4. clear current workspace 时只清空 WorkspaceSessionState。

## 7. 错误处理建议

| 场景 | 建议处理 |
| --- | --- |
| 401 AUTH_NOT_LOGGED_IN | 清空 platform token 与 workspace token，跳回登录页 |
| 401 AUTH_INVALID_CREDENTIALS | 登录页提示“用户名或密码错误” |
| 403 ACCOUNT_NOT_ACTIVE | 登录页提示账号不可用，阻止继续登录 |
| 403 WORKSPACE_MEMBER_NOT_FOUND | 清空当前 workspace 状态，重新展示 workspace 选择页 |
| 403 WORKSPACE_MEMBER_INACTIVE | 清空当前 workspace 状态，提示成员状态不可用 |
| 404 WORKSPACE_NOT_FOUND | 清空当前 workspace 状态，重新拉取 workspace 列表 |
| 409 WORKSPACE_NOT_ACTIVE | 清空当前 workspace 状态，提示 workspace 已冻结或不可用 |
| 409 USERNAME_ALREADY_EXISTS / EMAIL_ALREADY_EXISTS / PHONE_ALREADY_EXISTS | 注册页字段级提示 |
| 400 EMAIL_VERIFICATION_CODE_INVALID / EMAIL_VERIFICATION_CODE_EXPIRED | 注册页验证码字段级提示，并引导重新发送验证码 |
| 403 INVITATION_EMAIL_MISMATCH | 提示用户切换到被邀请邮箱对应账号后再接受邮箱邀请 |
| 409 INVITATION_ACCEPT_EMAIL_REQUIRED | 提示当前账号缺少可用邮箱，无法接受邮箱邀请 |
| 409 WORKSPACE_INVITATION_ALREADY_ACCEPTED | 邀请详情页提示“该邀请已被使用”，并刷新 workspace 状态 |
| 410 WORKSPACE_INVITATION_CANCELED / WORKSPACE_INVITATION_EXPIRED | 邮箱邀请页进入失效态，不再展示接受按钮 |
| 410 WORKSPACE_INVITATION_LINK_DISABLED / WORKSPACE_INVITATION_LINK_EXPIRED | 分享邀请页进入失效态，不再展示接受按钮 |
| 429 EMAIL_VERIFICATION_SEND_TOO_FREQUENT | 验证码发送页启动重发倒计时，不要立即重试 |
| 502 EMAIL_VERIFICATION_SEND_FAILED / 503 EMAIL_VERIFICATION_NOT_CONFIGURED | 发送验证码失败，提示稍后重试或联系管理员 |
| 400 INVALID_ARGUMENT | 表单字段前置校验并展示具体 message |

## 8. 前端易错点

1. defaultWorkspace 不等于 currentWorkspace。
2. 登录成功不返回 workspace token。
3. 不要写死 token header 名称。
4. 登录请求中的 `remember` 会直接影响平台 token 有效期，前端不要只存本地勾选态而不透传后端。
5. 登录和注册前要先读取 RSA 公钥接口，再加密密码；不要继续直接提交明文 `password`。
6. `encryptionKeyId` 必须与公钥接口返回值一致，不能本地伪造或长期缓存旧值。
7. 第一个 workspace 即使 rememberAsDefault 为 false，也可能被后端自动设为默认 workspace。
8. workspaceOptions 不是无条件可切换列表，仍需关注 workspaceStatus 与 memberStatus。
9. DELETE /auth/workspace-session/current 不是退出登录。
10. GET /auth/workspace-session/current 返回 204 是正常业务态。
11. 恢复当前 workspace 的可信来源是 GET /auth/me 的 currentWorkspace，不是本地 localStorage 中缓存的 workspaceId。
12. `workspaceType`、`defaultLocale`、`defaultTimezone` 由后端字典表维护，前端不要本地硬编码枚举。
13. `workspaceCode` 已改为系统生成字段，前端不再展示或提交该输入项。
14. 邮箱邀请 accept 不是“谁登录都能点”，必须使用与 inviteeEmail 一致的账号。
15. 邀请预览接口返回 200 只代表 token 可读取，不代表 token 仍可接受，必须看 `canAccept`。
16. 邮箱批量邀请是逐项结果接口，`successCount` 只是汇总，不足以驱动逐邮箱提示。
17. 已取消邮箱邀请、已禁用链接、已过期链接的预览页仍然可能正常打开，但只能展示失效态。
18. `maxUseCount = 1` 的分享链接在第一个新成员接受后会自动变为 `EXPIRED`。
19. 平台管理员必须走独立登录与会话校验接口，不参与 workspace 恢复流程。

## 9. 当前已验证的集成测试覆盖

当前 auth-service 已通过 VS Code 内置集成测试验证以下场景：

1. 注册邮箱验证码发送与消费成功
2. 注册成功
3. 登录成功
4. 创建 workspace 成功并自动建立当前会话
5. 多 workspace 切换成功
6. 读取当前 workspace 会话
7. 清理当前 workspace 会话
8. 重复注册拒绝
9. 验证码发送过频拒绝
10. 错误邮箱验证码注册拒绝
11. 错误密码登录拒绝
12. 非成员切换 workspace 拒绝
13. 禁用账号登录拒绝
14. 未登录访问受保护接口拒绝
15. inactive 成员切换 workspace 拒绝
16. frozen workspace 切换拒绝
17. 邮箱邀请预览与接受成功
18. 邮箱批量邀请返回 `PENDING_EXISTS` 与 `DUPLICATE_INPUT`
19. 分享链接预览与接受成功
20. 邮箱邀请接受时邮箱不匹配拒绝
21. 已取消邮箱邀请不能再次接受，且预览进入不可接受态
22. 已禁用分享链接不能再次接受，且预览进入不可接受态
23. 单次分享链接首个新成员接受后自动过期
24. 登录 remember 选项返回不同平台 token 有效期，并实际生效
25. 密码加密公钥接口可用并返回当前 RSA 公钥
26. 当前配置下明文密码登录会被拒绝
27. 密码公钥在当前有效窗口内可稳定复用，错误或失效的 `encryptionKeyId` 会被拒绝
28. dev 环境默认平台管理员会在启动时按配置幂等创建，并可在无 workspace 的情况下独立登录
29. 没有平台角色的普通账号访问平台管理员登录/会话接口会被拒绝

说明：当前最近一次 AuthFlowControllerIT 结果为 30 passed / 0 failed；另外 PasswordTransportSecurityServiceTest 为 2 passed / 0 failed，RegisterEmailTemplateRendererTest 为 3 passed / 0 failed，本轮总验证结果为 35 passed / 0 failed。
