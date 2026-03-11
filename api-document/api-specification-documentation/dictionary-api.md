# 字典接口 API 文档（plm-attribute-service）

更新时间：2026-03-11

> 本文为字典接口正式文档，覆盖分类管理场景所需字典能力。
>
> 当前实现已支持：批量字典、单字典、场景字典集合。

---

## 1. 功能列表（是否实现）

| 功能 | 相关接口 | 是否实现 | 备注 |
|---|---|---:|---|
| 批量查询字典 | `POST /api/meta/dictionaries:batch` | ✅ | 推荐前端初始化时使用 |
| 查询单个字典 | `GET /api/meta/dictionaries/{code}` | ✅ | 支持 `lang` 回退与 `includeDisabled` |
| 按场景查询字典集合 | `GET /api/meta/dictionary-scenes/{sceneCode}` | ✅ | 场景配置解析后复用 batch |

---

## 2. 统一响应模型

### 2.1 字典对象（MetaDictionaryDto）

```json
{
  "code": "META_CATEGORY_STATUS",
  "name": "分类状态",
  "version": 1,
  "source": "DB",
  "locale": "zh-CN",
  "entries": [
    {
      "key": "EFFECTIVE",
      "value": "EFFECTIVE",
      "label": "生效",
      "order": 2,
      "enabled": true,
      "extra": {
        "dbValue": "active"
      }
    }
  ]
}
```

字段说明：

- `code`：字典编码。
- `name`：字典名称。
- `version`：字典版本号。
- `source`：数据来源（当前为 `DB` 或 `SERVICE`）。
- `locale`：语言区域（如 `zh-CN`）。
- `entries[].key`：稳定键。
- `entries[].value`：业务值（前端提交建议使用该值）。
- `entries[].label`：展示文本。
- `entries[].order`：排序。
- `entries[].enabled`：是否启用。
- `entries[].extra`：扩展字段（例如状态映射 `dbValue`）。

---

## 3. 接口定义

### 3.1 批量查询字典

- 方法：`POST`
- 路径：`/api/meta/dictionaries:batch`

请求体：

```json
{
  "codes": [
    "META_CATEGORY_BUSINESS_DOMAIN",
    "META_CATEGORY_STATUS",
    "META_TAXONOMY"
  ],
  "lang": "zh-CN",
  "includeDisabled": false
}
```

请求字段：

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| codes | string[] | 是 | - | 字典编码集合，不能为空 |
| lang | string | 否 | zh-CN | 语言区域 |
| includeDisabled | boolean | 否 | false | 是否包含禁用项 |

响应：

```json
{
  "items": [
    {
      "code": "META_CATEGORY_BUSINESS_DOMAIN",
      "name": "分类业务领域",
      "version": 1,
      "source": "DB",
      "locale": "zh-CN",
      "entries": [
        { "key": "PRODUCT", "value": "PRODUCT", "label": "产品", "order": 1, "enabled": true, "extra": {} },
        { "key": "MATERIAL", "value": "MATERIAL", "label": "物料", "order": 2, "enabled": true, "extra": {} }
      ]
    },
    {
      "code": "META_CATEGORY_STATUS",
      "name": "分类状态",
      "version": 1,
      "source": "DB",
      "locale": "zh-CN",
      "entries": [
        { "key": "CREATED", "value": "CREATED", "label": "创建", "order": 1, "enabled": true, "extra": { "dbValue": "draft" } },
        { "key": "EFFECTIVE", "value": "EFFECTIVE", "label": "生效", "order": 2, "enabled": true, "extra": { "dbValue": "active" } },
        { "key": "INVALID", "value": "INVALID", "label": "失效", "order": 3, "enabled": true, "extra": { "dbValue": "inactive" } }
      ]
    }
  ]
}
```

实现说明：

- 字典编码会在服务端自动规范化为大写后查找。
- `codes` 去重后按请求顺序返回。
- 当指定 `lang` 未命中时，自动回退到 `zh-CN`。

---

### 3.2 查询单个字典

- 方法：`GET`
- 路径：`/api/meta/dictionaries/{code}`

Query 参数：

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| lang | string | 否 | zh-CN | 语言区域 |
| includeDisabled | boolean | 否 | false | 是否包含禁用项 |

请求示例：

```http
GET /api/meta/dictionaries/META_CATEGORY_STATUS?lang=zh-CN&includeDisabled=true HTTP/1.1
```

响应示例：

```json
{
  "code": "META_CATEGORY_STATUS",
  "name": "分类状态",
  "version": 1,
  "source": "DB",
  "locale": "zh-CN",
  "entries": [
    { "key": "CREATED", "value": "CREATED", "label": "创建", "order": 1, "enabled": true, "extra": { "dbValue": "draft" } },
    { "key": "EFFECTIVE", "value": "EFFECTIVE", "label": "生效", "order": 2, "enabled": true, "extra": { "dbValue": "active" } },
    { "key": "INVALID", "value": "INVALID", "label": "失效", "order": 3, "enabled": true, "extra": { "dbValue": "inactive" } },
    { "key": "DELETED", "value": "DELETED", "label": "删除", "order": 4, "enabled": false, "extra": { "dbValue": "deleted" } }
  ]
}
```

实现说明：

- `code` 在服务端自动转大写匹配。
- `META_TAXONOMY` 为 `SERVICE` 源字典，当前从 taxonomy 服务动态构建，返回 `UNSPSC`。

---

### 3.3 按场景查询字典集合

- 方法：`GET`
- 路径：`/api/meta/dictionary-scenes/{sceneCode}`

Query 参数：

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| lang | string | 否 | zh-CN | 语言区域 |
| includeDisabled | boolean | 否 | false | 是否包含禁用项 |

请求示例：

```http
GET /api/meta/dictionary-scenes/category-admin?lang=zh-CN HTTP/1.1
```

响应示例（结构同 batch）：

```json
{
  "items": [
    { "code": "META_CATEGORY_BUSINESS_DOMAIN", "name": "分类业务领域", "version": 1, "source": "DB", "locale": "zh-CN", "entries": [] },
    { "code": "META_CATEGORY_STATUS", "name": "分类状态", "version": 1, "source": "DB", "locale": "zh-CN", "entries": [] },
    { "code": "META_TAXONOMY", "name": "分类体系", "version": 1, "source": "SERVICE", "locale": "zh-CN", "entries": [] }
  ]
}
```

实现说明：

- 场景本质是字典编码集合配置。
- 场景解析后复用 batch 逻辑返回，保证行为一致。
- 当前内置场景：`category-admin`。

---

## 4. 当前内置字典与场景（V18 初始化）

### 4.1 字典编码

- `META_CATEGORY_BUSINESS_DOMAIN`
  - PRODUCT / MATERIAL / BOM / PROCESS / TEST / EXPERIMENT
- `META_CATEGORY_STATUS`
  - CREATED / EFFECTIVE / INVALID / DELETED
  - 其中 `extra.dbValue` 分别映射 draft / active / inactive / deleted
- `META_TAXONOMY`
  - 当前返回 UNSPSC（服务动态构建）

### 4.2 场景编码

- `category-admin`
  - 包含字典：META_CATEGORY_BUSINESS_DOMAIN, META_CATEGORY_STATUS, META_TAXONOMY

---

## 5. 通用错误响应

当抛出 `IllegalArgumentException` 时返回 400：

```json
{
  "timestamp": "2026-03-11T12:30:00+08:00",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_ARGUMENT",
  "message": "codes is required"
}
```

常见错误语义：

- `codes is required`
- `dictionary not found: code={code}, locale={locale}`
- `sceneCode is required`
- `dictionary scene not found: sceneCode={sceneCode}, locale={locale}`
- `invalid dictionary scene configuration`
- `invalid dictionary extra_json`

---

## 6. 联调建议

- 前端初始化优先调用 `POST /api/meta/dictionaries:batch`。
- 业务逻辑建议使用 `entries[].key`，提交值使用 `entries[].value`。
- 状态展示建议读取 `entries[].extra.dbValue` 做 API/DB 状态映射透出。
