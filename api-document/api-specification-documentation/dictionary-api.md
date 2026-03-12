# 字典 API 文档（plm-attribute-service）

更新时间：2026-03-12

> taxonomy 字典能力已下线：`META_TAXONOMY` 不再作为动态或静态字典返回。

---

## 1. 概述

字典服务用于为前端页面提供可选项数据，支持：

- 按字典编码查询字典项列表。
- 按场景一次性返回多个字典。

当前分类管理相关场景仅保留以下字典：

- `CATEGORY_BUSINESS_DOMAIN`
- `CATEGORY_STATUS`

---

## 2. 接口列表

| 接口 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 查询字典项 | GET | /api/meta/dictionaries/{dictCode}/items | 根据字典编码返回字典项 |
| 场景字典批量查询 | GET | /api/meta/dictionary-scenes/{sceneCode}/items | 根据场景返回字典映射 |

---

## 3. 查询字典项

- 方法：GET
- 路径：`/api/meta/dictionaries/{dictCode}/items`

Path 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| dictCode | string | 是 | 字典编码 |

响应示例

```json
[
  {
    "dictCode": "CATEGORY_BUSINESS_DOMAIN",
    "itemCode": "MATERIAL",
    "itemName": "物料",
    "sortOrder": 1,
    "enabled": true
  },
  {
    "dictCode": "CATEGORY_BUSINESS_DOMAIN",
    "itemCode": "PRODUCT",
    "itemName": "产品",
    "sortOrder": 2,
    "enabled": true
  }
]
```

---

## 4. 场景字典批量查询

- 方法：GET
- 路径：`/api/meta/dictionary-scenes/{sceneCode}/items`

Path 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| sceneCode | string | 是 | 场景编码 |

响应示例（sceneCode=`category-admin`）

```json
{
  "CATEGORY_BUSINESS_DOMAIN": [
    { "itemCode": "MATERIAL", "itemName": "物料", "enabled": true }
  ],
  "CATEGORY_STATUS": [
    { "itemCode": "ACTIVE", "itemName": "启用", "enabled": true },
    { "itemCode": "INACTIVE", "itemName": "停用", "enabled": true },
    { "itemCode": "DRAFT", "itemName": "草稿", "enabled": true }
  ]
}
```

---

## 5. taxonomy 下线说明

以下能力已移除，不应再被调用或依赖：

- 字典编码：`META_TAXONOMY`
- 场景绑定：`category-admin` 中的 `META_TAXONOMY`
- 运行时动态组装 taxonomy 字典项逻辑

如果客户端仍请求 `META_TAXONOMY`，将返回空集合或找不到字典（取决于环境数据状态）。

---

## 6. 错误响应

```json
{
  "timestamp": "2026-03-12T10:00:00+08:00",
  "status": 404,
  "error": "Not Found",
  "message": "dictionary not found: META_TAXONOMY"
}
```
