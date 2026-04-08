# 元数据工作簿导出 API 设计草案

更新时间：2026-04-08
阶段：设计草案（本阶段先不改代码实现）

---

## 1. 背景

当前前端已经完成一版工作簿导出配置器原型，核心能力包括：

- 选择导出范围。
- 按模块启停导出内容。
- 自定义导出字段。
- 调整字段顺序。
- 修改导出表头。
- 选择文件格式与路径分隔符。

但当前前端数据全部基于 mock 数据，只能证明交互形态和配置结构，不代表后端真实模型，也不能直接作为正式接口契约。

因此，本草案的目标不是“把前端 mock JSON 生成功能搬到后端”，而是设计一套正式的、以后端真实元数据模型为准的工作簿导出接口体系，使前端当前的配置能力可以映射为后端可执行的导出作业。

---

## 2. 目标

- 支持以分类范围为起点，导出分类、属性、枚举值三类元数据。
- 支持用户按模块选择需要导出的 sheet。
- 支持用户按稳定字段键选择列、调整列顺序、重命名表头。
- 由后端基于真实数据拼装 xlsx 工作簿并提供下载。
- 与现有 workbook import 保持风格对称，复用 job、日志、SSE 流等运行模型。
- 明确导出能力仅服务于下载，不承担回导入契约。
- 明确一切实际字段、枚举与事实口径以后端为准，不信任前端 mock 字段定义。
- 三个模块的可导出字段应尽可能与数据库已落表、已持久化的真实字段对齐，不做前端 mock 裁剪版契约。
- 属性模块除基础标识字段外，应覆盖属性参数字段，例如 unit、defaultValue、required、unique、hidden、readOnly、searchable、minValue、maxValue、step、precision、trueLabel、falseLabel、lovKey 等。

---

## 3. 非目标

- 不要求后端保存前端 profile。前端的内置方案和 localStorage 自定义方案仍可保留在前端。
- 不以当前前端 mock 中的字段命名、状态枚举、业务域展示词作为正式契约。
- 首版不以同步导出为主路径。
- 首版不承诺 CSV 与 XLSX 同等优先级，主目标是 XLSX。

---

## 4. 前端现状解读

### 4.1 当前前端真实可复用能力

前端当前最有价值的不是 mock 数据本身，而是以下配置语义：

- scope：用户选择哪些分类节点。
- includeChildren：是否自动带出子树。
- modules：是否导出 category / attribute / enumOption。
- columns：每个模块导出哪些列。
- column order：列顺序由前端显式给出。
- targetHeader：列头允许自定义。
- output.format：导出格式。
- output.pathSeparator：路径展示方式。

这些能力都可以稳定映射到后端导出请求。

### 4.2 当前前端 mock 与后端真实模型的差异

以下内容不能直接下沉为后端正式契约：

- selectedNodeKeys：这是前端 UI 词汇，且当前值是 mock tree key，不是正式领域字段。
- sourceLabel：只是前端展示文本，后端不能信任。
- targetHeader：只能作为输出展示名，不能作为字段绑定依据。
- category.path：当前前端语义偏展示路径，后端需要区分名称路径、编码路径和结构路径。
- category.status：前端 mock 状态与后端分类状态枚举不一致。
- attribute.type：前端存在 boolean，而后端属性文档当前正式口径是 bool。
- attribute.readonly：前端字段是 readonly，后端正式字段是 readOnly。
- enumOption.color / image / order：当前更偏前端增强展示字段，是否为真实后端字段要以后端模型确认。

结论：

- 后端只接受稳定 fieldKey。
- 后端不接受前端 mock 字段清单作为事实来源。
- 前端后续应通过 schema 接口动态读取可导出字段定义。

---

## 5. 总体设计原则

### 5.1 以后端稳定字段键为唯一绑定依据

导出列绑定必须只基于 fieldKey，不得基于：

- sourceLabel
- targetHeader
- 前端列 id
- 前端 mock 字段名

### 5.2 导出对象固定为三类模块

建议后端统一模块键：

- CATEGORY
- ATTRIBUTE
- ENUM_OPTION

每个模块对应一个 sheet。

字段完整性原则：

- CATEGORY：覆盖分类定义字段、最新版本字段、层级路径与父子关系派生字段。
- ATTRIBUTE：覆盖属性定义字段、最新版本字段，以及 structureJson 中已持久化的属性参数字段。
- ENUM_OPTION：覆盖 LOV 定义字段、最新版本字段，以及 valueJson 中已持久化的枚举值字段。

### 5.3 导出应 job 化

考虑到 includeChildren 开启后，导出规模可能迅速扩大到：

- 整个分类子树
- 子树下全部属性
- 属性下全部枚举值

因此正式接口应采用异步作业模型，而不是同步返回整份文件。

### 5.4 与 workbook import 保持对称

推荐在 URL 风格、状态模型、日志模型、SSE 事件模型上与现有 workbook import 保持一致。

建议统一入口：

- /api/meta/exports/workbook

### 5.5 明确区分 round-trip 与 custom

导出能力仅用于按后端真实字段生成下载文件，不承担再次导入的契约。

因此本期设计不考虑：

- round-trip 导出模式
- 可回导入字段最小集合约束
- 为回导入而设计的隐藏元数据

本期只保留 CUSTOM 导出语义：

- 允许按需裁剪字段
- 允许改表头
- 允许调整顺序
- 允许按模块启停 sheet

补充约束：

- 不做“为了后续可能回导入”而压缩字段集合。
- CATEGORY、ATTRIBUTE、ENUM_OPTION 三个模块的字段定义应优先覆盖后端 def/version/json 中已有真实字段。
- 若某字段仅存在于前端 mock 而未在数据库或后端持久化结构中落地，则不纳入正式 schema。

---

## 6. 推荐接口总览

建议统一入口：/api/meta/exports/workbook

| 方法 | 路径 | 用途 | 说明 |
| --- | --- | --- | --- |
| GET | /api/meta/exports/workbook/schema | 获取导出字段 schema | 前端字段面板、模块默认列来源 |
| POST | /api/meta/exports/workbook/plan | 导出计划预校验 | 可选接口，用于返回预计行数、字段修正、风险提示 |
| POST | /api/meta/exports/workbook/jobs | 创建导出作业 | 页面主入口 |
| GET | /api/meta/exports/workbook/jobs/{jobId} | 查询作业状态 | 轮询兜底 |
| GET | /api/meta/exports/workbook/jobs/{jobId}/logs | 查询作业日志 | 断线补拉、详情面板 |
| GET | /api/meta/exports/workbook/jobs/{jobId}/stream | 导出 SSE | 页面主进度来源 |
| GET | /api/meta/exports/workbook/jobs/{jobId}/result | 查询导出结果 | 返回文件信息、摘要、最终配置 |
| GET | /api/meta/exports/workbook/jobs/{jobId}/download | 下载导出文件 | 仅 COMPLETED 可用 |
| DELETE | /api/meta/exports/workbook/jobs/{jobId} | 取消导出作业 | 可选增强 |

---

## 7. 推荐调用链路

### 7.1 页面初始化

1. 调用 GET /api/meta/exports/workbook/schema 获取后端正式字段 schema。
2. 以前端内置 profile 作为初始配置来源，但字段清单以后端 schema 为准。

### 7.2 用户点击导出

1. 前端按当前配置构造 WorkbookExportStartRequestDto。
2. 调用 POST /api/meta/exports/workbook/jobs 创建作业。
3. 拿到 jobId 后立即连接 GET /api/meta/exports/workbook/jobs/{jobId}/stream。
4. 以 SSE progress 为主进度来源，并用 GET /jobs/{jobId} 做轮询兜底。
5. 作业完成后先调 GET /jobs/{jobId}/result 获取文件元数据。
6. 再调用 GET /jobs/{jobId}/download 下载文件流。

### 7.3 不推荐的调用方式

- 不建议同步直接返回文件。
- 不建议只依赖 completed 事件而不再拉一次最终状态。
- 不建议前端自己拼 workbook 内容再上传或下载。

---

## 8. 请求与响应 DTO 设计

### 8.1 WorkbookExportStartRequestDto

建议结构：

```json
{
  "businessDomain": "MATERIAL",
  "scope": {
    "categoryIds": [
      "6513139a-4899-4808-8751-bb0b3eaeb0a8"
    ],
    "includeChildren": true
  },
  "output": {
    "format": "XLSX",
    "fileName": "material-workbook-export.xlsx",
    "pathSeparator": " > "
  },
  "modules": [
    {
      "moduleKey": "CATEGORY",
      "enabled": true,
      "sheetName": "分类",
      "columns": [
        {
          "fieldKey": "categoryCode",
          "headerText": "分类编码",
          "clientColumnId": "col_code_0"
        }
      ]
    }
  ],
  "operator": "alice",
  "clientRequestId": "f0b7f3b8-2d5f-4b57-a07a-b17b6f6d0d50"
}
```

### 8.2 字段说明

#### 顶层字段

| 字段 | 必填 | 说明 |
| --- | ---: | --- |
| businessDomain | 是 | 业务域，正式值以后端枚举为准 |
| scope | 是 | 导出范围 |
| output | 是 | 输出配置 |
| modules | 是 | 模块配置 |
| operator | 否 | 操作人 |
| clientRequestId | 否 | 幂等或排查辅助 |

#### scope

| 字段 | 必填 | 说明 |
| --- | ---: | --- |
| categoryIds | 是 | 真实分类 ID 列表，建议不用 selectedNodeKeys 命名 |
| includeChildren | 否 | 是否导出所选节点全部子树 |

说明：

- 若后端后续更倾向 categoryCode，也可以改为 categoryCodes。
- 但首版建议使用稳定主键 ID，避免 code 变更或歧义问题。

#### output

| 字段 | 必填 | 说明 |
| --- | ---: | --- |
| format | 是 | 首版主路径建议只保证 XLSX |
| fileName | 否 | 自定义文件名 |
| pathSeparator | 否 | 仅影响展示型路径列 |

#### modules

每个模块建议字段如下：

| 字段 | 必填 | 说明 |
| --- | ---: | --- |
| moduleKey | 是 | CATEGORY / ATTRIBUTE / ENUM_OPTION |
| enabled | 是 | 是否导出该模块 |
| sheetName | 否 | 允许前端自定义 sheet 名 |
| columns | 是 | 列定义数组，按顺序输出 |

#### WorkbookExportColumnRequestDto

| 字段 | 必填 | 说明 |
| --- | ---: | --- |
| fieldKey | 是 | 唯一绑定依据 |
| headerText | 否 | 输出表头 |
| clientColumnId | 否 | 前端调试字段，无业务语义 |

### 8.3 严格禁止作为绑定依据的字段

后端必须忽略或禁止以下字段参与导出字段绑定：

- sourceLabel
- targetHeader 之外的任意展示文案
- mock 数据值
- 前端列序号
- 前端列 id

---

## 9. Schema 接口设计

### 9.1 用途

schema 接口用于收敛：

- 后端正式支持的模块。
- 每个模块正式支持的稳定字段键。
- 默认表头。
- 字段是否默认选中。
- 是否允许自定义表头。

### 9.2 响应结构建议

```json
{
  "schemaVersion": "2026-04-08",
  "modules": [
    {
      "moduleKey": "CATEGORY",
      "defaultSheetName": "分类",
      "fields": [
        {
          "fieldKey": "categoryCode",
          "defaultHeader": "分类编码",
          "description": "分类业务编码",
          "valueType": "STRING",
          "defaultSelected": true,
          "allowCustomHeader": true
        }
      ]
    }
  ]
}
```

### 9.3 字段 schema 的价值

schema 是前后端对齐的唯一来源，后续前端不应再硬编码 mock 字段列表。

---

## 10. 三类模块的推荐字段键

以下为当前实现已经覆盖的主字段方向，后续若数据库模型继续扩展，应优先在 schema 中新增 fieldKey，而不是回退到前端硬编码。

### 10.1 CATEGORY 模块

当前实现已覆盖的核心字段包括：

- categoryId
- businessDomain
- categoryCode
- categoryName
- status
- parentId
- parentCode
- parentName
- rootId
- rootCode
- rootName
- path
- fullPathName
- level
- depth
- sortOrder
- isLeaf
- hasChildren
- externalCode
- codeKeyManualOverride
- codeKeyFrozen
- generatedRuleCode
- generatedRuleVersionNo
- copiedFromCategoryId
- latestVersionNo
- latestVersionDate
- latestVersionUpdatedBy
- latestVersionDescription
- createdAt
- createdBy
- modifiedAt
- modifiedBy

### 10.2 ATTRIBUTE 模块

当前实现已覆盖的核心字段包括：

- attributeId
- businessDomain
- categoryId
- categoryCode
- categoryName
- attributeKey
- status
- hasLov
- autoBindKey
- keyManualOverride
- keyFrozen
- generatedRuleCode
- generatedRuleVersionNo
- latestVersionId
- latestVersionNo
- categoryVersionId
- resolvedCodePrefix
- structureHash
- displayName
- description
- attributeField
- dataType
- unit
- defaultValue
- required
- unique
- hidden
- readOnly
- searchable
- lovKey
- minValue
- maxValue
- step
- precision
- trueLabel
- falseLabel
- createdAt
- createdBy
- modifiedAt
- modifiedBy

### 10.3 ENUM_OPTION 模块

当前实现已覆盖的核心字段包括：

- businessDomain
- categoryId
- categoryCode
- categoryName
- attributeId
- attributeKey
- attributeDisplayName
- attributeField
- attributeDataType
- lovDefId
- lovKey
- lovStatus
- lovDescription
- sourceAttributeKey
- lovKeyManualOverride
- lovKeyFrozen
- lovGeneratedRuleCode
- lovGeneratedRuleVersionNo
- lovCreatedAt
- lovCreatedBy
- lovVersionId
- lovVersionNo
- lovResolvedCodePrefix
- lovHash
- lovVersionCreatedAt
- lovVersionCreatedBy
- optionCode
- optionName
- optionLabel
- optionOrder
- optionDisabled

---

## 11. 作业状态与阶段建议

当前实现已采用与 workbook import 对称的异步作业风格。

### 11.1 状态枚举

- QUEUED
- RESOLVING_SCOPE
- LOADING_CATEGORIES
- LOADING_ATTRIBUTES
- LOADING_ENUM_OPTIONS
- BUILDING_WORKBOOK
- STORING_FILE
- COMPLETED
- FAILED
- CANCELED

### 11.2 当前状态 DTO 信息

当前 WorkbookExportJobStatusDto 已包含：

- jobId
- businessDomain
- status
- currentStage
- fileName
- overallPercent
- stagePercent
- createdAt
- startedAt
- updatedAt
- completedAt
- progress
- latestLogs
- warnings

当前 progress 分模块提供：

- categories
- attributes
- enumOptions

每个模块当前提供：

- total
- processed
- exported
- failed

---

## 12. 结果与下载接口建议

### 12.1 WorkbookExportJobResultDto

当前实现已包含：

- jobId
- status
- summary
- resolvedRequest
- file
- warnings
- completedAt

其中 file 当前包含：

- fileName
- contentType
- size
- checksum
- expiresAt

### 12.2 下载接口

当前实现接口：

- GET /api/meta/exports/workbook/jobs/{jobId}/download

当前实现行为：

- 仅在结果已生成时允许下载。
- 文件过期后拒绝下载。

---

## 13. 与 workbook import 的对称设计建议

建议导出与导入共享以下概念：

- 相同模块划分：CATEGORY / ATTRIBUTE / ENUM_OPTION。
- 相似 job 状态模型。
- 相似日志模型。
- 相似 SSE stream 模型。
- 相同 businessDomain 约束。
- 相同稳定字段 key 体系。
- 相同 schemaVersion 管理思路。

差异点：

- import 的核心产物是 preview、issues、execution plan。
- export 的核心产物是 file、summary、resolved request。

---

## 14. 风险与边界

### 14.1 表头可改写不应影响字段绑定

即使用户改了表头，后端仍必须严格按 fieldKey 导出，不能反向把 headerText 当字段标识。

### 14.2 范围标识必须正式化

前端当前 selectedNodeKeys 是 UI 命名和 mock 值，后端必须改为正式的 categoryIds 或 categoryCodes。

### 14.3 路径语义必须拆清楚

path 不能作为单一笼统字段继续沿用，至少要区分：

- categoryNamePath
- categoryCodePath
- 结构路径或内部 path

### 14.4 前端 mock 枚举与后端真实枚举存在偏差

包括：

- businessDomain 展示词
- category status
- attribute dataType
- readonly/readOnly 命名

正式接口必须以后端字段与枚举为准。

### 14.5 枚举值增强字段需谨慎纳入首版

color、image、description 等字段是否真实存在于 LOV 正式模型，需要先确认，不建议首版默认纳入核心导出字段集合。

### 14.6 大子树导出必须异步化

includeChildren 打开后，数据量会按分类、属性、枚举值三级联动放大，同步导出风险很高。

### 14.7 文件生成与存储需要资源治理

建议考虑：

- 流式写出。
- 临时文件存储。
- 过期清理。
- 下载有效期控制。
- 大文件上限保护。

### 14.8 快照一致性需要提前定义

导出过程中若元数据发生修改，需要定义：

- 是按作业启动时快照导出。
- 还是允许读取到进行中的新版本。

首版建议按作业启动时的一致视图执行。

### 14.9 权限控制不能后置

应在建作业阶段校验：

- 用户是否有权导出该 businessDomain。
- 是否有权访问所选分类子树。
- 是否有权导出敏感列。

---

## 15. 当前落地状态

当前已实现：

1. schema 接口。
2. plan 预校验接口。
3. 异步 jobs 创建、状态、日志、SSE、结果、下载、取消接口。
4. CATEGORY / ATTRIBUTE / ENUM_OPTION 三模块 schema 与取数。
5. xlsx 文件生成与下载。

当前仍保留为后续增强项：

1. 权限控制接入。
2. 更强的一致性快照定义与持久化。
3. 大文件存储治理与对象存储抽象。
4. 前端从 mock 配置向 schema 驱动配置的正式切换文档。
