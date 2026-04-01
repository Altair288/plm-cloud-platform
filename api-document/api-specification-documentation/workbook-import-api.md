# 元数据工作簿导入 API 文档（plm-attribute-service）

更新时间：2026-03-31

> 本文覆盖“元数据工作簿导入”正式接口，供前端页面联调与接入使用。
>
> 说明：
>
> - 本文描述的是全新的工作簿级导入接口体系，不兼容旧版分类导入、属性导入、UNSPSC 导入接口。
> - 导入对象包含三类实体：分类层级、属性定义、枚举值定义。
> - 推荐接入顺序：dry-run -> 查看预览/修正数据 -> start import -> SSE/轮询状态 -> 增量拉取日志。

---

## 1. 设计目标

- 支持通过统一 Excel 工作簿一次性导入分类、属性、枚举值。
- 支持 dry-run 预校验，并向前端返回标准化预览结果。
- 支持正式导入异步执行，并提供任务状态、日志分页、SSE 实时事件。
- 支持按实体分别配置编码模式与重复数据处理策略。
- 支持前端在导入过程中实时拿到进度和行级错误，而不是只拿到最终 summary。

---

## 2. 功能列表（是否实现）

| 功能 | 相关接口 | 是否实现 | 备注 |
|---|---|---:|---|
| 上传工作簿并执行 dry-run | POST /api/meta/imports/workbook/dry-run | ✅ | multipart/form-data；返回 importSessionId 与完整预览 |
| 按 importSessionId 重新获取 dry-run 结果 | GET /api/meta/imports/workbook/sessions/{importSessionId} | ✅ | 返回与 dry-run 相同的数据结构 |
| 启动正式导入任务 | POST /api/meta/imports/workbook/import | ✅ | 创建异步任务并返回 jobId |
| 查询任务状态快照 | GET /api/meta/imports/workbook/jobs/{jobId} | ✅ | 返回进度、阶段、最近日志 |
| 分页/增量查询任务日志 | GET /api/meta/imports/workbook/jobs/{jobId}/logs | ✅ | 支持 cursor、level、stage、sheetName、rowNumber 过滤 |
| SSE 订阅任务实时事件 | GET /api/meta/imports/workbook/jobs/{jobId}/stream | ✅ | 返回 progress/stage-changed/log/completed/failed 事件 |

---

## 3. 调用顺序

### 3.1 推荐时序

1. 前端上传 Excel 与导入配置到 dry-run 接口。
2. 前端根据 `summary`、`changeSummary`、`preview`、`issues` 展示预览结果。
3. 若 `summary.canImport=true`，前端调用 start import 接口。
4. 前端获取 `jobId` 后，立即建立 SSE 订阅，同时按需轮询 job status。
5. 若 SSE 中断，前端使用 logs 接口基于 `nextCursor` 增量补拉日志。
6. 当 job status 进入 `COMPLETED` 或 `FAILED` 后，前端停止轮询，并保留日志查看入口。

### 3.2 标识符语义

| 标识 | 来源接口 | 作用 | 生命周期 |
|---|---|---|---|
| importSessionId | dry-run | 对应一次预校验会话，正式导入时作为输入 | 内存态，默认约 24 小时；服务重启会失效 |
| jobId | start import | 对应一次正式导入任务 | 内存态，终态默认约保留 1 小时；服务重启会失效 |

---

## 4. 通用错误响应

参数错误统一返回 400：

```json
{
  "timestamp": "2026-03-31T10:00:00+08:00",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_ARGUMENT",
  "message": "invalid workbook import options format: Unexpected character ('i' (code 105))"
}
```

未知异常统一返回 500：`code = INTERNAL_ERROR`。

---

## 5. 工作簿约束

### 5.1 必需 Sheet

| Sheet 名称 | 用途 | 是否必需 |
|---|---|---:|
| 分类层级 | 分类树输入 | 是 |
| 属性定义 | 属性输入 | 是 |
| 枚举值定义 | 枚举值输入 | 是 |

任一必需 Sheet 缺失时，dry-run 返回 400。

### 5.2 有效数据识别

- 分类层级按 A-D 输入列识别有效行。
- 属性定义按 A-T 输入列识别有效行。
- 枚举值定义按 A-E 输入列识别有效行。
- 模板预留空白行会被忽略，不进入预览与导入。

---

## 6. 导入配置模型

dry-run 的 `options` 使用 `WorkbookImportDryRunOptionsDto`，结构如下：

```json
{
  "codingOptions": {
    "categoryCodeMode": "EXCEL_MANUAL",
    "attributeCodeMode": "SYSTEM_RULE_AUTO",
    "enumOptionCodeMode": "SYSTEM_RULE_AUTO"
  },
  "duplicateOptions": {
    "categoryDuplicatePolicy": "FAIL_ON_DUPLICATE",
    "attributeDuplicatePolicy": "KEEP_EXISTING",
    "enumOptionDuplicatePolicy": "OVERWRITE_EXISTING"
  }
}
```

### 6.1 编码模式

| 字段 | 可选值 | 默认值 | 说明 |
|---|---|---|---|
| categoryCodeMode | EXCEL_MANUAL / SYSTEM_RULE_AUTO | EXCEL_MANUAL | 分类编码模式 |
| attributeCodeMode | EXCEL_MANUAL / SYSTEM_RULE_AUTO | EXCEL_MANUAL | 属性编码模式 |
| enumOptionCodeMode | EXCEL_MANUAL / SYSTEM_RULE_AUTO | EXCEL_MANUAL | 枚举值编码模式 |

### 6.2 重复处理策略

| 字段 | 可选值 | 默认值 | 说明 |
|---|---|---|---|
| categoryDuplicatePolicy | OVERWRITE_EXISTING / KEEP_EXISTING / FAIL_ON_DUPLICATE | FAIL_ON_DUPLICATE | 分类重复处理策略 |
| attributeDuplicatePolicy | OVERWRITE_EXISTING / KEEP_EXISTING / FAIL_ON_DUPLICATE | FAIL_ON_DUPLICATE | 属性重复处理策略 |
| enumOptionDuplicatePolicy | OVERWRITE_EXISTING / KEEP_EXISTING / FAIL_ON_DUPLICATE | FAIL_ON_DUPLICATE | 枚举值重复处理策略 |

---

## 7. Dry-Run 预校验

### 7.1 上传工作簿并预校验

- 方法：POST
- 路径：/api/meta/imports/workbook/dry-run
- Content-Type：multipart/form-data

### 7.2 请求参数

Query 参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| operator | string | 否 | - | 操作人，用于会话记录 |

Multipart Part

| Part 名称 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| file | file | 是 | 上传的 Excel 工作簿 |
| options | json string | 是 | 导入配置 JSON，推荐以 application/json 形式上传 |

### 7.3 curl 示例

```bash
curl -X POST "http://localhost:8080/api/meta/imports/workbook/dry-run?operator=alice" \
  -H "Content-Type: multipart/form-data" \
  -F "file=@./metadata-import.xlsx" \
  -F 'options={"codingOptions":{"categoryCodeMode":"EXCEL_MANUAL","attributeCodeMode":"SYSTEM_RULE_AUTO","enumOptionCodeMode":"SYSTEM_RULE_AUTO"},"duplicateOptions":{"categoryDuplicatePolicy":"FAIL_ON_DUPLICATE","attributeDuplicatePolicy":"FAIL_ON_DUPLICATE","enumOptionDuplicatePolicy":"FAIL_ON_DUPLICATE"}};type=application/json'
```

### 7.4 响应体结构

响应：`WorkbookImportDryRunResponseDto`

顶层字段

| 字段 | 类型 | 说明 |
|---|---|---|
| importSessionId | string | 预校验会话 ID，正式导入必填 |
| template | object | 模板识别结果 |
| summary | object | 行数与错误/警告统计 |
| changeSummary | object | 分类/属性/枚举值的 create/update/skip/conflict 汇总 |
| resolvedImportOptions | object | 后端标准化后的导入配置 |
| preview | object | 三类实体的预览明细 |
| issues | array | 全局问题列表 |
| createdAt | string(date-time) | 创建时间 |

template 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| recognized | boolean | 是否识别到预期模板结构 |
| templateVersion | string | 预留字段；当前可能为空 |
| sheetNames | string[] | 识别到的工作簿 Sheet 名列表 |

summary 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| categoryRowCount | int | 分类有效行数 |
| attributeRowCount | int | 属性有效行数 |
| enumRowCount | int | 枚举值有效行数 |
| errorCount | int | 错误数 |
| warningCount | int | 警告数 |
| canImport | boolean | 是否允许正式导入 |

changeSummary 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| categories.create/update/skip/conflict | int | 分类动作统计 |
| attributes.create/update/skip/conflict | int | 属性动作统计 |
| enumOptions.create/update/skip/conflict | int | 枚举值动作统计 |

### 7.5 预览明细字段

CategoryPreviewItemDto

| 字段 | 类型 | 说明 |
|---|---|---|
| sheetName | string | 所在 Sheet |
| rowNumber | int | Excel 行号，从 1 开始 |
| businessDomain | string | 业务域 |
| excelReferenceCode | string | Excel 中填写的分类编码 |
| categoryCode | string | Excel 中的原始分类编码展示字段 |
| categoryPath | string | Excel 中填写的路径 |
| resolvedFinalCode | string | 后端解析后的最终编码 |
| resolvedFinalPath | string | 后端解析后的最终路径 |
| codeMode | string | EXCEL_MANUAL 或 SYSTEM_RULE_AUTO |
| categoryName | string | 分类名称 |
| parentPath | string | 父路径 |
| parentCode | string | 父编码 |
| resolvedAction | string | CREATE / UPDATE / SKIP / CONFLICT |
| issues | array | 该行问题列表 |

AttributePreviewItemDto

| 字段 | 类型 | 说明 |
|---|---|---|
| sheetName | string | 所在 Sheet |
| rowNumber | int | Excel 行号 |
| businessDomain | string | 业务域 |
| categoryCode | string | 所属分类编码 |
| excelReferenceCode | string | Excel 中填写的属性编码 |
| attributeKey | string | Excel 中原始属性编码展示字段 |
| resolvedFinalCode | string | 最终属性编码 |
| codeMode | string | 编码模式 |
| attributeName | string | 属性名称 |
| attributeField | string | 属性字段名 |
| dataType | string | string/number/bool/enum/multi_enum/date |
| resolvedAction | string | CREATE / UPDATE / SKIP / CONFLICT |
| issues | array | 该行问题列表 |

EnumOptionPreviewItemDto

| 字段 | 类型 | 说明 |
|---|---|---|
| sheetName | string | 所在 Sheet |
| rowNumber | int | Excel 行号 |
| categoryCode | string | 所属分类编码 |
| attributeKey | string | 所属属性编码 |
| excelReferenceCode | string | Excel 中填写的枚举值编码 |
| optionCode | string | Excel 中原始枚举值编码展示字段 |
| resolvedFinalCode | string | 最终枚举值编码 |
| codeMode | string | 编码模式 |
| optionName | string | 选项名称 |
| displayLabel | string | 显示标签 |
| resolvedAction | string | CREATE / UPDATE / SKIP / CONFLICT |
| issues | array | 该行问题列表 |

IssueDto

| 字段 | 类型 | 说明 |
|---|---|---|
| level | string | ERROR / WARNING |
| sheetName | string | 所在 Sheet |
| rowNumber | int | 行号 |
| columnName | string | 列名 |
| errorCode | string | 错误码 |
| message | string | 错误说明 |
| rawValue | string | 原始值 |
| expectedRule | string | 期望规则/修复建议 |

### 7.6 响应示例

```json
{
  "importSessionId": "9d91d94f-5b4d-46d2-a8bb-c33d6aa8d73f",
  "template": {
    "recognized": true,
    "templateVersion": null,
    "sheetNames": ["分类层级", "属性定义", "枚举值定义"]
  },
  "summary": {
    "categoryRowCount": 2,
    "attributeRowCount": 1,
    "enumRowCount": 2,
    "errorCount": 0,
    "warningCount": 0,
    "canImport": true
  },
  "changeSummary": {
    "categories": { "create": 2, "update": 0, "skip": 0, "conflict": 0 },
    "attributes": { "create": 1, "update": 0, "skip": 0, "conflict": 0 },
    "enumOptions": { "create": 2, "update": 0, "skip": 0, "conflict": 0 }
  },
  "resolvedImportOptions": {
    "codingOptions": {
      "categoryCodeMode": "SYSTEM_RULE_AUTO",
      "attributeCodeMode": "SYSTEM_RULE_AUTO",
      "enumOptionCodeMode": "SYSTEM_RULE_AUTO"
    },
    "duplicateOptions": {
      "categoryDuplicatePolicy": "FAIL_ON_DUPLICATE",
      "attributeDuplicatePolicy": "FAIL_ON_DUPLICATE",
      "enumOptionDuplicatePolicy": "FAIL_ON_DUPLICATE"
    }
  },
  "preview": {
    "categories": [
      {
        "sheetName": "分类层级",
        "rowNumber": 2,
        "businessDomain": "MATERIAL",
        "excelReferenceCode": "ROOT-001",
        "categoryCode": "ROOT-001",
        "categoryPath": "/ROOT-001",
        "resolvedFinalCode": "M-CAT-000001",
        "resolvedFinalPath": "/M-CAT-000001",
        "codeMode": "SYSTEM_RULE_AUTO",
        "categoryName": "Root 001",
        "parentPath": null,
        "parentCode": null,
        "resolvedAction": "CREATE",
        "issues": []
      }
    ],
    "attributes": [
      {
        "sheetName": "属性定义",
        "rowNumber": 2,
        "businessDomain": "MATERIAL",
        "categoryCode": "M-CAT-000001",
        "excelReferenceCode": "ATTR-REF-001",
        "attributeKey": "ATTR-REF-001",
        "resolvedFinalCode": "M-ATTR-000001",
        "codeMode": "SYSTEM_RULE_AUTO",
        "attributeName": "Color 001",
        "attributeField": "color001",
        "dataType": "enum",
        "resolvedAction": "CREATE",
        "issues": []
      }
    ],
    "enumOptions": [
      {
        "sheetName": "枚举值定义",
        "rowNumber": 2,
        "categoryCode": "M-CAT-000001",
        "attributeKey": "M-ATTR-000001",
        "excelReferenceCode": "ENUM-REF-A-001",
        "optionCode": "ENUM-REF-A-001",
        "resolvedFinalCode": "M-LOV-000001",
        "codeMode": "SYSTEM_RULE_AUTO",
        "optionName": "Red 001",
        "displayLabel": "Red 001",
        "resolvedAction": "CREATE",
        "issues": []
      }
    ]
  },
  "issues": [],
  "createdAt": "2026-03-31T14:00:00Z"
}
```

### 7.7 典型错误场景

| 场景 | HTTP 状态 | code | message 示例 |
|---|---:|---|---|
| file 缺失 | 400 | INVALID_ARGUMENT | file is required |
| options 不是合法 JSON | 400 | INVALID_ARGUMENT | invalid workbook import options format: ... |
| 必需 Sheet 缺失 | 400 | INVALID_ARGUMENT | required sheet missing: 分类层级 |
| code mode 非法 | 400 | INVALID_ARGUMENT | unsupported code mode: AUTO |
| duplicate policy 非法 | 400 | INVALID_ARGUMENT | unsupported duplicate policy: SKIP |
| 工作簿读取失败 | 400 | INVALID_ARGUMENT | failed to read workbook |

---

## 8. 获取 Dry-Run 会话

### 8.1 查询会话结果

- 方法：GET
- 路径：/api/meta/imports/workbook/sessions/{importSessionId}

Path 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| importSessionId | string | 是 | dry-run 返回的会话 ID |

响应：与 dry-run 完全同结构。

典型错误：

- 会话不存在：400，`code = INVALID_ARGUMENT`，`message = workbook import session not found: importSessionId=...`

---

## 9. 启动正式导入

### 9.1 创建导入任务

- 方法：POST
- 路径：/api/meta/imports/workbook/import
- Content-Type：application/json

请求体：`WorkbookImportStartRequestDto`

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| importSessionId | string | 是 | - | dry-run 返回的会话 ID |
| operator | string | 否 | - | 操作人 |
| atomic | boolean | 否 | true | 是否原子执行 |
| overwriteMode | string | 否 | - | 预留字段；当前后端未使用，不建议前端依赖 |

请求示例

```json
{
  "importSessionId": "9d91d94f-5b4d-46d2-a8bb-c33d6aa8d73f",
  "operator": "alice",
  "atomic": true,
  "overwriteMode": null
}
```

响应：`WorkbookImportStartResponseDto`

| 字段 | 类型 | 说明 |
|---|---|---|
| jobId | string | 异步任务 ID |
| importSessionId | string | 对应的会话 ID |
| status | string | 初始状态，通常为 QUEUED |
| atomic | boolean | 是否原子执行 |
| createdAt | string(date-time) | 任务创建时间 |

响应示例

```json
{
  "jobId": "fc9a8c10-e7e0-4f7f-a2ae-f3184e4081e8",
  "importSessionId": "9d91d94f-5b4d-46d2-a8bb-c33d6aa8d73f",
  "status": "QUEUED",
  "atomic": true,
  "createdAt": "2026-03-31T14:01:10Z"
}
```

---

## 10. 查询任务状态

### 10.1 获取任务快照

- 方法：GET
- 路径：/api/meta/imports/workbook/jobs/{jobId}

Path 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| jobId | string | 是 | 导入任务 ID |

响应：`WorkbookImportJobStatusDto`

顶层字段

| 字段 | 类型 | 说明 |
|---|---|---|
| jobId | string | 任务 ID |
| importSessionId | string | 来源会话 |
| status | string | 任务状态 |
| currentStage | string | 当前阶段 |
| overallPercent | int | 整体进度百分比 |
| stagePercent | int | 当前阶段进度百分比 |
| startedAt | string(date-time) | 任务开始时间 |
| updatedAt | string(date-time) | 最后更新时间 |
| progress | object | 三类实体的进度计数 |
| latestLogCursor | string | 最近一条日志 cursor |
| latestLogs | array | 最近 20 条日志快照 |

status 取值

| 值 | 说明 |
|---|---|
| QUEUED | 已入队 |
| PREPARING | 准备中 |
| IMPORTING_CATEGORIES | 正在导入分类 |
| IMPORTING_ATTRIBUTES | 正在导入属性 |
| IMPORTING_ENUM_OPTIONS | 正在导入枚举值 |
| FINALIZING | 收尾阶段 |
| COMPLETED | 任务完成 |
| FAILED | 任务失败 |

currentStage 取值

| 值 | 说明 |
|---|---|
| PREPARING | 准备阶段 |
| CATEGORIES | 分类阶段 |
| ATTRIBUTES | 属性阶段 |
| ENUM_OPTIONS | 枚举值阶段 |
| FINALIZING | 收尾阶段 |

progress.EntityProgressDto 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| total | int | 总数 |
| processed | int | 已处理数 |
| created | int | 已创建数 |
| updated | int | 已更新数 |
| skipped | int | 已跳过数 |
| failed | int | 失败数 |

响应示例

```json
{
  "jobId": "fc9a8c10-e7e0-4f7f-a2ae-f3184e4081e8",
  "importSessionId": "9d91d94f-5b4d-46d2-a8bb-c33d6aa8d73f",
  "status": "COMPLETED",
  "currentStage": "FINALIZING",
  "overallPercent": 100,
  "stagePercent": 100,
  "startedAt": "2026-03-31T14:01:10Z",
  "updatedAt": "2026-03-31T14:01:14Z",
  "progress": {
    "categories": { "total": 2, "processed": 2, "created": 2, "updated": 0, "skipped": 0, "failed": 0 },
    "attributes": { "total": 1, "processed": 1, "created": 1, "updated": 0, "skipped": 0, "failed": 0 },
    "enumOptions": { "total": 2, "processed": 2, "created": 2, "updated": 0, "skipped": 0, "failed": 0 }
  },
  "latestLogCursor": "11",
  "latestLogs": []
}
```

前端注意：

- `status = COMPLETED` 不等于所有行都成功；当 `atomic=false` 时，任务可能整体完成但 `failed > 0`。
- 前端应同时关注 `progress.*.failed` 和日志明细。

---

## 11. 查询任务日志

### 11.1 日志分页 / 增量拉取

- 方法：GET
- 路径：/api/meta/imports/workbook/jobs/{jobId}/logs

Query 参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| cursor | string | 否 | - | 增量游标；取 `sequence > cursor` 的日志 |
| limit | int | 否 | 100 | 单次返回上限，最大 500 |
| level | string | 否 | - | 日志级别过滤 |
| stage | string | 否 | - | 阶段过滤 |
| sheetName | string | 否 | - | Sheet 名过滤 |
| rowNumber | int | 否 | - | 行号过滤 |

LogEvent 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| cursor | string | 游标，通常等于 sequence 的字符串 |
| sequence | long | 单任务内自增序号 |
| timestamp | string(date-time) | 时间 |
| level | string | INFO / WARN / ERROR 等 |
| stage | string | PREPARING / CATEGORIES / ATTRIBUTES / ENUM_OPTIONS / FINALIZING |
| eventType | string | 事件类型 |
| sheetName | string | 关联 Sheet |
| rowNumber | int | 关联行号 |
| entityType | string | CATEGORY / ATTRIBUTE / ENUM_OPTION 等 |
| entityKey | string | 实体唯一标识 |
| action | string | CREATE / UPDATE / SKIP / CONTINUE / ROLLBACK 等 |
| code | string | 业务事件码 |
| message | string | 日志消息 |
| details | object | 扩展上下文 |

响应：`WorkbookImportLogPageDto`

| 字段 | 类型 | 说明 |
|---|---|---|
| jobId | string | 任务 ID |
| nextCursor | string | 下一次增量拉取游标 |
| items | array | 当前页日志 |

响应示例

```json
{
  "jobId": "fc9a8c10-e7e0-4f7f-a2ae-f3184e4081e8",
  "nextCursor": "11",
  "items": [
    {
      "cursor": "9",
      "sequence": 9,
      "timestamp": "2026-03-31T14:01:12Z",
      "level": "INFO",
      "stage": "CATEGORIES",
      "eventType": "ROW_RESULT",
      "sheetName": "分类层级",
      "rowNumber": 2,
      "entityType": "CATEGORY",
      "entityKey": "MATERIAL::ROOT-001",
      "action": "CREATE",
      "code": "CATEGORY_IMPORTED",
      "message": "分类导入成功",
      "details": {
        "finalCode": "M-CAT-000001"
      }
    }
  ]
}
```

前端注意：

- `cursor` 不是页码，而是增量日志位置。
- `nextCursor` 应原样保存，下一次继续传回 `cursor`。
- 没有新日志时，`nextCursor` 可能保持不变。
- 若 `cursor` 非数字，接口返回 400。

---

## 12. SSE 实时事件流

### 12.1 订阅任务事件

- 方法：GET
- 路径：/api/meta/imports/workbook/jobs/{jobId}/stream
- Produces：text/event-stream

服务端当前行为：

- 订阅建立时立即推送一次 `progress` 事件。
- 连接默认超时约 30 分钟。
- 事件不会自动补发历史日志；断线后需要配合 logs 接口补拉。

### 12.2 事件名称

| 事件名 | 数据结构 | 说明 |
|---|---|---|
| progress | WorkbookImportJobStatusDto | 任务状态完整快照 |
| stage-changed | object | 阶段切换事件 |
| log | WorkbookImportLogEventDto | 单条日志事件 |
| completed | object | 任务完成事件 |
| failed | object | 任务失败事件 |

### 12.3 事件数据示例

progress

```text
event: progress
data: {"jobId":"fc9a8c10-e7e0-4f7f-a2ae-f3184e4081e8","status":"IMPORTING_ATTRIBUTES","currentStage":"ATTRIBUTES","overallPercent":66,"stagePercent":50}
```

stage-changed

```text
event: stage-changed
data: {"jobId":"fc9a8c10-e7e0-4f7f-a2ae-f3184e4081e8","status":"IMPORTING_ENUM_OPTIONS","currentStage":"ENUM_OPTIONS","updatedAt":"2026-03-31T14:01:13Z"}
```

log

```text
event: log
data: {"cursor":"10","sequence":10,"level":"INFO","stage":"ATTRIBUTES","code":"ATTRIBUTE_IMPORTED","message":"属性导入成功"}
```

completed

```text
event: completed
data: {"jobId":"fc9a8c10-e7e0-4f7f-a2ae-f3184e4081e8","status":"COMPLETED"}
```

failed

```text
event: failed
data: {"jobId":"fc9a8c10-e7e0-4f7f-a2ae-f3184e4081e8","status":"FAILED","message":"workbook import failed"}
```

---

## 13. 前端接入说明

### 13.1 multipart 提交要求

- `options` 必须作为单独 JSON part 提交，不能把 `codingOptions.xxx`、`duplicateOptions.xxx` 拆成普通表单字段。
- 推荐前端使用 `Blob([JSON.stringify(options)], { type: 'application/json' })` 或同等能力上传。
- `operator` 当前是 query 参数，不在 multipart body 里。

### 13.2 自动编码模式说明

- 当编码模式为 `SYSTEM_RULE_AUTO` 时，Excel 中的编码列只作为内部引用键。
- dry-run 返回的 `resolvedFinalCode` 只是预览结果，不保证与正式导入时的真实保留码完全一致。
- 正式导入阶段会再次按系统规则保留/生成编码，并记录对应日志事件，例如 `CATEGORY_CODE_SEQUENCE_RESERVED`。
- 因此前端页面中应明确区分“Excel 引用编码”和“预览最终编码”，避免把 dry-run 的自动码当作最终落库主键缓存。

### 13.3 任务结果判断

- 原子模式 `atomic=true`：任一关键错误可能导致整个任务失败。
- 非原子模式 `atomic=false`：单行失败可能只记日志与 failed 计数，整体任务仍返回 `COMPLETED`。
- 前端最终展示建议同时参考：
  - `status`
  - `progress.categories/attributes/enumOptions.failed`
  - `latestLogs`
  - logs 接口全量明细

### 13.4 生命周期与断线恢复

- `importSessionId` 和 `jobId` 都是内存态，不保证跨服务重启可恢复。
- SSE 断线后，前端应：
  1. 重新订阅 stream
  2. 用上次保存的 `nextCursor` 调用 logs 接口补齐中间日志

### 13.5 建议前端状态管理字段

建议页面至少持久化以下字段：

- `importSessionId`
- `jobId`
- `lastLogCursor`
- `summary`
- `changeSummary`
- `preview`
- `jobStatus`
- `latestLogs`

---

## 14. 实现入口

- Controller：plm-attribute-service/src/main/java/com/plm/attribute/version/controller/WorkbookImportController.java
- Dry-run：plm-attribute-service/src/main/java/com/plm/attribute/version/service/workbook/WorkbookImportDryRunService.java
- 正式导入：plm-attribute-service/src/main/java/com/plm/attribute/version/service/workbook/WorkbookImportExecutionService.java
- 运行时状态/SSE/日志：plm-attribute-service/src/main/java/com/plm/attribute/version/service/workbook/WorkbookImportRuntimeService.java
- DTO：plm-common/src/main/java/com/plm/common/api/dto/imports/workbook
