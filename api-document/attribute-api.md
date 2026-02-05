# 属性相关 API 文档（plm-attribute-service）

更新时间：2026-02-04

> 本文覆盖“属性元数据（Meta Attribute Def/Version）”的查询与写入接口；旧版 `/api/attributes` 已移除。
> 
> 约定：
> - `categoryCode` 使用 UNSPSC 分类的 `codeKey`（例如：`44120000`）。
> - `attrKey` 对应 `meta_attribute_def.key`（即属性编码）。

---

## 功能列表（是否实现）

| 功能 | 相关接口 | 是否实现 | 备注 |
|---|---|---:|---|
| 元数据属性列表（分页） | `GET /api/meta/attribute-defs` | ✅ | 支持 `keyword/dataType/required/unique/searchable` 过滤 |
| 元数据属性详情（最新版本 + 版本摘要） | `GET /api/meta/attribute-defs/{attrKey}` | ✅ | `includeValues=true` 时返回最新 LOV 值列表 |
| 元数据属性版本摘要列表 | `GET /api/meta/attribute-defs/{attrKey}/versions` | ✅ | 返回 versionNo/hash/latest/createdAt |
| 创建元数据属性（写入 def + 首个 version） | `POST /api/meta/attribute-defs` | ✅ | `categoryCode` 必填；enum 未传 `lovKey` 会自动生成 |
| 更新元数据属性（新增 version） | `PUT/PATCH /api/meta/attribute-defs/{attrKey}` | ✅ | 若 hash 未变化会跳过新增版本 |
| 导入元数据属性（Excel） | `POST /api/meta/attributes/import` | ✅ | `multipart/form-data` 上传文件 |
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
| categoryCode | string | 是 | - | UNSPSC 分类 codeKey |
| createdBy | string | 否 | system | 兜底操作人（如果没传 header） |
| includeValues | boolean | 否 | true | 当前保留参数：manageService 暂时固定返回 includeValues=true |

**Header（可选）**

- `X-User: alice`（优先于 `createdBy`）

**Body：MetaAttributeUpsertRequestDto（示例）**

```json
{
  "key": "color",
  "displayName": "颜色",
  "description": "物料颜色",
  "dataType": "string",
  "unit": null,
  "defaultValue": null,
  "required": true,
  "unique": false,
  "hidden": false,
  "readOnly": false,
  "searchable": true,
  "lovKey": null
}
```

**curl 示例**

```bash
curl -X POST "http://localhost:8080/api/meta/attribute-defs?categoryCode=44120000" \
  -H "Content-Type: application/json" \
  -H "X-User: alice" \
  -d '{
    "key":"color",
    "displayName":"颜色",
    "dataType":"string",
    "required":true,
    "searchable":true
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
  "key": "color",
  "displayName": "颜色(新)",
  "dataType": "string",
  "searchable": false
}
```

**curl 示例（PUT）**

```bash
curl -X PUT "http://localhost:8080/api/meta/attribute-defs/color?categoryCode=44120000" \
  -H "Content-Type: application/json" \
  -H "X-User: bob" \
  -d '{
    "key":"color",
    "displayName":"颜色(新)",
    "dataType":"string",
    "searchable":false
  }'
```

**说明（lovKey 行为）**

- 若 `dataType=enum` 且请求未传 `lovKey`：会优先沿用历史最新版本的 `lovKey`；若历史也没有则生成一个默认 `lovKey`。

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

## 附录：与属性联动的 UNSPSC 分类浏览/搜索（可选参考）

- `GET /api/meta/categories/unspsc/segments`
- `GET /api/meta/categories/unspsc/segments/{segmentCodeKey}/families`
- `GET /api/meta/categories/unspsc/families/{familyCodeKey}/classes-with-commodities`
- `GET /api/meta/categories/unspsc/search?q=...&scopeCodeKey=...&limit=50`
