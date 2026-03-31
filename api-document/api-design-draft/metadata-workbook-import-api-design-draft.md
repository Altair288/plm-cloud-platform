# 元数据导入模板接口设计草案

更新时间：2026-03-31
阶段：设计草案（本阶段先不改代码实现）

---

## 1. 背景

本次设计面向一套全新的元数据工作簿导入能力。

明确约束：

- 当前系统内部已有的分类导入接口、属性导入接口以及 UNSPSC 导入接口，均视为早期测试期快捷实现。
- 本期设计直接忽略这些旧接口，不做兼容设计，也不以它们为演进目标。
- 本期要设计的是一套完全符合当前系统业务模型的新接口体系。

本次模板不是单一 sheet 的数据表，而是一个统一工作簿，业务上承载的是一次完整的元数据导入批次，包含：

- 分类层级
- 属性定义
- 枚举值定义
- 模板内置字典与填写规则说明

因此，本期能力的本质不是“补一个 Excel 上传接口”，而是建立“工作簿级导入编排能力”。

---

## 2. 目标

- 支持基于统一 Excel 模板完成分类、属性、枚举值的一次性导入。
- 提供导入前 dry-run 校验接口，并将解析后的标准化预览结果完整返回前端。
- 提供正式导入接口。
- 支持导入配置项，允许用户选择“使用 Excel 中配置的编码”或“导入过程中按系统编码规则自动生成编码”。
- 支持对重复数据的处理策略配置，明确覆盖、保留原样或直接失败。
- 提供真实进度快照与高颗粒度操作日志能力，避免前端在导入过程中拿不到错误与执行状态。
- 必须提供 SSE 实时日志流与进度流，保证前端可实时获取导入进度和执行日志。
- 服务端按模板真实输入列解析，忽略公式列、辅助列、隐藏列和参考字典列。
- 新接口体系独立成立，不依赖旧导入接口的 URL、返回模型或兼容语义。

---

## 3. 模板识别结论

### 3.1 工作表识别

| 工作表 | 作用 | 是否进入导入 | 说明 |
| --- | --- | --- | --- |
| 分类层级 | 分类树主表 | 是 | 只取 A-D 输入列 |
| 属性定义 | 分类下属性主表 | 是 | 一行一个属性 |
| 枚举值定义 | 枚举型属性值集子表 | 是 | 一行一个枚举值 |
| 代码清单 | 模板字典与填写说明 | 否 | 不做业务导入，仅做模板参考 |

### 3.2 模板结构特征

- 这是“输入列 + 公式辅助列 + 字典说明 sheet”的复合模板。
- 分类层级 sheet 中 E-N 为公式与校验辅助列，不能作为可信输入。
- 分类层级 sheet 中 G、H、I 为隐藏辅助列，上传后服务端仍需自行推导父级关系。
- 模板大量使用预制空白行，因此不能按 Excel 物理非空行数识别有效数据。

### 3.3 有效行识别规则

推荐只按真实输入列判断业务有效行：

- 分类层级：只看 A-D
- 属性定义：只看 A-T
- 枚举值定义：只看 A-E

若整行输入列全部为空，则该行视为模板预留空行，必须忽略。

---

## 4. 模板字段建模

### 4.1 分类层级 sheet

建议只读取以下输入列：

| 列 | 字段 | 含义 |
| --- | --- | --- |
| A | businessDomain | 业务域 |
| B | categoryCode | 分类编码 |
| C | categoryPath | 分类路径 |
| D | categoryName | 分类名称 |

服务端自行推导：

- levelPreview
- currentNodeFromPath
- parentPath
- parentCode
- codePathMatchCheck
- parentExistsCheck
- pathUniqueCheck
- codeUniqueCheck
- rowStatus

### 4.2 属性定义 sheet

| 列 | 字段 | 含义 |
| --- | --- | --- |
| A | categoryCode | 所属分类编码 |
| B | categoryName | 所属分类名称，建议作为冗余校验字段 |
| C | attributeKey | 属性编码 |
| D | attributeName | 属性名称 |
| E | attributeField | 属性字段名 |
| F | description | 属性说明 |
| G | dataType | 数据类型 |
| H | unit | 单位 |
| I | defaultValue | 默认值 |
| J | required | 必填标志 |
| K | unique | 唯一标志 |
| L | searchable | 可搜索标志 |
| M | hidden | 隐藏标志 |
| N | readOnly | 只读标志 |
| O | minValue | 最小值 |
| P | maxValue | 最大值 |
| Q | step | 步长 |
| R | precision | 精度 |
| S | trueLabel | 布尔真值显示 |
| T | falseLabel | 布尔假值显示 |

### 4.3 枚举值定义 sheet

| 列 | 字段 | 含义 |
| --- | --- | --- |
| A | categoryCode | 所属分类编码 |
| B | attributeKey | 所属属性编码 |
| C | optionCode | 选项编码 |
| D | optionName | 选项值 |
| E | displayLabel | 显示标签 |

### 4.4 编码字段在 manual/auto 模式下的解释

模板中存在多个编码列：

- 分类层级.categoryCode
- 属性定义.attributeKey
- 枚举值定义.optionCode

当用户选择 manual 模式时：

- 这些列的值直接作为最终业务编码写入系统。

当用户选择 auto 模式时：

- 这些列不再作为最终落库编码。
- 这些列在工作簿内部转为“导入引用键”或“Excel 引用编码”，仅用于：
  - 解析父子关系
  - 解析分类与属性关联
  - 解析属性与枚举值关联
  - 向前端返回 Excel 编码与最终系统编码的映射关系

因此，auto 模式下前端预览必须同时展示：

- excelReferenceCode
- resolvedFinalCode

对于分类层级，auto 模式下的 categoryPath 也应理解为“基于 Excel 引用编码的逻辑路径”，服务端需额外生成：

- resolvedFinalPath

---

## 5. 业务主键与幂等键建议

### 5.1 分类

- 推荐唯一键：businessDomain + categoryCode
- 次级唯一键：businessDomain + categoryPath
- 推荐幂等键：businessDomain + categoryCode

说明：

- categoryPath 适合做批次内校验，不适合作为长期幂等主键。
- 后续若支持分类移动，路径可能变化，但编码通常仍应稳定。

### 5.2 属性

- 推荐唯一键：categoryCode + attributeKey
- 次级唯一键：categoryCode + attributeField
- 推荐幂等键：categoryCode + attributeKey

### 5.3 枚举值

- 推荐唯一键：categoryCode + attributeKey + optionCode
- 次级唯一键：categoryCode + attributeKey + optionName
- 推荐幂等键：categoryCode + attributeKey + optionCode

---

## 6. 核心设计原则

### 6.1 全新接口体系

本期接口体系完全独立设计，不兼容、不复用、不包装旧导入接口的外部契约。

旧接口在本草案中一律不作为约束条件。

### 6.2 导入主模型

逻辑结构：

- 一级：分类树
- 二级：属性
- 三级：枚举值

导入顺序：

1. 分类层级
2. 属性定义
3. 枚举值定义

### 6.3 模板辅助列处理原则

服务端必须忽略模板中的所有辅助列、公式列、隐藏列和参考字典列，不接受这些列作为事实来源。

服务端必须自行完成：

- 路径解析
- 父级推导
- 批次内唯一性校验
- 跨 sheet 引用校验
- 与数据库现状的差异比对
- 前端预览模型组装

### 6.4 前后端信息对齐原则

前端不能只拿到一个最终 summary。

新接口必须同时提供：

- 解析后的标准化预览数据
- dry-run 校验结果
- 正式导入过程中的真实进度
- 行级、实体级、阶段级操作日志
- 最终执行结果

### 6.5 编码模式设计

本期导入必须支持两种编码模式：

- EXCEL_MANUAL：使用 Excel 中填写的编码
- SYSTEM_RULE_AUTO：使用系统当前生效的编码规则自动生成编码

建议按实体类型分别配置，而不是一个全局开关：

- categoryCodeMode
- attributeCodeMode
- enumOptionCodeMode

说明：

- 分类、属性、枚举值的编码策略可能不同。
- 例如分类保留 Excel 编码，属性和枚举值走系统规则，是允许的。

### 6.6 重复数据处理策略设计

本期导入必须支持对“幂等键已存在”的数据做显式策略配置。

建议按实体类型分别配置：

- categoryDuplicatePolicy
- attributeDuplicatePolicy
- enumOptionDuplicatePolicy

建议策略值：

- OVERWRITE_EXISTING：覆盖已有数据
- KEEP_EXISTING：保留已有数据，本次记录按 skip 处理
- FAIL_ON_DUPLICATE：命中重复即报错

预览阶段必须把策略计算后的最终动作回显给前端，例如：

- CREATE
- UPDATE
- KEEP_EXISTING
- CONFLICT

---

## 7. 推荐接口拓扑

推荐采用“预校验会话 + 异步导入任务”模型。

### 7.1 dry-run 校验接口

- 方法：POST
- 路径：/api/meta/imports/workbook/dry-run
- Content-Type：multipart/form-data

用途：

- 上传工作簿
- 识别模板
- 解析并标准化数据
- 执行全部 dry-run 校验
- 生成可供前端展示的预览结果
- 生成 importSessionId，供后续正式导入使用

请求参数建议：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | ---: | --- |
| file | MultipartFile | 是 | 上传工作簿 |
| operator | string | 否 | 操作人 |
| failFast | boolean | 否 | 默认 false，建议尽量收集更多错误 |
| validationMode | string | 否 | 默认 FULL |
| options | json string / request part | 是 | 导入配置，包含编码模式与重复数据处理策略 |

options 草案：

```json
{
  "codingOptions": {
    "categoryCodeMode": "EXCEL_MANUAL",
    "attributeCodeMode": "SYSTEM_RULE_AUTO",
    "enumOptionCodeMode": "SYSTEM_RULE_AUTO"
  },
  "duplicateOptions": {
    "categoryDuplicatePolicy": "OVERWRITE_EXISTING",
    "attributeDuplicatePolicy": "KEEP_EXISTING",
    "enumOptionDuplicatePolicy": "FAIL_ON_DUPLICATE"
  }
}
```

说明：

- dry-run 阶段即确定导入配置，并固化到 importSession 中。
- 正式导入阶段不建议允许再次改动这些配置，避免前后不一致。

### 7.2 dry-run 预览详情接口

- 方法：GET
- 路径：/api/meta/imports/workbook/sessions/{importSessionId}

用途：

- 前端重新打开预览页时拉取完整解析结果
- 支持分页查看解析后的分类、属性、枚举值清单

### 7.3 正式导入接口

- 方法：POST
- 路径：/api/meta/imports/workbook/import
- Content-Type：application/json

用途：

- 基于 importSessionId 发起正式导入
- 创建异步导入任务
- 立即返回 jobId 和初始状态

请求体建议：

```json
{
  "importSessionId": "c1bb5c02-2b03-4f34-94f3-f63f8cf6835e",
  "operator": "admin",
  "atomic": true,
  "overwriteMode": "UPSERT"
}
```

### 7.4 导入任务状态接口

- 方法：GET
- 路径：/api/meta/imports/workbook/jobs/{jobId}

用途：

- 返回当前任务真实进度快照
- 返回各阶段累计执行结果
- 返回最近一批关键日志摘要

### 7.5 导入任务日志接口

- 方法：GET
- 路径：/api/meta/imports/workbook/jobs/{jobId}/logs

建议查询参数：

- cursor
- limit
- level
- stage
- sheetName
- rowNumber

用途：

- 让前端按游标增量拉取操作日志
- 精确展示每一阶段、每一行、每一个实体的处理记录

### 7.6 导入任务实时流接口

必须提供 SSE：

- 方法：GET
- 路径：/api/meta/imports/workbook/jobs/{jobId}/stream
- Content-Type：text/event-stream

用途：

- 实时推送进度更新
- 实时推送日志事件
- 避免前端轮询延迟过大
- 保证前端在长时导入过程中实时拿到错误与阶段状态

说明：

- jobs/{jobId} 和 jobs/{jobId}/logs 用于恢复现场、补拉历史和断线重连。
- SSE 是本期必需能力，不是可选优化项。

---

## 8. dry-run 响应模型草案

dry-run 响应必须不仅有 summary，还要把“解析后的标准化预览数据”返回前端。

```json
{
  "importSessionId": "c1bb5c02-2b03-4f34-94f3-f63f8cf6835e",
  "template": {
    "recognized": true,
    "templateVersion": "v1",
    "sheetNames": ["分类层级", "属性定义", "枚举值定义", "代码清单"]
  },
  "summary": {
    "categoryRowCount": 12,
    "attributeRowCount": 38,
    "enumRowCount": 126,
    "errorCount": 3,
    "warningCount": 5,
    "canImport": false
  },
  "changeSummary": {
    "categories": { "create": 8, "update": 2, "skip": 2, "conflict": 0 },
    "attributes": { "create": 20, "update": 10, "skip": 8, "conflict": 0 },
    "enumOptions": { "create": 96, "update": 20, "skip": 10, "conflict": 0 }
  },
  "resolvedImportOptions": {
    "codingOptions": {
      "categoryCodeMode": "EXCEL_MANUAL",
      "attributeCodeMode": "SYSTEM_RULE_AUTO",
      "enumOptionCodeMode": "SYSTEM_RULE_AUTO"
    },
    "duplicateOptions": {
      "categoryDuplicatePolicy": "OVERWRITE_EXISTING",
      "attributeDuplicatePolicy": "KEEP_EXISTING",
      "enumOptionDuplicatePolicy": "FAIL_ON_DUPLICATE"
    }
  },
  "preview": {
    "categories": [
      {
        "sheetName": "分类层级",
        "rowNumber": 4,
        "businessDomain": "MATERIAL",
        "excelReferenceCode": "A01_01_001_01",
        "categoryCode": "A01_01_001_01",
        "categoryPath": "/A01/A01_01/A01_01_001/A01_01_001_01",
        "resolvedFinalCode": "A01_01_001_01",
        "resolvedFinalPath": "/A01/A01_01/A01_01_001/A01_01_001_01",
        "codeMode": "EXCEL_MANUAL",
        "categoryName": "连续单频激光器",
        "parentPath": "/A01/A01_01/A01_01_001",
        "parentCode": "A01_01_001",
        "resolvedAction": "CREATE",
        "issues": []
      }
    ],
    "attributes": [
      {
        "sheetName": "属性定义",
        "rowNumber": 3,
        "categoryCode": "A01_01_001_01",
        "excelReferenceCode": "ATT_POWER",
        "attributeKey": "ATT_POWER",
        "resolvedFinalCode": "ATTR-A01_01_001_01-001",
        "codeMode": "SYSTEM_RULE_AUTO",
        "attributeName": "功率",
        "attributeField": "power",
        "dataType": "number",
        "resolvedAction": "CREATE",
        "issues": []
      }
    ],
    "enumOptions": []
  },
  "issues": [
    {
      "level": "ERROR",
      "sheetName": "分类层级",
      "rowNumber": 8,
      "columnName": "Category_Path",
      "errorCode": "CATEGORY_PARENT_NOT_FOUND",
      "message": "父级路径未在当前批次上方出现",
      "rawValue": "/A01/A01_02/A01_02_001",
      "expectedRule": "父节点必须先于子节点出现"
    }
  ]
}
```

关键要求：

- preview 中必须返回前端可直接渲染的数据，而不是只给摘要。
- preview 中每条记录都应带 rowNumber、resolvedAction、issues。
- preview 中启用 auto 的实体必须返回 excelReferenceCode 与 resolvedFinalCode 的映射。
- 前端不能自己二次推导 parentPath、resolvedAction、是否可导入。

说明：

- dry-run 阶段的 auto 编码只用于预览，不做最终流水号锁定。
- dry-run 返回的 auto 编码结果应标记为 preview code，而不是最终保留承诺。

---

## 9. 正式导入接口响应模型草案

正式导入接口不应同步执行完整导入，而应启动异步任务并返回 jobId。

```json
{
  "jobId": "79d7ef51-3048-4d18-9e56-76d8bb3e5eb2",
  "importSessionId": "c1bb5c02-2b03-4f34-94f3-f63f8cf6835e",
  "status": "QUEUED",
  "atomic": true,
  "createdAt": "2026-03-31T10:22:11+08:00"
}
```

说明：

- 正式结果统一通过任务状态接口和日志接口获取。
- 前端发起 import 后应立即跳转到“执行中”视图，而不是等待长连接 HTTP 返回。

---

## 10. 真实进度与操作日志设计

### 10.1 为什么必须单独设计

导入场景的核心问题不是“能不能落库”，而是“前端是否能在执行过程中拿到足够清晰的信息”。

因此，新接口必须把以下两类信息拆开建模：

- 进度快照：告诉前端当前执行到了哪一步
- 操作日志：告诉前端每一步具体做了什么、哪一行失败了、为什么失败

### 10.2 任务状态枚举建议

- QUEUED
- PREPARING
- IMPORTING_CATEGORIES
- IMPORTING_ATTRIBUTES
- IMPORTING_ENUM_OPTIONS
- FINALIZING
- COMPLETED
- FAILED
- CANCELED

### 10.3 任务状态接口返回模型草案

```json
{
  "jobId": "79d7ef51-3048-4d18-9e56-76d8bb3e5eb2",
  "importSessionId": "c1bb5c02-2b03-4f34-94f3-f63f8cf6835e",
  "status": "IMPORTING_ATTRIBUTES",
  "currentStage": "ATTRIBUTES",
  "overallPercent": 64,
  "stagePercent": 42,
  "startedAt": "2026-03-31T10:22:13+08:00",
  "updatedAt": "2026-03-31T10:22:27+08:00",
  "progress": {
    "categories": {
      "total": 12,
      "processed": 12,
      "created": 8,
      "updated": 2,
      "skipped": 2,
      "failed": 0
    },
    "attributes": {
      "total": 38,
      "processed": 16,
      "created": 10,
      "updated": 4,
      "skipped": 2,
      "failed": 0
    },
    "enumOptions": {
      "total": 126,
      "processed": 0,
      "created": 0,
      "updated": 0,
      "skipped": 0,
      "failed": 0
    }
  },
  "latestLogCursor": "184",
  "latestLogs": []
}
```

### 10.4 日志事件模型草案

```json
{
  "cursor": "184",
  "sequence": 184,
  "timestamp": "2026-03-31T10:22:27.318+08:00",
  "level": "INFO",
  "stage": "ATTRIBUTES",
  "eventType": "ROW_PROCESSED",
  "sheetName": "属性定义",
  "rowNumber": 12,
  "entityType": "ATTRIBUTE",
  "entityKey": "A01_01_001_01/ATT_POWER",
  "action": "CREATE",
  "code": "ATTRIBUTE_CREATED",
  "message": "属性创建成功",
  "details": {
    "categoryCode": "A01_01_001_01",
    "attributeKey": "ATT_POWER"
  }
}
```

### 10.5 日志颗粒度要求

至少要覆盖：

- 任务开始
- 模板识别完成
- 分类阶段开始/结束
- 属性阶段开始/结束
- 枚举值阶段开始/结束
- 每条业务行的处理成功/跳过/失败
- 事务回滚
- 任务完成
- 任务失败

### 10.6 前端接入要求

前端执行导入时至少需要消费两类接口：

1. jobs/{jobId}：刷新进度条、阶段状态、累计结果
2. jobs/{jobId}/logs：展示历史日志与断线补拉
3. jobs/{jobId}/stream：展示实时日志与实时进度

结论：

- 不能只返回最终 summary。
- 不能只返回异常 message。
- 不能让前端自己猜当前卡在哪个阶段。

### 10.7 SSE 事件模型草案

推荐事件类型：

- progress
- log
- stage-changed
- completed
- failed
- heartbeat

progress 事件示例：

```json
{
  "event": "progress",
  "jobId": "79d7ef51-3048-4d18-9e56-76d8bb3e5eb2",
  "status": "IMPORTING_ATTRIBUTES",
  "overallPercent": 64,
  "stagePercent": 42,
  "currentStage": "ATTRIBUTES"
}
```

log 事件示例：

```json
{
  "event": "log",
  "jobId": "79d7ef51-3048-4d18-9e56-76d8bb3e5eb2",
  "cursor": "184",
  "level": "ERROR",
  "stage": "ENUM_OPTIONS",
  "sheetName": "枚举值定义",
  "rowNumber": 19,
  "code": "ENUM_OPTION_DUPLICATE",
  "message": "枚举值编码已存在，且当前策略为 FAIL_ON_DUPLICATE"
}
```

---

## 11. 校验规则设计

### 11.1 字段级校验

分类层级：

- businessDomain 必填，且必须在模板允许值中
- categoryCode 必填，且不能包含 /
- categoryPath 必填，且必须以 / 开头
- categoryPath 不能以 / 结尾
- categoryPath 不能包含 .
- categoryPath 不能包含 //
- categoryPath 最后一段必须等于 categoryCode
- categoryName 必填

属性定义：

- categoryCode、attributeKey、attributeName、attributeField、dataType 为核心必填
- dataType 必须属于模板字典：string、number、bool、enum、multi_enum、date
- required、unique、searchable、hidden、readOnly 只接受 Y/N

枚举值定义：

- categoryCode、attributeKey、optionCode、optionName 必填
- displayLabel 可选

### 11.2 类型驱动校验

- dataType=number 时，允许使用 unit、defaultValue、minValue、maxValue、step、precision
- dataType=bool 时，允许使用 trueLabel、falseLabel
- dataType 非 number 时，O-R 推荐为空；若非空则报类型不匹配警告或错误
- dataType 非 bool 时，S-T 推荐为空；若非空则报类型不匹配警告或错误
- dataType=enum 或 multi_enum 时，应允许并建议在枚举值定义 sheet 中存在关联选项

### 11.3 sheet 内校验

分类层级：

- businessDomain + categoryCode 批次内唯一
- businessDomain + categoryPath 批次内唯一
- 父级路径必须先于子级出现

属性定义：

- categoryCode + attributeKey 批次内唯一
- categoryCode + attributeField 批次内建议唯一

枚举值定义：

- categoryCode + attributeKey + optionCode 批次内唯一
- 同一属性下 optionName 建议唯一

### 11.4 跨 sheet 校验

- 属性定义.categoryCode 必须能在分类层级中找到，或数据库中已存在
- 若属性定义.categoryName 有值，建议与分类名称一致
- 枚举值定义.categoryCode + attributeKey 必须能在属性定义中找到，或数据库中已存在
- 枚举值定义只能绑定到 enum 或 multi_enum 属性

### 11.5 存量数据校验

按推荐幂等键判定 create、update、skip、conflict：

- 分类：businessDomain + categoryCode
- 属性：categoryCode + attributeKey
- 枚举值：categoryCode + attributeKey + optionCode

### 11.6 重复数据策略执行规则

当命中幂等键已存在时，必须按用户在导入配置中选择的策略执行：

- OVERWRITE_EXISTING
  - dry-run：resolvedAction = UPDATE
  - import：更新已有数据
- KEEP_EXISTING
  - dry-run：resolvedAction = KEEP_EXISTING
  - import：跳过，不改动已有数据
- FAIL_ON_DUPLICATE
  - dry-run：resolvedAction = CONFLICT，并返回 ERROR
  - import：若仍允许提交，则命中时任务失败或阶段失败

---

## 12. 执行阶段设计

### 12.1 执行顺序

1. 分类阶段
2. 属性阶段
3. 枚举值阶段

### 12.2 原子性建议

首选：整本工作簿原子提交。

原因：

- 三个阶段天然有依赖关系。
- 若分类成功、属性失败，会留下半成品数据。
- 当前元数据导入更偏管理动作，但单次执行可能较长，因此更适合“异步任务 + 原子提交”组合。

如未来因批量规模过大无法整批单事务，则次优方案为：

- 阶段原子
- 幂等提交
- 失败后允许按 session 重试

### 12.3 执行模式建议

overwriteMode 首期可支持：

- UPSERT：按幂等键存在即更新，不存在则创建
- INSERT_ONLY：存在则报冲突

不建议首期支持 DELETE_MISSING 或全量覆盖式清空。

### 12.4 自动编码与流水号预留

这是本期必须保证的关键能力。

当用户选择 SYSTEM_RULE_AUTO 时：

- 正式导入阶段必须在真正写入业务数据前完成流水号预留。
- 不能在逐行写入时临时取号，否则会与其他用户同时创建产生竞争，占用不可预测。

推荐机制：

1. 在任务 PREPARING 阶段扫描本批次所有需要 auto 编码的对象。
2. 按规则编码 scope 分组统计所需数量。
3. 对每个 scope 一次性预留连续号段。
4. 将号段映射回本批次实体，形成最终确定编码。
5. 后续分类、属性、枚举值阶段只消费已预留号段，不再实时争抢序列。

必须说明：

- dry-run 只做模拟预览，不做真实预留。
- 真实预留发生在 import 任务开始后。
- 一旦预留成功，即使任务后续失败，也不建议回收流水号；允许出现号码空洞，以换取并发安全和唯一性稳定。

日志要求：

- 预留阶段必须输出 sequence reservation 日志事件。
- 前端必须能看到每个 rule scope 预留了多少号段、起止范围是什么。

日志事件示例：

```json
{
  "level": "INFO",
  "stage": "PREPARING",
  "eventType": "SEQUENCE_RESERVED",
  "code": "CODE_SEQUENCE_RESERVED",
  "message": "属性编码规则已预留号段",
  "details": {
    "ruleCode": "ATTRIBUTE",
    "scopeKey": "CATEGORY_CODE=A01_01_001_01",
    "reservedCount": 12,
    "startValue": 57,
    "endValue": 68
  }
}
```

---

## 13. 后端实现建议

### 13.1 推荐新增服务编排层

建议新增统一工作簿导入编排服务，例如：

- MetaWorkbookImportService

职责：

- 解析工作簿
- 识别模板版本
- 组装标准化中间模型
- 生成前端预览模型
- 执行 dry-run 校验
- 生成 importSession
- 发起正式导入 job
- 汇总进度快照与日志事件
- 协调 auto 编码场景下的流水号预留

### 13.2 推荐子模块拆分

- WorkbookTemplateParser：模板识别与 sheet 解析
- WorkbookPreviewAssembler：前端预览模型组装
- WorkbookImportValidator：统一校验入口
- CategoryWorkbookImportStage：分类阶段
- AttributeWorkbookImportStage：属性阶段
- EnumWorkbookImportStage：枚举值阶段
- WorkbookImportSessionStore：dry-run 会话存储
- WorkbookImportJobService：正式导入任务管理
- WorkbookImportLogStore：进度日志与事件持久化
- WorkbookCodeReservationService：批量流水号预留与编码分配

### 13.3 与现有领域服务的关系

虽然外部接口是全新的，但内部领域规则仍建议复用现有核心能力：

- 分类创建尽量复用 MetaCategoryCrudService 的落库语义
- 属性与 LOV 创建尽量复用当前属性管理与编码规则能力
- 统一编码生成继续走 MetaCodeRuleService 与 MetaCodeRuleSetService

原则：

- 新接口外观完全独立
- 内部领域规则尽量复用
- 不把旧导入接口本身当成可复用边界

### 13.4 会话与任务中的配置固化

dry-run 阶段确定的以下配置必须写入 importSession：

- codingOptions
- duplicateOptions

正式导入任务启动后，应从 session 读取固化配置，不允许前端在 import 阶段再次变更。

---

## 14. 前端交互建议

前端建议采用三段式交互：

1. 上传并 dry-run
2. 查看解析预览与校验结果
3. 发起正式导入并进入实时执行页

推荐页面能力：

- 模板识别结果展示
- 编码模式配置面板
- 重复数据策略配置面板
- 分类、属性、枚举值三类解析预览
- 分类树预览
- create/update/skip/conflict 统计
- 错误列表与警告列表
- 按 sheet、行号、错误码过滤
- 导入执行页进度条
- 阶段状态条
- 实时日志面板
- 编码映射预览面板（Excel 编码 -> 最终系统编码）
- 失败行快速定位与展开详情

---

## 15. 首期范围与非目标

### 15.1 首期范围

- 识别当前模板固定 sheet
- 支持统一 dry-run 接口
- 支持统一正式导入接口
- 支持异步导入任务
- 支持真实进度快照
- 支持高颗粒度操作日志
- 支持 manual/auto 编码配置
- 支持 auto 编码的批量流水号预留
- 支持重复数据处理策略配置
- 支持 SSE 实时进度与日志推送
- 支持分类、属性、枚举值三级导入
- 支持新增、更新、跳过、冲突预览

### 15.2 非目标

- 不兼容旧分类导入、旧属性导入、旧 UNSPSC 导入接口
- 不支持用户自定义 sheet 名称与列结构
- 不支持通过模板直接删除数据库中缺失数据
- 不支持把模板中的公式结果作为服务端输入事实
- 不支持把代码清单 sheet 作为业务字典写回数据库

---

## 16. 设计结论

本模板对应的导入能力，需要设计为一套全新的“工作簿级导入接口体系”，而不是在旧导入接口上做兼容扩展。

最关键的设计判断如下：

1. 模板真实输入只有三张业务 sheet，且分类层级只应信任 A-D 四列。
2. 模板中的公式、预览、隐藏列、代码清单都只能作为用户填写辅助，不能作为服务端事实来源。
3. 导入主模型天然是 分类树 -> 属性 -> 枚举值 的三级结构，必须按依赖顺序校验和提交。
4. 新接口必须支持按实体类型配置 manual/auto 编码模式，并在 auto 场景下返回 Excel 引用编码与最终系统编码的映射。
5. auto 编码在正式导入前必须完成流水号预留，不能逐行临时抢号。
6. 命中重复数据时，必须按用户显式选择的覆盖、保留原样或失败策略执行，并在 dry-run 中回显最终动作。
7. 新接口必须同时提供 dry-run 校验、解析后预览返回、正式导入、进度快照、操作日志、SSE 实时流六类能力。
8. 正式导入必须采用异步任务模型，避免前端在长时导入过程中丢失阶段状态和错误信息。
