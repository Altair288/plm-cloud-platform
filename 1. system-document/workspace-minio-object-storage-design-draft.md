# Workspace 统一 MinIO 对象存储设计草案

更新时间：2026-06-04  
阶段：对象存储基础设计草案（当前阶段服务于平台统一配置 MinIO、每个 Workspace 一个 Bucket、数据库维护元数据、后端统一鉴权、Presigned URL 上传下载）

---

## 1. 目标与范围

本稿用于给当前平台补一套统一的对象存储基础方案，覆盖以下核心约束：

- 平台管理员统一配置 MinIO 集群连接。
- 平台管理员初次配置时可以通过管理员专用测试 Bucket 验证连接、上传、下载链路。
- 每个 Workspace 拥有独立 Bucket。
- 数据库存储 Bucket 与文件对象元数据，不在数据库中保存二进制文件内容。
- 前端不能直接持有 MinIO AK/SK，所有访问都先经过后端鉴权。
- 文件上传下载统一通过 Presigned URL 完成。

本稿当前重点回答以下问题：

- MinIO 集群配置由谁管理，配置元数据放哪里。
- 管理员初次配置时的测试 Bucket 与测试上传/下载链路如何设计。
- Workspace 与 Bucket 的绑定关系如何建模。
- 对象元数据表放在控制面还是运行态。
- 上传、下载、删除、Workspace 创建/归档时的核心状态流转如何设计。
- 后端接口与权限边界应该如何切分。

本稿不覆盖：

- CDN 加速。
- 跨区域多活对象存储。
- 病毒扫描、DLP、内容审核等高级治理。
- 文档在线预览转换链路。
- 对象版本控制的完整实现。
- 多云对象存储切换的最终实现细节。

---

## 2. 核心结论

### 2.1 总体方案

当前推荐采用：

- 一个平台统一管理的 MinIO 集群。
- 每个存储集群额外保留一个仅平台管理员可访问的私有测试 Bucket。
- 一个 Workspace 对应一个私有 Bucket。
- Bucket 元数据与集群配置元数据放在 `plm_platform`。
- 文件对象元数据放在 `plm_runtime`，并以 `workspace_id` 做逻辑隔离。
- 上传下载链路统一为“后端鉴权 + 生成短时 Presigned URL + 客户端直传/直下 MinIO”。

### 2.2 当前阶段不推荐的做法

当前不推荐：

- 每个 Workspace 一套独立 MinIO 实例。
- 给前端下发 MinIO 长期凭证。
- 把文件二进制直接存 PostgreSQL。
- 让业务前端直接拼 Bucket 名和对象 key 后访问 MinIO。
- 在 Workspace 创建事务里直接同步创建 Bucket 并强绑定提交。

原因：

- MinIO 实例按 Workspace 复制会迅速放大运维成本。
- 长期凭证泄漏风险远高于短时签名 URL。
- 文件流量和元数据事务应解耦，数据库只维护元信息。
- Bucket 创建是外部副作用，不应和数据库本地事务强耦合。

---

## 3. 设计原则

1. 控制面与运行态分离：集群配置与 Bucket 归属属于平台控制面，文件对象元数据属于 Workspace 运行态。
2. 统一鉴权：客户端不能绕过平台权限体系直接访问对象存储。
3. Bucket 私有化：所有 Workspace Bucket 默认 private，不开放匿名读写。
4. 只签发短时 URL：上传下载都使用极短 TTL 的 Presigned URL。
5. 外部副作用异步化：Workspace 创建成功后再异步申请 Bucket，避免外部依赖拖垮主事务。
6. 元数据先行：上传前先生成对象元数据与上传意图，上传完成后再做确认。
7. 可扩展但不过度设计：当前先面向 MinIO/S3 兼容协议，不一步到位引入多云抽象平台。

---

## 4. 服务边界与复杂度目标

虽然本稿当前是设计草案，不直接落代码，但实现边界建议应提前定清，避免后续写成巨大 if/else 服务。

### 4.1 建议边界

- Controller：只负责鉴权入口、参数校验、响应组装。
- Application Service：负责上传申请、下载申请、完成上传、删除对象、Bucket 申请编排。
- Policy：负责权限判断、对象可见性判断、签名 URL 约束策略。
- Repository：负责 Bucket 元数据、对象元数据、上传会话持久化。
- Storage Gateway：负责 MinIO SDK 调用、Bucket 创建、对象 HEAD、Presigned URL 生成。

### 4.2 核心热点路径复杂度目标

- 平台管理员按 `cluster_id` 发起连通性测试：目标 `O(1)`，一次集群查询 + 常数次 MinIO 调用。
- 平台管理员测试上传/下载：目标 `O(1)`，依赖 `cluster_id` 或 `test_session_id` 唯一索引。
- 通过 `workspace_id` 查 Bucket 元数据：目标 `O(1)`，依赖唯一索引。
- 通过 `object_id` 查对象元数据：目标 `O(1)`，依赖主键或唯一索引。
- 单文件上传申请：目标 `O(1)` 数据库操作 + `O(1)` 一次签名。
- 单文件下载签名：目标 `O(1)` 元数据查询 + `O(1)` 一次签名。
- 批量上传申请：目标 `O(n)`，禁止对每个文件额外做重复全表扫描。
- Bucket 补偿扫描：目标 `O(n)`，按状态分页扫描，不做嵌套循环补偿。

### 4.3 为什么这样划分更优

如果把“权限判断 + Bucket 路由 + 元数据写入 + MinIO 调用 + 状态回写”全部塞进一个大 service，后续一旦新增下载、删除、预览、归档、跨存储后端切换，就会退化成多层 if/else。

当前建议的边界更适合后续扩展：

- 新增一种对象操作，只增加一个 command/service 切片。
- 新增一种存储后端，只替换 gateway/strategy，而不是重写主流程。
- 新增一种权限规则，只扩展 policy，而不是改多个 controller。

---

## 5. 总体架构建议

### 5.1 逻辑结构

- 平台管理员在平台管理端维护对象存储集群配置。
- 系统始终只认一个 active 的 MinIO 集群配置。
- 每个集群维护一个管理员专用测试 Bucket，用于初次配置与日常巡检验证。
- 用户创建 Workspace 后，系统异步为该 Workspace 申请 Bucket。
- Bucket 就绪前，相关上传接口返回 `WORKSPACE_BUCKET_NOT_READY`。
- 上传时先向后端申请 upload intent，再拿 Presigned PUT URL 直传 MinIO。
- 下载时先向后端申请 download URL，再拿 Presigned GET URL 下载。

### 5.2 推荐链路

#### Workspace 创建与 Bucket 申请 [已完成]

1. 用户调用创建 Workspace 接口。
2. 数据库提交 `workspace` 主数据。
3. 后端写入一条 `workspace_storage_bucket`，状态为 `PENDING`。
4. 异步任务或事件消费者执行 Bucket 创建。
5. MinIO 创建成功后，状态更新为 `READY`。
6. 创建失败则更新为 `FAILED`，记录错误信息并允许重试。

#### 上传链路 [已完成]

1. 客户端调用“创建上传申请”接口。
2. 后端校验当前用户是否具备当前 Workspace 的上传权限。
3. 后端查询 Workspace Bucket，确认状态为 `READY`。
4. 后端创建对象元数据与上传会话，状态为 `PENDING_UPLOAD`。
5. 后端生成短时 Presigned PUT URL 返回前端。
6. 前端直传 MinIO。
7. 前端调用“完成上传”接口。
8. 后端通过 HEAD/Object Stat 校验对象存在、大小、ETag 等信息。
9. 校验成功后把对象状态改为 `ACTIVE`。

#### 下载链路 [已完成]

1. 客户端请求下载某个对象。
2. 后端校验 Workspace 权限与对象可见性。
3. 后端确认对象状态为 `ACTIVE`。
4. 后端生成短时 Presigned GET URL。
5. 客户端使用签名 URL 下载。

---

## 6. Schema 与数据落点建议

### 6.1 plm_platform [已完成]

用途：平台控制面。

推荐放置：

- MinIO 集群配置。
- Workspace 与 Bucket 绑定关系。
- Bucket 申请状态与平台运维错误信息。

### 6.2 plm_runtime [已完成]

用途：Workspace 运行态。

推荐放置：

- 文件对象元数据。
- 上传会话。
- 后续对象业务引用关系。

结论：

- Bucket 不是单纯业务对象，而是平台级存储资源绑定，建议放 `plm_platform`。
- 文件对象是 Workspace 下的运行态数据，建议放 `plm_runtime`。

---

## 7. 数据模型建议

### 7.1 StorageCluster [已完成]

Schema：`plm_platform`  
表名：`storage_cluster`

用途：存储平台管理员维护的 MinIO 集群配置元数据。

建议字段：

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| cluster_code | VARCHAR(64) | Y | 集群编码 |
| provider_type | VARCHAR(20) | Y | 当前固定 `MINIO`，后续可扩展 `S3_COMPATIBLE` |
| endpoint | VARCHAR(255) | Y | MinIO API Endpoint |
| public_endpoint | VARCHAR(255) | N | 对外下载或浏览器访问使用的 Endpoint |
| region_name | VARCHAR(64) | N | 区域名，可为空 |
| bucket_prefix | VARCHAR(64) | Y | Bucket 公共前缀，例如 `plm-dev` |
| test_bucket_name | VARCHAR(128) | Y | 平台管理员专用测试 Bucket 名 |
| secret_ref | VARCHAR(255) | Y | 凭证引用，不建议直接存明文 AK/SK |
| status | VARCHAR(20) | Y | `ACTIVE` / `INACTIVE` / `FAILED` |
| health_status | VARCHAR(20) | Y | `HEALTHY` / `UNHEALTHY` / `UNKNOWN` |
| last_tested_at | TIMESTAMPTZ | N | 最近一次管理员测试时间 |
| last_test_status | VARCHAR(20) | Y | `NOT_TESTED` / `PASSED` / `FAILED` |
| last_test_error_code | VARCHAR(64) | N | 最近一次测试失败错误码 |
| last_test_error_message | VARCHAR(512) | N | 最近一次测试失败摘要 |
| last_checked_at | TIMESTAMPTZ | N | 最近健康检查时间 |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |
| updated_at | TIMESTAMPTZ | N | 更新时间 |
| updated_by | VARCHAR(64) | N | 更新者 |

约束建议：

- `PRIMARY KEY (id)`
- `UNIQUE (cluster_code)`
- 业务约束：同一时刻只允许一个 `ACTIVE` 集群。

说明：

- 第一阶段即使只支持一个 MinIO 集群，也建议保留表结构，不要把连接配置永久写死在 YAML 中。
- `test_bucket_name` 不允许复用任何 Workspace Bucket，只服务于平台管理员连接测试与上传/下载验证。
- `secret_ref` 推荐指向密文配置、KMS、Vault 或平台加密表，而不是直接在业务表落明文密钥。

### 7.1.1 管理员测试 Bucket 约定 [已完成]

测试 Bucket 只用于平台管理员验证当前集群配置，不承载任何业务文件。

当前建议：

- 每个集群唯一一个测试 Bucket。
- Bucket 默认 private，不开放匿名读写。
- Bucket 命名规则推荐：`{bucket_prefix}-cluster-test`。
- 测试对象统一写入固定前缀：`__cluster_test__/{yyyy}/{MM}/{dd}/{testSessionId}`。
- 测试对象默认设置短生命周期清理，例如 1 天自动清理。

不建议：

- 复用某个真实 Workspace Bucket 作为测试 Bucket。
- 在测试 Bucket 中长期保留人工上传文件。

原因：

- 测试对象与业务对象混放，会让后续容量统计、审计与清理策略变得混乱。
- 独立测试 Bucket 能把“平台配置是否正常”和“某个 Workspace 业务是否正常”严格区分开。

### 7.1.2 StorageClusterTestSession [已完成]

Schema：`plm_platform`  
表名：`storage_cluster_test_session`

用途：维护平台管理员专用的测试上传、测试下载会话。

建议字段：

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| cluster_id | UUID | Y | 对应存储集群 |
| test_bucket_name | VARCHAR(128) | Y | 测试 Bucket 名 |
| object_key | VARCHAR(512) | Y | 测试对象 key |
| session_status | VARCHAR(20) | Y | `INITIATED` / `UPLOADED` / `DOWNLOAD_VERIFIED` / `COMPLETED` / `EXPIRED` / `CANCELED` |
| upload_token | VARCHAR(128) | Y | 完成上传时的测试会话令牌 |
| original_file_name | VARCHAR(255) | Y | 原始测试文件名 |
| content_type | VARCHAR(128) | N | 测试文件类型 |
| file_size | BIGINT | N | 测试文件大小 |
| etag | VARCHAR(128) | N | 测试对象 ETag |
| created_by_user_id | UUID | Y | 发起测试的管理员 |
| expires_at | TIMESTAMPTZ | Y | 测试会话过期时间 |
| completed_at | TIMESTAMPTZ | N | 测试完成时间 |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |
| updated_at | TIMESTAMPTZ | N | 更新时间 |
| updated_by | VARCHAR(64) | N | 更新者 |

约束建议：

- `PRIMARY KEY (id)`
- `UNIQUE (upload_token)`
- `FOREIGN KEY (cluster_id) REFERENCES plm_platform.storage_cluster(id)`

索引建议：

- `INDEX idx_storage_cluster_test_session_cluster_status ON (cluster_id, session_status)`
- `INDEX idx_storage_cluster_test_session_expire ON (expires_at)`

### 7.2 WorkspaceStorageBucket [已完成]

Schema：`plm_platform`  
表名：`workspace_storage_bucket`

用途：维护 Workspace 与 Bucket 的绑定关系及申请状态。

建议字段：

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| workspace_id | UUID | Y | 对应 Workspace |
| cluster_id | UUID | Y | 归属存储集群 |
| bucket_name | VARCHAR(128) | Y | 实际 Bucket 名 |
| bucket_status | VARCHAR(20) | Y | `PENDING` / `READY` / `FAILED` / `FROZEN` / `DELETING` / `DELETED` |
| provision_error_code | VARCHAR(64) | N | 最近一次申请失败错误码 |
| provision_error_message | VARCHAR(512) | N | 最近一次申请失败摘要 |
| quota_bytes | BIGINT | N | 配额上限，可为空表示未配置 |
| used_bytes | BIGINT | Y | 当前已使用字节数 |
| object_count | BIGINT | Y | 当前对象数 |
| provisioned_at | TIMESTAMPTZ | N | Bucket 实际创建完成时间 |
| frozen_at | TIMESTAMPTZ | N | 冻结时间 |
| deleted_at | TIMESTAMPTZ | N | 删除时间 |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |
| updated_at | TIMESTAMPTZ | N | 更新时间 |
| updated_by | VARCHAR(64) | N | 更新者 |

约束建议：

- `PRIMARY KEY (id)`
- `UNIQUE (workspace_id)`
- `UNIQUE (bucket_name)`
- `FOREIGN KEY (workspace_id) REFERENCES plm_platform.workspace(id)`
- `FOREIGN KEY (cluster_id) REFERENCES plm_platform.storage_cluster(id)`

索引建议：

- `INDEX idx_workspace_storage_bucket_status ON (bucket_status)`
- `INDEX idx_workspace_storage_bucket_cluster_status ON (cluster_id, bucket_status)`

命名建议：

- Bucket 名不要使用可变的 workspace 名称。
- 推荐规则：`{bucket_prefix}-ws-{workspaceIdNoDash}`。

原因：

- `workspace_id` 是稳定且不可变的。
- 使用 `workspace_code` 或 `workspace_name` 未来会面临 rename 漂移与碰撞问题。

### 7.3 ObjectAsset [已完成]

Schema：`plm_runtime`  
表名：`object_asset`

用途：维护 Workspace 文件对象元数据。

建议字段：

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| workspace_id | UUID | Y | 所属 Workspace |
| bucket_id | UUID | Y | 归属 Bucket 元数据 |
| object_key | VARCHAR(512) | Y | MinIO 对象 key |
| object_status | VARCHAR(20) | Y | `PENDING_UPLOAD` / `ACTIVE` / `UPLOAD_FAILED` / `DELETING` / `DELETED` |
| biz_type | VARCHAR(64) | Y | 业务类型，如 `DOCUMENT` / `AVATAR` / `IMPORT` / `EXPORT` |
| biz_ref_id | UUID | N | 业务引用对象 ID |
| original_file_name | VARCHAR(255) | Y | 原始文件名 |
| content_type | VARCHAR(128) | N | MIME 类型 |
| file_size | BIGINT | N | 文件字节数 |
| etag | VARCHAR(128) | N | 对象 ETag |
| sha256 | VARCHAR(128) | N | 可选摘要 |
| uploaded_by_user_id | UUID | Y | 上传人 |
| visibility_scope | VARCHAR(20) | Y | `WORKSPACE` / `PRIVATE` / `BIZ_CONTROLLED` |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |
| updated_at | TIMESTAMPTZ | N | 更新时间 |
| updated_by | VARCHAR(64) | N | 更新者 |

约束建议：

- `PRIMARY KEY (id)`
- `UNIQUE (workspace_id, object_key)`

索引建议：

- `INDEX idx_object_asset_workspace_status ON (workspace_id, object_status)`
- `INDEX idx_object_asset_workspace_biz ON (workspace_id, biz_type, biz_ref_id)`
- `INDEX idx_object_asset_uploaded_by ON (uploaded_by_user_id)`

对象 key 建议：

- 不建议直接使用原始文件名作为主 key。
- 推荐规则：`objects/{bizType}/{yyyy}/{MM}/{dd}/{objectId}`。

这样做的好处：

- key 稳定且无重名冲突。
- 原始文件名可以自由修改，不影响真实存储定位。
- 后续做版本化时可以平滑扩展为 `objects/{objectId}/v{n}`。

### 7.4 ObjectUploadSession [已完成]

Schema：`plm_runtime`  
表名：`object_upload_session`

用途：维护上传意图与上传完成确认链路。

建议字段：

| 字段名 | 类型 | 非空 | 说明 |
| --- | --- | ---: | --- |
| id | UUID | Y | 主键 |
| object_id | UUID | Y | 对应对象 |
| workspace_id | UUID | Y | 所属 Workspace |
| session_status | VARCHAR(20) | Y | `INITIATED` / `UPLOADED` / `COMPLETED` / `EXPIRED` / `CANCELED` |
| upload_token | VARCHAR(128) | Y | 前后端完成上传时的会话标识 |
| presigned_method | VARCHAR(10) | Y | `PUT` / `POST` |
| expires_at | TIMESTAMPTZ | Y | 上传签名过期时间 |
| expected_content_type | VARCHAR(128) | N | 预期内容类型 |
| expected_max_size | BIGINT | N | 预期最大大小 |
| created_by_user_id | UUID | Y | 发起人 |
| completed_at | TIMESTAMPTZ | N | 完成时间 |
| created_at | TIMESTAMPTZ | Y | 创建时间 |
| created_by | VARCHAR(64) | N | 创建者 |
| updated_at | TIMESTAMPTZ | N | 更新时间 |
| updated_by | VARCHAR(64) | N | 更新者 |

约束建议：

- `PRIMARY KEY (id)`
- `UNIQUE (upload_token)`
- `FOREIGN KEY (object_id) REFERENCES plm_runtime.object_asset(id)`

---

## 8. 状态机建议

### 8.1 Bucket 状态机

`workspace_storage_bucket.bucket_status`：

- `PENDING`：已登记，待申请 Bucket。
- `READY`：Bucket 已创建，可提供上传下载服务。
- `FAILED`：最近一次申请失败，待人工或任务重试。
- `FROZEN`：Workspace 冻结或归档，不允许新增上传。
- `DELETING`：Bucket 清理中。
- `DELETED`：Bucket 已删除，仅保留历史元数据。

推荐流转：

- Workspace 创建后：`PENDING -> READY`
- 申请失败：`PENDING -> FAILED`
- Workspace 归档：`READY -> FROZEN`
- Workspace 删除：`FROZEN/READY -> DELETING -> DELETED`

### 8.2 对象状态机

`object_asset.object_status`：

- `PENDING_UPLOAD`
- `ACTIVE`
- `UPLOAD_FAILED`
- `DELETING`
- `DELETED`

推荐流转：

- 创建上传意图：`PENDING_UPLOAD`
- 完成上传校验成功：`ACTIVE`
- 上传超时或校验失败：`UPLOAD_FAILED`
- 删除开始：`DELETING`
- 删除完成：`DELETED`

### 8.3 上传会话状态机

`object_upload_session.session_status`：

- `INITIATED`
- `UPLOADED`
- `COMPLETED`
- `EXPIRED`
- `CANCELED`

说明：

- 如果第一阶段不做客户端上传回调前的中间状态，也可以直接从 `INITIATED` 跳到 `COMPLETED`。
- 但保留独立 session 状态，会让后续断点续传、分片上传、失败补偿更容易扩展。

### 8.4 集群测试会话状态机

`storage_cluster_test_session.session_status`：

- `INITIATED`
- `UPLOADED`
- `DOWNLOAD_VERIFIED`
- `COMPLETED`
- `EXPIRED`
- `CANCELED`

推荐流转：

- 管理员申请测试上传：`INITIATED`
- 测试对象已上传并校验存在：`UPLOADED`
- 管理员成功获取并验证测试下载：`DOWNLOAD_VERIFIED`
- 管理员确认整条测试链路完成：`COMPLETED`
- 超时未完成：`EXPIRED`

---

## 9. 权限与鉴权建议

### 9.1 平台管理员测试接口鉴权 [已完成]

管理员测试连接、测试上传、测试下载接口必须满足：

- 只允许平台登录态调用。
- 只允许平台管理员角色或显式平台权限调用。
- Workspace token 不允许调用这些接口。
- 测试 Bucket 不允许任何 Workspace 侧业务接口访问。

建议新增以下平台权限：

- `platform.storage.cluster.manage`
- `platform.storage.cluster.test`

### 9.2 基本原则

- MinIO 不直接承担业务用户鉴权。
- MinIO 只接受平台服务端签发的 Presigned URL。
- Workspace 内真正的权限校验必须由后端完成。

### 9.3 推荐权限码 [已完成]

建议新增以下 Workspace 权限：

- `storage.bucket.read`
- `storage.object.upload`
- `storage.object.download`
- `storage.object.delete`
- `storage.object.manage`

说明：

- `upload` 和 `download` 不应默认合并。
- 后续若有文档模块、导入模块、导出模块，还可以继续叠加业务级校验，而不是只靠对象存储权限本身。

### 9.4 下载授权建议

下载不应只校验对象属于当前 Workspace，还应校验：

- 当前成员是否有读取该业务对象的权限。
- 当前对象是否仍然处于可见状态。
- 当前对象是否被业务规则冻结或删除。

因此推荐保留 `biz_type + biz_ref_id + visibility_scope` 这组字段，避免把对象存储权限误当成业务对象权限。

---

## 10. Presigned URL 策略建议

### 10.1 上传 URL

建议：

- 方法：优先 `PUT`，第一阶段简单直接。
- TTL：5 到 15 分钟。
- URL 只绑定一个确定的 Bucket 和 object key。
- 尽量约束 `content-type` 与最大文件大小。

### 10.2 下载 URL

建议：

- 方法：`GET`
- TTL：1 到 5 分钟，尽量比上传更短。
- 返回时可附带建议下载文件名。

### 10.3 管理员测试 URL

建议：

- 测试上传 URL 与测试下载 URL 只允许平台管理员接口签发。
- TTL 建议更短，优先 1 到 3 分钟。
- 测试 URL 只允许落到 `test_bucket_name` 和 `__cluster_test__` 前缀。

### 10.4 当前不建议

- 超长有效期 URL。
- 公共可分享的永久 URL。
- 客户端自行构造对象路径后换签。

原因：

- 一旦 URL 外泄，TTL 越长，风险越高。
- 对象 key 规则和 Bucket 路由应该始终由后端掌控。

---

## 11. 核心流程设计建议

### 11.1 平台管理员配置存储集群 [已完成]

推荐流程：

1. 平台管理员提交 MinIO endpoint、bucket prefix、secret ref 等信息。
2. 后端保存 `storage_cluster` 元数据，并生成 `test_bucket_name`。
3. 后端执行一次连接测试与测试 Bucket 检查，如不存在则创建测试 Bucket。
4. 平台管理员可继续调用上传/下载测试接口完成端到端验证。
5. 测试通过后更新 `last_tested_at`、`last_test_status=PASSED`，再标记为 `ACTIVE`。
6. 如存在旧 active 集群，新配置切换应走显式切换流程，不建议隐式覆盖。

推荐管理员测试接口：

- `POST /api/platform/storage/clusters/{clusterId}/connection-test`
- `POST /api/platform/storage/clusters/{clusterId}/test-upload-intents`
- `POST /api/platform/storage/clusters/test-sessions/{testSessionId}/complete`
- `POST /api/platform/storage/clusters/test-sessions/{testSessionId}/download-url`
- `DELETE /api/platform/storage/clusters/test-sessions/{testSessionId}`

这些接口的目标不是替代真实业务上传下载，而是验证：

- 当前 MinIO endpoint 是否可连通。
- 当前凭证是否具备 Bucket 与对象级权限。
- Presigned PUT 和 Presigned GET 是否能被浏览器或客户端正确使用。

### 11.2 Workspace 创建后申请 Bucket [已完成]

推荐流程：

1. 创建 Workspace 成功。
2. 事务提交后发出 bucket provision command/event。
3. BucketProvisioningService 读取 active cluster。
4. 生成 bucket 名并调用 MinIO 创建 Bucket。
5. 配置默认 bucket policy、生命周期规则。
6. 更新 `workspace_storage_bucket` 为 `READY`。

### 11.3 申请上传 [已完成]

推荐接口：

- `POST /api/storage/workspaces/{workspaceId}/objects/upload-intents`

请求建议包含：

- `bizType`
- `bizRefId`
- `fileName`
- `contentType`
- `expectedSize`

返回建议包含：

- `objectId`
- `uploadToken`
- `bucketName`
- `objectKey`
- `presignedUploadUrl`
- `expireAt`

### 11.4 完成上传 [已完成]

推荐接口：

- `POST /api/storage/workspaces/{workspaceId}/objects/{objectId}/complete`

后端动作：

- 校验 `uploadToken`
- 调用 MinIO `statObject` 或 HEAD
- 校验对象存在、大小、ETag
- 更新 `object_asset` 和 `object_upload_session`
- 回写 Bucket `used_bytes` 与 `object_count`

### 11.5 申请下载 [已完成]

推荐接口：

- `POST /api/storage/workspaces/{workspaceId}/objects/{objectId}/download-url`

后端动作：

- 校验成员权限
- 校验业务可见性
- 生成 Presigned GET URL

### 11.6 删除对象 [已完成]

推荐接口：

- `DELETE /api/storage/workspaces/{workspaceId}/objects/{objectId}`

推荐策略：

- 第一阶段优先“元数据软删 + 异步物理删对象”。
- 删除完成后回写 `used_bytes` 与 `object_count`。

---

## 12. 生命周期与运维建议

### 12.1 Bucket 生命周期 [已完成]

建议每个 Bucket 默认应用以下治理：

- 禁止公开读写。
- 开启未完成分片上传清理策略。
- 可选：开启服务端加密。
- 可选：设置临时上传前缀生命周期规则。

### 12.2 Workspace 冻结与删除 [已完成]

当 Workspace 被冻结或归档时：

- Bucket 状态改为 `FROZEN`。
- 不再允许新上传。
- 已存在对象可按业务规则决定是否允许下载。

当 Workspace 被删除时：

- 先冻结。
- 再异步执行对象清理和 Bucket 删除。
- 最后把 Bucket 元数据改为 `DELETED`。

### 12.3 容量统计 [已完成]

当前建议：

- 上传完成与删除完成时做增量更新。
- 定时任务按 Bucket 维度做对账校准。

不要依赖：

- 每次请求实时全量扫描 MinIO 统计空间。

那样会把热点路径退化成高成本 I/O。

### 12.4 测试对象清理 [已完成]

当前建议：

- 管理员测试对象默认按短生命周期自动清理。
- 额外由定时任务按 `storage_cluster_test_session.expires_at` 清理过期对象与过期会话。
- 测试对象不纳入任何 Workspace 配额统计。

---

## 13. 异常码建议

建议新增以下错误码：

- `STORAGE_CLUSTER_NOT_CONFIGURED`
- `STORAGE_CLUSTER_UNHEALTHY`
- `STORAGE_CLUSTER_TEST_FAILED`
- `STORAGE_TEST_BUCKET_NOT_READY`
- `STORAGE_TEST_SESSION_NOT_FOUND`
- `STORAGE_TEST_SESSION_EXPIRED`
- `WORKSPACE_BUCKET_NOT_FOUND`
- `WORKSPACE_BUCKET_NOT_READY`
- `WORKSPACE_BUCKET_PROVISION_FAILED`
- `OBJECT_UPLOAD_SESSION_EXPIRED`
- `OBJECT_UPLOAD_NOT_COMPLETED`
- `OBJECT_NOT_FOUND`
- `OBJECT_ALREADY_DELETED`
- `OBJECT_ACCESS_DENIED`
- `OBJECT_QUOTA_EXCEEDED`

前端价值：

- 可以准确区分“平台没配 MinIO”“当前空间 Bucket 还没就绪”“对象权限不足”“对象不存在”。

---

## 14. 实现结构建议

### 14.1 模块建议

推荐由 `plm-infrastructure` 提供统一对象存储适配层：

- `storage/config`
- `storage/gateway`
- `storage/presign`
- `storage/policy`

再由具体业务服务消费：

- `plm-document-service`
- 后续导入导出服务
- 头像、附件、导出包等业务模块

### 14.2 代码结构建议

如果进入实现阶段，推荐以下切分：

- `StorageClusterCommandService`
- `StorageClusterTestCommandService`
- `WorkspaceBucketProvisioningService`
- `ObjectUploadCommandService`
- `ObjectDownloadQueryService`
- `ObjectDeletionCommandService`
- `MinioStorageGateway`
- `StoragePermissionPolicy`
- `PresignedUrlPolicy`

后续若新增其他对象存储后端，扩展点应放在：

- `StorageGateway` 接口。
- `StorageProviderType` 枚举或策略分发。

不建议在 service 里写成：

- `if MINIO -> ... else if OSS -> ... else if S3 -> ...`

那会让后续每次扩展都要改主流程。

---

## 15. 后续扩展点

后续如果新增一个业务类型或一个存储后端，推荐扩展点如下：

- 新增业务类型：扩展 `biz_type` 解释器或业务对象可见性策略，不改上传下载主流程。
- 新增存储后端：扩展 `StorageGateway` 实现，不改 controller 和 repository。
- 新增对象版本：在 `object_asset` 上方增加 version 表，不改 Bucket 绑定模型。
- 新增病毒扫描：在上传完成后插入异步扫描状态，不改上传签名主链路。

---

## 16. 测试建议

虽然当前只出设计稿，但后续实现至少应补以下测试：

1. 集群未配置时，上传申请接口返回 `STORAGE_CLUSTER_NOT_CONFIGURED`。
2. 平台管理员发起连接测试时，如果 endpoint 或凭证不可用，返回 `STORAGE_CLUSTER_TEST_FAILED`。
3. 平台管理员测试上传完成后，可以成功拿到测试下载 URL，并完成整条验证链路。
4. 非平台管理员调用测试接口时，被权限系统正确拒绝。
5. Workspace 创建后 Bucket 申请成功时，`workspace_storage_bucket` 从 `PENDING` 变 `READY`。
6. Bucket 申请失败时，状态进入 `FAILED`，错误码与错误消息被持久化。
7. 上传申请时，如果 Bucket 未就绪，接口返回 `WORKSPACE_BUCKET_NOT_READY`。
8. 上传完成时，如果对象不存在或 ETag 不匹配，状态改为 `UPLOAD_FAILED`。
9. 下载申请时，如果用户没有当前 Workspace 下载权限，返回 `OBJECT_ACCESS_DENIED`。
10. 删除对象后，元数据状态和 Bucket 统计被正确回写。
11. Workspace 冻结后，不允许继续上传。
12. 使用 workspace_id 生成 Bucket 名时，同一个 Workspace 重试申请不会生成多个 Bucket。
13. 使用 Presigned URL 时，过期 URL 会被 MinIO 正常拒绝。

---

## 17. 当前阶段推荐决策

为便于评审，当前建议先定以下结论：

1. 平台统一维护一个 active MinIO 集群配置，配置元数据落 `plm_platform.storage_cluster`。
2. 每个集群额外维护一个管理员专用测试 Bucket，只用于平台连接与上传/下载验证，不承载业务对象。
3. 平台管理员测试连接、测试上传、测试下载接口只允许平台管理员身份调用，不允许 Workspace token 调用。
4. 每个 Workspace 唯一绑定一个 Bucket，绑定关系落 `plm_platform.workspace_storage_bucket`。
5. Bucket 名使用不可变 `workspace_id` 生成，不使用可变名称字段。
6. 对象元数据与上传会话落 `plm_runtime`，统一带 `workspace_id`。
7. 上传下载只允许通过后端鉴权后签发 Presigned URL，不给前端任何长期存储凭证。
8. Workspace 创建与 Bucket 创建解耦，Bucket 走异步申请与补偿。
9. 对象上传采用“upload intent -> 直传 -> complete”三段式，避免脏对象与未知来源写入。

如果这 7 点评审通过，后续就可以继续进入：

- Flyway 表结构设计。
- plm-infrastructure MinIO 适配层落地。
- Workspace 创建后的 BucketProvisioningService 实现。
- 文档服务或通用附件服务接口实现。