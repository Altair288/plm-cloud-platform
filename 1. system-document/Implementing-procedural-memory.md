# 实现过程记忆

## 2026-04-10 用户域与 Workspace 数据库落地

- 已新增数据库设计草案 user-workspace-rbac-database-design-draft.md，作为用户域 schema / 表 / 字段 / 索引的唯一数据库说明来源。
- 已将基础设计稿中数据库细节收敛为摘要 + 引用，避免双份定义漂移。
- 当前阶段用户域核心控制面表统一落在 plm_platform，不进入 plm_runtime。
- plm_runtime 当前定位已明确为：共享数据库、共享表结构、按 workspace_id 做逻辑隔离，而不是每个 workspace 独库。
- 后续 Flyway 落地优先事项：先创建 plm_platform 与 user/workspace/member/role/permission 相关表；plm_runtime 先落实 schema 命名与隔离约束，不在本轮展开完整业务表重建。
- 仓库现状确认：Flyway 仍是单数据源单迁移链，旧 schema 以 plm_meta + plm 为主；本轮不直接重命名 plm，避免误伤历史运行态基线与潜在兼容链路。
- 本轮落地策略：通过新增 V33 迁移创建 plm_platform 与 plm_runtime，并在 plm_platform 中落地 user_account、user_credential、workspace、workspace_member、permission、platform_role、platform_user_role、platform_role_permission、workspace_role、workspace_member_role、workspace_role_permission、workspace_invitation、login_audit。
- FlywayConfig 已同步规划为显式管理 plm_meta、plm、plm_platform、plm_runtime 四个 schema；旧 plm 继续保留作为过渡，不在本轮执行 schema rename。
- 本轮编辑器校验结果：FlywayConfig.java、V33__platform_workspace_rbac_foundation.sql、user-workspace-rbac-database-design-draft.md、Implementing-procedural-memory.md 均无报错。

## 2026-04-10 用户域基础流程设计

- 已新增 user-workspace-auth-basic-flow-design-draft.md，明确第一阶段最小闭环：注册、密码登录、创建 workspace、切换 workspace。
- 当前决策：注册只创建 user_account + user_credential，不自动创建 workspace。
- 当前决策：登录先建立 platform 登录态，不强制立即进入某个 workspace。
- 当前决策：创建 workspace 成功后，自动创建 owner member、内建 workspace 角色、owner 角色分配，并自动切换进入新 workspace。
- 当前决策：切换 workspace 使用独立 workspace 上下文态，建议采用 Sa-Token loginType = workspace，与 platform 登录态分离。
- 当前决策：默认 workspace 通过 workspace_member.is_default_workspace 持久化，切换接口支持 rememberAsDefault。

## 2026-04-10 用户域代码实现进行中

- 已在 plm-common 中新增 auth DTO 与基础用户域实体：UserAccount、UserCredential、Workspace、WorkspaceMember、WorkspaceRole、WorkspaceMemberRole、LoginAudit。
- 已在 plm-infrastructure 中新增 auth Repository，覆盖 user/credential/workspace/member/role/member-role/login-audit 基础读写。
- 已在 plm-auth-service 中补齐依赖与扫描配置，接入 plm-infrastructure 和 JPA 实体扫描。
- 已开始实现最小闭环接口：/auth/public/register、/auth/public/login/password、/auth/me、/auth/workspaces、/auth/workspace-session/switch、/auth/logout。
- 当前会话实现策略：平台登录态使用 Sa-Token loginType = platform；workspace 上下文态使用 loginType = workspace；当前阶段 workspace 侧上下文主要通过切换接口建立，并在响应体中返回 workspace token。
- 当前编辑器校验结果：plm-auth-service、plm-common/auth、plm-infrastructure/auth-repository 新增代码均无报错；本轮尚未补自动化测试用例。

## 2026-04-10 用户域集成测试

- 已为 plm-auth-service 补充 spring-boot-starter-test 测试依赖。
- 已新增 AuthFlowControllerIT，覆盖注册、密码登录、创建 workspace、切换 workspace、查询当前会话主链路。
- 测试策略遵循 VS Code 编辑器侧内置测试执行，不走命令行 Maven 作为首选验证路径。
- 集成测试首轮暴露出一个实现细节：仅依赖 workspace token 恢复当前空间态在 /auth/me 场景下不稳定；当前已改为在切换或创建 workspace 时，同步把 currentWorkspaceMemberId 写入 platform session，用于恢复当前上下文。
- 当前状态：AuthFlowControllerIT 已通过 VS Code 内置测试，结果为 3 passed / 0 failed。

## 2026-04-10 Workspace 默认权限绑定

- 已新增 Permission 与 WorkspaceRolePermission 实体及对应 Repository。
- 创建 workspace 时，系统除初始化内建角色外，还会自动为 workspace_owner、workspace_admin、workspace_member、workspace_viewer 绑定默认权限。
- 当前集成测试已补充断言，确认 workspace_owner 至少持有 workspace.config.update 与 runtime.export.execute 等关键权限。

## 2026-04-10 auth-service 接口收口与异常测试

- auth-service 已补 current workspace session 管理接口：GET /auth/workspace-session/current 与 DELETE /auth/workspace-session/current。
- WorkspaceSessionService 已抽出统一上下文校验与当前 session 读取逻辑，后续 workspace 侧接口可以直接复用。
- AuthFlowControllerIT 已补异常分支覆盖：重复注册、错误密码登录、非成员切换 workspace、禁用账号登录。
- 当前 VS Code 内置测试结果：AuthFlowControllerIT 已提升为 7 passed / 0 failed，覆盖主链路与关键异常分支。

## 2026-04-10 auth-service 最终异常测试与前端对接文档

- AuthFlowControllerIT 已继续补充未登录访问受保护接口、inactive 成员切换 workspace、frozen workspace 切换等分支。
- 当前 VS Code 内置测试最终结果：AuthFlowControllerIT 为 10 passed / 0 failed。
- 已新增 system-user-api-document/auth-service-frontend-integration.md，作为前端对接 auth-service 的正式接口说明。
- 文档已明确平台 token 与 workspace token 的职责边界、登录后初始化顺序、workspace 创建与切换顺序、204 current workspace 空态处理方式，以及常见错误码的前端处理建议。

## 2026-04-13 注册邮箱验证码与验证码表关联修正

- 当前注册流程已新增邮箱验证码发送接口：POST /auth/public/register/email-code。
- email_verification_code 的设计已从“纯邮箱锚点无关系”调整为“注册前以 target_email 为业务锚点，注册成功后通过 consumed_by_user_id 回填关联 user_account”。
- 该调整的原因是：注册验证码天然发生在 user_account 创建之前，发送阶段无法强制依赖用户外键；但成功消费后需要有能力追溯验证码归属，因此新增 consumed_by_user_id 作为后置外键关联。
- 已新增 Flyway 迁移 V35__auth_email_verification_consumed_user_fk.sql，用于给现有数据库补 consumed_by_user_id 列、外键和索引。
- AuthFlowControllerIT 已补充对验证码成功消费结果的断言，确认注册成功后验证码状态为 USED，且 consumed_by_user_id 正确回填为新用户 ID。
- 当前 VS Code 内置测试结果：AuthFlowControllerIT 为 12 passed / 0 failed。
- 已同步更新 user-workspace-rbac-database-design-draft.md、user-workspace-auth-basic-flow-design-draft.md 和 system-user-api-document/auth-service-frontend-integration.md，使实现、数据库设计和前端联调文档保持一致。

## 2026-04-13 gateway 本地多服务访问配置草案与实现

- 已确定本地开发端口规划：plm-gateway 使用 8080，plm-auth-service 使用 8081，plm-attribute-service 使用 8082。
- 已确认 auth-service 的对外接口统一前缀为 /auth/**，attribute-service 的对外接口统一前缀为 /api/meta/**。
- 已在 plm-gateway 下新增 1. gateway-document/gateway-auth-attribute-routing-design-draft.md，用于记录本地开发阶段的 gateway 路由设计草案。
- 已在 plm-gateway 下新增 1. gateway-document/Implementing-procedural-memory.md，用于记录 gateway 自身实现过程记忆。
- 当前 gateway 路由策略为保留原始路径转发：`/auth/** -> http://localhost:8081`，`/api/meta/** -> http://localhost:8082`。
- 当前阶段不引入 StripPrefix、服务发现、统一鉴权过滤器和限流，先完成本地前后端联调闭环。
- 父 pom 已引入 spring-cloud-dependencies 2025.0.0 BOM，gateway 已切换为不显式指定 spring-cloud-starter-gateway-server-webflux 版本的依赖写法。
- gateway 已补全本地联调用的全局 CORS 配置，允许 localhost 与 127.0.0.1 不同端口访问 8080 网关。
- gateway 已新增终端访问日志 GlobalFilter，用于直接观察接口调用路径、命中 routeId、状态码和耗时。
- Spring Cloud Gateway 2025.0.x 下 dev 配置 key 已迁移到 `spring.cloud.gateway.server.webflux.*`，避免运行期 properties migration 警告。
- gateway 已新增最小 JSON 异常处理：未命中路由返回 `GATEWAY_ROUTE_NOT_FOUND`，下游不可达返回 `GATEWAY_DOWNSTREAM_UNAVAILABLE`，下游已返回的业务错误继续原样透传。
- auth 前端对接文档与 attribute API 文档已补充“前端统一通过 gateway 8080 访问”的 base URL 约定。

## 2026-04-16 用户首次登录与 workspace 数量状态

- 已为 `plm_platform.user_account` 增加 `is_first_login` 与 `workspace_count` 字段，并通过 `V36__auth_user_first_login_and_workspace_count.sql` 回填历史数据。
- 语义约定：`is_first_login` 表示用户是否尚未完成首次 workspace 建立；一旦首次成功创建 workspace，就永久翻转为 `false`。
- `workspace_count` 表示用户当前活跃 workspace 数，供前端处理“用户后来删光所有 workspace”的空态分支，避免停留在空白页。
- 登录与 `/auth/me` 查询链路已接入 `UserWorkspaceStateService`，会基于 `workspace_member` 活跃记录做一次自愈同步，降低未来删除或迁移逻辑导致字段漂移的风险。
- `AuthUserSummaryDto` 与前端 `auth.ts` 类型已同步增加 `isFirstLogin`、`workspaceCount` 字段。

## 2026-04-16 workspace 类型 / 语言 / 时区目录化

- 已通过 `V37__auth_workspace_type_locale_timezone_catalogs.sql` 新增 `plm_platform.workspace_type`、`workspace_locale`、`workspace_timezone` 三张目录表，并为 `workspace.workspace_type`、`default_locale`、`default_timezone` 增加外键约束。
- workspace 类型首批固定为 `TEAM`、`PERSONAL`、`LEARNING`；语言首批为 `zh-CN`、`en-US`；时区首批为 `Asia/Shanghai`、`UTC`、`America/Los_Angeles`。
- 历史 `workspace.workspace_type = DEFAULT` 已在迁移中统一映射到 `TEAM`，随后再补外键，避免旧数据挡住上线。
- auth-service 新增公开引导接口 `GET /auth/public/workspace-bootstrap-options`，前端 workspace 创建页必须通过该接口读取可选类型、语言和时区，不再本地硬编码。
- `AuthWorkspaceOptionDto` 与 `AuthWorkspaceSessionResponseDto` 已补充 `workspaceType`、`defaultLocale`、`defaultTimezone`，登录后的 `workspaceOptions`、`defaultWorkspace`、`currentWorkspace` 现在都会返回这三项信息。
- `WorkspaceCommandService` 已切换为通过 `WorkspaceDictionaryService` 解析和校验目录 code，非法 `workspaceType` / locale / timezone 会直接拒绝。
- 前端 `WorkspaceCreationOnboarding` 已改为基于 bootstrap 接口动态渲染 workspace type 卡片、语言和时区选项，`login/page.tsx` 也已改用 `isFirstLogin` / `workspaceCount` 做创建页跳转判定。

## 2026-04-16 workspace_code 改为系统生成

- `POST /auth/workspaces` 的请求 DTO 已移除 `workspaceCode` 字段，前端创建 workspace 时只提交名称、类型、语言、时区和默认标记。
- 新增 `WorkspaceCodeGenerationService`，当前默认规则为 `ws_{ownerUserId8}_{workspaceNameSlug}_{workspaceId8}`，其中 owner 和 workspace 都取 UUID 去连字符后的前 8 位。
- 为避免用户名改名带来的稳定性风险，workspace_code 生成规则已彻底移除对 username 的依赖。
- `workspaceName` 会先做 slug 归一化；如果归一化后为空，则回退到 `workspaceType` 的小写值，保证纯中文等场景仍能生成可用 code。
- `workspaceCode` 继续作为响应字段返回，用于日志、路由、审计和系统内部稳定标识，但不再作为前端输入项。
- AuthFlowControllerIT 已补充对系统生成 code、同名 workspace 生成不同 code 等场景的覆盖。

## 2026-04-16 邀请功能草案

- 已新增 `user-workspace-invitation-design-draft.md`，结合当前 `workspace_invitation` 设计补充了批量邮箱邀请、复制邀请链接、工作区内单个邮箱邀请三条主链路的推荐方案。

## 2026-04-16 邀请功能三阶段实现落地与文档收口

- `plm-auth-service` 已完成邀请功能三阶段落地，覆盖邮箱批量邀请、邮箱邀请预览与接受、邀请记录查询、邀请取消、分享链接创建、链接预览、链接接受、链接禁用。
- `plm-common`、`plm-infrastructure`、`plm-auth-service` 已同步补齐 invitation DTO、实体、Repository、Flyway 迁移与邮件模板能力；当前邀请主数据落在 `workspace_invitation`、`workspace_invitation_link`、`workspace_invitation_link_accept_log`。
- 邀请接受成功后，后端会直接创建或复用 workspace_member，并立即建立当前 workspace session；邮箱邀请写入 `joinType = INVITE`，分享链接写入 `joinType = INVITE_LINK`。
- 本轮联调中曾出现邀请相关接口全部返回 400 的问题，根因不是业务逻辑，而是当前编译配置下 Spring MVC 不应依赖参数名推断；后续新增 controller 参数时统一显式写 `@PathVariable("token")`、`@PathVariable("id")`、`@RequestParam("workspaceId")` 这一类声明。
- `AuthFlowControllerIT` 当前已提升到 23 passed / 0 failed，新增覆盖邮箱不匹配拒绝、取消后不可接受、禁用链接不可接受、单次链接首次使用后自动过期等边界场景。
- `system-user-api-document/auth-service-frontend-integration.md` 已新增统一的邀请接口版块，集中说明邀请鉴权、预览、接受、状态机、逐项结果语义与前端错误处理，不再把邀请接口散写在其他章节。

## 2026-04-17 登录 remember 选项与 token 有效期

- `POST /auth/public/login/password` 已新增可选字段 `remember`，用于区分普通登录与“记住登录”两档平台 token 有效期。
- auth-service 已新增 `plm.auth.login.expire-in-seconds` 与 `plm.auth.login.remember-expire-in-seconds` 配置；当前 dev 默认分别为 12 小时和 30 天，并支持环境变量覆盖。
- `AuthLoginService` 已改为通过 Sa-Token `SaLoginModel` 显式设置平台 token timeout，而不是继续使用框架默认登录时长。
- 登录响应已新增 `remember` 与 `platformTokenExpireInSeconds`，前端应以后端返回的实际有效期管理本地 token 生命周期，而不是自行写死 TTL。
- `AuthFlowControllerIT` 已补 remember 登录分支，校验普通登录与 remember 登录返回的有效期差异，并校验平台 token 实际超时配置已生效。

## 2026-04-17 注册与登录密码改为 RSA 非对称加密传输

- auth-service 已新增公开接口 `GET /auth/public/security/password-encryption-key`，用于给前端下发当前 password transport RSA 公钥、keyId 与加密 transformation。
- `POST /auth/public/register` 与 `POST /auth/public/login/password` 当前优先接收 `passwordCiphertext` / `confirmPasswordCiphertext` 与 `encryptionKeyId`，由后端解密后再进入原有密码校验与 BCrypt 哈希流程。
- 当前 `plm.auth.password-rsa.allow-plaintext-fallback=false`，表示明文 `password` 在当前配置下会被拒绝，避免前端“名义支持加密，实际仍传明文”。
- RSA 实现当前使用 `RSA/ECB/OAEPWithSHA-256AndMGF1Padding`；若未通过环境变量注入固定公私钥，auth-service 会在启动时生成一组临时密钥用于本地联调。
- `AuthFlowControllerIT` 的注册与登录 helper 已切到“先拉公钥、再加密密码提交”的真实链路，并补充了公钥接口可用、明文登录被拒绝等安全测试。
- 额外排查确认：仅传 `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` 给 JCE 并不能保证 MGF1 摘要也一定是 SHA-256；当前实现与测试已改为显式传入 `OAEPParameterSpec(SHA-256, MGF1, SHA-256, default)`，避免落成 SHA-256 + MGF1(SHA-1) 的混合模式。

## 2026-04-17 RSA 密钥对改为 Redis 24 小时持久化

- auth-service 已引入 `spring-boot-starter-data-redis`，并为 password transport RSA 增加 Redis key store 配置：`redis-key-prefix`、`redis-ttl-seconds`。
- 当前默认策略为：未显式配置固定 PEM 公私钥时，GET `/auth/public/security/password-encryption-key` 会先查 Redis 当前活动 keyId；若不存在则生成新密钥对，写入 Redis，并设置 24 小时 TTL。
- 注册与登录解密流程已改为按请求中的 `encryptionKeyId` 从 Redis 读取对应私钥，而不是继续依赖服务启动时保存在内存中的单例私钥。
- 当前实现已收口为“未配置固定 PEM 时必须使用 Redis”；如果 Redis 不可达或认证失败，公钥接口与解密流程会直接报错，不再回退到进程内临时密钥。
- `application-dev.yml` 已补充 `spring.data.redis.password`，当前默认开发密码为 `p@ssw0rd@2025`，并支持通过 `PLM_AUTH_REDIS_PASSWORD` 覆盖。
- 新增 `PasswordTransportSecurityServiceTest`，覆盖 Redis 模式下的 keyId 复用、TTL 写入和私钥解密链路；当前 Maven 聚合验证结果为 `AuthFlowControllerIT 27 passed / 0 failed`、`PasswordTransportSecurityServiceTest 2 passed / 0 failed`、`RegisterEmailTemplateRendererTest 3 passed / 0 failed`。
