# 对象存储 API 文档（plm-auth-service）

更新时间：2026-06-26

> 本文覆盖当前平台统一 MinIO 对象存储的正式接口，对接范围包括：
>
> - 平台管理员存储集群配置与测试
> - Workspace 对象上传/完成上传/下载/删除
> - Workspace 冻结与删除生命周期接口

---

## 1. 本地联调约定

当前对象存储接口由 `plm-auth-service` 提供，路径分为两类：

- 平台/登录态接口：`/auth/**`
- Workspace 对象存储接口：`/api/storage/**`

本地联调建议：

- 当前 gateway 已补对象存储路由，前端统一通过 `http://localhost:8080` 对接
- auth-service 直连 `http://localhost:8081` 仅建议用于后端本地排障

当前文档样例默认以 gateway 统一入口为例：

- `http://localhost:8080/auth/...`
- `http://localhost:8080/api/storage/...`

---

## 2. 鉴权说明

### 2.1 平台管理员存储接口

- 使用平台登录态 token
- 只允许平台管理员调用
- 额外受显式平台权限控制：
  - `platform.storage.cluster.manage`
  - `platform.storage.cluster.test`

### 2.2 Workspace 对象接口

- 仍使用平台登录态 token
- 不要求前端直接持有 MinIO 凭证
- 后端会基于当前用户与 Workspace 成员关系校验以下权限：
  - `storage.object.upload`
  - `storage.object.download`
  - `storage.object.delete`

### 2.3 Token Header

登录成功后，前端应把平台 token 按登录响应中的字段透传，例如：

- Header 名：`satoken`
- Header 值：`<platformToken>`

示例：

```http
satoken: 1f2f1cb0-0af2-4e2f-9d70-7c72f6c8f3c8
```

---

## 3. 功能列表（是否实现）

| 功能 | 相关接口 | 是否实现 | 备注 |
| --- | --- | ---: | --- |
| 存储集群列表 | `GET /auth/platform-admin/storage/clusters` | ✅ | 平台管理员 |
| 创建存储集群 | `POST /auth/platform-admin/storage/clusters` | ✅ | 平台管理员 |
| 更新存储集群 | `PUT /auth/platform-admin/storage/clusters/{clusterId}` | ✅ | 平台管理员 |
| 测试存储连接 | `POST /auth/platform-admin/storage/clusters/{clusterId}/test-connection` | ✅ | 平台管理员 |
| 申请测试上传 | `POST /auth/platform-admin/storage/clusters/{clusterId}/test-upload-intents` | ✅ | 平台管理员 |
| 完成测试上传 | `POST /auth/platform-admin/storage/clusters/{clusterId}/test-sessions/{testSessionId}/complete` | ✅ | 平台管理员 |
| 申请测试下载 URL | `POST /auth/platform-admin/storage/clusters/{clusterId}/test-sessions/{testSessionId}/download-url` | ✅ | 平台管理员 |
| 清理测试对象 | `DELETE /auth/platform-admin/storage/clusters/{clusterId}/test-sessions/{testSessionId}` | ✅ | 平台管理员 |
| 申请对象上传 | `POST /api/storage/workspaces/{workspaceId}/objects/upload-intents` | ✅ | Workspace 成员 |
| 完成对象上传 | `POST /api/storage/workspaces/{workspaceId}/objects/{objectId}/complete` | ✅ | Workspace 成员 |
| 申请对象下载 URL | `POST /api/storage/workspaces/{workspaceId}/objects/{objectId}/download-url` | ✅ | Workspace 成员 |
| 删除对象 | `DELETE /api/storage/workspaces/{workspaceId}/objects/{objectId}` | ✅ | Workspace 成员 |
| 冻结 Workspace | `POST /auth/workspaces/{workspaceId}/freeze` | ✅ | 需要 `workspace.config.update` |
| 删除 Workspace | `DELETE /auth/workspaces/{workspaceId}` | ✅ | 需要 `workspace.config.update`；请求返回时状态为 `FROZEN/DELETING`，后台清理后收口到 `DELETED` |

---

## 4. 通用错误响应

统一错误响应示例：

```json
{
  "timestamp": "2026-06-26T15:30:00+08:00",
  "status": 409,
  "error": "Conflict",
  "code": "WORKSPACE_BUCKET_NOT_READY",
  "message": "workspace bucket is not ready"
}
```

当前对象存储相关常见错误码：

- `STORAGE_CLUSTER_NOT_CONFIGURED`
- `STORAGE_CLUSTER_TEST_FAILED`
- `WORKSPACE_BUCKET_NOT_FOUND`
- `WORKSPACE_BUCKET_NOT_READY`
- `WORKSPACE_BUCKET_PROVISION_FAILED`
- `OBJECT_UPLOAD_SESSION_EXPIRED`
- `OBJECT_UPLOAD_NOT_COMPLETED`
- `OBJECT_NOT_FOUND`
- `OBJECT_ALREADY_DELETED`
- `OBJECT_ACCESS_DENIED`
- `PLATFORM_PERMISSION_DENIED`

---

## 5. 平台管理员存储接口

### 5.1 查询存储集群列表

- 方法：`GET`
- 路径：`/auth/platform-admin/storage/clusters`

curl 示例：

```bash
curl -H "satoken: <platformToken>" \
  http://localhost:8080/auth/platform-admin/storage/clusters
```

响应示例：

```json
[
  {
    "id": "4c7ef4c4-0e3d-49dc-af50-6fe504c4d86c",
    "clusterCode": "minio-dev",
    "providerType": "MINIO",
    "endpoint": "http://127.0.0.1:9000",
    "publicEndpoint": "http://localhost:9000",
    "regionName": null,
    "bucketPrefix": "plm-dev",
    "testBucketName": "plm-dev-minio-dev-admin-test",
    "secretRefPreview": "inline:minioadmin:***",
    "status": "ACTIVE",
    "healthStatus": "HEALTHY",
    "lastTestedAt": "2026-06-26T10:20:30Z",
    "lastTestStatus": "PASSED",
    "lastTestErrorCode": null,
    "lastTestErrorMessage": null,
    "lastCheckedAt": "2026-06-26T10:20:30Z",
    "createdAt": "2026-06-26T09:30:00Z",
    "updatedAt": "2026-06-26T10:20:30Z"
  }
]
```

### 5.2 创建存储集群

- 方法：`POST`
- 路径：`/auth/platform-admin/storage/clusters`

请求体示例：

```json
{
  "clusterCode": "minio-dev",
  "endpoint": "http://127.0.0.1:9000",
  "publicEndpoint": "http://localhost:9000",
  "regionName": null,
  "bucketPrefix": "plm-dev",
  "testBucketName": "plm-dev-minio-dev-admin-test",
  "secretRef": "inline:minioadmin:minioadmin",
  "status": "ACTIVE"
}
```

curl 示例：

```bash
curl -X POST "http://localhost:8080/auth/platform-admin/storage/clusters" \
  -H "Content-Type: application/json" \
  -H "satoken: <platformToken>" \
  -d '{
    "clusterCode": "minio-dev",
    "endpoint": "http://127.0.0.1:9000",
    "publicEndpoint": "http://localhost:9000",
    "bucketPrefix": "plm-dev",
    "secretRef": "inline:minioadmin:minioadmin",
    "status": "ACTIVE"
  }'
```

### 5.3 更新存储集群

- 方法：`PUT`
- 路径：`/auth/platform-admin/storage/clusters/{clusterId}`

路径参考样例：

```text
/auth/platform-admin/storage/clusters/4c7ef4c4-0e3d-49dc-af50-6fe504c4d86c
```

请求体与创建接口相同。

### 5.4 测试连接

- 方法：`POST`
- 路径：`/auth/platform-admin/storage/clusters/{clusterId}/test-connection`

响应示例：

```json
{
  "clusterId": "4c7ef4c4-0e3d-49dc-af50-6fe504c4d86c",
  "clusterCode": "minio-dev",
  "testBucketName": "plm-dev-minio-dev-admin-test",
  "healthStatus": "HEALTHY",
  "lastTestStatus": "PASSED",
  "testedAt": "2026-06-26T10:25:00Z"
}
```

### 5.5 申请测试上传

- 方法：`POST`
- 路径：`/auth/platform-admin/storage/clusters/{clusterId}/test-upload-intents`

请求体示例：

```json
{
  "originalFileName": "check-file.txt",
  "contentType": "text/plain",
  "fileSize": 128
}
```

响应示例：

```json
{
  "testSessionId": "6d65aa26-0b22-487b-82a8-6282e2119178",
  "uploadToken": "8c7a94161d934d11bf8a44e0cbf519bf",
  "objectKey": "__cluster_test__/2026/06/26/6d65aa26-0b22-487b-82a8-6282e2119178",
  "uploadUrl": "http://minio.local/upload",
  "expiresAt": "2026-06-26T10:40:00Z"
}
```

### 5.6 完成测试上传

- 方法：`POST`
- 路径：`/auth/platform-admin/storage/clusters/{clusterId}/test-sessions/{testSessionId}/complete`

响应示例：

```json
{
  "testSessionId": "6d65aa26-0b22-487b-82a8-6282e2119178",
  "clusterId": "4c7ef4c4-0e3d-49dc-af50-6fe504c4d86c",
  "sessionStatus": "COMPLETED",
  "objectKey": "__cluster_test__/2026/06/26/6d65aa26-0b22-487b-82a8-6282e2119178",
  "originalFileName": "check-file.txt",
  "contentType": "text/plain",
  "fileSize": 128,
  "etag": "etag-123",
  "expiresAt": "2026-06-26T10:40:00Z",
  "completedAt": "2026-06-26T10:31:05Z"
}
```

### 5.7 申请测试下载 URL

- 方法：`POST`
- 路径：`/auth/platform-admin/storage/clusters/{clusterId}/test-sessions/{testSessionId}/download-url`

响应示例：

```json
{
  "downloadUrl": "http://minio.local/download",
  "expiresAt": "2026-06-26T10:35:00Z"
}
```

### 5.8 清理测试对象

- 方法：`DELETE`
- 路径：`/auth/platform-admin/storage/clusters/{clusterId}/test-sessions/{testSessionId}`

成功响应：`204 No Content`

---

## 6. Workspace 对象接口

### 6.1 申请上传

- 方法：`POST`
- 路径：`/api/storage/workspaces/{workspaceId}/objects/upload-intents`

路径参考样例：

```text
/api/storage/workspaces/9a1b2f75-9d8e-4d3e-82fd-23a5772ea3d5/objects/upload-intents
```

curl 示例：

```bash
curl -X POST "http://localhost:8080/api/storage/workspaces/9a1b2f75-9d8e-4d3e-82fd-23a5772ea3d5/objects/upload-intents" \
  -H "Content-Type: application/json" \
  -H "satoken: <platformToken>" \
  -d '{
    "bizType": "DOCUMENT",
    "bizRefId": null,
    "fileName": "manual.pdf",
    "contentType": "application/pdf",
    "expectedSize": 128
  }'
```

请求体示例：

```json
{
  "bizType": "DOCUMENT",
  "bizRefId": null,
  "fileName": "manual.pdf",
  "contentType": "application/pdf",
  "expectedSize": 128
}
```

响应示例：

```json
{
  "objectId": "6c53d8d1-cad2-4c57-b53f-2d5c037807d0",
  "uploadToken": "78b5d909f28d43f49a4a9fc2aa6ee2d6",
  "bucketName": "plm-dev-ws-9a1b2f759d8e4d3e82fd23a5772ea3d5",
  "objectKey": "objects/document/2026/06/26/6c53d8d1-cad2-4c57-b53f-2d5c037807d0",
  "presignedUploadUrl": "http://minio.local/object-upload",
  "expireAt": "2026-06-26T10:45:00Z"
}
```

### 6.2 完成上传

- 方法：`POST`
- 路径：`/api/storage/workspaces/{workspaceId}/objects/{objectId}/complete`

请求体示例：

```json
{
  "uploadToken": "78b5d909f28d43f49a4a9fc2aa6ee2d6"
}
```

响应示例：

```json
{
  "objectId": "6c53d8d1-cad2-4c57-b53f-2d5c037807d0",
  "workspaceId": "9a1b2f75-9d8e-4d3e-82fd-23a5772ea3d5",
  "objectKey": "objects/document/2026/06/26/6c53d8d1-cad2-4c57-b53f-2d5c037807d0",
  "objectStatus": "ACTIVE",
  "bizType": "DOCUMENT",
  "bizRefId": null,
  "originalFileName": "manual.pdf",
  "contentType": "application/pdf",
  "fileSize": 128,
  "etag": "etag-object-1",
  "visibilityScope": "WORKSPACE",
  "createdAt": "2026-06-26T10:38:05Z",
  "updatedAt": "2026-06-26T10:38:30Z"
}
```

### 6.3 申请下载 URL

- 方法：`POST`
- 路径：`/api/storage/workspaces/{workspaceId}/objects/{objectId}/download-url`

响应示例：

```json
{
  "objectId": "6c53d8d1-cad2-4c57-b53f-2d5c037807d0",
  "downloadUrl": "http://minio.local/object-download",
  "expireAt": "2026-06-26T10:40:00Z"
}
```

### 6.4 删除对象

- 方法：`DELETE`
- 路径：`/api/storage/workspaces/{workspaceId}/objects/{objectId}`

成功响应：`204 No Content`

---

## 7. Workspace 生命周期接口

### 7.1 冻结 Workspace

- 方法：`POST`
- 路径：`/auth/workspaces/{workspaceId}/freeze`

成功响应：`204 No Content`

冻结后的接口行为：

- 新上传申请会被拒绝
- 已存在对象当前阶段仍允许下载
- bucket 状态会同步切为 `FROZEN`

### 7.2 删除 Workspace

- 方法：`DELETE`
- 路径：`/auth/workspaces/{workspaceId}`

成功响应：`204 No Content`

删除语义：

1. 请求返回时，Workspace 已被推进到 `FROZEN/DELETING`
2. 成员会被停用，默认 Workspace 标记会被清理
3. 后台清理任务会继续删除 bucket 内对象和物理 bucket
4. 清理完成后，Workspace 和 bucket 最终状态都会变成 `DELETED`

---

## 10. 当前完成度结论

基于 `workspace-minio-object-storage-design-draft.md` 当前阶段定义的实现目标，现阶段已完成：

- 平台统一 MinIO 集群配置与单 active 控制
- 平台管理员测试 bucket、测试上传/下载/清理接口
- Workspace bucket 申请、失败回写与命名规则
- Workspace 对象上传、完成上传、下载 URL、删除
- Workspace 冻结与删除生命周期主链路
- bucket 容量统计对账
- 测试对象过期清理
- MinIO gateway bucket 私有 policy、multipart 清理、测试对象生命周期、可选 SSE 能力
- gateway 对 `/api/storage/**` 的统一转发

当前剩余内容如果继续做，已属于草案之外的增强项，而不是当前阶段主闭环缺口，例如：

- 更细粒度的业务对象可见性策略实现
- 更完整的 bucket policy / SSE 多策略扩展
- 文档预览、病毒扫描、对象版本化等高级能力

---

## 8. 存储治理说明

当前 MinIO gateway 已默认落地以下治理：

- bucket policy 统一收口为私有 bucket policy
- 未完成 multipart 上传默认 1 天自动清理
- 平台测试 bucket 的 `__cluster_test__/` 前缀对象默认 1 天过期
- 可选 SSE-S3 默认加密能力已经接入，默认关闭，可通过配置打开

当前配置项：

- `plm.storage.governance.enforce-private-bucket-policy`
- `plm.storage.governance.abort-incomplete-multipart-after-days`
- `plm.storage.governance.test-object-expire-days`
- `plm.storage.governance.test-object-prefix`
- `plm.storage.governance.enable-server-side-encryption`

---

## 9. 对接顺序建议

### 9.1 平台管理员初始化集群

1. 登录平台管理员账号
2. `POST /auth/platform-admin/storage/clusters`
3. `POST /auth/platform-admin/storage/clusters/{clusterId}/test-connection`
4. `POST /auth/platform-admin/storage/clusters/{clusterId}/test-upload-intents`
5. 前端把测试文件上传到 `uploadUrl`
6. `POST /auth/platform-admin/storage/clusters/{clusterId}/test-sessions/{testSessionId}/complete`
7. `POST /auth/platform-admin/storage/clusters/{clusterId}/test-sessions/{testSessionId}/download-url`
8. `DELETE /auth/platform-admin/storage/clusters/{clusterId}/test-sessions/{testSessionId}`

### 9.2 Workspace 文件上传下载

1. 用户登录并拿到平台 token
2. 创建或选择 Workspace
3. `POST /api/storage/workspaces/{workspaceId}/objects/upload-intents`
4. 前端把文件直传到 `presignedUploadUrl`
5. `POST /api/storage/workspaces/{workspaceId}/objects/{objectId}/complete`
6. 下载时调用 `POST /api/storage/workspaces/{workspaceId}/objects/{objectId}/download-url`
7. 删除时调用 `DELETE /api/storage/workspaces/{workspaceId}/objects/{objectId}`

### 9.3 Workspace 生命周期

1. 暂停新上传时调用 `POST /auth/workspaces/{workspaceId}/freeze`
2. 彻底删除时调用 `DELETE /auth/workspaces/{workspaceId}`
3. 后端后台清理完成后，相关 Workspace/bucket 状态会最终收口到 `DELETED`
