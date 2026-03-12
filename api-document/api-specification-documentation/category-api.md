# 分类通用 API 文档（plm-attribute-service）

更新时间：2026-03-12

> 本文为分类接口正式文档（已完成 taxonomy 移除重构）。
>
> 当前分类体系以 businessDomain 作为唯一业务归属维度。

---

## 1. 设计目标

- 分类树查询、搜索、路径回显统一基于 businessDomain。
- 支持任意层级深度，不在接口层写死层级名称。
- 搜索命中返回完整路径信息（path + pathNodes）。
- 使用闭包表支撑子树检索与路径回显。
- 管理态支持 status=ALL（排除 deleted）。

---

## 2. 功能列表（是否实现）

| 功能 | 相关接口 | 是否实现 | 备注 |
|---|---|---:|---|
| 查询子节点（逐级加载） | GET /api/meta/categories/nodes | ✅ | businessDomain/parentId/level/keyword/status/page/size |
| 查询节点路径（面包屑回显） | GET /api/meta/categories/nodes/{id}/path | ✅ | 返回从根到当前节点有序列表 |
| 通用搜索（返回完整路径） | GET /api/meta/categories/search | ✅ | 返回 path 与 pathNodes |
| 批量查询子节点 | POST /api/meta/categories/nodes:children-batch | ✅ | 降低前端 N 次展开请求 |
| 分类详情 | GET /api/meta/categories/{id} | ✅ | 返回完整详情与历史版本 |
| 创建分类 | POST /api/meta/categories | ✅ | 主标识语义：businessDomain + code |
| 全量更新 | PUT /api/meta/categories/{id} | ✅ | 内容变化新增版本 |
| 局部更新 | PATCH /api/meta/categories/{id} | ✅ | 局部更新语义 |
| 删除分类 | DELETE /api/meta/categories/{id} | ✅ | 软删除，支持可选级联 |
| taxonomy 元数据 | GET /api/meta/taxonomies/{code} | ❌（已下线） | 已移除 |

---

## 3. 统一节点模型

```json
{
  "id": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
  "businessDomain": "MATERIAL",
  "code": "27121504",
  "name": "Machine screws",
  "level": 4,
  "parentId": "cae7a410-f951-4780-bad1-3c15ebed4dd4",
  "path": "/27/2712/271215/27121504",
  "hasChildren": true,
  "leaf": false,
  "status": "ACTIVE",
  "sort": 120,
  "createdAt": "2026-02-20T10:00:00Z",
  "updatedAt": null
}
```

字段说明：

- level 从 1 开始递增。
- parentId 为 null 表示根节点。
- hasChildren/leaf 用于前端展开状态。
- businessDomain 当前支持：PRODUCT/MATERIAL/BOM/PROCESS/TEST/EXPERIMENT。

---

## 4. 查询接口

### 4.1 查询子节点（逐级加载）

- 方法：GET
- 路径：/api/meta/categories/nodes

Query 参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| businessDomain | string | 是 | - | 分类业务领域 |
| parentId | UUID | 否 | null | 父节点 ID；为空查询根节点 |
| level | int | 否 | - | 层级（从 1 开始） |
| keyword | string | 否 | - | 当前层关键字过滤（编码/名称） |
| status | string | 否 | ACTIVE | 状态过滤；支持 ACTIVE/INACTIVE/DRAFT/ALL |
| page | int | 否 | 0 | 0-based |
| size | int | 否 | 50 | 每页大小 |

响应：Page<MetaCategoryNodeDto>

### 4.2 查询节点路径

- 方法：GET
- 路径：/api/meta/categories/nodes/{id}/path

Query 参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| businessDomain | string | 是 | - | 分类业务领域 |

响应：List<MetaCategoryNodeDto>（从根到当前节点）

### 4.3 通用搜索

- 方法：GET
- 路径：/api/meta/categories/search

Query 参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| businessDomain | string | 是 | - | 分类业务领域 |
| keyword | string | 是 | - | 编码/名称关键字 |
| scopeNodeId | UUID | 否 | - | 限定在该节点子树内 |
| maxDepth | int | 否 | - | 相对 scopeNodeId 的向下深度 |
| status | string | 否 | ACTIVE | 状态过滤；支持 ACTIVE/INACTIVE/DRAFT/ALL |
| page | int | 否 | 0 | 0-based |
| size | int | 否 | 20 | 每页大小 |

### 4.4 批量查询多个父节点的子节点

- 方法：POST
- 路径：/api/meta/categories/nodes:children-batch

请求体示例

```json
{
  "businessDomain": "MATERIAL",
  "parentIds": [
    "cae7a410-f951-4780-bad1-3c15ebed4dd4",
    "f40e8e8e-b2f6-4f75-a6d2-f4254e91dbf7"
  ],
  "status": "ALL"
}
```

响应：Map<parentId, List<MetaCategoryNodeDto>>

---

## 5. 写接口要点

- 主标识语义：(businessDomain, code)。
- code 创建后不可修改。
- businessDomain 创建后不可修改。
- 排序规则：
  - 创建自动 max+1。
  - 同级/跨父移动后自动连续重排。
  - 删除后同级自动重排。
- 删除语义：
  - 默认 cascade=false。
  - 有子节点时需 cascade=true&confirm=true。

---

## 6. 通用错误响应

```json
{
  "timestamp": "2026-03-12T10:00:00+08:00",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_ARGUMENT",
  "message": "businessDomain is required"
}
```

常见错误语义：

- businessDomain is required
- businessDomain not supported: {value}
- category not found: id={id}
- scope node not found: id={id}
- CATEGORY_HAS_CHILDREN
- CATEGORY_NOT_FOUND
