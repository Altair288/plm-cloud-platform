# 用户 Workspace 邀请功能设计草案

更新时间：2026-04-16  
阶段：邀请模块设计草案（支持 onboarding 批量邀请、复制邀请链接、工作区内单个邀请）

---

## 1. 目标

本稿用于在当前 user -> workspace 第一阶段模型上补充邀请能力，覆盖以下真实场景：

1. 用户在首次创建 workspace 的 onboarding 流程中，批量输入多个邮箱，系统发送邀请邮件。
2. 用户在 onboarding 页面点击“复制邀请链接”，把链接通过 IM、企业微信、飞书等方式发给同事。
3. 用户进入 workspace 后，在成员管理页继续邀请单个邮箱用户。

本稿目标不是立即落地全部代码，而是把数据库扩展方向、接口边界、状态机和安全约束一次定清。

---

## 2. 设计原则

1. workspace 创建与邀请发送解耦，不建议把“创建 workspace”和“发邀请邮件”塞进同一个事务。
2. 邮箱邀请与邀请链接是两种不同模型，不建议强行复用同一条记录表示两者。
3. 邀请能力必须复用当前 workspace/member/role 权限体系，受 `workspace.member.invite` 控制。
4. 邀请最终仍然要落到 `workspace_member`，不额外创造第二套成员体系。
5. 邀请链接允许后续迭代为可轮换、可失效、可限次使用；因此需要独立数据模型。

---

## 3. 当前数据库基础

当前数据库设计中已经有以下基础：

1. `plm_platform.workspace_invitation`：适合承载按邮箱发出的定向邀请。
2. `plm_platform.workspace_member`：已支持 `member_status=INVITED`、`join_type=INVITE`、`invited_by_user_id`。
3. 权限字典中已有 `workspace.member.invite`。

结论：

1. 邮箱邀请可以直接基于现有 `workspace_invitation` 扩展。
2. 复制邀请链接不建议直接复用 `workspace_invitation`，因为当前表天然是一条记录绑定一个邮箱、一个 token、一次接受结果。

---

## 4. 推荐能力拆分

### 4.1 邮箱邀请

用途：

1. onboarding 页面批量输入邮箱后发送邀请邮件。
2. workspace 内成员页发送单个邮箱邀请。

特点：

1. 一条邀请记录对应一个邮箱。
2. 邮件中的链接带一次性 token。
3. 接受时必须校验当前登录用户邮箱与 invitee_email 一致。

### 4.2 邀请链接

用途：

1. onboarding 页面“复制邀请链接”。
2. workspace 内成员页生成一条通用分享链接。

特点：

1. 链接不绑定预先输入的邮箱。
2. 一个链接可以支持单次或多次使用。
3. 接受时不依赖 invitee_email，而是依赖当前登录用户身份。

---

## 5. 数据模型建议

### 5.1 保留并增强 workspace_invitation

表：`plm_platform.workspace_invitation`

当前表继续作为“邮箱定向邀请”主表，建议在现有字段基础上增加：

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| source_scene | VARCHAR(20) | Y | ONBOARDING / WORKSPACE |
| invitation_channel | VARCHAR(20) | Y | EMAIL |
| target_role_code | VARCHAR(64) | Y | 目标角色，默认 `workspace_member` |
| batch_id | UUID | N | 批量邀请批次 ID |
| sent_at | TIMESTAMPTZ | N | 邮件实际发送时间 |
| canceled_at | TIMESTAMPTZ | N | 取消时间 |
| canceled_by_user_id | UUID | N | 取消人 |
| cancel_reason | VARCHAR(255) | N | 取消原因 |

说明：

1. `source_scene` 用于区分 onboarding 发出的邀请与工作区成员页发出的邀请。
2. `target_role_code` 让邀请接受后默认落什么角色可配置，第一阶段建议固定 `workspace_member`。
3. `batch_id` 用于把一次批量输入的多邮箱邀请归为同一批，便于列表回显和失败重发。

### 5.2 新增 workspace_invitation_link

表：`plm_platform.workspace_invitation_link`

用途：承载复制分享的邀请链接。

建议字段：

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| workspace_id | UUID | Y | 所属 workspace |
| invited_by_user_id | UUID | Y | 生成链接的操作者 |
| source_scene | VARCHAR(20) | Y | ONBOARDING / WORKSPACE |
| link_status | VARCHAR(20) | Y | ACTIVE / DISABLED / EXPIRED |
| invitation_token | VARCHAR(128) | Y | 分享 token |
| target_role_code | VARCHAR(64) | Y | 默认授予角色，第一阶段建议 `workspace_member` |
| max_use_count | INTEGER | N | 最大可使用次数，null 表示不限次 |
| used_count | INTEGER | Y | 已使用次数 |
| expires_at | TIMESTAMPTZ | N | 过期时间，null 表示不过期 |
| last_used_at | TIMESTAMPTZ | N | 最近使用时间 |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |
| updated_at | TIMESTAMPTZ | N | 更新时间 |
| updated_by | VARCHAR(64) | N | 更新者 |

约束建议：

1. `UNIQUE (invitation_token)`
2. `FOREIGN KEY (workspace_id) REFERENCES plm_platform.workspace(id) ON DELETE CASCADE`
3. `FOREIGN KEY (invited_by_user_id) REFERENCES plm_platform.user_account(id)`

索引建议：

1. `INDEX idx_workspace_invitation_link_workspace_status ON (workspace_id, link_status)`
2. `INDEX idx_workspace_invitation_link_expires_at ON (expires_at)`

### 5.3 新增 workspace_invitation_link_accept_log

表：`plm_platform.workspace_invitation_link_accept_log`

用途：记录共享链接被谁使用过，解决共享链接天然一对多的问题。

建议字段：

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| invitation_link_id | UUID | Y | 邀请链接 |
| accepted_by_user_id | UUID | Y | 实际接受人 |
| workspace_member_id | UUID | Y | 创建出的成员记录 |
| accepted_at | TIMESTAMPTZ | Y | 接受时间 |
| accept_ip | VARCHAR(64) | N | 接受 IP |
| user_agent | VARCHAR(512) | N | 终端信息 |

说明：

1. 如果后续要追溯“某个分享链接邀请到了哪些人”，这个表是必须的。
2. 第一阶段如果想先轻量，也可以暂不建表，但会牺牲审计能力。

---

## 6. 状态机建议

### 6.1 邮箱邀请状态

`workspace_invitation.invitation_status` 建议保留：

1. `PENDING`：待接受
2. `ACCEPTED`：已接受
3. `EXPIRED`：已过期
4. `CANCELED`：已取消

状态流转：

1. 创建邀请 -> `PENDING`
2. 用户接受 -> `ACCEPTED`
3. 到达过期时间 -> `EXPIRED`
4. 邀请人主动撤销 -> `CANCELED`

### 6.2 邀请链接状态

`workspace_invitation_link.link_status` 建议使用：

1. `ACTIVE`
2. `DISABLED`
3. `EXPIRED`

状态流转：

1. 创建链接 -> `ACTIVE`
2. 主动关闭 -> `DISABLED`
3. 超时或达到 max_use_count -> `EXPIRED`

---

## 7. 核心流程建议

### 7.1 onboarding 批量邮箱邀请

推荐前端流程：

1. 用户先完成 `POST /auth/workspaces`，创建 workspace。
2. 创建成功后，前端拿到 `workspaceId` 和当前 workspace token。
3. 再调用批量邮箱邀请接口发送邀请。

不建议把邀请逻辑并入创建 workspace 的原因：

1. 发邮件属于外部副作用，不应回滚 workspace 创建事务。
2. 用户即使一封邀请都没发出去，也不应该失去刚创建成功的 workspace。

### 7.2 复制邀请链接

推荐流程：

1. 前端调用“创建或获取当前有效邀请链接”接口。
2. 后端返回 shareUrl、expiresAt、usedCount、maxUseCount。
3. 前端仅负责复制，不自己拼接 token。

### 7.3 工作区内单个邮箱邀请

推荐流程：

1. 在 workspace 成员管理页输入一个邮箱。
2. 调用与批量邀请相同的接口，但只传一个邮箱。
3. 后端统一走邮箱邀请逻辑，减少两套代码分叉。

### 7.4 接受邮箱邀请

推荐流程：

1. 用户点击邮件链接进入前端邀请接受页。
2. 前端先调用“解析邀请 token”接口，显示目标 workspace 名称、邀请人、过期时间。
3. 若未登录，引导注册或登录。
4. 登录后调用“接受邀请”接口。
5. 后端校验当前用户邮箱必须等于 invitee_email。
6. 通过后创建 `workspace_member`，状态为 `ACTIVE`，`join_type = INVITE`。

### 7.5 接受共享邀请链接

推荐流程：

1. 用户点击分享链接进入邀请接受页。
2. 前端调用“解析链接 token”接口，显示 workspace 信息。
3. 用户登录后调用“接受链接邀请”接口。
4. 后端校验 link 状态、过期时间、使用次数。
5. 通过后创建 `workspace_member`。

说明：

1. 当前 `workspace_member.join_type` 只有 `OWNER / INVITE / DIRECT`，建议扩展出 `INVITE_LINK`，区分邮箱定向邀请和共享链接加入。

---

## 8. 接口草案

### 8.1 批量邮箱邀请

接口：`POST /auth/workspace-invitations/email-batch`

请求体建议：

```json
{
  "workspaceId": "uuid",
  "emails": [
    "annie@myteam.com",
    "fay@company.com",
    "henry@company.com"
  ],
  "targetRoleCode": "workspace_member",
  "sourceScene": "ONBOARDING"
}
```

成功响应建议：

```json
{
  "workspaceId": "uuid",
  "batchId": "uuid",
  "successCount": 2,
  "skippedCount": 1,
  "results": [
    {
      "email": "annie@myteam.com",
      "result": "CREATED"
    },
    {
      "email": "fay@company.com",
      "result": "ALREADY_MEMBER"
    }
  ]
}
```

### 8.2 创建或获取邀请链接

接口：`POST /auth/workspace-invitation-links`

请求体建议：

```json
{
  "workspaceId": "uuid",
  "sourceScene": "ONBOARDING",
  "targetRoleCode": "workspace_member",
  "expiresInHours": 168,
  "maxUseCount": null
}
```

成功响应建议：

```json
{
  "linkId": "uuid",
  "shareUrl": "https://app.example.com/invite/link?token=...",
  "expiresAt": "2026-04-23T12:00:00Z",
  "usedCount": 0,
  "maxUseCount": null,
  "status": "ACTIVE"
}
```

### 8.3 解析邮箱邀请

接口：`GET /auth/public/workspace-invitations/email/{token}`

用途：打开邮件邀请页前，先解析 token 是否有效。

### 8.4 接受邮箱邀请

接口：`POST /auth/workspace-invitations/email/{token}/accept`

认证要求：

1. 需要 platform token。

### 8.5 解析共享链接

接口：`GET /auth/public/workspace-invitation-links/{token}`

### 8.6 接受共享链接

接口：`POST /auth/workspace-invitation-links/{token}/accept`

认证要求：

1. 需要 platform token。

### 8.7 列出邀请记录

接口：`GET /auth/workspace-invitations?workspaceId=...&status=PENDING`

用途：供工作区成员管理页查看待接受、已接受、已过期邀请。

### 8.8 取消邮箱邀请

接口：`POST /auth/workspace-invitations/{id}/cancel`

### 8.9 关闭邀请链接

接口：`POST /auth/workspace-invitation-links/{id}/disable`

---

## 9. 校验规则建议

### 9.1 通用校验

1. 只有具备 `workspace.member.invite` 权限的成员可以发起邀请。
2. 不能邀请自己。
3. 已经是 ACTIVE 成员的邮箱不重复创建邀请。
4. 对同一 workspace + 同一邮箱，若已有 `PENDING` 邀请，默认不重复创建，可返回 `PENDING_EXISTS`。

### 9.2 邮箱邀请校验

1. 接受邀请时，当前登录用户邮箱必须与 `invitee_email` 一致。
2. 若当前登录用户无邮箱，不允许直接接受邮箱邀请，应先补齐邮箱。

### 9.3 邀请链接校验

1. 需要校验 `link_status`。
2. 需要校验 `expires_at`。
3. 若设置 `max_use_count`，需要校验 `used_count < max_use_count`。

---

## 10. 与当前成员模型的衔接

### 10.1 接受邀请后如何落库

建议：

1. 不提前创建 `workspace_member` 的 `INVITED` 占位记录。
2. 真正接受时再创建 `workspace_member`。

原因：

1. 被邀请人可能一直不注册，提前创建占位成员会污染成员列表。
2. 复制邀请链接场景本来就无法预知 invitee_user_id。

创建成员时建议写入：

1. `member_status = ACTIVE`
2. `join_type = INVITE` 或 `INVITE_LINK`
3. `invited_by_user_id = 邀请人`
4. `joined_at = accepted_at`

### 10.2 默认角色分配

第一阶段建议：

1. 所有邀请接受后默认分配 `workspace_member` 角色。
2. 后续如果需要邀请时选角色，再开放 `target_role_code` 的前端选择能力。

---

## 11. 推荐分阶段落地

### Phase 1

1. 先实现工作区内单个邮箱邀请。
2. 复用 `workspace_invitation`。
3. 支持邮件发送、邀请接受、邀请取消。

### Phase 2

1. 在 onboarding 页面接入批量邮箱邀请。
2. 增加批次返回结构和部分失败结果。

### Phase 3

1. 新增 `workspace_invitation_link`。
2. 支持复制分享链接与链接接受。
3. 增加链接使用日志。

---

## 12. 结论

基于当前数据库设计，推荐这样演进：

1. 邮箱邀请继续以 `workspace_invitation` 为核心。
2. 复制邀请链接新增独立的 `workspace_invitation_link`，不要和邮箱邀请混表。
3. 接受邀请后统一落到 `workspace_member`，保持成员体系单一。
4. onboarding 页面上的“批量邀请”和“复制邀请链接”在后端应拆成两个能力，不建议揉进创建 workspace 事务。

这样既能匹配你现在的前端页面，也不会把当前数据库设计拉向难以维护的混合模型。
