# 分类通用 API 文档（plm-attribute-service）

更新时间：2026-02-28

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
| status | string | 否 | ACTIVE | 状态过滤 |
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
| status | string | 否 | ACTIVE | 状态过滤 |
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
