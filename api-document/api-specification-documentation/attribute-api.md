# 属性相关 API 文档（plm-attribute-service）

更新时间：2026-03-12

> 本文覆盖“属性元数据（Meta Attribute Def/Version）”的查询与写入接口；旧版 `/api/attributes` 已移除。
>
> 约定：
>
> - `categoryCode` 使用业务域内分类的 `codeKey`（例如：`44120000`）。
> - `attrKey` 对应 `meta_attribute_def.key`（即属性编码）。
> - 分类编码、分类名称、属性编码、枚举值编码均按 `businessDomain` 维度唯一。
> - 属性名称、枚举值名称允许在不同分类下重复，但编码不允许在同一 `businessDomain` 下重复。

---

## 功能列表（是否实现）

| 功能 | 相关接口 | 是否实现 | 备注 |
|---|---|---:|---|
| 元数据属性列表（分页） | `GET /api/meta/attribute-defs` | ✅ | 支持 `keyword/dataType/required/unique/searchable` 过滤 |
| 元数据属性详情（最新版本 + 版本摘要） | `GET /api/meta/attribute-defs/{attrKey}` | ✅ | `businessDomain` 必填；`includeValues=true` 时返回最新 LOV 值列表 |
| 元数据属性版本摘要列表 | `GET /api/meta/attribute-defs/{attrKey}/versions` | ✅ | `businessDomain` 必填；返回 versionNo/hash/latest/createdAt |
| 创建元数据属性（写入 def + 首个 version） | `POST /api/meta/attribute-defs` | ✅ | `businessDomain/categoryCode` 必填；属性编码与 LOV 编码统一走规则服务 |
| 更新元数据属性（新增 version） | `PUT/PATCH /api/meta/attribute-defs/{attrKey}` | ✅ | `businessDomain/categoryCode` 必填；若 hash 未变化会跳过新增版本 |
| 删除元数据属性（软删） | `DELETE /api/meta/attribute-defs/{attrKey}` | ✅ | `businessDomain/categoryCode` 必填；仅将 def.status 置为 `deleted` |
| 导入元数据属性（Excel） | `POST /api/meta/attributes/import` | ✅ | `multipart/form-data` 上传文件，`businessDomain` 必填 |
| 旧版属性实例接口（`/api/attributes`） | - | ❌（已移除） | 已删除 Controller/Service/Repository/Domain 代码 |

---

## 通用错误响应

当抛出 `IllegalArgumentException` 时返回 400：

```json
{
  "timestamp": "2026-02-04T10:00:00+08:00",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_ARGUMENT",
  "message": "属性不存在:color"
}
```

未知异常返回 500：`code = INTERNAL_ERROR`。

---

## 1) 元数据属性（Meta Attribute Def/Version）

### 1.1 列表（分页 + 过滤）

- 方法：`GET`
- 路径：`/api/meta/attribute-defs`

**Query 参数**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| categoryCode | string | 否 | - | 限定在某个分类 codeKey 下 |
| keyword | string | 否 | - | 名称关键字（后端走 ILIKE/索引列） |
| dataType | string | 否 | - | 例如 `string/number/bool/enum` |
| required | boolean | 否 | - | 过滤必填 |
| unique | boolean | 否 | - | 过滤唯一 |
| searchable | boolean | 否 | - | 过滤可搜索 |
| includeDeleted | boolean | 否 | false | 是否包含已删除属性（默认不包含） |
| page | int | 否 | 0 | 0-based 页码 |
| size | int | 否 | 20 | 每页大小 |

**curl 示例**

```bash
curl "http://localhost:8080/api/meta/attribute-defs?categoryCode=44120000&keyword=colo&dataType=string&required=true&page=0&size=20"
```

**响应：Page<MetaAttributeDefListItemDto>（示例）**

```json
{
  "content": [
    {
      "key": "color",
      "lovKey": null,
      "categoryCode": "44120000",
      "status": "ACTIVE",
      "latestVersionNo": 3,
      "displayName": "颜色",
      "attributeField": "colorValue",
      "dataType": "string",
      "unit": null,
      "hasLov": false,
      "required": true,
      "unique": false,
      "hidden": false,
      "readOnly": false,
      "searchable": true,
      "createdAt": "2026-02-04T09:30:00Z"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "size": 20,
  "number": 0
}
```

---

### 1.2 详情（最新版本 + 版本摘要；可选 LOV 值）

- 方法：`GET`
- 路径：`/api/meta/attribute-defs/{attrKey}`

**Query 参数**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| includeValues | boolean | 否 | false | true 时返回 `lovValues`（若有） |

**curl 示例**

```bash
curl "http://localhost:8080/api/meta/attribute-defs/color?includeValues=false"
```

**响应：MetaAttributeDefDetailDto（示例）**

```json
{
  "key": "color",
  "categoryCode": "44120000",
  "status": "ACTIVE",
  "createdBy": "alice",
  "createdAt": "2026-02-01T08:00:00Z",
  "modifiedBy": "bob",
  "modifiedAt": "2026-02-03T09:00:00Z",
  "latestVersion": {
    "versionNo": 3,
    "displayName": "颜色",
    "attributeField": "colorValue",
    "description": "物料颜色",
    "dataType": "string",
    "unit": null,
    "defaultValue": null,
    "required": true,
    "unique": false,
    "hidden": false,
    "readOnly": false,
    "searchable": true,
    "lovKey": null,
    "createdBy": "bob",
    "createdAt": "2026-02-03T09:00:00Z"
  },
  "lovKey": null,
  "hasLov": false,
  "versions": [
    { "versionNo": 1, "hash": "...", "latest": false, "createdAt": "2026-02-01T08:00:00Z" },
    { "versionNo": 2, "hash": "...", "latest": false, "createdAt": "2026-02-02T08:00:00Z" },
    { "versionNo": 3, "hash": "...", "latest": true,  "createdAt": "2026-02-03T09:00:00Z" }
  ],
  "lovValues": null
}
```

---

### 1.3 版本摘要列表

- 方法：`GET`
- 路径：`/api/meta/attribute-defs/{attrKey}/versions`

**curl 示例**

```bash
curl "http://localhost:8080/api/meta/attribute-defs/color/versions"
```

**响应：List<MetaAttributeVersionSummaryDto>（示例）**

```json
[
  { "versionNo": 1, "hash": "...", "latest": false, "createdAt": "2026-02-01T08:00:00Z" },
  { "versionNo": 2, "hash": "...", "latest": false, "createdAt": "2026-02-02T08:00:00Z" },
  { "versionNo": 3, "hash": "...", "latest": true,  "createdAt": "2026-02-03T09:00:00Z" }
]
```

---

## 2) 元数据属性写接口（创建/更新）

> 写入规则：编辑不会覆盖旧版本，而是创建新版本（`versionNo+1`，旧 `isLatest=false`，新 `isLatest=true`）。

### 2.1 创建属性

- 方法：`POST`
- 路径：`/api/meta/attribute-defs`

**Query 参数**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| categoryCode | string | 是 | - | 业务域内分类 codeKey |
| createdBy | string | 否 | system | 兜底操作人（如果没传 header） |
| includeValues | boolean | 否 | true | 当前保留参数：manageService 暂时固定返回 includeValues=true |

**Header（可选）**

- `X-User: alice`（优先于 `createdBy`）

**Body：MetaAttributeUpsertRequestDto（示例）**

```json
{
  "key": null,
  "generationMode": "AUTO",
  "freezeKey": false,
  "displayName": "颜色",
  "attributeField": "colorValue",
  "description": "物料颜色",
  "dataType": "string",
  "unit": null,
  "defaultValue": null,
  "required": true,
  "unique": false,
  "hidden": false,
  "readOnly": false,
  "searchable": true,
  "lovKey": null,
  "lovGenerationMode": "AUTO",
  "freezeLovKey": false,
  "minValue": null,
  "maxValue": null,
  "step": null,
  "precision": null,
  "trueLabel": null,
  "falseLabel": null,
  "lovValues": []
}
```

**编码生成约定**

- `generationMode` 支持 `AUTO` 或 `MANUAL`。
- 未显式传 `generationMode` 时：`key` 为空按 `AUTO`，`key` 非空按 `MANUAL`。
- `lovGenerationMode` 支持 `AUTO` 或 `MANUAL`。
- 未显式传 `lovGenerationMode` 时：`lovKey` 为空按 `AUTO`，`lovKey` 非空按 `MANUAL`。
- 当 `generationMode=AUTO` 时，请求中必须不传 `key`。
- 当 `lovGenerationMode=AUTO` 时，请求中必须不传 `lovKey`。
- 当 `generationMode=MANUAL` 或 `lovGenerationMode=MANUAL` 时，仍会经过统一编码规则校验，只有规则允许手工覆盖时才可成功。

**值配置约定（新增）**

- 通用字段：支持 `attributeField`（属性字段/业务字段名，用于前后端字段映射）
- 数字型（`dataType=number`）：支持 `minValue/maxValue/step/precision`
- 布尔型（`dataType=boolean`）：支持 `trueLabel/falseLabel`
- 枚举型（`dataType=enum`）：支持 `lovValues[]`，每项可配置 `code/name/label`

**示例：数字型配置**

```json
{
  "key": "length",
  "displayName": "长度",
  "attributeField": "lengthValue",
  "dataType": "number",
  "unit": "mm",
  "minValue": 0,
  "maxValue": 9999,
  "step": 0.1,
  "precision": 2
}
```

**示例：布尔型配置**

```json
{
  "key": "isStandard",
  "displayName": "是否标准件",
  "attributeField": "standardFlag",
  "dataType": "boolean",
  "trueLabel": "是",
  "falseLabel": "否"
}
```

**示例：枚举值配置（编码/名称/标签）**

```json
{
  "generationMode": "AUTO",
  "displayName": "颜色",
  "attributeField": "colorValue",
  "dataType": "enum",
  "lovGenerationMode": "AUTO",
  "lovValues": [
    { "code": "RED", "name": "红色", "label": "warm" },
    { "code": "BLUE", "name": "蓝色", "label": "cool" }
  ]
}
```

**示例：手工指定属性编码与 LOV 编码**

```json
{
  "key": "ATTR_MANUAL_COLOR",
  "generationMode": "MANUAL",
  "freezeKey": true,
  "displayName": "颜色",
  "attributeField": "colorValue",
  "dataType": "enum",
  "lovKey": "ATTR_MANUAL_COLOR_CUSTOM_LOV",
  "lovGenerationMode": "MANUAL",
  "freezeLovKey": true,
  "lovValues": [
    { "code": "RED", "name": "红色", "label": "warm" },
    { "code": "BLUE", "name": "蓝色", "label": "cool" }
  ]
}
```

**curl 示例**

```bash
curl -X POST "http://localhost:8080/api/meta/attribute-defs?categoryCode=44120000" \
  -H "Content-Type: application/json" \
  -H "X-User: alice" \
  -d '{
    "displayName":"颜色",
    "attributeField":"colorValue",
    "dataType":"enum",
    "generationMode":"AUTO",
    "lovGenerationMode":"AUTO",
    "required":true,
    "searchable":true,
    "lovValues":[
      {"code":"RED","name":"红色","label":"warm"},
      {"code":"BLUE","name":"蓝色","label":"cool"}
    ]
  }'
```

**响应**：同“详情接口”的 `MetaAttributeDefDetailDto`。

---

### 2.2 更新属性（PUT / PATCH）

- 方法：`PUT` 或 `PATCH`
- 路径：`/api/meta/attribute-defs/{attrKey}`

**Query 参数 / Header**：同创建。

**Body（示例：修改展示名 + searchable）**

```json
{
  "key": "ATTR_000001",
  "displayName": "颜色(新)",
  "attributeField": "colorValue",
  "dataType": "enum",
  "searchable": false,
  "lovValues": [
    { "code": "RED", "name": "红色", "label": "warm" },
    { "code": "BLUE", "name": "蓝色", "label": "cool" },
    { "code": "GREEN", "name": "绿色", "label": "fresh" }
  ]
}
```

**curl 示例（PUT）**

```bash
curl -X PUT "http://localhost:8080/api/meta/attribute-defs/color?categoryCode=44120000" \
  -H "Content-Type: application/json" \
  -H "X-User: bob" \
  -d '{
    "key":"ATTR_000001",
    "displayName":"颜色(新)",
    "attributeField":"colorValue",
    "dataType":"enum",
    "searchable":false,
    "lovValues":[
      {"code":"RED","name":"红色","label":"warm"},
      {"code":"BLUE","name":"蓝色","label":"cool"},
      {"code":"GREEN","name":"绿色","label":"fresh"}
    ]
  }'
```

**说明（lovKey 行为）**

- 若 `dataType=enum` 或 `multi-enum` 且请求未传 `lovKey`：优先沿用历史最新版本的 `lovKey`；若历史也没有，则按 `LOV` 活跃规则生成。
- 若 `lovGenerationMode=AUTO`，请求中必须不传 `lovKey`，否则返回 400。
- 若 `lovGenerationMode=MANUAL` 且传入了 `lovKey`，会按统一编码规则做手工覆盖校验。

---

### 2.3 删除属性（软删）

- 方法：`DELETE`
- 路径：`/api/meta/attribute-defs/{attrKey}`

**Query 参数**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| categoryCode | string | 是 | - | 业务域内分类 codeKey |
| createdBy | string | 否 | system | 兜底操作人（如果没传 header） |

**Header（可选）**

- `X-User: alice`

**curl 示例**

```bash
curl -X DELETE "http://localhost:8080/api/meta/attribute-defs/color?categoryCode=44120000" \
  -H "X-User: alice"
```

**响应**

- `204 No Content`

**说明**

- 删除后该属性不会出现在列表接口中（除非 `includeDeleted=true`）。

---

## 3) 元数据属性导入（Excel）

- 方法：`POST`
- 路径：`/api/meta/attributes/import`
- Content-Type：`multipart/form-data`

**表单字段**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| file | file | 是 | Excel 文件 |
| createdBy | string | 否 | 兜底操作人，默认 system |

**curl 示例**

```bash
curl -X POST "http://localhost:8080/api/meta/attributes/import?createdBy=system" \
  -F "file=@D:/tmp/attributes.xlsx"
```

**响应：AttributeImportSummaryDto（示例）**

```json
{
  "totalRows": 100,
  "attributeGroupCount": 30,
  "createdAttributeDefs": 10,
  "createdAttributeVersions": 25,
  "createdLovDefs": 3,
  "createdLovVersions": 3,
  "skippedUnchanged": 65,
  "errorCount": 0,
  "errors": []
}
```

---

## 附录：与属性联动的通用分类浏览/搜索（taxonomy 已下线）

- `GET /api/meta/categories/nodes?businessDomain=MATERIAL&parentId=...&page=0&size=50`
- `GET /api/meta/categories/nodes/{id}/path?businessDomain=MATERIAL`
- `GET /api/meta/categories/search?businessDomain=MATERIAL&keyword=...&scopeNodeId=...&page=0&size=20`
- `POST /api/meta/categories/nodes:children-batch`（body 中传 `businessDomain`）
