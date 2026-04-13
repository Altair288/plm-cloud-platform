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
