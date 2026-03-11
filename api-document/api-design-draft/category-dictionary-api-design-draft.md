# 分类字典接口设计草案（评审稿）

更新时间：2026-03-11  
阶段：设计草案（本轮不改代码）

---

## 1. 背景与目标

当前分类管理前端已通过 `GET /api/meta/categories/{id}` 获取完整元数据，但以下字段仍缺少统一字典来源：

- `businessDomain`
- `status`（接口语义值与DB落库值映射）
- `taxonomy` 与层级展示文案

若前端硬编码这些映射，会带来多端不一致、国际化困难、升级成本高的问题。  
本草案目标是定义统一字典接口，供分类管理页与后续元数据模块复用。

---

## 2. 设计原则

- 单一事实源：字典由后端统一输出，前端只做渲染。
- 兼容当前实现：优先复用已有结构（`MetaCategoryCrudService` 枚举、`/api/meta/taxonomies/{code}`）。
- 面向演进：现阶段可由静态配置/代码提供，后续可平滑迁移到数据库字典表。
- 批量获取：支持页面初始化一次拉取多个字典，减少往返请求。

---

## 3. 结合现状的字典范围

### 3.1 已确认来源（当前系统）

- `businessDomain`
  - 来源：`MetaCategoryCrudService.BusinessDomain` 枚举。
  - 当前值：`PRODUCT`、`MATERIAL`、`BOM`、`PROCESS`、`TEST`、`EXPERIMENT`。
- `categoryStatusApi`
  - 来源：`mapDbStatusToApi/mapApiStatusToDb` 映射。
  - 当前API语义：`CREATED`、`EFFECTIVE`、`INVALID`、`DELETED`。
- `categoryStatusDb`
  - 来源：`meta_category_def.status` 落库值。
  - 当前值：`draft`、`active`、`inactive`、`deleted`。
- `taxonomy`
  - 来源：`MetaCategoryGenericQueryService`（当前仅 `UNSPSC`）。

### 3.2 建议字典编码

- `META_CATEGORY_BUSINESS_DOMAIN`
- `META_CATEGORY_STATUS`
- `META_TAXONOMY`

---

## 4. 接口草案

### 4.1 批量字典接口（主入口）

- 方法：`POST`
- 路径：`/api/meta/dictionaries:batch`
- 用途：页面初始化一次取齐多个字典。

请求示例：

```json
{
  "codes": [
    "META_CATEGORY_BUSINESS_DOMAIN",
    "META_CATEGORY_STATUS",
    "META_TAXONOMY"
  ],
  "lang": "zh-CN"
}
```

响应示例：

```json
{
  "items": [
    {
      "code": "META_CATEGORY_BUSINESS_DOMAIN",
      "name": "分类业务领域",
      "version": 1,
      "source": "STATIC",
      "locale": "zh-CN",
      "entries": [
        { "key": "PRODUCT", "value": "PRODUCT", "label": "产品", "order": 1, "enabled": true },
        { "key": "MATERIAL", "value": "MATERIAL", "label": "物料", "order": 2, "enabled": true },
        { "key": "BOM", "value": "BOM", "label": "BOM", "order": 3, "enabled": true },
        { "key": "PROCESS", "value": "PROCESS", "label": "工艺", "order": 4, "enabled": true },
        { "key": "TEST", "value": "TEST", "label": "测试", "order": 5, "enabled": true },
        { "key": "EXPERIMENT", "value": "EXPERIMENT", "label": "实验", "order": 6, "enabled": true }
      ]
    },
    {
      "code": "META_CATEGORY_STATUS",
      "name": "分类状态",
      "version": 1,
      "source": "STATIC",
      "locale": "zh-CN",
      "entries": [
        { "key": "CREATED", "value": "CREATED", "label": "创建", "order": 1, "enabled": true, "extra": { "dbValue": "draft" } },
        { "key": "EFFECTIVE", "value": "EFFECTIVE", "label": "生效", "order": 2, "enabled": true, "extra": { "dbValue": "active" } },
        { "key": "INVALID", "value": "INVALID", "label": "失效", "order": 3, "enabled": true, "extra": { "dbValue": "inactive" } },
        { "key": "DELETED", "value": "DELETED", "label": "删除", "order": 4, "enabled": false, "extra": { "dbValue": "deleted" } }
      ]
    },
    {
      "code": "META_TAXONOMY",
      "name": "分类体系",
      "version": 1,
      "source": "SERVICE",
      "locale": "zh-CN",
      "entries": [
        { "key": "UNSPSC", "value": "UNSPSC", "label": "UNSPSC", "order": 1, "enabled": true }
      ]
    }
  ]
}
```

### 4.2 单字典接口

- 方法：`GET`
- 路径：`/api/meta/dictionaries/{code}`
- 用途：按需刷新单个字典。

Query 参数建议：

- `lang`：可选，默认 `zh-CN`。
- `includeDisabled`：可选，默认 `false`。

### 4.3 场景化聚合接口（可选）

- 方法：`GET`
- 路径：`/api/meta/dictionary-scenes/{sceneCode}`
- 示例：`/api/meta/dictionary-scenes/category-admin`
- 用途：场景本质是字典编码集合，后端解析后直接复用 batch 逻辑返回。

`category-admin` 场景建议包含：

- `META_CATEGORY_BUSINESS_DOMAIN`
- `META_CATEGORY_STATUS`
- `META_TAXONOMY`

场景配置示例（服务端配置概念）：

```json
{
  "sceneCode": "category-admin",
  "dictionaryCodes": [
    "META_CATEGORY_BUSINESS_DOMAIN",
    "META_CATEGORY_STATUS",
    "META_TAXONOMY"
  ]
}
```

---

## 5. 统一响应模型建议

```json
{
  "code": "META_CATEGORY_STATUS",
  "name": "分类状态",
  "version": 1,
  "source": "STATIC",
  "locale": "zh-CN",
  "entries": [
    {
      "key": "EFFECTIVE",
      "value": "EFFECTIVE",
      "label": "生效",
      "order": 2,
      "enabled": true,
      "extra": {
        "dbValue": "active",
        "color": "success"
      }
    }
  ]
}
```

字段说明：

- `code`：字典编码。
- `version`：字典版本号（用于缓存失效控制）。
- `source`：来源（`STATIC`/`DB`/`SERVICE`）。
- `locale`：当前字典语言区域（如 `zh-CN`、`en-US`）。
- `entries[].key`：稳定键，建议生命周期内保持不变，用于抗 `value` 变更。
- `entries[].value`：后端真实值（前端提交/匹配使用）。
- `entries[].label`：展示文案（本地化后）。
- `entries[].extra`：扩展信息（颜色、标签类型、db映射等）。

---

## 6. 与现有数据库/结构的映射建议

| 字典编码 | 当前事实来源 | 未来可落库建议 | 说明 |
|---|---|---|---|
| `META_CATEGORY_BUSINESS_DOMAIN` | `MetaCategoryCrudService.BusinessDomain` 枚举 | `plm_meta.meta_dictionary_item` | 当前由代码枚举维护 |
| `META_CATEGORY_STATUS` | 状态映射函数 + `extra.dbValue` | `plm_meta.meta_dictionary_item` | `value` 表示API状态，`extra.dbValue` 表示DB状态 |
| `META_TAXONOMY` | `MetaCategoryGenericQueryService` 固定 `UNSPSC` | `plm_meta.meta_taxonomy` | 已有 taxonomy 接口可复用 |

---

## 7. 前端落地建议（不改代码阶段）

- 页面初始化先调一次 `POST /api/meta/dictionaries:batch`。
- 表单项统一用 `value` 提交，`label` 仅展示；业务逻辑优先依赖 `key`。
- 状态颜色和Tag文本从 `entries[].extra` 读取，移除页面硬编码。
- 字典结果按 `code+version+lang` 做本地缓存（session 或内存）。

---

## 8. 实施路线（评审后定稿）

评审结论：不采用过渡阶段，直接一次性构建完整字典接口。

### 8.1 一次性完整实现范围

- 后端接口一次到位：
  - `POST /api/meta/dictionaries:batch`
  - `GET /api/meta/dictionaries/{code}`
  - `GET /api/meta/dictionary-scenes/{sceneCode}`
- 场景配置一次到位：`category-admin -> [META_CATEGORY_BUSINESS_DOMAIN, META_CATEGORY_STATUS, META_TAXONOMY]`。
- 响应模型一次到位：包含 `locale`、`entries[].key`、`entries[].value`、`entries[].label`、`entries[].extra`。
- 状态映射一次到位：`value=API状态`，`extra.dbValue=DB状态`。

### 8.2 数据与配置模型（目标态）

- 建议直接落地通用字典模型：
  - `plm_meta.meta_dictionary_def`
  - `plm_meta.meta_dictionary_item`
  - （可选）`plm_meta.meta_dictionary_scene`
- 多语言通过 `locale` 维度支持，避免后续接口结构再变更。

### 8.3 下个对话编码实施清单

- 新增数据库迁移脚本（字典定义、字典项、场景配置及初始化数据）。
- 新增字典 DTO、Repository、Service、Controller。
- 实现 scenes -> dictionary codes -> batch 复用链路。
- 对接现有 `META_TAXONOMY` 数据来源（与当前 taxonomy 服务保持一致）。
- 补充接口文档与联调示例，完成基础编译与自测。

---

## 9. 风险与评审点

- 风险1：`businessDomain` 当前仍是代码枚举，变更需发版。  
  建议：阶段B迁移到字典表。
- 风险2：`DELETED` 是否对前端可见需统一口径。  
  建议：默认 `enabled=false` 且管理端可按 `includeDisabled=true` 查看。

评审结论：

- 风险1：同意。
- 风险2：同意。
- taxonomy level 字典：不纳入本期设计范围。

---

## 10. 评审结果与执行决议

- 已确认采纳“批量字典接口 + 单字典接口 + 场景接口”三层设计。
- 已确认不采用过渡阶段，不先做静态临时方案。
- 已确认直接进入一次性完整实现：接口、数据模型、场景配置、i18n 字段同步落地。
- 下个对话将直接进入代码实现阶段（DB migration + 后端接口 + 文档与示例同步）。
