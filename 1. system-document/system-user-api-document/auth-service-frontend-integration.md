# PLM Cloud Auth-Service 前端对接说明

## 1. 文档范围

本文档面向前端联调用途，覆盖当前 auth-service 已实现并通过集成测试验证的注册邮箱验证码发送、账号注册、密码登录、workspace 创建、workspace 切换、当前会话读取与退出能力。

本地联调时，前端统一通过 gateway 访问 auth 接口：

- base URL：`http://localhost:8080`
- auth 路径前缀：`/auth/**`
- 不再建议前端直接请求 `http://localhost:8081`

当前接口语义有 4 个前置约束，前端必须按此理解：

1. 注册前必须先发送并校验邮箱验证码。
2. 密码登录只建立平台登录态，不自动建立当前 workspace 会话。
3. 创建 workspace 会自动创建 owner member、内建角色、默认权限绑定，并自动切入该 workspace。
4. 当前 workspace 上下文以后端 platform session 中保存的 currentWorkspaceMemberId 为准，前端本地缓存只用于恢复请求头，不可替代后端真实会话。

另外，`user` 摘要中新增两个与 workspace 引导直接相关的字段：

1. `isFirstLogin`：表示用户尚未完成首次 workspace 建立；首次成功创建 workspace 后会永久变为 `false`。
2. `workspaceCount`：表示用户当前可用的活跃 workspace 数，用于处理“用户后来删光了所有 workspace”的空态分支。

## 2. 鉴权与 Header 约定

### 2.1 平台登录态

- 来源接口：POST /auth/public/login/password
- 返回字段：platformToken、platformTokenName
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

- platformAuth：platformToken、platformTokenName、user
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
3. 再调用 POST /auth/public/register
4. 注册成功后跳转登录页或回填登录表单
5. 不要假设已登录
6. 不要假设已有 defaultWorkspace 或 currentWorkspace

### 4.2 登录后初始化流程

推荐顺序：

1. 调用 POST /auth/public/login/password
2. 保存 platformToken、platformTokenName、user、defaultWorkspace、workspaceOptions
3. 如果 `user.isFirstLogin = true`，直接进入“创建第一个 workspace”页面
4. 如果 `user.isFirstLogin = false` 且 `user.workspaceCount = 0`，进入“无 workspace 空态页”或“恢复创建 workspace”页面，不要停留在空白页
5. 如果 `user.workspaceCount > 0` 且 `defaultWorkspace` 存在，优先调用 POST /auth/workspace-session/switch 切入 defaultWorkspace
6. switch 成功后保存 workspaceToken、workspaceTokenName、currentWorkspace、roleCodes
7. 进入依赖 workspace 上下文的业务页面

注意：登录成功不代表可以直接访问 workspace 业务页面，因为此时通常还没有 workspace token。

### 4.3 创建 Workspace 流程

1. 前提是已登录并持有 platform token
2. 调用 POST /auth/workspaces
3. 成功后直接使用返回值刷新 currentWorkspaceSession
4. 不需要额外再调用一次 switch
5. 如果需要同步 defaultWorkspace 与 workspaceOptions，可以追加调用 GET /auth/me

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

## 5.2 POST /auth/public/register

用途：创建平台账号，不创建 workspace，不返回 token。

请求体：

```json
{
  "username": "alice_001",
  "displayName": "Alice",
  "password": "Password123!",
  "confirmPassword": "Password123!",
  "email": "alice@example.com",
  "emailVerificationCode": "123456",
  "phone": "13800001234"
}
```

字段说明：

- username：用户名
- displayName：展示名
- password：密码
- confirmPassword：确认密码
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

- 400 INVALID_ARGUMENT：字段为空、格式非法、密码确认不一致、验证码格式非法
- 400 EMAIL_VERIFICATION_CODE_INVALID
- 400 EMAIL_VERIFICATION_CODE_EXPIRED
- 409 USERNAME_ALREADY_EXISTS
- 409 EMAIL_ALREADY_EXISTS
- 409 PHONE_ALREADY_EXISTS

前端约束：注册成功后不要调用业务首页初始化逻辑，先进入登录流程。验证码成功消费后不能复用同一条验证码再次注册。

## 5.3 POST /auth/public/login/password

用途：建立平台登录态，返回平台 token、用户摘要、默认 workspace 与可选 workspace 列表。

请求体：

```json
{
  "identifier": "alice_001",
  "password": "Password123!"
}
```

说明：identifier 支持用户名、邮箱或手机号语义。

成功响应示例：

```json
{
  "platformToken": "e2f8d7...",
  "platformTokenName": "satoken-platform",
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

- 400 INVALID_ARGUMENT：identifier 或 password 为空
- 401 AUTH_INVALID_CREDENTIALS：用户名不存在或密码错误
- 403 ACCOUNT_NOT_ACTIVE：账号被禁用或不处于可登录状态

前端约束：登录成功后如果要进入业务页面，必须继续执行 create workspace 或 switch workspace。

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
    "status": "ACTIVE"
  },
  "defaultWorkspace": {
    "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
    "workspaceCode": "alpha_team",
    "workspaceName": "Alpha Team",
    "workspaceStatus": "ACTIVE",
    "workspaceMemberId": "ad97145a-3aa4-42f7-8f65-714b48a4d2a2",
    "memberStatus": "ACTIVE",
    "isDefaultWorkspace": true
  },
  "workspaceOptions": [
    {
      "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
      "workspaceCode": "alpha_team",
      "workspaceName": "Alpha Team",
      "workspaceStatus": "ACTIVE",
      "workspaceMemberId": "ad97145a-3aa4-42f7-8f65-714b48a4d2a2",
      "memberStatus": "ACTIVE",
      "isDefaultWorkspace": true
    }
  ],
  "currentWorkspace": {
    "workspaceToken": "7a8b9c...",
    "workspaceTokenName": "satoken-workspace",
    "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
    "workspaceCode": "alpha_team",
    "workspaceName": "Alpha Team",
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
    "workspaceCode": "alpha_team",
    "workspaceName": "Alpha Team",
    "workspaceStatus": "ACTIVE",
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
  "workspaceCode": "alpha_team",
  "workspaceType": "DEFAULT",
  "defaultLocale": "zh-CN",
  "defaultTimezone": "Asia/Shanghai",
  "rememberAsDefault": true
}
```

字段说明：

- workspaceName：workspace 名称
- workspaceCode：workspace 唯一编码
- workspaceType：workspace 类型，当前测试使用 DEFAULT
- defaultLocale：默认语言
- defaultTimezone：默认时区
- rememberAsDefault：是否设置为默认 workspace

成功响应示例：

```json
{
  "workspaceToken": "7a8b9c...",
  "workspaceTokenName": "satoken-workspace",
  "workspaceId": "3d8b6370-f1f2-4704-8cc8-e4b3535198db",
  "workspaceCode": "alpha_team",
  "workspaceName": "Alpha Team",
  "workspaceMemberId": "ad97145a-3aa4-42f7-8f65-714b48a4d2a2",
  "roleCodes": [
    "workspace_owner"
  ]
}
```

成功状态码：200

典型失败：

- 400 INVALID_ARGUMENT：workspaceName、workspaceCode 等字段不合法
- 401 AUTH_NOT_LOGGED_IN
- 403 USER_NOT_ACTIVE
- 409 WORKSPACE_CODE_ALREADY_EXISTS

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

## 6. 前端状态管理建议

建议至少维护以下状态：

```ts
type PlatformAuthState = {
  platformToken: string | null;
  platformTokenName: string | null;
  user: {
    id: string;
    username: string;
    displayName: string;
    email: string | null;
    phone: string | null;
    status: string;
  } | null;
};

type WorkspaceSessionState = {
  workspaceToken: string | null;
  workspaceTokenName: string | null;
  workspaceId: string | null;
  workspaceCode: string | null;
  workspaceName: string | null;
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
| 429 EMAIL_VERIFICATION_SEND_TOO_FREQUENT | 验证码发送页启动重发倒计时，不要立即重试 |
| 502 EMAIL_VERIFICATION_SEND_FAILED / 503 EMAIL_VERIFICATION_NOT_CONFIGURED | 发送验证码失败，提示稍后重试或联系管理员 |
| 409 WORKSPACE_CODE_ALREADY_EXISTS | 创建 workspace 表单字段级提示 |
| 400 INVALID_ARGUMENT | 表单字段前置校验并展示具体 message |

## 8. 前端易错点

1. defaultWorkspace 不等于 currentWorkspace。
2. 登录成功不返回 workspace token。
3. 不要写死 token header 名称。
4. 第一个 workspace 即使 rememberAsDefault 为 false，也可能被后端自动设为默认 workspace。
5. workspaceOptions 不是无条件可切换列表，仍需关注 workspaceStatus 与 memberStatus。
6. DELETE /auth/workspace-session/current 不是退出登录。
7. GET /auth/workspace-session/current 返回 204 是正常业务态。
8. 恢复当前 workspace 的可信来源是 GET /auth/me 的 currentWorkspace，不是本地 localStorage 中缓存的 workspaceId。

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

当前测试结果：12 passed / 0 failed。
