# 元数据工作簿导出前端对接文档

更新时间：2026-04-08

本文面向前端联调，描述当前已经落地的元数据工作簿导出接口如何接入。重点覆盖：

- 推荐调用链路
- schema 驱动的字段配置方式
- plan 预校验
- job 状态、日志、SSE 与下载

说明：

- 当前服务统一入口为 /api/meta/exports/workbook。
- 导出对象固定为三类：CATEGORY、ATTRIBUTE、ENUM_OPTION。
- 后端只接受稳定 fieldKey，不接受前端 mock 字段名、sourceLabel 或 targetHeader 作为绑定依据。
- 导出能力是下载型能力，不承担回导入契约。

---

## 1. 功能列表（是否实现）

| 功能 | 相关接口 | 是否实现 | 备注 |
|---|---|---:|---|
| 获取导出 schema | GET /api/meta/exports/workbook/schema | ✅ | 前端字段面板、默认列来源 |
| 导出计划预校验 | POST /api/meta/exports/workbook/plan | ✅ | 返回 normalizedRequest 与预计行数 |
| 创建导出作业 | POST /api/meta/exports/workbook/jobs | ✅ | 页面主入口 |
| 查询导出作业状态 | GET /api/meta/exports/workbook/jobs/{jobId} | ✅ | 轮询兜底 |
| 查询导出作业日志 | GET /api/meta/exports/workbook/jobs/{jobId}/logs | ✅ | 断线补拉、日志面板 |
| 导出 SSE 进度流 | GET /api/meta/exports/workbook/jobs/{jobId}/stream | ✅ | 页面主进度来源 |
| 查询导出结果 | GET /api/meta/exports/workbook/jobs/{jobId}/result | ✅ | 获取文件元数据与 resolvedRequest |
| 下载导出文件 | GET /api/meta/exports/workbook/jobs/{jobId}/download | ✅ | 仅结果已生成时可下载 |
| 取消导出作业 | DELETE /api/meta/exports/workbook/jobs/{jobId} | ✅ | 适合用户主动取消 |

---

## 2. 推荐前端对接流程

### 2.1 页面初始化

1. 调用 GET /api/meta/exports/workbook/schema 获取后端正式 schema。
2. 以前端内置 profile 或本地缓存配置作为初始值，但字段清单、默认表头、默认选中状态以后端 schema 为准。
3. 页面内部只保存 fieldKey、headerText、列顺序、sheetName、includeChildren、categoryIds 等正式请求字段，不再依赖 mock 字段定义。

### 2.2 用户点击“开始导出”

1. 可先调用 POST /api/meta/exports/workbook/plan 做预校验，获取 normalizedRequest 与预计导出行数。
2. 前端按当前配置构造 WorkbookExportStartRequestDto。
3. 调用 POST /api/meta/exports/workbook/jobs 创建导出作业。
4. 拿到 jobId 后，立即连接 GET /api/meta/exports/workbook/jobs/{jobId}/stream。
5. 以 SSE progress 事件作为主进度来源，并用 GET /api/meta/exports/workbook/jobs/{jobId} 做轮询兜底。
6. 作业完成后，先调用 GET /api/meta/exports/workbook/jobs/{jobId}/result 获取文件元数据与最终 resolvedRequest。
7. 再调用 GET /api/meta/exports/workbook/jobs/{jobId}/download 下载文件流。

### 2.3 不推荐的调用方式

- 不建议前端自己拼 workbook 内容。
- 不建议跳过 schema 接口，继续硬编码字段列表。
- 不建议只依赖 completed SSE 事件而不再拉一次 result。
- 不建议把 headerText 反向当作字段标识。

---

## 3. 接口总览

| 方法 | 路径 | 用途 | 前端建议 |
|---|---|---|---|
| GET | /api/meta/exports/workbook/schema | 获取导出字段 schema | 页面初始化必调 |
| POST | /api/meta/exports/workbook/plan | 导出计划预校验 | 点击导出前可选调 |
| POST | /api/meta/exports/workbook/jobs | 创建导出作业 | 页面主入口 |
| GET | /api/meta/exports/workbook/jobs/{jobId} | 查询作业状态 | 轮询兜底 |
| GET | /api/meta/exports/workbook/jobs/{jobId}/logs | 查询日志分页 | 断线补拉、日志面板 |
| GET | /api/meta/exports/workbook/jobs/{jobId}/stream | 导出 SSE | 页面主进度来源 |
| GET | /api/meta/exports/workbook/jobs/{jobId}/result | 查询导出结果 | 完成后必调 |
| GET | /api/meta/exports/workbook/jobs/{jobId}/download | 下载文件 | 结果就绪后调用 |
| DELETE | /api/meta/exports/workbook/jobs/{jobId} | 取消任务 | 用户主动取消时调用 |

---

## 4. Schema 接入

### 4.1 请求

- 方法：GET
- 路径：/api/meta/exports/workbook/schema

### 4.2 响应结构

响应体：WorkbookExportSchemaResponseDto

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

### 4.3 前端约束

- moduleKey 当前固定为 CATEGORY、ATTRIBUTE、ENUM_OPTION。
- fieldKey 是唯一绑定依据。
- defaultHeader 适合作为初始化表头。
- defaultSelected 可直接用于默认勾选。
- allowCustomHeader 决定该列是否允许前端改名。

### 4.4 当前 schema 特点

- CATEGORY 已覆盖分类定义、最新版本、层级与路径派生字段。
- ATTRIBUTE 已覆盖属性定义、最新版本与 structureJson 中已持久化参数字段。
- ENUM_OPTION 已覆盖 LOV 定义、最新版本与 valueJson 中已持久化枚举值字段。

前端结论：

- 所有字段面板都应以后端 schema 为准。
- 如果后端未来新增 fieldKey，前端无需改协议，只需要按 schema 渲染即可。

---

## 5. Plan 预校验

### 5.1 请求

- 方法：POST
- 路径：/api/meta/exports/workbook/plan
- Content-Type：application/json

请求体：WorkbookExportStartRequestDto

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
          "clientColumnId": "categoryCode"
        }
      ]
    }
  ],
  "operator": "alice",
  "clientRequestId": "f0b7f3b8-2d5f-4b57-a07a-b17b6f6d0d50"
}
```

### 5.2 响应

响应体：WorkbookExportPlanResponseDto

```json
{
  "normalizedRequest": {
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
            "clientColumnId": "categoryCode"
          }
        ]
      }
    ],
    "operator": "alice",
    "clientRequestId": "f0b7f3b8-2d5f-4b57-a07a-b17b6f6d0d50"
  },
  "estimate": {
    "categoryRows": 12,
    "attributeRows": 37,
    "enumOptionRows": 108
  },
  "warnings": []
}
```

### 5.3 前端用途

- 在真正创建 job 前预览预计导出规模。
- 以后端 normalizedRequest 覆盖前端未标准化的大小写、默认值、默认 sheetName 等。
- 对大范围导出提前给用户风险提示。

---

## 6. 创建导出作业

### 6.1 请求

- 方法：POST
- 路径：/api/meta/exports/workbook/jobs
- Content-Type：application/json

请求体仍然是 WorkbookExportStartRequestDto，与 /plan 相同。

### 6.2 顶层字段说明

| 字段 | 必填 | 说明 |
|---|---:|---|
| businessDomain | 是 | 业务域 |
| scope | 是 | 导出范围 |
| output | 是 | 输出配置 |
| modules | 是 | 模块配置 |
| operator | 否 | 操作人 |
| clientRequestId | 否 | 前端追踪或排查辅助 |

#### scope

| 字段 | 必填 | 说明 |
|---|---:|---|
| categoryIds | 是 | 分类主键数组 |
| includeChildren | 否 | 是否带出子树 |

#### output

| 字段 | 必填 | 说明 |
|---|---:|---|
| format | 是 | 当前仅支持 XLSX |
| fileName | 否 | 导出文件名 |
| pathSeparator | 否 | 仅影响展示型路径列 |

#### modules

| 字段 | 必填 | 说明 |
|---|---:|---|
| moduleKey | 是 | CATEGORY / ATTRIBUTE / ENUM_OPTION |
| enabled | 是 | 是否导出该模块 |
| sheetName | 否 | 自定义 sheet 名 |
| columns | 是 | 列定义数组，按顺序输出 |

#### columns

| 字段 | 必填 | 说明 |
|---|---:|---|
| fieldKey | 是 | 唯一绑定依据 |
| headerText | 否 | 输出表头 |
| clientColumnId | 否 | 前端调试字段，无业务语义 |

### 6.3 响应

响应体：WorkbookExportStartResponseDto

```json
{
  "jobId": "export-a58a5ce4",
  "status": "QUEUED",
  "currentStage": "RESOLVING_SCOPE",
  "createdAt": "2026-04-08T17:25:00+08:00"
}
```

前端收到响应后应立即做两件事：

1. 打开 SSE：GET /api/meta/exports/workbook/jobs/{jobId}/stream。
2. 启动低频轮询：GET /api/meta/exports/workbook/jobs/{jobId}。

---

## 7. 任务状态模型

GET /api/meta/exports/workbook/jobs/{jobId} 返回 WorkbookExportJobStatusDto。

同一个 DTO 也是 SSE 的 progress、completed、failed、canceled 事件负载。因此：

- progress 事件 = 状态接口的实时版。
- GET status = progress 的轮询兜底版。

### 7.1 字段定义

| 字段 | 说明 | 前端用途 |
|---|---|---|
| jobId | 当前任务 ID | 任务主键 |
| businessDomain | 当前业务域 | 页面顶部信息 |
| status | 当前状态 | 状态标签 |
| currentStage | 当前阶段 | 阶段文案 |
| fileName | 目标文件名 | 下载前展示 |
| overallPercent | 总体百分比 | 页面主进度条 |
| stagePercent | 当前阶段进度 | 次级进度条 |
| createdAt | 创建时间 | 任务信息 |
| startedAt | 开始时间 | 任务信息 |
| updatedAt | 最近更新时间 | 心跳参考 |
| completedAt | 完成时间 | 任务信息 |
| progress | 各模块进度 | 明细卡片 |
| latestLogCursor | 最新日志游标 | 日志补拉起点 |
| latestLogs | 最近日志快照 | 页面首屏日志 |
| warnings | 当前 warning 列表 | 风险提示 |

### 7.2 状态枚举

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

### 7.3 progress 结构

progress 分为：

- categories
- attributes
- enumOptions

每个模块都包含：

- total
- processed
- exported
- failed

前端建议：

- overallPercent 用于主进度条。
- progress.categories / attributes / enumOptions 用于模块级明细展示。
- status 进入 COMPLETED、FAILED、CANCELED 任一终态后，仍建议再调用一次 result 或 logs 做最终确认。

---

## 8. 日志接口

### 8.1 请求

- 方法：GET
- 路径：/api/meta/exports/workbook/jobs/{jobId}/logs

Query 参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| cursor | string | 否 | 增量拉取游标 |
| limit | int | 否 | 条数，默认 100，最大 500 |
| level | string | 否 | INFO / WARN / ERROR |
| stage | string | 否 | 按阶段过滤 |
| moduleKey | string | 否 | 按 CATEGORY / ATTRIBUTE / ENUM_OPTION 过滤 |

### 8.2 响应

响应体：WorkbookExportLogPageDto

```json
{
  "jobId": "export-a58a5ce4",
  "nextCursor": "18",
  "items": [
    {
      "cursor": "17",
      "sequence": 17,
      "timestamp": "2026-04-08T17:25:02+08:00",
      "level": "INFO",
      "stage": "BUILDING_WORKBOOK",
      "eventType": "WORKBOOK_BUILD_STARTED",
      "moduleKey": null,
      "code": "WORKBOOK_EXPORT_BUILD_START",
      "message": "building xlsx workbook export file",
      "details": null
    }
  ]
}
```

### 8.3 前端建议

- 首屏日志优先使用 status.latestLogs。
- 若需要完整日志或断线恢复，再用 latestLogCursor 从 /logs 做增量补拉。
- 不要假设日志会通过 SSE 自动补发历史数据。

---

## 9. SSE 实时事件

### 9.1 请求

- 方法：GET
- 路径：/api/meta/exports/workbook/jobs/{jobId}/stream
- Accept：text/event-stream

### 9.2 当前事件名

当前服务会推送这些事件：

- progress
- log
- completed
- failed
- canceled

其中：

- progress、completed、failed、canceled 的 data 都是 WorkbookExportJobStatusDto。
- log 的 data 是 WorkbookExportLogEventDto。

### 9.3 前端接入建议

- 以 progress 事件更新主进度。
- 以 log 事件实时刷新日志面板。
- 收到 completed 后，必须再调一次 /result。
- 收到 failed 或 canceled 后，仍建议补拉一次 /logs，拿到最终错误或取消日志。

---

## 10. 导出结果与下载

### 10.1 查询结果

- 方法：GET
- 路径：/api/meta/exports/workbook/jobs/{jobId}/result

响应体：WorkbookExportJobResultDto

```json
{
  "jobId": "export-a58a5ce4",
  "status": "COMPLETED",
  "summary": {
    "categories": {
      "sheetName": "分类",
      "totalRows": 12,
      "exportedRows": 12
    },
    "attributes": {
      "sheetName": "属性",
      "totalRows": 37,
      "exportedRows": 37
    },
    "enumOptions": {
      "sheetName": "枚举值",
      "totalRows": 108,
      "exportedRows": 108
    }
  },
  "resolvedRequest": {
    "businessDomain": "MATERIAL"
  },
  "file": {
    "fileName": "material-workbook-export.xlsx",
    "contentType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "size": 24576,
    "checksum": "sha256...",
    "expiresAt": "2026-04-08T18:25:03+08:00"
  },
  "warnings": [],
  "completedAt": "2026-04-08T17:25:03+08:00"
}
```

### 10.2 下载文件

- 方法：GET
- 路径：/api/meta/exports/workbook/jobs/{jobId}/download

响应：

- Content-Type：application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
- Content-Disposition：attachment; filename*=UTF-8''...
- Body：xlsx 二进制流

前端建议：

- 下载前先读取 /result，拿 fileName、size、expiresAt 做展示。
- 若下载失败，应提示用户文件可能尚未生成或已过期，并允许重新发起导出。

---

## 11. 取消任务

### 11.1 请求

- 方法：DELETE
- 路径：/api/meta/exports/workbook/jobs/{jobId}

### 11.2 响应

响应体仍为 WorkbookExportJobStatusDto。

当前语义：

- 未终态任务会被标记为 CANCELED。
- 已终态任务再次取消时，返回当前终态快照，不重复追加取消日志。

前端建议：

- 用户点击“取消导出”后，可直接把任务视图切换到取消中状态。
- 最终仍以 DELETE 响应体和后续 SSE canceled / GET status 的终态为准。

---

## 12. 前端必须遵守的绑定规则

后端当前明确只认可这些请求语义：

- categoryIds
- includeChildren
- moduleKey
- fieldKey
- headerText
- sheetName
- pathSeparator

前端必须避免以下错误用法：

- 用 sourceLabel 绑定字段。
- 用 targetHeader 反向识别字段。
- 把 mock 字段名当作正式协议。
- 把前端列顺序号或列组件内部 id 当作后端语义字段。

---

## 13. 当前实现边界

以下内容不影响前端现在接入，但不应在页面上做错误假设：

- 当前导出结果是作业内存持有并带 expiresAt，不是长期归档文件。
- 当前还没有接入导出权限控制，前端暂时不要自行假设哪些列会被权限裁剪。
- 当前没有 round-trip manifest，不应把导出文件视为再次导入的契约文件。

---

## 14. 推荐前端落地顺序

1. 先改字段配置器，改成完全消费 /schema。
2. 再接 /plan，用于点击导出前的规模确认。
3. 再接 /jobs、/stream、/result、/download 形成完整异步导出链路。
4. 最后补 /logs 和 /cancel，完善日志面板与任务取消体验。