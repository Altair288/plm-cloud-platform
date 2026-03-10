# 分类通用 API 文档（plm-attribute-service）

更新时间：2026-03-10

> 本文为分类接口正式文档。
>
> 已完成从旧固定层级接口 `/api/meta/categories/unspsc/**` 到通用接口的完整迁移。

---

## 1. 设计目标

- 支持任意层级深度（8级/10级等），不在接口层写死层级名称。
- 支持按父节点逐级加载，满足前端懒加载树形交互。
- 搜索命中必须返回完整路径信息（`path` + `pathNodes`）。
- 使用闭包表（closure table）支撑子树迁移、路径回显、子树范围检索。
- 支持 taxonomy 元数据接口，动态返回层级展示文案。

---

## 2. 功能列表（是否实现）

| 功能 | 相关接口 | 是否实现 | 备注 |
|---|---|---:|---|
| 查询子节点（逐级加载） | `GET /api/meta/categories/nodes` | ✅ | `taxonomy/parentId/level/keyword/status/page/size` |
| 查询节点路径（面包屑回显） | `GET /api/meta/categories/nodes/{id}/path` | ✅ | 返回从根到当前节点有序列表 |
| 通用搜索（返回完整路径） | `GET /api/meta/categories/search` | ✅ | 返回 `path` 与 `pathNodes` |
| 批量查询子节点 | `POST /api/meta/categories/nodes:children-batch` | ✅ | 降低前端 N 次展开请求 |
| taxonomy 元数据 | `GET /api/meta/taxonomies/{code}` | ✅ | 返回 `levelConfigs` |
| 旧 UNSPSC 固定层级接口 | `/api/meta/categories/unspsc/**` | ❌（已下线） | 已按完整迁移方案移除 |

---

## 3. 统一节点模型

```json
{
  "id": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
  "taxonomy": "UNSPSC",
  "code": "27121504",
  "name": "Machine screws",
  "level": 4,
  "parentId": "cae7a410-f951-4780-bad1-3c15ebed4dd4",
  "path": "/UNSPSC/27/2712/271215/27121504",
  "hasChildren": true,
  "leaf": false,
  "status": "ACTIVE",
  "sort": 120,
  "createdAt": "2026-02-20T10:00:00Z",
  "updatedAt": null
}
```

字段说明：

- `level` 从 `1` 开始递增。
- `parentId` 为 `null` 表示根节点。
- `hasChildren`/`leaf` 用于前端快速渲染展开状态。
- `taxonomy` 当前实现支持 `UNSPSC`，后续可扩展。

---

## 4. 通用接口

### 4.1 查询子节点（逐级加载）

- 方法：`GET`
- 路径：`/api/meta/categories/nodes`

**Query 参数**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| taxonomy | string | 是 | - | 当前支持 `UNSPSC` |
| parentId | UUID | 否 | null | 父节点 ID；为空查询根节点 |
| level | int | 否 | - | 层级（从 1 开始） |
| keyword | string | 否 | - | 当前层关键字过滤（编码/名称） |
| status | string | 否 | ACTIVE | 状态过滤；支持 `ACTIVE/INACTIVE/DRAFT/ALL`，其中 `ALL` 表示“除 deleted 外全部状态” |
| page | int | 否 | 0 | 0-based |
| size | int | 否 | 50 | 每页大小 |

**响应**：`Page<MetaCategoryNodeDto>`

---

### 4.2 查询节点路径（面包屑回显）

- 方法：`GET`
- 路径：`/api/meta/categories/nodes/{id}/path`

**Query 参数**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| taxonomy | string | 是 | - | 当前支持 `UNSPSC` |

**响应**：`List<MetaCategoryNodeDto>`（从根到当前节点有序）

---

### 4.3 通用搜索（返回完整路径）

- 方法：`GET`
- 路径：`/api/meta/categories/search`

**Query 参数**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| taxonomy | string | 是 | - | 当前支持 `UNSPSC` |
| keyword | string | 是 | - | 编码/名称关键字 |
| scopeNodeId | UUID | 否 | - | 限定在该节点子树内 |
| maxDepth | int | 否 | - | 相对 `scopeNodeId` 的向下深度 |
| status | string | 否 | ACTIVE | 状态过滤；支持 `ACTIVE/INACTIVE/DRAFT/ALL`，其中 `ALL` 表示“除 deleted 外全部状态” |
| page | int | 否 | 0 | 0-based |
| size | int | 否 | 20 | 每页大小 |

**响应（示例）**

```json
{
  "content": [
    {
      "node": {
        "id": "l4-uuid",
        "taxonomy": "UNSPSC",
        "code": "27121504",
        "name": "Machine screws",
        "level": 4,
        "parentId": "l3-uuid",
        "path": "/UNSPSC/27/2712/271215/27121504",
        "hasChildren": true,
        "leaf": false,
        "status": "ACTIVE",
        "sort": 120
      },
      "path": "/UNSPSC/27/2712/271215/27121504",
      "pathNodes": [
        { "id": "l1-uuid", "code": "27", "name": "Tools and General Machinery", "level": 1 },
        { "id": "l2-uuid", "code": "2712", "name": "Hardware", "level": 2 },
        { "id": "l3-uuid", "code": "271215", "name": "Nuts", "level": 3 },
        { "id": "l4-uuid", "code": "27121504", "name": "Machine screws", "level": 4 }
      ]
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "size": 20,
  "number": 0
}
```

---

### 4.4 批量查询多个父节点的子节点

- 方法：`POST`
- 路径：`/api/meta/categories/nodes:children-batch`

**请求体（示例）**

```json
{
  "taxonomy": "UNSPSC",
  "parentIds": [
    "cae7a410-f951-4780-bad1-3c15ebed4dd4",
    "f40e8e8e-b2f6-4f75-a6d2-f4254e91dbf7"
  ],
  "status": "ACTIVE"
}
```

**响应**：`Map<parentId, List<MetaCategoryNodeDto>>`

---

### 4.5 taxonomy 元数据（方案 A）

- 方法：`GET`
- 路径：`/api/meta/taxonomies/{code}`

**响应（示例）**

```json
{
  "code": "UNSPSC",
  "name": "UNSPSC",
  "status": "ACTIVE",
  "levelConfigs": [
    { "level": 1, "displayName": "Segment" },
    { "level": 2, "displayName": "Family" },
    { "level": 3, "displayName": "Class" },
    { "level": 4, "displayName": "Commodity" }
  ]
}
```

---

## 5. 数据模型与索引建议

### 5.1 模型原则

- 分类主表：`meta_category_def`（父子关系、状态、层级字段）。
- 闭包表：`category_hierarchy`（祖先-后代关系 + 距离）。
- 唯一约束建议：`(taxonomy, code)`（当前实现可在后续 migration 补齐 taxonomy 维度唯一约束）。

### 5.2 闭包表建议

- `distance=0`：节点到自身。
- `distance=1`：直接父子。
- 子树迁移时，优先基于闭包关系重建受影响路径。

### 5.3 索引方案（已落地）

> 已通过迁移脚本 `V16__category_generic_query_indexes.sql` 落地。

- `meta_category_def`
  - `idx_meta_cat_def_parent_status_sort_code`：`(parent_def_id, lower(status), sort_order, code_key)`
  - `idx_meta_cat_def_depth_status_sort_code`：`(depth, lower(status), sort_order, code_key)`
  - `idx_meta_cat_def_status_code`：`(lower(status), code_key)`
- `category_hierarchy`
  - `idx_cat_hierarchy_ancestor_distance_desc`：`(ancestor_def_id, distance, descendant_def_id)`
  - `idx_cat_hierarchy_desc_distance_ancestor`：`(descendant_def_id, distance, ancestor_def_id)`
- `meta_category_version`
  - `idx_category_version_latest_def`：`(is_latest, category_def_id)`
- 关键词检索（依赖 `pg_trgm`，权限不足时自动跳过）
  - `idx_cat_ver_latest_display_name_trgm`：`lower(display_name)`（`where is_latest = true`）
  - `idx_cat_def_code_key_trgm`：`lower(code_key)`
  - `idx_cat_def_full_path_name_trgm`：`lower(full_path_name)`

---

## 6. 迁移与兼容说明

- 已执行完整迁移，不保留旧接口并行。
- 前端统一使用：`nodes + path + search + nodes:children-batch + taxonomies`。
- 当前 taxonomy 白名单：`UNSPSC`。

---

## 7. 通用错误响应

当抛出 `IllegalArgumentException` 时返回 400：

```json
{
  "timestamp": "2026-02-28T10:00:00+08:00",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_ARGUMENT",
  "message": "taxonomy is required"
}
```

常见错误语义：

- `taxonomy is required`
- `taxonomy not supported: {code}`
- `category not found: id={id}`
- `scope node not found: id={id}`

---

## 8. 本次新增（2026-03-09）

> 本节为本次迭代新增内容，与上文“通用分类查询接口”区分。
>
> 上文 1~7 章节继续描述查询体系（`nodes/path/search/nodes:children-batch/taxonomies`）；
> 本节仅描述新增的分类管理写接口（CRUD）与主标识语义调整。

### 8.1 变更摘要

- 新增分类写接口：创建、全量更新、局部更新、删除（软删除）。
- 分类主标识语义调整为：`(business_domain, code_key)`。
- `code` 创建后不可修改。
- `businessDomain` 当前采用静态枚举，创建后不可修改。
- 删除默认不级联；存在子节点时需前端二次确认后级联删除。

### 8.2 写接口清单（新增）

| 功能 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 分类详情 | `GET` | `/api/meta/categories/{id}` | 返回完整详细信息（含当前版本与历史版本） |
| 创建分类 | `POST` | `/api/meta/categories` | 新增 def + v1 version |
| 全量编辑 | `PUT` | `/api/meta/categories/{id}` | 按全量语义更新 |
| 局部编辑 | `PATCH` | `/api/meta/categories/{id}` | 按局部语义更新 |
| 删除分类 | `DELETE` | `/api/meta/categories/{id}` | 软删除；支持可选级联 |

### 8.2.1 分类详情接口（新增）

- 方法：`GET`
- 路径：`/api/meta/categories/{id}`

路径参数：

- `id`：分类 ID（UUID）。

返回字段覆盖：

- 编码与名称：`code`、`latestVersion.name`。
- 业务与状态：`businessDomain`、`status`。
- 上下级关系：`parentId/parentCode/parentName`、`rootId/rootCode/rootName`。
- 结构信息：`path`、`level`、`depth`、`sort`。
- 说明信息：`description`。
- 审计信息：`createdBy/createdAt`、`modifiedBy/modifiedAt`。
- 版本信息：`version`、`latestVersion`、`historyVersions`。

响应示例（200）：

```json
{
  "id": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
  "code": "27121504",
  "businessDomain": "MATERIAL",
  "status": "EFFECTIVE",
  "parentId": "f40e8e8e-b2f6-4f75-a6d2-f4254e91dbf7",
  "parentCode": "271215",
  "parentName": "Nuts",
  "rootId": "f1e1f3f8-80c3-4702-a4c1-2f9f5a34ad5a",
  "rootCode": "27",
  "rootName": "Tools and General Machinery",
  "path": "/27/2712/271215/27121504",
  "level": 4,
  "depth": 3,
  "sort": 12,
  "description": "仅更新描述",
  "createdBy": "alice",
  "createdAt": "2026-03-09T09:10:00Z",
  "modifiedBy": "bob",
  "modifiedAt": "2026-03-09T09:40:00Z",
  "version": 3,
  "latestVersion": {
    "versionNo": 3,
    "versionDate": "2026-03-09T09:40:00Z",
    "name": "Machine screws and bolts",
    "description": "仅更新描述",
    "updatedBy": "bob"
  },
  "historyVersions": [
    {
      "versionNo": 3,
      "versionDate": "2026-03-09T09:40:00Z",
      "name": "Machine screws and bolts",
      "description": "仅更新描述",
      "updatedBy": "bob",
      "latest": true
    },
    {
      "versionNo": 2,
      "versionDate": "2026-03-09T09:25:00Z",
      "name": "Machine screws and bolts",
      "description": "更新描述",
      "updatedBy": "bob",
      "latest": false
    },
    {
      "versionNo": 1,
      "versionDate": "2026-03-09T09:10:00Z",
      "name": "Machine screws",
      "description": "用于连接紧固件分类",
      "updatedBy": "alice",
      "latest": false
    }
  ]
}
```

### 8.3 请求字段（写接口）

```json
{
  "code": "27121504",
  "name": "Machine screws",
  "businessDomain": "MATERIAL",
  "parentId": "cae7a410-f951-4780-bad1-3c15ebed4dd4",
  "status": "CREATED",
  "description": "用于连接紧固件分类"
}
```

字段说明：

- `code`：分类编码，创建后不可变。
- `name`：分类名称，写入 `meta_category_version.display_name`。
- `businessDomain`：业务领域，写入 `meta_category_def.business_domain`。
- `parentId`：父分类 ID，可为空（根节点）。
- `status`：接口语义状态（见 8.4）。
- `description`：写入 `meta_category_version.structure_json.description`。
- `sort`：可选；若不传由系统自动分配（同级最大值 + 1）。

### 8.3.1 排序规则（自动化）

- 创建时：`sort` 缺省自动分配为同级 `max(sort) + 1`。
- 同级拖拽或改序时：按目标顺序自动重排为连续序号（1..N）。
- 跨父节点移动时：
  - 节点自动插入目标父级同级序列，
  - 旧父级与新父级都执行连续重排（1..N）。
- 删除时：删除节点后，原父级同级序列自动重排（1..N）。

说明：管理端不建议让用户手填排序值，优先使用拖拽与系统自动重排。

### 8.4 状态映射（写接口）

- `CREATED -> draft`
- `EFFECTIVE -> active`
- `INVALID -> inactive`
- 删除时内部状态：`deleted`

### 8.5 删除语义（重点）

- 默认：`cascade=false`，仅删除本级。
- 当目标节点存在子节点时：
  - 若未携带 `cascade=true&confirm=true`，返回冲突错误。
  - 前端确认后，携带 `cascade=true&confirm=true` 再次调用执行级联软删。

删除接口参数：

- `cascade`：是否级联删除（默认 `false`）。
- `confirm`：是否确认级联删除（默认 `false`）。

### 8.6 新增错误码

- `CATEGORY_HAS_CHILDREN`：存在子节点，需确认级联删除（HTTP 409）。
- `CATEGORY_NOT_FOUND`：分类不存在或不可用（HTTP 404）。

### 8.7 版本策略（写接口）

- 创建：生成 `version_no=1`，`is_latest=true`。
- 编辑：内容变化时新增版本，旧版本 `is_latest=false`。
- `versionDate` 规则：
  - `v1`：创建时间。
  - `v2+`：每次编辑生成新版本时的 `created_at`。

### 8.8 数据库迁移与索引（本次）

- 新增迁移：`V17__category_business_domain_and_crud_indexes.sql`
- 主要内容：
  - 新增 `meta_category_def.business_domain` 并回填历史值。
  - 唯一约束由 `code_key` 调整为 `(business_domain, code_key)`。
  - 新增写场景索引：
    - `idx_meta_cat_def_domain_parent_status_sort`
    - `idx_meta_cat_def_domain_code`

### 8.9 与历史文档边界说明

- 1~7 章节：通用分类查询接口（既有内容）。
- 8 章节：本次新增 CRUD 与主标识演进（增量内容）。

### 8.9.1 管理态状态查询（新增）

- 管理界面查询建议显式传 `status=ALL`。
- `ALL` 语义：返回 `draft/active/inactive`，排除 `deleted`。
- 业务展示界面可继续使用 `status=ACTIVE` 只看生效分类。

### 8.10 联调示例（成功）

#### 8.10.1 创建分类

- 方法：`POST`
- 路径：`/api/meta/categories`

请求示例：

```http
POST /api/meta/categories?operator=alice HTTP/1.1
Content-Type: application/json
```

```json
{
  "code": "27121504",
  "name": "Machine screws",
  "businessDomain": "MATERIAL",
  "parentId": "cae7a410-f951-4780-bad1-3c15ebed4dd4",
  "status": "CREATED",
  "description": "用于连接紧固件分类"
}
```

响应示例（200）：

```json
{
  "id": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
  "code": "27121504",
  "businessDomain": "MATERIAL",
  "status": "CREATED",
  "parentId": "cae7a410-f951-4780-bad1-3c15ebed4dd4",
  "rootId": "f1e1f3f8-80c3-4702-a4c1-2f9f5a34ad5a",
  "rootCode": "27",
  "path": "/27/2712/271215/27121504",
  "level": 4,
  "depth": 3,
  "sort": 57,
  "createdBy": "alice",
  "createdAt": "2026-03-09T09:10:00Z",
  "latestVersion": {
    "versionNo": 1,
    "versionDate": "2026-03-09T09:10:00Z",
    "name": "Machine screws",
    "description": "用于连接紧固件分类",
    "updatedBy": "alice"
  }
}
```

#### 8.10.2 全量更新分类

- 方法：`PUT`
- 路径：`/api/meta/categories/{id}`

请求示例：

```http
PUT /api/meta/categories/8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1?operator=bob HTTP/1.1
Content-Type: application/json
```

```json
{
  "name": "Machine screws and bolts",
  "businessDomain": "MATERIAL",
  "parentId": "f40e8e8e-b2f6-4f75-a6d2-f4254e91dbf7",
  "status": "EFFECTIVE",
  "description": "更新描述"
}
```

响应示例（200）：

```json
{
  "id": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
  "code": "27121504",
  "businessDomain": "MATERIAL",
  "status": "EFFECTIVE",
  "parentId": "f40e8e8e-b2f6-4f75-a6d2-f4254e91dbf7",
  "rootId": "f1e1f3f8-80c3-4702-a4c1-2f9f5a34ad5a",
  "rootCode": "27",
  "path": "/27/2712/271215/27121504",
  "level": 4,
  "depth": 3,
  "sort": 12,
  "createdBy": "alice",
  "createdAt": "2026-03-09T09:10:00Z",
  "latestVersion": {
    "versionNo": 2,
    "versionDate": "2026-03-09T09:25:00Z",
    "name": "Machine screws and bolts",
    "description": "更新描述",
    "updatedBy": "bob"
  }
}
```

#### 8.10.3 局部更新分类

- 方法：`PATCH`
- 路径：`/api/meta/categories/{id}`

请求示例：

```http
PATCH /api/meta/categories/8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1?operator=bob HTTP/1.1
Content-Type: application/json
```

```json
{
  "description": "仅更新描述"
}
```

响应示例（200）：

```json
{
  "id": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
  "code": "27121504",
  "businessDomain": "MATERIAL",
  "status": "EFFECTIVE",
  "parentId": "f40e8e8e-b2f6-4f75-a6d2-f4254e91dbf7",
  "rootId": "f1e1f3f8-80c3-4702-a4c1-2f9f5a34ad5a",
  "rootCode": "27",
  "path": "/27/2712/271215/27121504",
  "level": 4,
  "depth": 3,
  "sort": 12,
  "createdBy": "alice",
  "createdAt": "2026-03-09T09:10:00Z",
  "latestVersion": {
    "versionNo": 3,
    "versionDate": "2026-03-09T09:40:00Z",
    "name": "Machine screws and bolts",
    "description": "仅更新描述",
    "updatedBy": "bob"
  }
}
```

#### 8.10.4 删除分类

- 方法：`DELETE`
- 路径：`/api/meta/categories/{id}`

请求示例（仅本级）：

```http
DELETE /api/meta/categories/8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1?cascade=false&confirm=false&operator=alice HTTP/1.1
```

响应示例（200）：

```json
{
  "id": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
  "cascade": false,
  "deletedCount": 1
}
```

请求示例（级联）：

```http
DELETE /api/meta/categories/f1e1f3f8-80c3-4702-a4c1-2f9f5a34ad5a?cascade=true&confirm=true&operator=alice HTTP/1.1
```

响应示例（200）：

```json
{
  "id": "f1e1f3f8-80c3-4702-a4c1-2f9f5a34ad5a",
  "cascade": true,
  "deletedCount": 12
}
```

### 8.11 联调示例（失败）

#### 8.11.1 存在子节点但未确认级联

响应示例（409）：

```json
{
  "timestamp": "2026-03-09T10:00:00+08:00",
  "status": 409,
  "error": "Conflict",
  "code": "CATEGORY_HAS_CHILDREN",
  "message": "category has children, please confirm cascade deletion with cascade=true&confirm=true"
}
```

#### 8.11.2 分类不存在

响应示例（404）：

```json
{
  "timestamp": "2026-03-09T10:01:00+08:00",
  "status": 404,
  "error": "Not Found",
  "code": "CATEGORY_NOT_FOUND",
  "message": "category not found: id=00000000-0000-0000-0000-000000000000"
}
```

#### 8.11.3 编码或业务域非法

响应示例（400）：

```json
{
  "timestamp": "2026-03-09T10:02:00+08:00",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_ARGUMENT",
  "message": "unsupported businessDomain: UNKNOWN"
}
```

#### 8.11.4 管理态查询全部状态

请求示例：

```http
GET /api/meta/categories/nodes?taxonomy=UNSPSC&level=1&status=ALL&page=0&size=200 HTTP/1.1
```

说明：

- 返回 `draft/active/inactive`。
- 不返回 `deleted`。
