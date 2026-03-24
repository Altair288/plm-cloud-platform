# 编码规则 API 文档（plm-attribute-service）

更新时间：2026-03-24

> 本文覆盖统一编码规则管理接口，包含规则查询、创建、更新、发布与预览能力。
>
> 当前控制器入口：`/api/meta/code-rules`

---

## 功能列表（是否实现）

| 功能 | 相关接口 | 是否实现 | 备注 |
|---|---|---:|---|
| 规则列表 | `GET /api/meta/code-rules` | ✅ | 支持 `targetType/status` 过滤 |
| 规则详情 | `GET /api/meta/code-rules/{ruleCode}` | ✅ | 返回最新版本摘要与 latestRuleJson |
| 创建规则 | `POST /api/meta/code-rules` | ✅ | 新建后默认 `DRAFT` |
| 更新规则 | `PUT /api/meta/code-rules/{ruleCode}` | ✅ | 仅 `DRAFT` 状态允许编辑 |
| 发布规则 | `POST /api/meta/code-rules/{ruleCode}:publish` | ✅ | 发布后状态切为 `ACTIVE` |
| 规则预览 | `POST /api/meta/code-rules/{ruleCode}:preview` | ✅ | 不占用正式序列 |

---

## 通用规则

### 1. 状态流转

- 新建规则默认状态为 `DRAFT`
- 只有 `DRAFT` 规则允许更新
- 发布后状态切为 `ACTIVE`
- `ARCHIVED` 规则不允许再次发布

### 2. 规则版本

- 每次创建或更新都会写入一条新的 `meta_code_rule_version`
- 新版本 `is_latest=true`
- 旧最新版本会被切为 `is_latest=false`

### 3. 预览行为

- `preview` 只做语法、上下文与规则校验
- 若 pattern 包含 `{SEQ}`，预览会基于当前序列值生成示例，但不会占用正式序列
- 若传入 `manualCode`，则只校验手工编码是否符合规则

### 4. 一致性口径

- 规则版本 hash 统一按 `MD5(rule_json)` 计算
- 历史数据已通过迁移 `V24__code_rule_version_hash_alignment.sql` 对齐到同一算法
- 序列宽度由共享常量统一维护：
  - `CATEGORY = 4`
  - `ATTRIBUTE = 6`
  - `LOV = 2`
  - `INSTANCE = 4`
  - 默认值 `5`

---

## 通用错误响应

参数校验或业务校验失败时返回 400：

```json
{
  "timestamp": "2026-03-24T10:00:00+08:00",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_ARGUMENT",
  "message": "only draft rule can be updated: ruleCode=CATEGORY"
}
```

常见错误信息示例：

- `request body is required`
- `ruleCode is required`
- `targetType is required`
- `code rule already exists: ruleCode=...`
- `only draft rule can be updated: ruleCode=...`
- `archived rule cannot be published: ruleCode=...`
- `manual code override is not allowed: ruleCode=...`
- `preview context is incomplete for pattern: ...`

---

## 1) 规则列表

- 方法：`GET`
- 路径：`/api/meta/code-rules`

### Query 参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| targetType | string | 否 | - | 按目标类型过滤，例如 `category` |
| status | string | 否 | - | 按状态过滤，例如 `DRAFT/ACTIVE/ARCHIVED` |

### curl 示例

```bash
curl "http://localhost:8080/api/meta/code-rules?targetType=category&status=ACTIVE"
```

### 响应示例

```json
[
  {
    "ruleCode": "CATEGORY",
    "name": "Category Code Rule",
    "targetType": "category",
    "scopeType": "GLOBAL",
    "scopeValue": null,
    "pattern": "{BUSINESS_DOMAIN}-{SEQ}",
    "status": "ACTIVE",
    "active": true,
    "allowManualOverride": true,
    "regexPattern": "^[A-Z][A-Z0-9_-]{0,63}$",
    "maxLength": 64,
    "latestVersionNo": 2,
    "latestRuleJson": {
      "pattern": "{BUSINESS_DOMAIN}-{SEQ}",
      "tokens": ["BUSINESS_DOMAIN", "SEQ"],
      "sequence": {
        "enabled": true,
        "width": 4,
        "step": 1
      },
      "validation": {
        "maxLength": 64,
        "regex": "^[A-Z][A-Z0-9_-]{0,63}$",
        "allowManualOverride": true
      }
    }
  }
]
```

---

## 2) 规则详情

- 方法：`GET`
- 路径：`/api/meta/code-rules/{ruleCode}`

### curl 示例

```bash
curl "http://localhost:8080/api/meta/code-rules/CATEGORY"
```

### 响应示例

```json
{
  "ruleCode": "CATEGORY",
  "name": "Category Code Rule",
  "targetType": "category",
  "scopeType": "GLOBAL",
  "scopeValue": null,
  "pattern": "{BUSINESS_DOMAIN}-{SEQ}",
  "status": "ACTIVE",
  "active": true,
  "allowManualOverride": true,
  "regexPattern": "^[A-Z][A-Z0-9_-]{0,63}$",
  "maxLength": 64,
  "latestVersionNo": 2,
  "latestRuleJson": {
    "pattern": "{BUSINESS_DOMAIN}-{SEQ}",
    "tokens": ["BUSINESS_DOMAIN", "SEQ"],
    "sequence": {
      "enabled": true,
      "width": 4,
      "step": 1
    },
    "validation": {
      "maxLength": 64,
      "regex": "^[A-Z][A-Z0-9_-]{0,63}$",
      "allowManualOverride": true
    }
  }
}
```

---

## 3) 创建规则

- 方法：`POST`
- 路径：`/api/meta/code-rules`

### Query 参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| operator | string | 否 | system | 操作人 |

### Body：CodeRuleSaveRequestDto

```json
{
  "ruleCode": "CATEGORY",
  "name": "Category Code Rule",
  "targetType": "category",
  "scopeType": "GLOBAL",
  "scopeValue": null,
  "pattern": "{BUSINESS_DOMAIN}-{SEQ}",
  "allowManualOverride": true,
  "regexPattern": "^[A-Z][A-Z0-9_-]{0,63}$",
  "maxLength": 64,
  "ruleJson": {
    "pattern": "{BUSINESS_DOMAIN}-{SEQ}",
    "tokens": ["BUSINESS_DOMAIN", "SEQ"],
    "sequence": {
      "enabled": true,
      "width": 4,
      "step": 1
    },
    "validation": {
      "maxLength": 64,
      "regex": "^[A-Z][A-Z0-9_-]{0,63}$",
      "allowManualOverride": true
    }
  }
}
```

### curl 示例

```bash
curl -X POST "http://localhost:8080/api/meta/code-rules?operator=alice" \
  -H "Content-Type: application/json" \
  -d '{
    "ruleCode":"IT_RULE_CATEGORY",
    "name":"Integration Category Rule",
    "targetType":"category",
    "scopeType":"GLOBAL",
    "pattern":"IT-{BUSINESS_DOMAIN}-{SEQ}",
    "allowManualOverride":true,
    "regexPattern":"^[A-Z][A-Z0-9_-]{0,63}$",
    "maxLength":64
  }'
```

### 响应示例

```json
{
  "ruleCode": "IT_RULE_CATEGORY",
  "name": "Integration Category Rule",
  "targetType": "category",
  "scopeType": "GLOBAL",
  "scopeValue": null,
  "pattern": "IT-{BUSINESS_DOMAIN}-{SEQ}",
  "status": "DRAFT",
  "active": false,
  "allowManualOverride": true,
  "regexPattern": "^[A-Z][A-Z0-9_-]{0,63}$",
  "maxLength": 64,
  "latestVersionNo": 1,
  "latestRuleJson": {
    "pattern": "IT-{BUSINESS_DOMAIN}-{SEQ}",
    "tokens": ["BUSINESS_DOMAIN", "SEQ"],
    "sequence": {
      "enabled": true,
      "width": 4,
      "step": 1
    },
    "validation": {
      "maxLength": 64,
      "regex": "^[A-Z][A-Z0-9_-]{0,63}$",
      "allowManualOverride": true
    }
  }
}
```

---

## 4) 更新规则

- 方法：`PUT`
- 路径：`/api/meta/code-rules/{ruleCode}`

### Query 参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| operator | string | 否 | system | 操作人 |

### 约束

- 只有 `DRAFT` 规则允许更新
- 如果 body 里传了 `ruleCode`，必须与 path 中一致

### curl 示例

```bash
curl -X PUT "http://localhost:8080/api/meta/code-rules/IT_RULE_CATEGORY?operator=bob" \
  -H "Content-Type: application/json" \
  -d '{
    "ruleCode":"IT_RULE_CATEGORY",
    "name":"Integration Category Rule v2",
    "targetType":"category",
    "scopeType":"GLOBAL",
    "pattern":"IT2-{BUSINESS_DOMAIN}-{SEQ}",
    "allowManualOverride":true,
    "regexPattern":"^[A-Z][A-Z0-9_-]{0,63}$",
    "maxLength":64
  }'
```

### 响应示例

```json
{
  "ruleCode": "IT_RULE_CATEGORY",
  "name": "Integration Category Rule v2",
  "targetType": "category",
  "scopeType": "GLOBAL",
  "scopeValue": null,
  "pattern": "IT2-{BUSINESS_DOMAIN}-{SEQ}",
  "status": "DRAFT",
  "active": false,
  "allowManualOverride": true,
  "regexPattern": "^[A-Z][A-Z0-9_-]{0,63}$",
  "maxLength": 64,
  "latestVersionNo": 2,
  "latestRuleJson": {
    "pattern": "IT2-{BUSINESS_DOMAIN}-{SEQ}",
    "tokens": ["BUSINESS_DOMAIN", "SEQ"],
    "sequence": {
      "enabled": true,
      "width": 4,
      "step": 1
    },
    "validation": {
      "maxLength": 64,
      "regex": "^[A-Z][A-Z0-9_-]{0,63}$",
      "allowManualOverride": true
    }
  }
}
```

---

## 5) 发布规则

- 方法：`POST`
- 路径：`/api/meta/code-rules/{ruleCode}:publish`

### Query 参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| operator | string | 否 | system | 操作人 |

### curl 示例

```bash
curl -X POST "http://localhost:8080/api/meta/code-rules/IT_RULE_CATEGORY:publish?operator=alice"
```

### 响应示例

```json
{
  "ruleCode": "IT_RULE_CATEGORY",
  "name": "Integration Category Rule v2",
  "targetType": "category",
  "scopeType": "GLOBAL",
  "scopeValue": null,
  "pattern": "IT2-{BUSINESS_DOMAIN}-{SEQ}",
  "status": "ACTIVE",
  "active": true,
  "allowManualOverride": true,
  "regexPattern": "^[A-Z][A-Z0-9_-]{0,63}$",
  "maxLength": 64,
  "latestVersionNo": 2,
  "latestRuleJson": {
    "pattern": "IT2-{BUSINESS_DOMAIN}-{SEQ}",
    "tokens": ["BUSINESS_DOMAIN", "SEQ"],
    "sequence": {
      "enabled": true,
      "width": 4,
      "step": 1
    },
    "validation": {
      "maxLength": 64,
      "regex": "^[A-Z][A-Z0-9_-]{0,63}$",
      "allowManualOverride": true
    }
  }
}
```

---

## 6) 规则预览

- 方法：`POST`
- 路径：`/api/meta/code-rules/{ruleCode}:preview`

### Body：CodeRulePreviewRequestDto

```json
{
  "context": {
    "BUSINESS_DOMAIN": "MATERIAL",
    "CATEGORY_CODE": "MATERIAL-0001",
    "ATTRIBUTE_CODE": "ATTR_000001"
  },
  "manualCode": null,
  "count": 3
}
```

### 示例一：自动预览

```bash
curl -X POST "http://localhost:8080/api/meta/code-rules/CATEGORY:preview" \
  -H "Content-Type: application/json" \
  -d '{
    "context": {
      "BUSINESS_DOMAIN": "MATERIAL"
    },
    "count": 3
  }'
```

响应示例：

```json
{
  "ruleCode": "CATEGORY",
  "ruleVersion": 2,
  "pattern": "{BUSINESS_DOMAIN}-{SEQ}",
  "examples": [
    "MATERIAL-0001",
    "MATERIAL-0002",
    "MATERIAL-0003"
  ],
  "warnings": []
}
```

### 示例二：手工编码校验预览

```bash
curl -X POST "http://localhost:8080/api/meta/code-rules/ATTRIBUTE:preview" \
  -H "Content-Type: application/json" \
  -d '{
    "manualCode": "ATTR_MANUAL_001",
    "count": 1
  }'
```

响应示例：

```json
{
  "ruleCode": "ATTRIBUTE",
  "ruleVersion": 2,
  "pattern": "ATTR_{SEQ}",
  "examples": [
    "ATTR_MANUAL_001"
  ],
  "warnings": []
}
```

### 预览警告说明

- `RULE_HAS_NO_SEQUENCE_PLACEHOLDER`
  - 规则 pattern 不包含 `{SEQ}`，但请求 `count > 1`
  - 系统只会返回单个渲染结果，并附带该警告

---

## 7) 字段说明

### CodeRuleSaveRequestDto

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| ruleCode | string | 是 | 规则编码，内部会统一转大写 |
| name | string | 是 | 规则名称 |
| targetType | string | 是 | 目标类型，例如 `category` |
| scopeType | string | 否 | 默认 `GLOBAL` |
| scopeValue | string | 否 | 作用域值 |
| pattern | string | 是 | 编码 pattern |
| allowManualOverride | boolean | 否 | 是否允许手工覆盖 |
| regexPattern | string | 否 | 手工编码校验正则 |
| maxLength | int | 否 | 最大长度，默认 64 |
| ruleJson | object | 否 | 若不传则后端按 pattern 自动生成标准结构 |

### CodeRuleDetailDto

| 字段 | 类型 | 说明 |
|---|---|---|
| ruleCode | string | 规则编码 |
| name | string | 规则名称 |
| targetType | string | 目标类型 |
| scopeType | string | 作用域类型 |
| scopeValue | string | 作用域值 |
| pattern | string | 当前解析后的 pattern |
| status | string | 当前状态 |
| active | boolean | 是否启用 |
| allowManualOverride | boolean | 是否允许手工覆盖 |
| regexPattern | string | 正则校验 |
| maxLength | int | 最大长度 |
| latestVersionNo | int | 最新版本号 |
| latestRuleJson | object | 最新版本规则 JSON |

### CodeRulePreviewRequestDto

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| context | object | 否 | 用于替换 pattern 占位符 |
| manualCode | string | 否 | 若传入则只做手工编码校验 |
| count | int | 否 | 预览返回的候选数量，默认 3，最大 20 |

### CodeRulePreviewResponseDto

| 字段 | 类型 | 说明 |
|---|---|---|
| ruleCode | string | 规则编码 |
| ruleVersion | int | 参与预览的规则版本号 |
| pattern | string | 当前使用的 pattern |
| examples | string[] | 预览候选结果 |
| warnings | string[] | 预览警告 |
