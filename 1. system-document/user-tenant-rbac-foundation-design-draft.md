# 用户、租户、角色与权限基础设计草案

更新时间：2026-04-09
阶段：基础设计草案（本阶段聚焦用户域，不直接进入审批回写、工作流与复杂协同）

---

## 1. 背景

当前平台后端已经明确区分两类数据语义：

- plm_meta：平台侧标准元数据，面向标准分类、属性、LOV、编码规则模板等公共资产。
- plm_runtime：租户侧运行数据，面向租户成员、租户配置、租户实例化后的业务数据与后续企业私有扩展。

现阶段需要优先落地的不是“元数据回写治理”或“复杂审批流”，而是整个平台后续一切能力的前置基础：

- 用户注册后不再只是单个账号，而是要形成一个租户空间。
- 一个租户下需要具备管理员、普通成员等身份区分。
- 平台管理员视角与租户用户视角必须严格分离。
- 权限不仅要回答“能做什么”，还要回答“在哪个租户里做”。

因此，本草案的核心目标是定义用户域第一阶段的基础模型，为后续元数据抽取、租户自定义、运行态业务数据隔离打基础。

---

## 2. 当前约束与前提

### 2.1 当前实施前提

- runtime 库已准备重新设计，不保留旧运行态内容。
- 运行态正式命名统一使用 plm_runtime，不再沿用旧的 plm 运行 schema 命名。
- 当前阶段只讨论用户、租户、角色、权限、视角隔离与租户上下文。
- 元数据回写、审批流、跨租户协同、复杂组织树不是本阶段实现范围。

### 2.2 当前仓库现状

- auth 服务目前仍处于骨架阶段，仅有 Sa-Token 的最薄接入。
- 旧运行态 V1 基线里的 user_account、role、permission、user_role 等模型是全局 RBAC 雏形，不能直接满足租户化要求。
- 当前基础设施仍偏单数据源思路，后续可继续演进，但本草案不把“多数据源路由”作为第一阶段前置条件。

### 2.3 本草案采用的正式术语

- 用户：全局账号主体，对应一个自然人或系统主体。
- 租户：注册后形成的独立业务空间，是运行态隔离的基础单位。
- 成员：某个全局用户在某个租户中的身份实例。
- 平台角色：只在平台视角下生效的角色。
- 租户角色：只在租户视角下生效的角色。
- 权限：细粒度操作能力标识。
- 作用域：权限生效范围，至少区分 GLOBAL 与 TENANT。

说明：

- business_domain 继续保留为元数据/业务领域维度。
- tenant 不等于 business_domain。
- permission scope 不等于 business_domain。

这三个概念必须严格分离。

---

## 3. 设计目标

### 3.1 必须实现的目标

- 支持“注册即创建租户”的 SaaS 化入口能力。
- 支持平台管理员与租户用户双视角隔离。
- 支持同一全局用户加入多个租户，并在不同租户下拥有不同身份。
- 支持租户内成员管理与租户角色分配。
- 支持权限按租户作用域生效，而不是全局平铺。
- 支持运行态数据后续统一按 tenant_id 进行隔离。
- 支持基于 Sa-Token 的双登录体系和权限校验基线。

### 3.2 必须避免的问题

- 不能把“全局用户角色”错误地当成“租户成员角色”。
- 不能让平台管理员与租户用户共用一套模糊 token 语义。
- 不能继续在运行态表上缺失 tenant_id，否则后续租户隔离无法收敛。
- 不能把回写审批、组织树、属性级权限一次性塞进第一阶段，导致边界失控。

---

## 4. 非目标

以下内容明确不在本阶段基础设计范围内：

- 元数据回写审批流。
- Flowable、Camunda 等流程引擎选型。
- 租户内部组织架构树、部门树、岗位树。
- 属性级、字段级、行级高级权限控制。
- 跨租户共享与协作。
- OAuth2、OIDC、LDAP、企业单点登录等外部身份源集成。
- 平台侧运营、计费、订阅、套餐能力。
- 多级角色模板市场化配置。

这些能力未来可以建立在本草案之上继续演进，但不作为第一阶段基础设计前提。

---

## 5. 核心设计原则

### 5.1 全局账号与租户成员分离

系统中“账号”与“成员身份”不是一回事。

- 全局用户只负责认证主体、凭证、基础资料。
- 租户成员才负责租户内身份、状态、角色、上下文。

同一个人可以：

- 在 A 租户是 tenant_admin。
- 在 B 租户是 tenant_member。
- 同时还可能具备平台侧 operator 或 platform_admin 视角。

### 5.2 角色按视角分层，而不是混成一张无语义表

角色必须至少区分：

- 平台角色：面向平台管理端。
- 租户角色：面向租户工作台。

如果不做这层拆分，后续在菜单、接口、数据范围、审计责任上都会越来越混乱。

### 5.3 权限按作用域生效

权限校验必须具备“当前在哪个作用域下操作”的上下文信息。

- GLOBAL：平台管理作用域。
- TENANT：租户业务作用域。

同一个权限码是否可用，不仅取决于用户是谁，也取决于当前上下文是否绑定了合法租户成员关系。

### 5.4 运行态数据最终统一走 tenant_id 隔离

本阶段虽然先聚焦用户域，但目标必须明确：

- plm_runtime 中的运行态核心业务表最终都应带 tenant_id。
- 任何租户侧接口都不能依赖前端显式传入任意 tenant_id 作为最终信任来源。
- 当前操作租户必须来自已认证上下文与成员关系校验结果。

### 5.5 平台视角与租户视角必须物理或逻辑强隔离

平台与租户不是简单菜单不同，而是边界完全不同的两类身份域：

- 平台视角：全局治理、租户管理、平台审计、标准元数据管理。
- 租户视角：本租户成员管理、租户内元数据使用、租户业务执行。

---

## 6. 视角模型

### 6.1 平台视角

平台视角的职责包括：

- 管理平台管理员账号。
- 管理租户生命周期。
- 审计全局运行情况。
- 维护 plm_meta 标准资源。

平台视角特点：

- 不依赖当前 tenant_id 才能访问核心平台接口。
- 使用平台侧登录体系。
- 权限来自平台角色，而不是租户角色。

### 6.2 租户视角

租户视角的职责包括：

- 管理本租户成员。
- 分配本租户角色。
- 配置本租户规则。
- 执行本租户业务。

租户视角特点：

- 必须绑定当前 tenant_id。
- 必须能在运行时确认当前用户与当前租户存在有效成员关系。
- 权限来自租户角色，而不是平台角色。

### 6.3 双视角关系

系统必须允许：

- 平台管理员单独登录平台端。
- 普通用户单独登录租户端。
- 后续如业务需要，再扩展“平台管理员进入租户观察态/代理态”的能力。

但第一阶段不要求实现复杂代理登录，只需先把双视角边界定义清楚。

---

## 7. 核心模型草案

### 7.1 全局用户 UserAccount

职责：

- 存储全局登录凭证。
- 存储账号级基础信息。
- 作为平台角色与租户成员的共同主体。

建议关键字段：

- id
- username
- password_hash
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

- 这是全局主体，不携带 tenant_id。
- 一个 UserAccount 可以对应多个 TenantMember。

### 7.2 租户 Tenant

职责：

- 表示独立业务空间。
- 承载租户级配置、状态、生命周期。

建议关键字段：

- id
- tenant_code
- tenant_name
- tenant_status
- owner_user_id
- default_locale
- default_timezone
- config_json
- created_at
- created_by
- updated_at
- updated_by

说明：

- tenant_code 应为系统内稳定标识，不建议直接依赖 tenant_name。
- owner_user_id 仅表示默认拥有者，不替代成员关系表。

### 7.3 租户成员 TenantMember

职责：

- 表示某用户在某租户中的成员身份。
- 承担租户上下文绑定的核心职责。

建议关键字段：

- id
- tenant_id
- user_id
- member_status
- join_type
- joined_at
- invited_by
- is_default_member
- remark
- created_at
- created_by
- updated_at
- updated_by

建议约束：

- tenant_id + user_id 唯一。

说明：

- 是否属于租户，看 TenantMember，不看 UserAccount。
- 后续切换当前租户时，本质上是切换当前使用的 TenantMember。

### 7.4 平台角色 PlatformRole

职责：

- 定义平台视角权限集合。

建议角色示例：

- platform_super_admin
- platform_admin
- platform_operator
- platform_auditor

### 7.5 平台用户角色 PlatformUserRole

职责：

- 给全局用户分配平台角色。

说明：

- 这是 UserAccount 到 PlatformRole 的关联。
- 不绑定 tenant_id。

### 7.6 租户角色 TenantRole

职责：

- 定义租户内权限模板。

建议角色示例：

- tenant_owner
- tenant_admin
- tenant_member
- tenant_viewer

建议关键字段：

- id
- tenant_id（若允许租户自定义角色）
- role_code
- role_name
- role_type（SYSTEM / CUSTOM）
- role_status
- built_in_flag
- created_at
- created_by

说明：

- 若第一阶段先不上租户自定义角色，也可以先做全局内建模板，再通过 tenant_member_role 直接绑定内建角色。
- 若第一阶段希望保留扩展空间，则 TenantRole 可以直接设计成 tenant 级角色表。

### 7.7 租户成员角色 TenantMemberRole

职责：

- 给某个租户成员分配一个或多个租户角色。

建议关键字段：

- tenant_member_id
- tenant_role_id
- assigned_at
- assigned_by

### 7.8 权限 Permission

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

- platform.tenant.read
- platform.tenant.create
- platform.tenant.freeze
- platform.user.read
- platform.user.assign-role
- tenant.member.read
- tenant.member.invite
- tenant.member.disable
- tenant.role.assign
- tenant.role.read
- runtime.import.execute
- runtime.export.execute
- runtime.config.manage

### 7.9 角色权限关系

建议拆分为：

- platform_role_permission
- tenant_role_permission

不建议继续使用无作用域语义的一张 role_permission 统包全部关系。

---

## 8. 推荐关系模型

### 8.1 用户与租户

- 一个 UserAccount 可以加入多个 Tenant。
- 一个 Tenant 可以有多个 TenantMember。

关系表达：

- UserAccount 1 - N TenantMember
- Tenant 1 - N TenantMember

### 8.2 用户与平台角色

- 一个 UserAccount 可以拥有多个 PlatformRole。
- 平台角色只在平台视角下生效。

### 8.3 成员与租户角色

- 一个 TenantMember 可以拥有多个 TenantRole。
- 租户角色只在该成员所属租户下生效。

### 8.4 权限生效规则

平台接口：

- 用户已登录平台端。
- 用户拥有匹配的平台角色。
- 平台角色映射出所需 GLOBAL 权限。

租户接口：

- 用户已登录租户端。
- 当前上下文已绑定 tenant_id。
- 当前用户在该 tenant_id 下存在有效 TenantMember。
- TenantMember 拥有对应 TenantRole。
- TenantRole 映射出所需 TENANT 权限。

---

## 9. Sa-Token 基础设计草案

### 9.1 双登录体系

第一阶段建议直接采用双 loginType，而不是单 StpUtil 混用：

- 平台端 loginType：platform
- 租户端 loginType：tenant

设计目的：

- 平台 token 与租户 token 语义隔离。
- 平台接口与租户接口可分别做鉴权拦截。
- 避免后期接口边界越来越模糊。

### 9.2 平台端 token 载荷建议

- loginId
- loginType = platform
- userId
- platformRoleSnapshot 或 roleVersion

平台端 token 不要求强绑定 tenant_id。

### 9.3 租户端 token 载荷建议

- loginId
- loginType = tenant
- userId
- currentTenantId
- currentTenantMemberId
- tenantRoleSnapshot 或 roleVersion

说明：

- currentTenantId 应作为当前租户上下文的可信来源之一。
- 但服务端仍应校验该用户与该租户成员关系是否有效，不能只信任前端传值。

### 9.4 鉴权分层建议

建议至少分成三层：

- 认证：是否已登录对应 loginType。
- 作用域：当前接口要求的是 platform 还是 tenant 作用域。
- 权限：当前主体在当前作用域下是否具备指定 permission。

### 9.5 当前租户切换

由于同一用户可加入多个租户，租户端必须支持“切换当前租户上下文”。

第一阶段建议：

- 显式提供 switch tenant 接口。
- 切换后更新租户端会话中的 currentTenantId 与 currentTenantMemberId。
- 所有 tenant 接口默认从会话中读取当前租户，而不是依赖每个接口都传 tenant_id。

---

## 10. 数据隔离原则

### 10.1 用户域表的隔离方式

建议如下：

- UserAccount：全局表，不带 tenant_id。
- Tenant：全局表。
- TenantMember：天然带 tenant_id。
- TenantRole：若做租户自定义角色，则天然带 tenant_id。

### 10.2 运行态业务表的隔离方式

第一阶段虽不改全业务域，但设计上必须统一目标：

- plm_runtime 中所有租户侧业务核心表应逐步补齐 tenant_id。
- 所有查询最终必须收敛到按 tenant_id 过滤。

### 10.3 平台元数据与运行态数据边界

- plm_meta 不接受租户端直接写入。
- plm_runtime 是租户使用与扩展的落点。
- 本草案阶段不涉及回写审批能力，因此不在本稿定义跨库写入策略。

---

## 11. 第一阶段范围定义

### 11.1 In Scope

- 全局用户注册。
- 注册即创建租户。
- 创建默认租户拥有者或管理员成员。
- 平台端与租户端双登录体系。
- 平台角色、租户角色、权限基础模型。
- 租户成员管理。
- 当前租户上下文切换。
- 基础的 tenant 级权限校验。
- 运行态后续 tenant_id 隔离的统一约束定义。

### 11.2 Out of Scope

- 回写审批。
- 工作流引擎。
- 元数据版本升级通知。
- 组织树与部门层级授权。
- 字段级敏感权限。
- 数据共享与协作。
- 复杂套餐、订阅、计费能力。

---

## 12. 第一阶段建议内建角色

### 12.1 平台侧内建角色

- platform_super_admin：拥有全部平台能力。
- platform_admin：具备大部分平台管理能力，不含超级危险操作。
- platform_operator：偏运营与租户处理。
- platform_auditor：只读审计视角。

### 12.2 租户侧内建角色

- tenant_owner：租户拥有者，最高租户权限。
- tenant_admin：租户管理员，可管理成员与租户配置。
- tenant_member：标准成员，可执行常规业务。
- tenant_viewer：只读成员。

说明：

- 第一阶段建议先以内建角色为主。
- 是否开放租户自定义角色，可以作为第二阶段能力评估项。

---

## 13. 第一阶段建议权限分组

### 13.1 平台权限

- platform.tenant.read
- platform.tenant.create
- platform.tenant.update
- platform.tenant.freeze
- platform.user.read
- platform.user.assign-role
- platform.audit.read

### 13.2 租户权限

- tenant.member.read
- tenant.member.invite
- tenant.member.disable
- tenant.member.assign-role
- tenant.profile.read
- tenant.profile.update
- tenant.config.read
- tenant.config.update
- runtime.import.execute
- runtime.export.execute

说明：

- 第一阶段先做到页面级、菜单级、接口级能力控制。
- 更细粒度的资源级授权后续再扩展。

---

## 14. 对数据库草案的直接影响

基于本草案，运行态旧表结构不建议原样沿用，至少需要重构为以下方向：

- user_account：保留，但重新定位为全局账号表。
- role：不建议继续作为无作用域统一角色表直接使用。
- permission：可保留为权限字典表，但需增强 scope_type。
- user_role：不建议继续承担全部关联职责。

建议新增或替换的核心表包括：

- tenant
- tenant_member
- platform_role
- platform_user_role
- tenant_role
- tenant_member_role
- platform_role_permission
- tenant_role_permission

若为了迁移平滑，也可在第一版保留 permission 表不拆，仅拆角色与关联关系。

---

## 15. 未决问题

以下问题需要在后续评审中逐项明确：

### 15.1 注册模型

- 是否所有自注册都必须自动创建一个新租户。
- 是否允许“仅注册账号，等待他人邀请加入租户”。

### 15.2 租户角色开放程度

- 第一阶段是否只允许内建角色。
- 是否允许租户管理员自定义角色。

### 15.3 平台管理员进入租户观察态

- 第一阶段是否支持平台管理员切换到租户视角排障。
- 若支持，是代理登录还是只读观察态。

### 15.4 登录入口与前端视图拆分方式

- 平台端与租户端是否采用不同路由前缀。
- 是否采用独立前端入口或同前端双工作台。

### 15.5 历史运行态表重建顺序

- tenant_id 补齐的优先级如何安排。
- 哪些运行态表第一阶段必须同步改造，哪些可以后置。

---

## 16. 本草案结论

本阶段用户域的正确起点不是“在旧 RBAC 表上小修小补”，而是明确建立以下四层：

- 全局账号层
- 租户层
- 租户成员层
- 分视角角色权限层

只有先把这四层立稳，后续的：

- 租户内成员管理
- 元数据抽取后的租户化使用
- 运行态业务数据隔离
- 未来回写审批与治理

才有稳定的上下文边界与权限基础。

因此，第一阶段的实现建议应围绕“用户、租户、成员、角色、权限、双视角登录、当前租户上下文”展开，而不是过早进入回写、流程与协同能力。
