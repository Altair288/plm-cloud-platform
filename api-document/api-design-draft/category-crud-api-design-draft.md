# 分类创建/编辑/删除接口设计规范草案

更新时间：2026-03-06
阶段：设计草案（本阶段不改代码）

---

## 1. 目标与范围

本文用于定义分类管理写接口（Create/Update/Delete）的统一设计，覆盖：

- 分类创建
- 分类编辑
- 分类删除（软删除）

并明确与当前查询接口（`/api/meta/categories/nodes/path/search`）和现有数据库结构（`meta_category_def`、`meta_category_version`、`category_hierarchy`）的衔接方式。

不在本阶段实现导入/导出接口，仅预留兼容点。

---

## 2. 现状与约束

## 2.1 现有核心表结构（摘录）

- `plm_meta.meta_category_def`
  - `id`
  - `code_key`
  - `status`
  - `parent_def_id`
  - `path`
  - `depth`
  - `sort_order`
  - `full_path_name`
  - `is_leaf`
  - `external_code`
  - `created_at`
  - `created_by`
  - `source_level`

- `plm_meta.meta_category_version`
  - `id`
  - `category_def_id`
  - `version_no`
  - `display_name`
  - `rule_resolved_code_prefix`
  - `structure_json`
  - `hash`
  - `is_latest`
  - `created_at`
  - `created_by`

- `plm_meta.category_hierarchy`
  - `ancestor_def_id`
  - `descendant_def_id`
  - `distance`

## 2.2 已上线查询能力

- `GET /api/meta/categories/nodes`
- `GET /api/meta/categories/nodes/{id}/path`
- `GET /api/meta/categories/search`
- `POST /api/meta/categories/nodes:children-batch`
- `GET /api/meta/taxonomies/{code}`

## 2.3 新需求要点

创建/编辑需覆盖字段：

1. 分类编码
2. 分类名称
3. 业务领域（新维度）
4. 父级分类
5. 分类状态（创建状态/生效状态/失效状态）
6. 根分类
7. 详细描述
8. 版本
9. 版本日期
10. 创建人
11. 创建日期

其中 6~11 大部分可由系统或已有查询结果自动推导，不要求前端全部手填。

补充结论：

- 本阶段以 `business_domain + code_key` 作为分类主标识语义。
- `taxonomy` 在当前规划中降级为兼容参数，后续可能移除。
- `businessDomain` 本阶段采用静态枚举，不引入字典接口。

---

## 3. 字段语义与存储映射

## 3.1 API 字段到数据库映射建议

| 业务字段 | API字段 | 当前承载位置 | 说明 |
|---|---|---|---|
| 分类编码 | `code` | `meta_category_def.code_key` | 在 `businessDomain` 维度内唯一，创建后不可变 |
| 分类名称 | `name` | `meta_category_version.display_name` | 版本化字段 |
| 业务领域 | `businessDomain` | `meta_category_def.business_domain`（新增建议） | 稳定归属维度，建议放 def 层，不随版本漂移 |
| 父级分类 | `parentId` | `meta_category_def.parent_def_id` | 支持为空（根） |
| 分类状态 | `status` | `meta_category_def.status` | 需要统一状态枚举映射 |
| 根分类 | `rootId/rootCode` | 由 `path`/闭包关系推导 | 建议响应返回，不单独存冗余列 |
| 详细描述 | `description` | `meta_category_version.structure_json` | 版本化字段 |
| 版本 | `versionNo` | `meta_category_version.version_no` | 系统维护 |
| 版本日期 | `versionDate` | `meta_category_version.created_at` | 系统维护 |
| 创建人 | `createdBy` | def/version 的 `created_by` | def为首次创建人，version为本次操作人 |
| 创建日期 | `createdAt` | def/version 的 `created_at` | def为首次创建时间 |

说明：

- `taxonomy` 非当前核心维度，不建议作为唯一主键的一部分。
- 若接口暂时保留 `taxonomy` 参数，仅用于兼容已有查询调用链，写接口可标记为可选并默认 `UNSPSC`。

## 3.2 状态枚举建议（接口语义）

前端语义：

- `CREATED`（创建状态）
- `EFFECTIVE`（生效状态）
- `INVALID`（失效状态）

后端落库映射（兼容现状）：

- `CREATED -> draft`
- `EFFECTIVE -> active`
- `INVALID -> inactive`
- 删除时额外使用 `deleted`（内部状态，不建议前端主动提交）

---

## 4. 接口设计草案

## 4.1 创建分类

- 方法：`POST`
- 路径：`/api/meta/categories`

### 请求体（建议）

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

### 处理规则

- 校验 `(businessDomain, code)` 唯一。
- 写入 `meta_category_def`（首条 def）。
- 写入 `meta_category_version`（`version_no=1`，`is_latest=true`）。
- `businessDomain` 写入 `meta_category_def.business_domain`。
- `description` 写入 `structure_json`。
- 维护闭包表：
  - 插入 self 关系（distance=0）
  - 继承父路径祖先关系（distance+1）
- 回写 `path/depth/full_path_name/is_leaf`。

### 响应（建议）

返回 `MetaCategoryDetailDto`（含 def + latestVersion）。

---

## 4.2 编辑分类

- 方法：`PUT`（全量）/`PATCH`（局部）
- 路径：`/api/meta/categories/{id}`

### 请求体（建议）

```json
{
  "name": "Machine screws and bolts",
  "businessDomain": "MATERIAL",
  "parentId": "f40e8e8e-b2f6-4f75-a6d2-f4254e91dbf7",
  "status": "EFFECTIVE",
  "description": "更新描述"
}
```

### 处理规则

- `code` 创建后禁止修改。
- `businessDomain` 默认不允许修改（涉及树归属迁移）；若未来开放，需单独定义迁移接口。
- 名称/业务领域/描述：新增 category version，旧版本 `is_latest=false`。
- `parentId` 变更：
  - 更新 `meta_category_def.parent_def_id`
  - 重建受影响子树闭包关系
  - 重算受影响子树 `path/depth/full_path_name`
- 状态更新写入 `meta_category_def.status`（含映射）。

---

## 4.3 删除分类（软删除）

- 方法：`DELETE`
- 路径：`/api/meta/categories/{id}`

### Query 参数（建议）

- `cascade`（默认 `false`）
- `confirm`（默认 `false`）

### 处理规则（最终明确）

- 接口能力上允许级联删除（可选）。
- 默认行为：仅删除本级（`cascade=false`）。
- 当目标节点存在子分级时：
  - 默认返回“存在子分级，需确认是否级联删除”的提示（建议 409 + 业务错误码）。
  - 前端二次确认后，携带 `cascade=true&confirm=true` 再次请求。
- 级联删除行为：删除该分类节点及其全部子树数据（软删除语义）。
- 软删除落库：`meta_category_def.status='deleted'`；查询端继续按状态过滤。
- 闭包表可保留（审计/追溯），不要求物理删除关系。

### 推荐错误码（删除场景）

- `CATEGORY_HAS_CHILDREN`：存在子分级，需确认级联删除。
- `CATEGORY_HAS_REFERENCES`：存在业务引用，需确认风险或先解除引用。
- `CATEGORY_NOT_FOUND`：分类不存在或已删除。

---

## 5. 响应模型建议

## 5.1 分类详情响应（建议）

```json
{
  "id": "cf7b9f98-9425-4e81-b3d2-33ecd9079932",
  "code": "A",
  "businessDomain": "MATERIAL",
  "status": "EFFECTIVE",
  "parentId": null,
  "rootId": "cf7b9f98-9425-4e81-b3d2-33ecd9079932",
  "path": "/A",
  "depth": 1,
  "createdBy": "alice",
  "createdAt": "2026-03-06T08:00:00Z",
  "latestVersion": {
    "versionNo": 3,
    "versionDate": "2026-03-06T08:10:00Z",
    "name": "Raw Materials、 Chemicals、 Paper、 Fuel",
    "businessDomain": "manufacturing",
    "description": "...",
    "updatedBy": "alice"
  }
}
```

---

## 6. 版本策略（与属性一致）

- `def` 保存稳定身份与层级关系主键。
- `version` 保存可演进内容（name、业务领域、描述等）。
- 编辑内容变更时生成新版本。
- 可引入 `hash` 做幂等：内容不变则跳过新版本生成。

版本日期约定：

- `v1` 使用创建时间戳。
- `v2+` 使用每次编辑产生新版本时的 `created_at`。

---

## 7. 校验与约束建议

- `code`：必填、长度限制、字符集约束（如大写字母数字下划线）。
- `name`：必填、长度限制。
- `businessDomain`：必填，建议枚举化（如 `PRODUCT/MATERIAL/BOM/PROCESS/TEST/EXPERIMENT`）。
- `parentId`：不能指向自身；不能形成环。
- `status`：仅允许 `CREATED/EFFECTIVE/INVALID`。
- `taxonomy`：写接口可选兼容字段，后续可能移除。

---

## 8. 事务与一致性

写操作建议单事务内完成：

1. 校验
2. 写 def/version
3. 更新 closure/path/depth/fullPathName
4. 提交

任何一步失败整体回滚，防止树结构与版本数据不一致。

---

## 9. 与前端联动建议

前端创建/编辑表单可只提交：

- `code`
- `name`
- `businessDomain`
- `parentId`
- `status`
- `description`

由后端自动补充或返回：

- `rootId/rootCode`
- `versionNo/versionDate`
- `createdBy/createdAt`
- `path/level/hasChildren`

---

## 10. 数据库演进建议（后续实现阶段）

为最小成本落地，建议两步走：

1. 在 `meta_category_def` 增加 `business_domain` 列（必填）。
2. 将唯一约束从 `code_key` 调整为 `(business_domain, code_key)`。
3. `description` 仍放 `meta_category_version.structure_json`，按需补 generated columns。

可选迁移项：

- `meta_category_version` 增 generated column：`description_text`（若需文本检索）
- 索引：`(business_domain, parent_def_id, lower(status), sort_order)`
- 若未来彻底移除 taxonomy，同步下线接口参数与相关文档

---

## 11. 评审结论（已确认）

- 分类编码创建后不可更改。
- 删除流程支持级联，但必须在存在子节点/关联数据时由前后端共同校验并显式确认后执行。
- 主标识语义采用 `(business_domain, code_key)`。
- `versionDate`：`v1` 复用创建时间，`v2+` 取每次编辑生成版本时的时间戳。
- `businessDomain`：本阶段采用静态枚举值。

## 11.1 待后续二次确认

- 静态枚举的最终值清单与展示文案（中英文）是否冻结。

---

## 12. 下一阶段实现清单（预告）

- 新增 CRUD Controller/Service/Repository。
- 新增 DTO：`CreateCategoryRequest`、`UpdateCategoryRequest`、`CategoryDetailDto`。
- 闭包表维护逻辑封装（含 parent 变更）。
- 增加单元测试与集成测试（创建、改父级、软删）。
