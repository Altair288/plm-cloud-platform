# 元数据工作簿导入前端对接文档（优化版）

更新时间：2026-04-02

本文面向前端联调，描述优化后的工作簿导入接口应该如何接入。重点覆盖三件事：

- 推荐调用链路
- progress 进度口径
- SSE 实时事件与断线恢复

说明：

- 当前服务统一入口为 /api/meta/imports/workbook。
- 导入对象固定为三类：分类层级、属性定义、枚举值定义。
- 前端推荐主流程是“异步 dry-run -> 查看结果 -> 正式 import -> SSE + 日志补拉”。
- 同步 dry-run 仍可用，但更适合本地调试或小文件，不建议作为页面主路径。

---

## 1. 推荐前端对接流程

### 1.1 主流程

1. 调用 POST /api/meta/imports/workbook/dry-run-jobs 创建异步 dry-run 任务。
2. 拿到 dryRunJobId 后，立即连接 GET /api/meta/imports/workbook/dry-run-jobs/{jobId}/stream。
3. 以 SSE progress 事件作为主进度来源，并用 GET /api/meta/imports/workbook/dry-run-jobs/{jobId} 做兜底轮询。
4. dry-run 完成后，调用 GET /api/meta/imports/workbook/dry-run-jobs/{jobId}/result 获取完整预览结果。
5. 若 summary.canImport = true，则调用 POST /api/meta/imports/workbook/import 或 /import-jobs 启动正式导入。
6. 拿到 importJobId 后，立即连接 GET /api/meta/imports/workbook/jobs/{jobId}/stream。
7. 导入过程中以 SSE progress 更新进度条，以 SSE log 和 GET /api/meta/imports/workbook/jobs/{jobId}/logs 展示日志。
8. 收到 completed 或 failed 事件后，不要只信 SSE 终态消息，必须再拉一次 GET /api/meta/imports/workbook/jobs/{jobId} 和日志接口，作为最终落库状态。

### 1.2 不推荐的旧式使用方式

- 不建议页面主流程直接使用 POST /api/meta/imports/workbook/dry-run，因为它没有 jobId，也没有 SSE 进度流。
- 不建议只依赖 completed 或 failed 事件判断最终结果，因为这两个事件负载很轻，不包含完整进度快照和完整日志。
- 不建议断线后直接重建 SSE 而不补拉日志，因为服务端不会回放已发送的 SSE 历史事件。

---

## 2. 标识符与保留时间

| 标识 | 来源 | 用途 | 前端建议 |
|---|---|---|---|
| importSessionId | dry-run result | 对应一次预校验会话；正式导入可直接复用 | 必存，本地状态要保留 |
| dryRunJobId | dry-run-jobs start response | 对应一次异步 dry-run 任务 | 用于 SSE、状态查询、结果查询 |
| importJobId | import start response | 对应一次正式导入任务 | 用于 SSE、状态查询、日志查询 |

默认运行时保留策略：

- sessionRetentionMillis 默认 24 小时：importSessionId 的内存会话可长期复用。
- snapshotRetentionMillis 默认 2 小时：服务重启后，会话快照短期可恢复，但前端不要把它当永久存档。
- terminalJobRetentionMillis 默认 1 小时：dryRunJobId 和 importJobId 的终态任务与其日志只适合短期查看。
- emitterTimeoutMillis 默认 30 分钟：单个 SSE 连接最长保持约 30 分钟，前端要具备自动重连能力。

前端结论：

- 页面真正应该长期保存的是 importSessionId。
- jobId 只适合做“当前任务联调态”的实时跟踪，不适合做长期归档主键。

---

## 3. 接口总览

| 方法 | 路径 | 用途 | 前端建议 |
|---|---|---|---|
| POST | /api/meta/imports/workbook/dry-run | 同步 dry-run | 仅调试或小文件使用 |
| POST | /api/meta/imports/workbook/dry-run-jobs | 异步 dry-run | 页面主入口 |
| GET | /api/meta/imports/workbook/dry-run-jobs/{jobId} | dry-run 任务状态 | 轮询兜底 |
| GET | /api/meta/imports/workbook/dry-run-jobs/{jobId}/result | dry-run 完整结果 | dry-run 完成后必调 |
| GET | /api/meta/imports/workbook/dry-run-jobs/{jobId}/logs | dry-run 日志分页 | 断线补拉、日志面板 |
| GET | /api/meta/imports/workbook/dry-run-jobs/{jobId}/stream | dry-run SSE | 页面主进度来源 |
| GET | /api/meta/imports/workbook/sessions/{importSessionId} | 重新获取会话结果 | 刷新页面后恢复结果 |
| POST | /api/meta/imports/workbook/import | 启动正式导入 | 与 /import-jobs 等价 |
| POST | /api/meta/imports/workbook/import-jobs | 启动正式导入 | 推荐新页面使用 |
| GET | /api/meta/imports/workbook/jobs/{jobId} | 导入任务状态 | 轮询兜底 |
| GET | /api/meta/imports/workbook/jobs/{jobId}/logs | 导入日志分页 | 断线补拉、日志面板 |
| GET | /api/meta/imports/workbook/jobs/{jobId}/stream | 导入 SSE | 页面主进度来源 |
| POST | /api/meta/imports/workbook/jobs/{jobId}/post-process/closure-rebuild | 分类闭包补偿 | 仅特殊场景人工触发 |

---

## 4. Dry-Run 接入

### 4.1 推荐：异步 dry-run

请求：

- 方法：POST
- 路径：/api/meta/imports/workbook/dry-run-jobs
- Content-Type：multipart/form-data
- Query：operator 可选

multipart part：

| part | 类型 | 必填 | 说明 |
|---|---|---:|---|
| file | file | 是 | Excel 工作簿 |
| options | string | 是 | WorkbookImportDryRunOptionsDto 的 JSON 字符串 |

options 示例：

```json
{
  "codingOptions": {
    "categoryCodeMode": "EXCEL_MANUAL",
    "attributeCodeMode": "SYSTEM_RULE_AUTO",
    "enumOptionCodeMode": "SYSTEM_RULE_AUTO"
  },
  "duplicateOptions": {
    "categoryDuplicatePolicy": "FAIL_ON_DUPLICATE",
    "attributeDuplicatePolicy": "FAIL_ON_DUPLICATE",
    "enumOptionDuplicatePolicy": "OVERWRITE_EXISTING"
  }
}
```

响应体：WorkbookImportDryRunStartResponseDto

```json
{
  "jobId": "dryrun-7b30e4c1",
  "status": "QUEUED",
  "currentStage": "PARSING",
  "createdAt": "2026-04-02T10:00:00+08:00"
}
```

前端收到响应后立刻做两件事：

1. 打开 SSE：GET /api/meta/imports/workbook/dry-run-jobs/{jobId}/stream。
2. 启动低频轮询：GET /api/meta/imports/workbook/dry-run-jobs/{jobId}。

### 4.2 同步 dry-run

POST /api/meta/imports/workbook/dry-run 会直接返回 WorkbookImportDryRunResponseDto。

这个接口的特点是：

- 没有 jobId
- 没有 SSE
- 页面无法看到解析中、预加载中、构建预览中的过程进度

因此只建议在本地测试、脚本场景或极小文件情况下使用。

### 4.3 dry-run 完成后取结果

调用：GET /api/meta/imports/workbook/dry-run-jobs/{jobId}/result

返回：WorkbookImportDryRunResponseDto

顶层关键字段：

| 字段 | 说明 | 前端用途 |
|---|---|---|
| importSessionId | 预校验会话 ID | 正式导入、页面刷新恢复 |
| template | 模板识别结果 | 展示模板合法性 |
| summary | 行数、错误数、warning 数、是否可导入 | 决定是否允许点“开始导入” |
| changeSummary | create/update/skip/conflict 汇总 | 展示预计变更量 |
| resolvedImportOptions | 后端实际生效的导入选项 | 回显用户配置 |
| preview | 分类、属性、枚举值明细预览 | 列表预览 |
| issues | 全局问题列表 | 顶部错误面板 |

前端强约束：

- 只有 summary.canImport = true 时才允许进入正式导入。
- 页面必须缓存 importSessionId，因为后续 retry、刷新恢复、重新查看预览都依赖它。

### 4.4 dry-run 结果刷新恢复

若页面刷新后只有 importSessionId，没有 dryRunJobId，可调用：

GET /api/meta/imports/workbook/sessions/{importSessionId}

返回结构与 dry-run result 完全一致，仍然是 WorkbookImportDryRunResponseDto。

---

## 5. 正式导入接入

### 5.1 启动导入

请求：POST /api/meta/imports/workbook/import-jobs

说明：

- /import 与 /import-jobs 完全等价。
- 推荐前端统一调用 /import-jobs，语义更清晰。

请求体：WorkbookImportStartRequestDto

| 字段 | 必填 | 说明 |
|---|---:|---|
| importSessionId | 否 | 与 dryRunJobId 二选一；页面刷新恢复时可直接传它 |
| dryRunJobId | 否 | 与 importSessionId 二选一；异步 dry-run 主路径推荐传它 |
| operator | 否 | 操作人 |
| atomic | 否 | 是否按原子语义执行 |
| executionMode | 否 | 显式指定执行模式 |
| overwriteMode | 否 | 预留字段，当前后端未使用，不要依赖 |

推荐请求示例一：前端“原子化开关 = 开”，显式使用 STAGING_ATOMIC

```json
{
  "dryRunJobId": "dryrun-7b30e4c1",
  "operator": "alice",
  "atomic": true,
  "executionMode": "STAGING_ATOMIC"
}
```

推荐请求示例二：前端“原子化开关 = 关”，使用分阶段提交

```json
{
  "dryRunJobId": "dryrun-7b30e4c1",
  "operator": "alice",
  "atomic": false
}
```

执行模式规则：

| 前端传参 | 后端结果 |
|---|---|
| atomic = true，executionMode 为空 | 默认 GLOBAL_TX |
| atomic = false，executionMode 为空 | 默认 STAGE_TX |
| executionMode = GLOBAL_TX | 要求 atomic 不能为 false |
| executionMode = STAGE_TX | 可显式指定分阶段提交 |
| executionMode = STAGING_ATOMIC | 要求 atomic 不能为 false |

响应体：WorkbookImportStartResponseDto

```json
{
  "jobId": "import-b1b00d2c",
  "importSessionId": "session-9ef2b6b7",
  "status": "QUEUED",
  "atomic": true,
  "executionMode": "STAGING_ATOMIC",
  "createdAt": "2026-04-02T10:10:00+08:00"
}
```

### 5.2 正式导入后前端立即执行的动作

1. 连接 SSE：GET /api/meta/imports/workbook/jobs/{jobId}/stream。
2. 启动低频轮询：GET /api/meta/imports/workbook/jobs/{jobId}。
3. 打开日志面板时，先读 status.latestLogs，再按 latestLogCursor 从 /logs 增量补齐。

### 5.3 STAGING_ATOMIC 前端需要知道的行为

当前 STAGING_ATOMIC 已不是占位模式，真实行为是：

- 先基于 dry-run 会话准备 staged execution plan。
- 准备好的 staged plan 会持久化到快照中。
- 正式 merge 在单事务中执行。
- 成功后会清理 staged plan。
- 失败后会保留 staged plan，便于在会话有效期内 retry。

前端含义：

- 如果 STAGING_ATOMIC 导入失败，在 session 未过期前，允许用户直接重试，不必强制重新上传文件。
- 如果同一个 importSessionId 已存在未结束的导入任务，再次启动会被后端拦截，前端应提示“已有导入进行中”。

---

## 6. 任务状态模型

无论 dry-run 还是 import，GET /dry-run-jobs/{jobId} 与 GET /jobs/{jobId} 返回的都是 WorkbookImportJobStatusDto。

同一个 DTO 也是 SSE progress 事件的完整 payload。因此：

- progress 事件 = 状态接口的实时版
- GET status = progress 的轮询兜底版

字段定义：

| 字段 | 说明 | 前端使用建议 |
|---|---|---|
| jobId | 当前任务 ID | 任务主键 |
| jobType | DRY_RUN 或 IMPORT | 区分页面流程 |
| importSessionId | 对应 dry-run 会话 | 恢复或重试用 |
| status | 当前状态 | 顶部状态标签 |
| currentStage | 当前阶段 | 阶段文案 |
| executionMode | GLOBAL_TX / STAGE_TX / STAGING_ATOMIC | 原子化标签 |
| totalRows | 三类实体总行数 | 总进度分母 |
| processedRows | 已处理总行数 | 总进度分子 |
| overallPercent | 总体百分比 | 页面主进度条 |
| stagePercent | 当前阶段百分比 | 次级进度条 |
| startedAt | 任务开始时间 | 任务信息区 |
| updatedAt | 最近更新时间 | SSE 心跳替代参考 |
| currentEntityType | 当前实体类型 | 如 CATEGORY / ATTRIBUTE / ENUM_OPTION |
| currentBusinessDomain | 当前业务域 | 细粒度提示 |
| progress | 分类、属性、枚举值维度的计数器 | 明细进度卡片 |
| latestLogCursor | 最近日志 cursor | 日志补拉起点 |
| latestLogs | 最近一段日志快照 | 页面首屏日志 |

### 6.1 overallPercent 的口径

overallPercent 不是按阶段平均算的，而是按三类实体的累计行数算的：

- totalRows = categories.total + attributes.total + enumOptions.total
- processedRows = categories.processed + attributes.processed + enumOptions.processed
- overallPercent = processedRows / totalRows

因此前端应把 overallPercent 当作“整份工作簿总体完成度”。

### 6.2 stagePercent 的口径

stagePercent 是“当前阶段内部进度”，在阶段切换时会重置为 0。

前端建议：

- 主进度条显示 overallPercent
- 副进度条或阶段标签显示 currentStage + stagePercent

不要把 stagePercent 当总进度使用，否则阶段切换时会出现进度回退。

### 6.3 progress 子对象的口径

progress 结构：

```json
{
  "categories": {
    "total": 100,
    "processed": 80,
    "created": 50,
    "updated": 20,
    "skipped": 8,
    "failed": 2
  },
  "attributes": {
    "total": 200,
    "processed": 120,
    "created": 60,
    "updated": 40,
    "skipped": 15,
    "failed": 5
  },
  "enumOptions": {
    "total": 300,
    "processed": 90,
    "created": 70,
    "updated": 10,
    "skipped": 5,
    "failed": 5
  }
}
```

前端解释方式：

- total：该实体总行数
- processed：该实体已处理行数
- created：本次新建数
- updated：本次更新数
- skipped：因策略或幂等判断被跳过的数量
- failed：该实体处理失败数量

### 6.4 status 与 currentStage 的常见值

status 常见值：

- QUEUED
- PARSING
- PRELOADING
- VALIDATING_CATEGORIES
- VALIDATING_ATTRIBUTES
- VALIDATING_ENUMS
- BUILDING_PREVIEW
- PREPARING
- IMPORTING_CATEGORIES
- IMPORTING_ATTRIBUTES
- IMPORTING_ENUM_OPTIONS
- FINALIZING
- COMPLETED
- FAILED

currentStage 常见值：

- PARSING
- PRELOADING
- VALIDATING_CATEGORIES
- VALIDATING_ATTRIBUTES
- VALIDATING_ENUMS
- BUILDING_PREVIEW
- PREPARING
- CATEGORIES
- ATTRIBUTES
- ENUM_OPTIONS
- FINALIZING

注意：

- status 更偏“任务状态机”
- currentStage 更偏“当前业务处理阶段”
- import 阶段里 status 可能是 IMPORTING_CATEGORIES，而 currentStage 是 CATEGORIES，这两者不应混用

---

## 7. SSE 事件模型

### 7.1 事件名

dry-run 与 import 的 SSE 事件名完全一致：

- progress
- stage-changed
- log
- completed
- failed

### 7.2 每种事件的真实负载

#### progress

payload 就是完整的 WorkbookImportJobStatusDto。

这是前端最重要的事件，也是页面实时状态的主数据源。

#### stage-changed

payload 示例：

```json
{
  "jobId": "import-b1b00d2c",
  "status": "PREPARING",
  "currentStage": "PREPARING",
  "updatedAt": "2026-04-02T10:11:03+08:00"
}
```

这个事件适合做阶段标题切换、时间轴高亮，但不适合单独作为进度来源。

#### log

payload 是 WorkbookImportLogEventDto。

```json
{
  "cursor": "128",
  "sequence": 128,
  "timestamp": "2026-04-02T10:11:10+08:00",
  "level": "INFO",
  "stage": "PREPARING",
  "eventType": "SYSTEM",
  "sheetName": null,
  "rowNumber": null,
  "entityType": "CATEGORY",
  "entityKey": "ELEC/PHONE",
  "action": "PREPARE",
  "code": "WORKBOOK_STAGING_PLAN_PREPARED",
  "message": "staging atomic execution plan prepared",
  "details": {
    "categoryCount": 12,
    "attributeCount": 48,
    "enumOptionCount": 160
  }
}
```

前端应使用 cursor 作为日志断点续传位置。

#### completed

payload 示例：

```json
{
  "jobId": "import-b1b00d2c",
  "status": "COMPLETED"
}
```

#### failed

payload 示例：

```json
{
  "jobId": "import-b1b00d2c",
  "status": "FAILED",
  "message": "workbook import failed"
}
```

注意：completed 和 failed 都只是轻量通知，不带完整快照。收到后一定要补一次 GET status 和 GET logs。

### 7.3 连接建立后的行为

服务端在新建 SSE 连接后，会立刻主动发送一条 progress 事件快照。

这意味着前端可以这样处理：

1. SSE 一连上，就等第一条 progress。
2. 第一条 progress 到达后，直接用它初始化页面状态。
3. 如果第一条 progress 的 status 已经是 COMPLETED 或 FAILED，说明任务可能已结束，此时不要等 completed 或 failed 回放，直接拉状态和日志即可。

这个点非常重要，因为服务端不会补发历史 completed 或 failed 事件。

### 7.4 SSE 的几个硬约束

- 没有 heartbeat：长时间没有事件不代表连接失效，也可能只是当前阶段没有新日志。
- 没有 event id：服务端没有设置 SSE id，前端不能依赖 Last-Event-ID。
- 没有历史回放：重新连接只会拿到当下最新 progress 快照，不会把断线期间的 log、stage-changed、completed、failed 再发一遍。

前端结论：

- progress 用 SSE 实时推送 + GET status 兜底。
- log 用 SSE 实时展示 + GET logs 游标补拉。
- 断线恢复不能只靠 SSE，必须配合 logs cursor。

---

## 8. 日志分页与断线恢复

日志接口：

- dry-run：GET /api/meta/imports/workbook/dry-run-jobs/{jobId}/logs
- import：GET /api/meta/imports/workbook/jobs/{jobId}/logs

查询参数：

| 参数 | 说明 |
|---|---|
| cursor | 从哪个 cursor 之后继续拉 |
| limit | 返回条数 |
| level | 过滤日志级别 |
| stage | 过滤阶段 |
| sheetName | 过滤 sheet |
| rowNumber | 过滤行号 |

返回：WorkbookImportLogPageDto

```json
{
  "jobId": "import-b1b00d2c",
  "nextCursor": "128",
  "items": [
    {
      "cursor": "127",
      "sequence": 127,
      "timestamp": "2026-04-02T10:11:08+08:00",
      "level": "INFO",
      "stage": "PREPARING",
      "eventType": "SYSTEM",
      "entityType": "CATEGORY",
      "action": "PREPARE",
      "code": "WORKBOOK_STAGING_MERGE_STARTED",
      "message": "staging atomic merge started",
      "details": {
        "executionMode": "STAGING_ATOMIC"
      }
    }
  ]
}
```

### 8.1 前端推荐的 cursor 策略

1. 本地维护一个 latestCursor。
2. 每收到一条 SSE log，就把 latestCursor 更新成该事件的 cursor。
3. SSE 断开或页面切回前台时，调用 /logs?cursor={latestCursor} 补拉遗漏日志。
4. 处理完补拉结果后，再重新建立 SSE。
5. 补拉后使用返回的 nextCursor 覆盖本地 latestCursor。

### 8.2 首屏日志初始化建议

status 接口里的 latestLogs 和 latestLogCursor 可以作为首屏日志初始化数据。

建议：

- 页面首次进入任务详情时，先渲染 latestLogs。
- 再从 latestLogCursor 开始调用 /logs 补齐可能遗漏的数据。
- 最后再建立 SSE，进入实时态。

这样能避免首屏空白日志。

---

## 9. 前端状态机建议

### 9.1 dry-run 页面状态机

1. idle：未上传
2. creating-job：已提交 dry-run-jobs，请求返回前
3. running：已拿到 dryRunJobId，正在消费 progress 和 log
4. completed：调用 result 成功，渲染 preview
5. failed：显示 status + 日志面板，允许重新上传

### 9.2 import 页面状态机

1. ready：已有 importSessionId 且 summary.canImport = true
2. starting：已点开始导入，请求返回前
3. running：已拿到 importJobId，实时展示进度
4. completed：展示最终成功态，可查看完整日志
5. failed：展示失败信息与日志；若 session 未过期，允许 retry

### 9.3 失败后 retry 建议

- GLOBAL_TX 或 STAGE_TX 失败：建议前端保留原 importSessionId，允许用户直接重试或先查看 dry-run 结果。
- STAGING_ATOMIC 失败：优先允许用户基于同一个 importSessionId 或 dryRunJobId 直接 retry，因为后端会在失败后保留 staged plan。
- 如果后端提示 session 不存在或已过期，再退回到“重新上传工作簿”。

---

## 10. SSE 前端示例

如果认证链路允许直接使用浏览器 EventSource，可以按下面方式接：

```ts
type JobStatus = {
  jobId: string;
  status: string;
  currentStage: string;
  overallPercent: number;
  stagePercent: number;
  latestLogCursor?: string;
};

type LogEvent = {
  cursor: string;
  level: string;
  stage: string;
  code?: string;
  message?: string;
};

export function subscribeWorkbookJob(
  url: string,
  onProgress: (data: JobStatus) => void,
  onLog: (data: LogEvent) => void,
  onTerminal: () => void,
) {
  const source = new EventSource(url, { withCredentials: true });

  source.addEventListener("progress", event => {
    const data = JSON.parse((event as MessageEvent).data) as JobStatus;
    onProgress(data);
  });

  source.addEventListener("log", event => {
    const data = JSON.parse((event as MessageEvent).data) as LogEvent;
    onLog(data);
  });

  source.addEventListener("stage-changed", event => {
    const data = JSON.parse((event as MessageEvent).data) as {
      status: string;
      currentStage: string;
    };
    console.debug("stage changed", data.status, data.currentStage);
  });

  source.addEventListener("completed", () => {
    onTerminal();
    source.close();
  });

  source.addEventListener("failed", () => {
    onTerminal();
    source.close();
  });

  source.onerror = () => {
    source.close();
  };

  return () => source.close();
}
```

如果前端必须带自定义鉴权头，不要依赖原生 EventSource，要改成支持 header 的 SSE 客户端实现。

---

## 11. 常见前端处理建议

### 11.1 页面应该以什么为准

- 进度条：以 progress.overallPercent 为准。
- 当前阶段：以 currentStage 为准。
- 任务终态：以 GET status 最终结果为准。
- 日志列表：以 SSE log 实时展示，/logs 负责补齐。

### 11.2 页面何时允许“开始导入”

同时满足：

- 已拿到 WorkbookImportDryRunResponseDto
- summary.canImport = true
- 当前不存在同 session 的运行中 import 任务

### 11.3 收到 failed 事件后页面应该做什么

1. 立即停止“运行中”动画。
2. 立刻调用 GET /jobs/{jobId} 或 GET /dry-run-jobs/{jobId} 读取最终状态快照。
3. 用 latestLogCursor 或本地 cursor 调用 /logs 补齐最后一段日志。
4. 根据 executionMode 和 session 是否仍有效，决定展示“直接重试”还是“重新上传”。

### 11.4 closure rebuild 接口何时使用

POST /api/meta/imports/workbook/jobs/{jobId}/post-process/closure-rebuild 不是常规前端主链路的一部分。

它适合这类场景：

- 导入主流程已经结束
- 日志中出现分类闭包补偿相关提示
- 运维或管理员需要手动补跑分类闭包

普通业务页面不建议默认展示这个按钮，可以只在高级运维页面暴露。

---

## 12. 前端最终落地建议

建议前端直接按下面的最小闭环实现：

1. 上传页只走 /dry-run-jobs，不走同步 /dry-run。
2. 任务详情页统一实现“status 轮询 + SSE + logs cursor 补拉”。
3. 主进度条只认 overallPercent，阶段条只认 stagePercent。
4. 导入时优先传 dryRunJobId；页面恢复时退化为 importSessionId。
5. 原子化开关开启时，显式发送 atomic = true 和 executionMode = STAGING_ATOMIC。
6. 终态事件到达后始终补一次 GET status 和 GET logs，不直接以 SSE 终态消息落盘。

如果按这个模型接入，当前优化后的后端能力已经足够支撑：

- 异步 dry-run
- 实时 progress
- SSE 日志流
- 断线补拉
- STAGING_ATOMIC 原子模式
- 失败后短期重试

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
