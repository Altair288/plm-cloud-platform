# 编码规则 API 文档（plm-attribute-service）

更新时间：2026-03-27

> 本文覆盖统一编码规则管理接口与业务领域规则集接口，包含规则查询、创建、更新、发布、预览，以及按业务域维护规则集的能力。
>
> 当前控制器入口：`/api/meta/code-rules`、`/api/meta/code-rule-sets`

---

## 2026-03-27 更新区域

> 本次为前端对接补齐了以下区域，阅读时可优先关注：

- `1) 规则列表`：补充摘要字段 `supportsHierarchy`、`supportsScopedSequence`、`supportedVariableKeys`
- `1) 规则列表` / `2) 规则详情` / `3) 创建规则` / `4) 更新规则`：补充 `businessDomain` 请求/响应口径
- `2) 规则详情`：补充结构化 `latestRuleJson` 示例
- `3) 创建规则` / `4) 更新规则`：补充结构化 `ruleJson` 提交格式
- `6) 规则预览`：补充 `resolvedContext`、`resolvedSequenceScope`、`resolvedPeriodKey` 和 warning 语义
- `7) 字段说明`：补充结构化规则 JSON 字段说明
- `8) 内置规则默认语义`：明确 `CATEGORY`、`ATTRIBUTE`、`LOV` 三个内置规则当前默认结构
- `9) 前端对接说明`：明确 `LOV` 规则已切换为“枚举值编码规则”，不是 lov_def 绑定 key 规则
- `10) 业务领域规则集` / `11) 规则集接口明细`：新增 `code-rule-sets` 全套接口与响应体示例

---

## 功能列表（是否实现）

| 功能     | 相关接口                                       | 是否实现 | 备注                                                      |
| -------- | ---------------------------------------------- | -------: | --------------------------------------------------------- |
| 规则列表 | `GET /api/meta/code-rules`                     |       ✅ | 支持 `targetType/status` 过滤，返回规则摘要与最新规则 JSON |
| 规则详情 | `GET /api/meta/code-rules/{ruleCode}`          |       ✅ | 返回结构化 `latestRuleJson` 与规则能力摘要                |
| 创建规则 | `POST /api/meta/code-rules`                    |       ✅ | 新建后默认 `DRAFT`                                        |
| 更新规则 | `PUT /api/meta/code-rules/{ruleCode}`          |       ✅ | 仅 `DRAFT` 状态允许编辑                                   |
| 发布规则 | `POST /api/meta/code-rules/{ruleCode}:publish` |       ✅ | 发布前执行结构化规则校验                                  |
| 规则预览 | `POST /api/meta/code-rules/{ruleCode}:preview` |       ✅ | 不占用正式序列，返回解析后的上下文与序列作用域信息        |
| 规则集列表 | `GET /api/meta/code-rule-sets`               |       ✅ | 返回每个业务领域当前唯一规则集摘要                        |
| 规则集详情 | `GET /api/meta/code-rule-sets/{businessDomain}` |    ✅ | 一次性返回规则集绑定关系与三条完整规则详情                |
| 创建规则集 | `POST /api/meta/code-rule-sets`             |       ✅ | 新建后默认 `DRAFT`，会校验三条规则的业务域归属            |
| 更新规则集 | `PUT /api/meta/code-rule-sets/{businessDomain}` |    ✅ | 仅更新绑定关系和备注，保存后回到 `DRAFT`                  |
| 发布规则集 | `POST /api/meta/code-rule-sets/{businessDomain}:publish` | ✅ | 会同时发布分类、属性、LOV 三条规则并激活规则集            |

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
- 若存在序列段，预览会基于当前序列状态计算候选值，但不会占用正式序列
- 若传入 `manualCode`，则只校验手工编码是否符合当前规则，不触发自动生成
- 若上下文不完整，预览不会直接报错，而是返回 `warnings`

### 4. 一致性口径

- 规则版本 hash 统一按 `MD5(rule_json)` 计算
- 历史数据已通过迁移 `V24__code_rule_version_hash_alignment.sql` 对齐到同一算法
- 序列宽度由共享常量统一维护：
  - `CATEGORY = 4`
  - `ATTRIBUTE = 6`
  - `LOV = 2`
  - `INSTANCE = 4`
  - 默认值 `5`

### 5. 规则语义口径

> [2026-03-27 更新]

- `CATEGORY` 规则：用于分类编码生成
- `ATTRIBUTE` 规则：用于属性编码生成
- `LOV` 规则：当前用于枚举值项的 `code` 生成
- 属性与 `lov_def` 的绑定 key 不再由规则系统配置；在业务写入链路中由系统内部自动绑定，前端无需为该绑定 key 维护单独编码规则

### 6. 业务领域规则集口径

> [2026-03-27 更新]

- 每个 `businessDomain` 只允许存在一套规则集
- 规则集固定绑定三条规则：`categoryRuleCode`、`attributeRuleCode`、`lovRuleCode`
- 规则集下绑定的三条规则必须与规则集属于同一 `businessDomain`
- 分类、属性、枚举值的自动编码链路已切换为：`businessDomain -> code-rule-set -> ruleCode`
- 自动编码场景下，如果业务域未配置已激活规则集，后端会直接报错，不再回退到写死内置规则

---

## 通用错误响应

参数校验或业务校验失败时返回 400：

```json
{
  "timestamp": "2026-03-27T10:00:00+08:00",
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
- `code rule not found: ruleCode=...`
- `only draft rule can be updated: ruleCode=...`
- `archived rule cannot be published: ruleCode=...`
- `ruleCode in path and body must match`
- `manual code override is not allowed: ruleCode=...`
- `businessDomain is required`
- `businessDomain in body must match existing rule`
- `code rule targetType already exists for businessDomain: businessDomain=..., targetType=...`
- `code rule set already exists: businessDomain=...`
- `code rule set not found: businessDomain=...`
- `businessDomain in path and body must match`
- `CODE_RULE_SET_NOT_CONFIGURED: businessDomain=...`
- `CODE_RULE_SET_NOT_ACTIVE: businessDomain=...`
- `CATEGORY_RULE_NOT_CONFIGURED: businessDomain=..., ruleCode=...`
- `ATTRIBUTE_RULE_NOT_CONFIGURED: businessDomain=..., ruleCode=...`
- `LOV_RULE_NOT_CONFIGURED: businessDomain=..., ruleCode=...`
- `variable is not allowed in subRule: ruleCode=..., subRuleKey=..., variableKey=...`
- `childSegments must not be empty when hierarchyMode=APPEND_CHILD_SUFFIX`
- `PER_PARENT reset requires non-global scopeKey: ruleCode=..., subRuleKey=...`
- `time-based reset requires a DATE segment: ruleCode=..., subRuleKey=...`

---

## 1) 规则列表

> [2026-03-27 更新] 本节响应示例已切换为当前真实返回结构，新增规则能力摘要字段。

- 方法：`GET`
- 路径：`/api/meta/code-rules`

### Query 参数

| 参数       | 类型   | 必填 | 默认值 | 说明                                     |
| ---------- | ------ | ---: | ------ | ---------------------------------------- |
| businessDomain | string | 否 | - | 按业务域过滤，例如 `MATERIAL`、`DEVICE` |
| targetType | string |   否 | -      | 按目标类型过滤，例如 `category`          |
| status     | string |   否 | -      | 按状态过滤，例如 `DRAFT/ACTIVE/ARCHIVED` |

### curl 示例

```bash
curl "http://localhost:8080/api/meta/code-rules?businessDomain=MATERIAL&targetType=category&status=ACTIVE"
```

### 响应示例

```json
[
  {
    "businessDomain": "MATERIAL",
    "ruleCode": "CATEGORY",
    "name": "分类编码规则",
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
    "supportsHierarchy": false,
    "supportsScopedSequence": false,
    "supportedVariableKeys": [
      "BUSINESS_DOMAIN",
      "PARENT_CODE"
    ],
    "latestRuleJson": {
      "pattern": "{BUSINESS_DOMAIN}-{SEQ}",
      "hierarchyMode": "NONE",
      "subRules": {
        "category": {
          "separator": "-",
          "segments": [
            {
              "type": "VARIABLE",
              "variableKey": "BUSINESS_DOMAIN"
            },
            {
              "type": "SEQUENCE",
              "length": 4,
              "startValue": 1,
              "step": 1,
              "resetRule": "NEVER",
              "scopeKey": "GLOBAL"
            }
          ],
          "allowedVariableKeys": [
            "BUSINESS_DOMAIN",
            "PARENT_CODE"
          ]
        }
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

> [2026-03-27 更新] 本节示例已切换为结构化规则 JSON，并补充返回摘要字段。

- 方法：`GET`
- 路径：`/api/meta/code-rules/{ruleCode}`

### curl 示例

```bash
curl "http://localhost:8080/api/meta/code-rules/ATTRIBUTE"
```

### 响应示例

```json
{
  "businessDomain": "MATERIAL",
  "ruleCode": "ATTRIBUTE",
  "name": "属性编码规则",
  "targetType": "attribute",
  "scopeType": "GLOBAL",
  "scopeValue": null,
  "pattern": "ATTR-{CATEGORY_CODE}-{SEQ}",
  "status": "ACTIVE",
  "active": true,
  "allowManualOverride": true,
  "regexPattern": "^[A-Z][A-Z0-9_-]{0,63}$",
  "maxLength": 64,
  "latestVersionNo": 3,
  "supportsHierarchy": false,
  "supportsScopedSequence": true,
  "supportedVariableKeys": [
    "BUSINESS_DOMAIN",
    "CATEGORY_CODE"
  ],
  "latestRuleJson": {
    "pattern": "ATTR-{CATEGORY_CODE}-{SEQ}",
    "hierarchyMode": "NONE",
    "subRules": {
      "attribute": {
        "separator": "-",
        "segments": [
          {
            "type": "STRING",
            "value": "ATTR"
          },
          {
            "type": "VARIABLE",
            "variableKey": "CATEGORY_CODE"
          },
          {
            "type": "SEQUENCE",
            "length": 6,
            "startValue": 1,
            "step": 1,
            "resetRule": "PER_PARENT",
            "scopeKey": "CATEGORY_CODE"
          }
        ],
        "allowedVariableKeys": [
          "BUSINESS_DOMAIN",
          "CATEGORY_CODE"
        ]
      }
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

> [2026-03-27 更新] `ruleJson` 已支持结构化规则定义。前端可直接提交复杂规则结构，不必再局限于旧版 `tokens/sequence` 模型。

- 方法：`POST`
- 路径：`/api/meta/code-rules`

### Query 参数

| 参数     | 类型   | 必填 | 默认值 | 说明   |
| -------- | ------ | ---: | ------ | ------ |
| operator | string |   否 | system | 操作人 |

### Body：CodeRuleSaveRequestDto

```json
{
  "businessDomain": "DEVICE",
  "ruleCode": "IT_RULE_ATTRIBUTE_BY_CATEGORY",
  "name": "按分类派生的属性编码规则",
  "targetType": "attribute",
  "scopeType": "GLOBAL",
  "scopeValue": null,
  "pattern": "ATTR-{CATEGORY_CODE}-{SEQ}",
  "allowManualOverride": true,
  "regexPattern": "^[A-Z][A-Z0-9_-]{0,63}$",
  "maxLength": 64,
  "ruleJson": {
    "pattern": "ATTR-{CATEGORY_CODE}-{SEQ}",
    "hierarchyMode": "NONE",
    "subRules": {
      "attribute": {
        "separator": "-",
        "segments": [
          {
            "type": "STRING",
            "value": "ATTR"
          },
          {
            "type": "VARIABLE",
            "variableKey": "CATEGORY_CODE"
          },
          {
            "type": "SEQUENCE",
            "length": 6,
            "startValue": 1,
            "step": 1,
            "resetRule": "PER_PARENT",
            "scopeKey": "CATEGORY_CODE"
          }
        ],
        "allowedVariableKeys": [
          "BUSINESS_DOMAIN",
          "CATEGORY_CODE"
        ]
      }
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
    "businessDomain":"DEVICE",
    "ruleCode":"IT_RULE_ATTRIBUTE_BY_CATEGORY",
    "name":"按分类派生的属性编码规则",
    "targetType":"attribute",
    "scopeType":"GLOBAL",
    "pattern":"ATTR-{CATEGORY_CODE}-{SEQ}",
    "allowManualOverride":true,
    "regexPattern":"^[A-Z][A-Z0-9_-]{0,63}$",
    "maxLength":64,
    "ruleJson":{
      "pattern":"ATTR-{CATEGORY_CODE}-{SEQ}",
      "hierarchyMode":"NONE",
      "subRules":{
        "attribute":{
          "separator":"-",
          "segments":[
            {"type":"STRING","value":"ATTR"},
            {"type":"VARIABLE","variableKey":"CATEGORY_CODE"},
            {"type":"SEQUENCE","length":6,"startValue":1,"step":1,"resetRule":"PER_PARENT","scopeKey":"CATEGORY_CODE"}
          ],
          "allowedVariableKeys":["BUSINESS_DOMAIN","CATEGORY_CODE"]
        }
      },
      "validation":{
        "maxLength":64,
        "regex":"^[A-Z][A-Z0-9_-]{0,63}$",
        "allowManualOverride":true
      }
    }
  }'
```

### 响应示例

响应结构与 `GET /api/meta/code-rules/{ruleCode}` 一致。

---

## 4) 更新规则

> [2026-03-27 更新] 更新接口与创建接口提交体一致，但仅允许修改 `DRAFT` 规则。

- 方法：`PUT`
- 路径：`/api/meta/code-rules/{ruleCode}`

### Query 参数

| 参数     | 类型   | 必填 | 默认值 | 说明   |
| -------- | ------ | ---: | ------ | ------ |
| operator | string |   否 | system | 操作人 |

### 约束

- 只有 `DRAFT` 规则允许更新
- 如果 body 里传了 `ruleCode`，必须与 path 中一致

### curl 示例

```bash
curl -X PUT "http://localhost:8080/api/meta/code-rules/IT_RULE_ATTRIBUTE_BY_CATEGORY?operator=bob" \
  -H "Content-Type: application/json" \
  -d '{
    "businessDomain":"DEVICE",
    "ruleCode":"IT_RULE_ATTRIBUTE_BY_CATEGORY",
    "name":"按分类派生的属性编码规则 v2",
    "targetType":"attribute",
    "scopeType":"GLOBAL",
    "pattern":"ATTR-{CATEGORY_CODE}-{SEQ}",
    "allowManualOverride":true,
    "regexPattern":"^[A-Z][A-Z0-9_-]{0,127}$",
    "maxLength":128,
    "ruleJson":{
      "pattern":"ATTR-{CATEGORY_CODE}-{SEQ}",
      "hierarchyMode":"NONE",
      "subRules":{
        "attribute":{
          "separator":"-",
          "segments":[
            {"type":"STRING","value":"ATTR"},
            {"type":"VARIABLE","variableKey":"CATEGORY_CODE"},
            {"type":"SEQUENCE","length":6,"startValue":1,"step":1,"resetRule":"PER_PARENT","scopeKey":"CATEGORY_CODE"}
          ],
          "allowedVariableKeys":["BUSINESS_DOMAIN","CATEGORY_CODE"]
        }
      },
      "validation":{
        "maxLength":128,
        "regex":"^[A-Z][A-Z0-9_-]{0,127}$",
        "allowManualOverride":true
      }
    }
  }'
```

### 响应示例

响应结构与 `GET /api/meta/code-rules/{ruleCode}` 一致。

---

## 5) 发布规则

> [2026-03-27 更新] 发布阶段会执行结构化规则校验，而不只是简单保存状态。

- 方法：`POST`
- 路径：`/api/meta/code-rules/{ruleCode}:publish`

### Query 参数

| 参数     | 类型   | 必填 | 默认值 | 说明   |
| -------- | ------ | ---: | ------ | ------ |
| operator | string |   否 | system | 操作人 |

### 发布校验范围

- `subRules` 不允许为空对象
- `segments` 不允许为空
- `VARIABLE` 段必须命中当前 subRule 的 `allowedVariableKeys`
- `SEQUENCE.length` 必须在 `1..32` 范围内
- `SEQUENCE.startValue` 不允许为负数
- `SEQUENCE.step` 必须大于 0
- `PER_PARENT` 必须使用非 `GLOBAL` 的 `scopeKey`
- `DAILY` / `MONTHLY` / `YEARLY` 必须有 `DATE` 段参与 period 解析
- 分类规则启用 `APPEND_CHILD_SUFFIX` 时必须提供 `childSegments`
- 分类 `childSegments` 中的序列规则必须声明 `PER_PARENT`

### curl 示例

```bash
curl -X POST "http://localhost:8080/api/meta/code-rules/IT_RULE_ATTRIBUTE_BY_CATEGORY:publish?operator=alice"
```

### 响应示例

响应结构与 `GET /api/meta/code-rules/{ruleCode}` 一致。

---

## 6) 规则预览

> [2026-03-27 更新] 本节已补充 preview 扩展字段与 warning 语义，前端可据此提示用户当前缺失上下文或当前预览使用的序列桶。

- 方法：`POST`
- 路径：`/api/meta/code-rules/{ruleCode}:preview`

### Body：CodeRulePreviewRequestDto

```json
{
  "context": {
    "BUSINESS_DOMAIN": "MATERIAL",
    "CATEGORY_CODE": "MAT-0010",
    "ATTRIBUTE_CODE": "ATTR-MAT-0010-000001",
    "PARENT_CODE": "MAT-0010",
    "SUB_RULE_KEY": "attribute"
  },
  "manualCode": null,
  "count": 3
}
```

### context 常用键

| 键名              | 说明 |
| ----------------- | ---- |
| `BUSINESS_DOMAIN` | 业务域 |
| `PARENT_CODE`     | 父级编码，分类层级预览时常用 |
| `CATEGORY_CODE`   | 分类编码，属性规则常用 |
| `ATTRIBUTE_CODE`  | 属性编码，LOV 枚举值规则常用 |
| `SUB_RULE_KEY`    | 显式指定使用哪一个 `subRule` |

### 示例一：自动预览按分类独立计数的属性规则

```bash
curl -X POST "http://localhost:8080/api/meta/code-rules/ATTRIBUTE:preview" \
  -H "Content-Type: application/json" \
  -d '{
    "context": {
      "BUSINESS_DOMAIN": "MATERIAL",
      "CATEGORY_CODE": "MAT-0010"
    },
    "count": 3
  }'
```

响应示例：

```json
{
  "ruleCode": "ATTRIBUTE",
  "ruleVersion": 3,
  "pattern": "ATTR-{CATEGORY_CODE}-{SEQ}",
  "examples": [
    "ATTR-MAT-0010-000001",
    "ATTR-MAT-0010-000002",
    "ATTR-MAT-0010-000003"
  ],
  "warnings": [],
  "resolvedContext": {
    "BUSINESS_DOMAIN": "MATERIAL",
    "CATEGORY_CODE": "MAT-0010"
  },
  "resolvedSequenceScope": "attribute#2:CATEGORY_CODE=MAT-0010",
  "resolvedPeriodKey": "NONE"
}
```

### 示例二：手工编码校验预览

```bash
curl -X POST "http://localhost:8080/api/meta/code-rules/ATTRIBUTE:preview" \
  -H "Content-Type: application/json" \
  -d '{
    "manualCode": "ATTR-MAT-0010-000099",
    "count": 1
  }'
```

响应示例：

```json
{
  "ruleCode": "ATTRIBUTE",
  "ruleVersion": 3,
  "pattern": "ATTR-{CATEGORY_CODE}-{SEQ}",
  "examples": [
    "ATTR-MAT-0010-000099"
  ],
  "warnings": [],
  "resolvedContext": {},
  "resolvedSequenceScope": null,
  "resolvedPeriodKey": null
}
```

### 示例三：上下文缺失时的 warning

```bash
curl -X POST "http://localhost:8080/api/meta/code-rules/ATTRIBUTE:preview" \
  -H "Content-Type: application/json" \
  -d '{
    "count": 2
  }'
```

响应示例：

```json
{
  "ruleCode": "ATTRIBUTE",
  "ruleVersion": 3,
  "pattern": "ATTR-{CATEGORY_CODE}-{SEQ}",
  "examples": [],
  "warnings": [
    "MISSING_CONTEXT_VARIABLE:CATEGORY_CODE"
  ],
  "resolvedContext": {},
  "resolvedSequenceScope": null,
  "resolvedPeriodKey": null
}
```

### 预览警告说明

- `RULE_HAS_NO_SEQUENCE_PLACEHOLDER`
  - 规则没有序列段，但请求 `count > 1`
  - 系统只会返回单个渲染结果，并附带该警告
- `MISSING_CONTEXT_VARIABLE:{KEY}`
  - 预览所需的上下文变量缺失
  - 前端可直接提示用户补充对应变量
- `PREVIEW_RENDER_FAILED:{MESSAGE}`
  - 预览渲染失败，但错误不归类为“上下文缺失”
  - 前端可展示 message，帮助排查规则配置问题

---

## 7) 字段说明

> [2026-03-27 更新] 新增结构化 `ruleJson` 字段定义与预览扩展字段说明。

### 7.1 CodeRuleSaveRequestDto

| 字段                | 类型    | 必填 | 说明 |
| ------------------- | ------- | ---: | ---- |
| businessDomain      | string  |   是 | 规则所属业务域，例如 `MATERIAL`、`DEVICE` |
| ruleCode            | string  |   是 | 规则编码，内部会统一转大写 |
| name                | string  |   是 | 规则名称 |
| targetType          | string  |   是 | 目标类型，例如 `category` |
| scopeType           | string  |   否 | 默认 `GLOBAL` |
| scopeValue          | string  |   否 | 作用域值 |
| pattern             | string  |   是 | 规则摘要 pattern |
| allowManualOverride | boolean |   否 | 是否允许手工覆盖 |
| regexPattern        | string  |   否 | 手工编码校验正则 |
| maxLength           | int     |   否 | 最大长度，默认 64 |
| ruleJson            | object  |   否 | 结构化规则 JSON；若不传，后端会生成默认结构 |

### 7.2 CodeRuleDetailDto

| 字段                   | 类型     | 说明 |
| ---------------------- | -------- | ---- |
| businessDomain         | string   | 规则所属业务域 |
| ruleCode               | string   | 规则编码 |
| name                   | string   | 规则名称 |
| targetType             | string   | 目标类型 |
| scopeType              | string   | 作用域类型 |
| scopeValue             | string   | 作用域值 |
| pattern                | string   | 当前规则摘要 pattern |
| status                 | string   | 当前状态 |
| active                 | boolean  | 是否启用 |
| allowManualOverride    | boolean  | 是否允许手工覆盖 |
| regexPattern           | string   | 正则校验 |
| maxLength              | int      | 最大长度 |
| latestVersionNo        | int      | 最新版本号 |
| supportsHierarchy      | boolean  | 是否支持层级派生 |
| supportsScopedSequence | boolean  | 是否支持作用域序列或周期重置 |
| supportedVariableKeys  | string[] | 当前规则允许使用的变量键摘要 |
| latestRuleJson         | object   | 最新版本规则 JSON |

### 7.3 CodeRulePreviewRequestDto

| 字段       | 类型   | 必填 | 说明 |
| ---------- | ------ | ---: | ---- |
| context    | object |   否 | 预览上下文变量集合 |
| manualCode | string |   否 | 若传入则只做手工编码校验 |
| count      | int    |   否 | 预览返回候选数量，默认 3，最大 20 |

### 7.4 CodeRulePreviewResponseDto

| 字段                  | 类型                | 说明 |
| --------------------- | ------------------- | ---- |
| ruleCode              | string              | 规则编码 |
| ruleVersion           | int                 | 参与预览的规则版本号 |
| pattern               | string              | 当前使用的摘要 pattern |
| examples              | string[]            | 预览候选结果 |
| warnings              | string[]            | 预览警告 |
| resolvedContext       | map[string, string] | 实际用于渲染的上下文 |
| resolvedSequenceScope | string              | 当前预览落在哪个序列桶 |
| resolvedPeriodKey     | string              | 当前预览落在哪个周期桶 |

### 7.5 结构化 ruleJson 根节点

| 字段          | 类型   | 必填 | 说明 |
| ------------- | ------ | ---: | ---- |
| pattern       | string |   否 | 摘要 pattern，供列表/详情展示 |
| hierarchyMode | string |   否 | 层级模式，当前常用 `NONE`、`APPEND_CHILD_SUFFIX` |
| subRules      | object |   是 | 子规则集合，key 为 `subRuleKey` |
| validation    | object |   否 | 校验规则，包含长度、正则、是否允许手工覆盖 |

### 7.6 subRule 结构

| 字段                | 类型     | 必填 | 说明 |
| ------------------- | -------- | ---: | ---- |
| separator           | string   |   否 | 段拼接分隔符，未提供时默认 `-`；显式传空字符串表示无分隔符 |
| segments            | object[] |   是 | 当前对象主编码段 |
| childSegments       | object[] |   否 | 分类层级派生时的子级后缀段 |
| allowedVariableKeys | string[] |   否 | 当前 subRule 允许引用的变量键 |

### 7.7 segment 结构

说明：

- `separator` 支持任意字符串，常见场景是 `-`、`_`、`.`、`/`、`\\`。
- 当需要“整体无默认分隔符，但只在局部位置插入符号”时，应将 `separator` 设为空字符串，并通过 `STRING` 段显式写入符号，例如 `LOV-`、`-`。

#### STRING 段

```json
{
  "type": "STRING",
  "value": "ATTR"
}
```

#### VARIABLE 段

```json
{
  "type": "VARIABLE",
  "variableKey": "CATEGORY_CODE"
}
```

#### DATE 段

```json
{
  "type": "DATE",
  "format": "yyyyMMdd"
}
```

#### SEQUENCE 段

```json
{
  "type": "SEQUENCE",
  "length": 6,
  "startValue": 1,
  "step": 1,
  "resetRule": "PER_PARENT",
  "scopeKey": "CATEGORY_CODE"
}
```

#### resetRule 取值

- `NEVER`：永不重置
- `DAILY`：按日重置
- `MONTHLY`：按月重置
- `YEARLY`：按年重置
- `PER_PARENT`：按父级或声明的 scope 独立计数

---

## 8) 内置规则默认语义

> [2026-03-27 更新] 本节为前端配置页提供默认规则认知基线。

### 8.1 CATEGORY

- 默认 pattern：`{BUSINESS_DOMAIN}-{SEQ}`
- 默认 `subRuleKey`：`category`
- 默认变量：`BUSINESS_DOMAIN`、`PARENT_CODE`
- 默认序列：全局序列，不重置

### 8.2 ATTRIBUTE

- 默认 pattern：`ATTR-{CATEGORY_CODE}-{SEQ}`
- 默认 `subRuleKey`：`attribute`
- 默认变量：`BUSINESS_DOMAIN`、`CATEGORY_CODE`
- 默认序列：按 `CATEGORY_CODE` 独立计数，`resetRule=PER_PARENT`

### 8.3 LOV

- 默认 pattern：`ENUM-{ATTRIBUTE_CODE}-{SEQ}`
- 默认 `subRuleKey`：`enum`
- 默认变量：`ATTRIBUTE_CODE`、`CATEGORY_CODE`、`BUSINESS_DOMAIN`
- 默认序列：按 `ATTRIBUTE_CODE` 独立计数，`resetRule=PER_PARENT`
- 语义说明：当前用于枚举值项编码生成，不用于 lov_def 绑定 key 生成

---

## 9) 前端对接说明

> [2026-03-27 更新] 本节是前端实际接入时最容易误解的口径汇总。

### 9.1 code-rules 页面需要感知的新增字段

- 列表与详情统一可读取：
  - `supportsHierarchy`
  - `supportsScopedSequence`
  - `supportedVariableKeys`
- preview 统一可读取：
  - `warnings`
  - `resolvedContext`
  - `resolvedSequenceScope`
  - `resolvedPeriodKey`

### 9.2 前端如何理解 `latestRuleJson`

- `pattern`：只作为摘要和展示，不应被视为完整规则逻辑来源
- 真正可编辑逻辑应来自：
  - `hierarchyMode`
  - `subRules`
  - `segments`
  - `childSegments`
  - `allowedVariableKeys`
  - `validation`

### 9.3 关于 LOV 规则的特别说明

- 规则中心中的 `LOV` 规则，当前表示“枚举值编码规则”
- 它不表示属性与 lov_def 绑定关系使用的 key 规则
- 在属性创建、属性更新、属性导入链路中：
  - lov_def 绑定 key 在 `AUTO` 模式下由系统内部自动生成或复用
  - 前端无需提供“给 lov_def key 配规则”的额外入口
- 如果属性编辑页需要展示 `lovKey`，应将其视为业务绑定字段，而不是 code-rules 页面里的可配置规则对象

### 9.4 preview 页面建议展示方式

- 若 `warnings` 为空：展示样例列表
- 若出现 `MISSING_CONTEXT_VARIABLE:*`：提示用户补充对应上下文变量
- 若返回了 `resolvedSequenceScope`：可直接显示“当前预览使用的序列桶”
- 若返回了 `resolvedPeriodKey`：可直接显示“当前预览使用的周期桶”

---

## 10) 业务领域规则集

> [2026-03-27 更新] 本节说明本轮新增的规则集聚合层，前端管理入口建议以本节接口为主，而不是自行拼接三条单规则接口。

### 10.1 核心语义

- 一个业务域只维护一套规则集
- 规则集详情会一次性返回：
  - 规则集自身摘要字段
  - `CATEGORY`、`ATTRIBUTE`、`LOV` 三条规则的完整详情
- 前端保存流程建议拆分为两层：
  - 先保存单条规则内容
  - 再保存规则集绑定关系

### 10.2 运行时路由说明

- 分类自动编码：按分类 `businessDomain` 解析 `categoryRuleCode`
- 属性自动编码：按分类 `businessDomain` 解析 `attributeRuleCode`
- 枚举值自动编码：按分类 `businessDomain` 解析 `lovRuleCode`
- 当前已开放接入的业务域至少包含：`MATERIAL`、`DEVICE`、`DOCUMENT`

---

## 11) 规则集接口明细

### 11.1 规则集列表

- 方法：`GET`
- 路径：`/api/meta/code-rule-sets`

```bash
curl "http://localhost:8080/api/meta/code-rule-sets"
```

响应示例：

```json
[
  {
    "businessDomain": "MATERIAL",
    "name": "Material 编码规则集",
    "status": "ACTIVE",
    "active": true,
    "remark": "默认初始化规则集",
    "categoryRuleCode": "CATEGORY",
    "attributeRuleCode": "ATTRIBUTE",
    "lovRuleCode": "LOV"
  },
  {
    "businessDomain": "DEVICE",
    "name": "Device Rule Set",
    "status": "ACTIVE",
    "active": true,
    "remark": "device-it",
    "categoryRuleCode": "CATEGORY_DEVICE",
    "attributeRuleCode": "ATTRIBUTE_DEVICE",
    "lovRuleCode": "LOV_DEVICE"
  }
]
```

### 11.2 规则集详情

- 方法：`GET`
- 路径：`/api/meta/code-rule-sets/{businessDomain}`

```bash
curl "http://localhost:8080/api/meta/code-rule-sets/DEVICE"
```

响应示例：

```json
{
  "businessDomain": "DEVICE",
  "name": "Device Rule Set",
  "status": "ACTIVE",
  "active": true,
  "remark": "device-it",
  "categoryRuleCode": "CATEGORY_DEVICE",
  "attributeRuleCode": "ATTRIBUTE_DEVICE",
  "lovRuleCode": "LOV_DEVICE",
  "rules": {
    "CATEGORY": {
      "businessDomain": "DEVICE",
      "ruleCode": "CATEGORY_DEVICE",
      "name": "CATEGORY_DEVICE",
      "targetType": "category",
      "pattern": "DEV-{SEQ}",
      "status": "ACTIVE",
      "active": true,
      "supportsHierarchy": false,
      "supportsScopedSequence": false,
      "supportedVariableKeys": [
        "BUSINESS_DOMAIN",
        "PARENT_CODE"
      ],
      "latestRuleJson": {
        "pattern": "DEV-{SEQ}",
        "hierarchyMode": "NONE"
      }
    },
    "ATTRIBUTE": {
      "businessDomain": "DEVICE",
      "ruleCode": "ATTRIBUTE_DEVICE",
      "name": "ATTRIBUTE_DEVICE",
      "targetType": "attribute",
      "pattern": "DATTR-{CATEGORY_CODE}-{SEQ}",
      "status": "ACTIVE",
      "active": true,
      "supportsHierarchy": false,
      "supportsScopedSequence": true,
      "supportedVariableKeys": [
        "BUSINESS_DOMAIN",
        "CATEGORY_CODE"
      ],
      "latestRuleJson": {
        "pattern": "DATTR-{CATEGORY_CODE}-{SEQ}",
        "hierarchyMode": "NONE"
      }
    },
    "LOV": {
      "businessDomain": "DEVICE",
      "ruleCode": "LOV_DEVICE",
      "name": "LOV_DEVICE",
      "targetType": "lov",
      "pattern": "DVAL-{ATTRIBUTE_CODE}-{SEQ}",
      "status": "ACTIVE",
      "active": true,
      "supportsHierarchy": false,
      "supportsScopedSequence": true,
      "supportedVariableKeys": [
        "BUSINESS_DOMAIN",
        "CATEGORY_CODE",
        "ATTRIBUTE_CODE"
      ],
      "latestRuleJson": {
        "pattern": "DVAL-{ATTRIBUTE_CODE}-{SEQ}",
        "hierarchyMode": "NONE"
      }
    }
  }
}
```

### 11.3 创建规则集

- 方法：`POST`
- 路径：`/api/meta/code-rule-sets`

Query 参数：

| 参数     | 类型   | 必填 | 默认值 | 说明   |
| -------- | ------ | ---: | ------ | ------ |
| operator | string |   否 | system | 操作人 |

Body：CodeRuleSetSaveRequestDto

```json
{
  "businessDomain": "DEVICE",
  "name": "Device Rule Set",
  "remark": "device-it",
  "categoryRuleCode": "CATEGORY_DEVICE",
  "attributeRuleCode": "ATTRIBUTE_DEVICE",
  "lovRuleCode": "LOV_DEVICE"
}
```

```bash
curl -X POST "http://localhost:8080/api/meta/code-rule-sets?operator=alice" \
  -H "Content-Type: application/json" \
  -d '{
    "businessDomain":"DEVICE",
    "name":"Device Rule Set",
    "remark":"device-it",
    "categoryRuleCode":"CATEGORY_DEVICE",
    "attributeRuleCode":"ATTRIBUTE_DEVICE",
    "lovRuleCode":"LOV_DEVICE"
  }'
```

响应结构与 `GET /api/meta/code-rule-sets/{businessDomain}` 一致，首次创建后通常返回 `status=DRAFT`、`active=false`。

### 11.4 更新规则集

- 方法：`PUT`
- 路径：`/api/meta/code-rule-sets/{businessDomain}`

```bash
curl -X PUT "http://localhost:8080/api/meta/code-rule-sets/DEVICE?operator=bob" \
  -H "Content-Type: application/json" \
  -d '{
    "businessDomain":"DEVICE",
    "name":"Device Rule Set V2",
    "remark":"device-it-v2",
    "categoryRuleCode":"CATEGORY_DEVICE",
    "attributeRuleCode":"ATTRIBUTE_DEVICE",
    "lovRuleCode":"LOV_DEVICE"
  }'
```

响应结构与 `GET /api/meta/code-rule-sets/{businessDomain}` 一致。

### 11.5 发布规则集

- 方法：`POST`
- 路径：`/api/meta/code-rule-sets/{businessDomain}:publish`

```bash
curl -X POST "http://localhost:8080/api/meta/code-rule-sets/DEVICE:publish?operator=alice"
```

发布行为：

- 会先校验三条绑定规则是否存在
- 会校验三条绑定规则的 `businessDomain` 与 `targetType` 是否匹配
- 会调用单条规则发布逻辑，最终把规则集设为 `ACTIVE`

响应结构与 `GET /api/meta/code-rule-sets/{businessDomain}` 一致，且返回值中的规则集与三条规则都应为激活状态。

### 11.6 规则集字段说明

#### CodeRuleSetSaveRequestDto

| 字段 | 类型 | 必填 | 说明 |
| ---- | ---- | ---: | ---- |
| businessDomain | string | 是 | 规则集所属业务域 |
| name | string | 是 | 规则集名称 |
| remark | string | 否 | 备注 |
| categoryRuleCode | string | 是 | 分类规则编码 |
| attributeRuleCode | string | 是 | 属性规则编码 |
| lovRuleCode | string | 是 | 枚举值规则编码 |

#### CodeRuleSetSummaryDto

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| businessDomain | string | 业务域 |
| name | string | 规则集名称 |
| status | string | 规则集状态 |
| active | boolean | 是否激活 |
| remark | string | 备注 |
| categoryRuleCode | string | 分类规则编码 |
| attributeRuleCode | string | 属性规则编码 |
| lovRuleCode | string | 枚举值规则编码 |

#### CodeRuleSetDetailDto

| 字段 | 类型 | 说明 |
| ---- | ---- | ---- |
| businessDomain | string | 业务域 |
| name | string | 规则集名称 |
| status | string | 规则集状态 |
| active | boolean | 是否激活 |
| remark | string | 备注 |
| categoryRuleCode | string | 分类规则编码 |
| attributeRuleCode | string | 属性规则编码 |
| lovRuleCode | string | 枚举值规则编码 |
| rules | map[string, CodeRuleDetailDto] | 按 `CATEGORY`、`ATTRIBUTE`、`LOV` 返回完整规则详情 |

---

## 12) 示例速查

### 10.1 分类编码

- 规则：`{BUSINESS_DOMAIN}-{SEQ}`
- 输入上下文：`BUSINESS_DOMAIN=MATERIAL`
- 生成示例：
  - `MATERIAL-0001`
  - `MATERIAL-0002`

### 10.2 属性编码

- 规则：`ATTR-{CATEGORY_CODE}-{SEQ}`
- 输入上下文：`CATEGORY_CODE=MAT-0010`
- 生成示例：
  - `ATTR-MAT-0010-000001`
  - `ATTR-MAT-0010-000002`

### 10.3 枚举值编码

- 规则：`ENUM-{ATTRIBUTE_CODE}-{SEQ}`
- 输入上下文：`ATTRIBUTE_CODE=ATTR-MAT-0010-000001`
- 生成示例：
  - `ENUM-ATTR-MAT-0010-000001-01`
  - `ENUM-ATTR-MAT-0010-000001-02`

---

## 13) 实现入口

- 控制器：`plm-attribute-service/src/main/java/com/plm/attribute/version/controller/MetaCodeRuleController.java`
- 控制器：`plm-attribute-service/src/main/java/com/plm/attribute/version/controller/MetaCodeRuleSetController.java`
- 服务：`plm-attribute-service/src/main/java/com/plm/attribute/version/service/MetaCodeRuleService.java`
- 服务：`plm-attribute-service/src/main/java/com/plm/attribute/version/service/MetaCodeRuleSetService.java`
- 生成器：`plm-infrastructure/src/main/java/com/plm/infrastructure/code/CodeRuleGenerator.java`
- DTO：
  - `plm-common/src/main/java/com/plm/common/api/dto/code/CodeRuleSaveRequestDto.java`
  - `plm-common/src/main/java/com/plm/common/api/dto/code/CodeRuleDetailDto.java`
  - `plm-common/src/main/java/com/plm/common/api/dto/code/CodeRulePreviewRequestDto.java`
  - `plm-common/src/main/java/com/plm/common/api/dto/code/CodeRulePreviewResponseDto.java`
  - `plm-common/src/main/java/com/plm/common/api/dto/code/CodeRuleSetSaveRequestDto.java`
  - `plm-common/src/main/java/com/plm/common/api/dto/code/CodeRuleSetSummaryDto.java`
  - `plm-common/src/main/java/com/plm/common/api/dto/code/CodeRuleSetDetailDto.java`
