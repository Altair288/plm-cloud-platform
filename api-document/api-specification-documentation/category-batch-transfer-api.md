# 分类批量移动/复制接口专项文档

更新时间：2026-05-11
适用模块：plm-attribute-service

---

## 1. 接口概览

- 方法：POST
- 路径：/api/meta/categories/batch-transfer
- 目标：统一承载分类批量 MOVE / COPY 操作

适用场景：

- 前端批量移动多个分类节点到同一目标父节点
- 前端批量复制多个分类节点到同一目标父节点或不同目标父节点
- 前端在正式提交前先执行 dryRun 预检
- 前端需要按 operation 维度展示成功、失败、归一化、回滚结果
- 前端需要在 CI/CD 或弱网络环境下通过 SSE 实时获取 started/completed/failed 事件与实际异常信息

响应协商：

- `Accept: application/json`：返回普通 JSON 结果
- `Accept: text/event-stream`：返回 SSE 流，同一路径同一请求体，服务端按事件推送执行状态

---

## 2. 请求模型

### 2.1 请求体字段

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| businessDomain | string | 是 | - | 本次批处理所属业务域 |
| action | string | 是 | - | MOVE 或 COPY |
| targetParentId | UUID | 否 | null | 批次默认目标父节点；单项可覆盖 |
| dryRun | boolean | 否 | false | true 仅校验，不执行写入 |
| atomic | boolean | 否 | true | true 任一失败整批回滚；当前实现默认 true |
| operator | string | 否 | null | 操作人 |
| copyOptions | object | 否 | null | 仅 COPY 生效 |
| operations | array | 是 | - | 批处理操作列表，最多 200 条 |

operations[i] 字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| clientOperationId | string | 否 | 前端侧操作流水号，用于结果回填 |
| sourceNodeId | UUID | 是 | 源节点 ID |
| targetParentId | UUID | 否 | 单项目标父节点；优先级高于批次 targetParentId |

copyOptions 字段：

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| versionPolicy | string | 否 | CURRENT_ONLY | 首期仅支持当前快照复制 |
| codePolicy | string | 否 | AUTO_SUFFIX | 编码冲突策略 |
| namePolicy | string | 否 | AUTO_SUFFIX | 名称策略 |
| defaultStatus | string | 否 | DRAFT | 复制后默认状态 |

### 2.2 请求示例

```json
{
  "businessDomain": "MATERIAL",
  "action": "COPY",
  "targetParentId": "cae7a410-f951-4780-bad1-3c15ebed4dd4",
  "dryRun": false,
  "atomic": true,
  "operator": "admin",
  "copyOptions": {
    "versionPolicy": "CURRENT_ONLY",
    "codePolicy": "AUTO_SUFFIX",
    "namePolicy": "AUTO_SUFFIX",
    "defaultStatus": "DRAFT"
  },
  "operations": [
    {
      "clientOperationId": "OP_1710661001_A",
      "sourceNodeId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1"
    },
    {
      "clientOperationId": "OP_1710661002_B",
      "sourceNodeId": "9df774b4-1216-4bfa-8a5f-43d35c1f4828",
      "targetParentId": "f40e8e8e-b2f6-4f75-a6d2-f4254e91dbf7"
    }
  ]
}
```

### 2.3 SSE 调用方式

当请求头携带 `Accept: text/event-stream` 时，接口会切换为流式返回。

示例：

```http
POST /api/meta/categories/batch-transfer HTTP/1.1
Content-Type: application/json
Accept: text/event-stream

{
  "businessDomain": "MATERIAL",
  "action": "COPY",
  "targetParentId": "cae7a410-f951-4780-bad1-3c15ebed4dd4",
  "dryRun": false,
  "atomic": true,
  "operator": "admin",
  "operations": [
    {
      "clientOperationId": "OP_1710661001_A",
      "sourceNodeId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1"
    }
  ]
}
```

SSE 事件约定：

- `started`：服务端已接收请求并开始执行
- `completed`：执行完成，`data` 为完整的 `MetaCategoryBatchTransferResponseDto`
- `failed`：执行失败，`data` 为结构化错误对象

---

## 3. 行为语义

### 3.1 source overlap 归一化

- 父子同时选中且目标相同：保留父节点，子节点返回 SOURCE_OVERLAP_NORMALIZED
- 父子同时选中且目标不同：失败，返回 CATEGORY_SOURCE_OVERLAP_TARGET_CONFLICT
- 兄弟节点同时选中：允许，逐项独立处理

### 3.2 MOVE

- 节点 id 不变
- 版本链不变
- code 不变
- 更新 parent_def_id、path、depth、full_path_name、sort_order、is_leaf
- 重建 category_hierarchy 中“外部祖先 -> 被移动子树”关系
- 原父节点和新父节点同级顺序自动连续重排

### 3.3 COPY

- 首期仅复制 latest version，对应 versionPolicy=CURRENT_ONLY
- 新对象生成全新 id
- 新版本从 versionNo=1 开始
- codePolicy=AUTO_SUFFIX，按当前实现自动派生 `-COPY-001`、`-COPY-002`...
- namePolicy=AUTO_SUFFIX，默认自动派生可用名称；显式传 KEEP 时若原名称冲突则失败
- defaultStatus 默认写为 draft
- 每个复制出的新分类都会写入 copiedFromCategoryId
- 若源分类下存在属性定义，则复制后的新分类会同步创建对应属性
- 属性同样只复制 latest version，不复制属性历史版本
- 枚举属性绑定的 LOV 选项会一并复制

属性复制补充说明：

- 复制后的属性不会复用源 attribute key，而是按当前属性编码规则重新生成。
- 若属性为枚举型，复制后的 `lovKey` 也会按新属性上下文重新生成，不直接复用源 `lovKey`。
- 枚举值 option code 不会直接复用源编码，而是重新生成，以满足同 businessDomain 下枚举值编码唯一约束。
- 枚举值的 `name` / `label` 以及属性的展示名、字段名、数据类型等业务内容保持与源 latest version 一致。
- 当前 batch-transfer 响应仍只返回分类维度的 `codeMappings`；如果前端需要查看复制后的属性结果，应在 COPY 成功后使用新分类 code 调用属性查询接口。

复制来源字段：

- 落库字段：meta_category_def.copied_from_category_id
- 语义：记录当前新节点直接复制来源的原分类节点 id

---

## 4. 响应模型

### 4.1 顶层响应字段

| 字段 | 类型 | 说明 |
|---|---|---|
| total | int | 提交操作总数 |
| successCount | int | 成功项数 |
| failureCount | int | 失败项数 |
| normalizedCount | int | 被祖先归一化跳过的项数 |
| movedCount | int | MOVE 成功累计影响节点数 |
| copiedCount | int | COPY 成功累计创建节点数 |
| atomic | boolean | 是否 atomic 模式 |
| dryRun | boolean | 是否 dryRun 模式 |
| warnings | array | 批次级 warning |
| results | array | 逐项结果 |

### 4.2 results[i] 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| clientOperationId | string | 对应前端提交的操作流水号 |
| sourceNodeId | UUID | 原始源节点 |
| normalizedSourceNodeId | UUID | 归一化后实际生效的源节点 |
| targetParentId | UUID | 最终目标父节点 |
| action | string | MOVE 或 COPY |
| success | boolean | 是否成功 |
| affectedNodeCount | int | 影响节点数 |
| movedIds | UUID[] | MOVE 成功涉及的节点 id 列表 |
| createdRootId | UUID | COPY 后新根节点 id |
| createdIds | UUID[] | COPY 后整棵新树 id 列表 |
| copiedFromCategoryId | UUID | 当前结果关联的直接来源分类 |
| sourceMappings | array | sourceNodeId -> createdNodeId -> copiedFromCategoryId 映射 |
| codeMappings | array | oldCode -> newCode 映射 |
| code | string | 结果码 |
| message | string | 结果消息 |
| exceptionType | string | 执行期异常类型，全限定类名；仅失败项返回 |
| rootCauseType | string | 根因异常类型，全限定类名；仅失败项返回 |
| rootCauseMessage | string | 根因异常消息；仅失败项返回 |
| warning | array | 项级 warning |

说明：

- 规划期校验失败（如 source/target 不合法）通常只返回 `code/message`。
- 执行期失败会额外回填 `exceptionType/rootCauseType/rootCauseMessage`，便于前端区分业务冲突与真实运行时异常。

### 4.3 响应示例

```json
{
  "total": 2,
  "successCount": 2,
  "failureCount": 0,
  "normalizedCount": 1,
  "movedCount": 3,
  "copiedCount": 0,
  "atomic": true,
  "dryRun": false,
  "warnings": [
    "1 child operation normalized because ancestor already included"
  ],
  "results": [
    {
      "clientOperationId": "OP-MOVE-ROOT",
      "sourceNodeId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
      "normalizedSourceNodeId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
      "targetParentId": "cae7a410-f951-4780-bad1-3c15ebed4dd4",
      "action": "MOVE",
      "success": true,
      "affectedNodeCount": 3,
      "movedIds": [
        "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
        "9df774b4-1216-4bfa-8a5f-43d35c1f4828",
        "f40e8e8e-b2f6-4f75-a6d2-f4254e91dbf7"
      ],
      "createdRootId": null,
      "createdIds": null,
      "copiedFromCategoryId": null,
      "sourceMappings": null,
      "codeMappings": null,
      "code": null,
      "message": null,
      "warning": null
    },
    {
      "clientOperationId": "OP-MOVE-CHILD",
      "sourceNodeId": "9df774b4-1216-4bfa-8a5f-43d35c1f4828",
      "normalizedSourceNodeId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
      "targetParentId": "cae7a410-f951-4780-bad1-3c15ebed4dd4",
      "action": "MOVE",
      "success": true,
      "affectedNodeCount": 0,
      "movedIds": null,
      "createdRootId": null,
      "createdIds": null,
      "copiedFromCategoryId": null,
      "sourceMappings": null,
      "codeMappings": null,
      "code": "SOURCE_OVERLAP_NORMALIZED",
      "message": "source node skipped because ancestor already covers subtree",
      "warning": [
        "normalized by ancestor operation"
      ]
    }
  ]
}
```

---

## 5. 结果码

| code | 场景 |
|---|---|
| CATEGORY_NOT_FOUND | 源节点不存在 |
| CATEGORY_TARGET_PARENT_NOT_FOUND | 目标父节点不存在 |
| CATEGORY_TARGET_IS_SELF | 目标父节点就是源节点自身 |
| CATEGORY_TARGET_IN_DESCENDANT | 目标父节点位于源节点后代中 |
| CATEGORY_DOMAIN_MISMATCH | 业务域不一致 |
| CATEGORY_DELETED | 源或目标已删除 |
| CATEGORY_SOURCE_OVERLAP_TARGET_CONFLICT | 父子同时选中且目标不同 |
| SOURCE_OVERLAP_NORMALIZED | 父子同时选中且被祖先归一化 |
| CATEGORY_CODE_CONFLICT | copy 派生 code 失败 |
| ATOMIC_ROLLBACK | atomic 模式下已执行项被回滚 |
| ATOMIC_ABORTED | atomic 模式下未执行项被中止 |
| INVALID_ARGUMENT | 参数非法 |
| INTERNAL_ERROR | 系统异常 |

---

## 6. SSE 事件模型

### 6.1 started 事件示例

```text
event:started
data:{"timestamp":"2026-05-11T09:46:05.345086700+08:00","streamType":"batch-transfer","phase":"started","action":"COPY"}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| timestamp | string | 事件时间，ISO-8601 带时区 |
| streamType | string | 固定为 `batch-transfer` |
| phase | string | 固定为 `started` |
| action | string | 当前批处理动作，MOVE 或 COPY |

### 6.2 completed 事件示例

```text
event:completed
data:{"total":1,"successCount":1,"failureCount":0,"normalizedCount":0,"movedCount":0,"copiedCount":2,"atomic":true,"dryRun":false,"warnings":[],"results":[{"clientOperationId":"copy-op","sourceNodeId":"8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1","normalizedSourceNodeId":"8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1","targetParentId":"cae7a410-f951-4780-bad1-3c15ebed4dd4","action":"COPY","success":true,"affectedNodeCount":2,"createdRootId":"c6f55e6a-cd65-4bcb-b37f-c5fb4a6b2f77"}]}
```

说明：

- `completed` 事件的 `data` 与普通 JSON 模式返回体完全一致。
- 前端可直接复用现有 JSON 解析模型处理 `completed` 事件。

### 6.3 failed 事件示例

```text
event:failed
data:{"timestamp":"2026-05-11T09:46:05.346085400+08:00","streamType":"batch-transfer","phase":"failed","action":"COPY","code":"INVALID_ARGUMENT","message":"businessDomain is required","exceptionType":"java.lang.IllegalArgumentException","rootCauseType":"java.lang.IllegalArgumentException","rootCauseMessage":"businessDomain is required"}
```

失败事件字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| timestamp | string | 事件时间 |
| streamType | string | 固定为 `batch-transfer` |
| phase | string | 固定为 `failed` |
| action | string | 当前批处理动作 |
| code | string | 错误码 |
| message | string | 错误消息 |
| exceptionType | string | 异常类型，全限定类名 |
| rootCauseType | string | 根因异常类型，全限定类名 |
| rootCauseMessage | string | 根因异常消息 |

适用说明：

- 当请求语义校验或执行过程在流式模式下失败时，服务端会发送 `failed` 事件而不是返回最终 JSON 结果体。
- 若请求在进入控制器前就发生 JSON 反序列化失败、媒体类型不匹配等 Spring MVC 绑定错误，仍可能直接返回标准 HTTP 4xx/5xx，而不会进入 SSE 流。

### 6.4 普通 JSON 失败项示例

```json
{
  "total": 1,
  "successCount": 0,
  "failureCount": 1,
  "normalizedCount": 0,
  "movedCount": 0,
  "copiedCount": 0,
  "atomic": false,
  "dryRun": false,
  "warnings": [],
  "results": [
    {
      "clientOperationId": "copy-broken-op",
      "sourceNodeId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
      "normalizedSourceNodeId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
      "targetParentId": "cae7a410-f951-4780-bad1-3c15ebed4dd4",
      "action": "COPY",
      "success": false,
      "affectedNodeCount": 0,
      "code": "INVALID_ARGUMENT",
      "message": "category has no latest version: id=8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
      "exceptionType": "java.lang.IllegalArgumentException",
      "rootCauseType": "java.lang.IllegalArgumentException",
      "rootCauseMessage": "category has no latest version: id=8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1"
    }
  ]
}
```

---

## 7. HTTP 语义

- 200：请求格式合法，服务端已进入逐项处理阶段；即使内部有部分失败也返回 200
- 400：请求体非法，整批无法开始处理
- 404：批次级目标节点不存在等无法进入逐项执行的错误
- 409：atomic 模式下出现整批性冲突

流式模式补充：

- 当 `Accept: text/event-stream` 且请求成功进入控制器后，HTTP 状态通常为 200，实际成功/失败由 `completed` 或 `failed` 事件表达。

当前实现建议前端使用方式：

1. 先执行 dryRun=true 做预检
2. 用户确认后再执行正式请求
3. 按 results[i].clientOperationId 回填前端操作结果

---

## 7. 关联接口

- 分类总览文档：category-api.md
- 分类拓扑感知批量移动接口：POST /api/meta/categories/batch-transfer/topology
- 子树查询接口：POST /api/meta/categories/nodes/subtree
- 批量删除接口：POST /api/meta/categories/batch-delete
*** Add File: d:\Github\plm-cloud-platform\plm-cloud-platform\api-document\api-specification-documentation\category-batch-transfer-topology-api.md
# 分类拓扑感知批量移动接口专项文档

更新时间：2026-03-18
适用模块：plm-attribute-service

---

## 1. 接口概览

- 方法：POST
- 路径：/api/meta/categories/batch-transfer/topology
- 目标：承载拓扑感知的分类批量 MOVE 操作

当前实现定位：

- 首期仅支持 MOVE，不支持 COPY
- 默认 atomic=true，且 topology 接口不支持 atomic=false
- 支持 dryRun 规划结果返回
- 支持 dependsOnOperationIds 显式依赖
- 支持“后代先、祖先后”的祖先链拆分
- 明确拒绝批内有效树成环

适用场景：

- 一次批处理中完成连环移动
- 前端已在工作区内完成 virtualRelationMap 规划
- 需要后端返回 resolvedOrder 和 finalParentMappings 与前端规划结果对账
- 需要在正式执行前先做拓扑预检

---

## 2. 请求模型

### 2.1 顶层字段

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| businessDomain | string | 是 | - | 本次批处理所属业务域 |
| action | string | 是 | MOVE | 当前实现仅支持 MOVE |
| dryRun | boolean | 否 | false | true 仅规划和校验，不执行写入 |
| atomic | boolean | 否 | true | 当前实现默认 true，传 false 会直接拒绝 |
| operator | string | 否 | null | 操作人 |
| planningMode | string | 否 | TOPOLOGY_AWARE | 规划模式标识 |
| orderingStrategy | string | 否 | CLIENT_ORDER | 当前实现仅按前端提交顺序解析 |
| strictDependencyValidation | boolean | 否 | true | 是否严格校验依赖与顺序 |
| operations | array | 是 | - | 批量移动操作列表，最多 200 条 |

### 2.2 operations[i] 字段

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| operationId | string | 是 | - | 批内唯一操作标识 |
| sourceNodeId | UUID | 是 | - | 源节点 ID |
| targetParentId | UUID | 否 | null | 目标父节点；null 表示移动到根 |
| dependsOnOperationIds | string[] | 否 | [] | 显式前置依赖 |
| allowDescendantFirstSplit | boolean | 否 | false | 是否允许后代先拆出后再移动祖先 |
| expectedSourceParentId | UUID | 否 | null | 可选并发保护字段；传入时才校验 |

### 2.3 请求示例

```json
{
  "businessDomain": "MATERIAL",
  "action": "MOVE",
  "dryRun": true,
  "atomic": true,
  "operator": "admin",
  "planningMode": "TOPOLOGY_AWARE",
  "orderingStrategy": "CLIENT_ORDER",
  "strictDependencyValidation": true,
  "operations": [
    {
      "operationId": "op-b-to-y",
      "sourceNodeId": "9df774b4-1216-4bfa-8a5f-43d35c1f4828",
      "targetParentId": "11111111-1111-1111-1111-111111111111",
      "dependsOnOperationIds": [],
      "allowDescendantFirstSplit": true,
      "expectedSourceParentId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1"
    },
    {
      "operationId": "op-a-to-x",
      "sourceNodeId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
      "targetParentId": "22222222-2222-2222-2222-222222222222",
      "dependsOnOperationIds": ["op-b-to-y"],
      "allowDescendantFirstSplit": true,
      "expectedSourceParentId": null
    }
  ]
}
```

---

## 3. 当前实现语义

### 3.1 支持的执行模型

- 服务端先校验 source、target、业务域、删除状态和依赖合法性
- 基于当前树快照构建 effectiveParentMap
- 先做一轮有效树目标预校验，拦截批内成环和无效拓扑目标
- 再按 CLIENT_ORDER 顺序模拟并生成 resolvedOrder
- 正式执行时按同一批顺序逐条落库，任一失败整批回滚

### 3.2 祖先链拆分规则

- 允许：后代先、祖先后
- 不允许：祖先先、后代后
- 当 source 存在祖先后代关系且目标不同，若祖先操作在请求序列中早于后代操作，则返回 CATEGORY_OPERATION_ORDER_INVALID
- 若该拆分场景未显式声明 allowDescendantFirstSplit=true，同样返回 CATEGORY_OPERATION_ORDER_INVALID

### 3.3 依赖规则

- dependsOnOperationIds 必须引用批内已存在的 operationId
- operationId 不可依赖自身
- 当前 orderingStrategy=CLIENT_ORDER 时，依赖项必须出现在当前项之前
- 若 dependsOnOperationIds 构成依赖环，返回 CATEGORY_BATCH_DEPENDENCY_CYCLE

### 3.4 并发保护规则

- expectedSourceParentId 是可选字段
- 未传时不参与校验
- 传入时，服务端会校验 source 当前父节点是否与该值一致
- 不一致时返回 CATEGORY_EXPECTED_PARENT_MISMATCH

### 3.5 成环规则

- 若某一步的 targetParentId 在有效树视角下位于 sourceNodeId 的后代中，返回 CATEGORY_EFFECTIVE_TARGET_IN_DESCENDANT
- 该类问题在规划阶段直接拒绝，不进入正式执行

---

## 4. 响应模型

### 4.1 顶层响应字段

| 字段 | 类型 | 说明 |
|---|---|---|
| total | int | 提交操作总数 |
| successCount | int | 成功项数 |
| failureCount | int | 失败项数 |
| atomic | boolean | 是否 atomic |
| dryRun | boolean | 是否 dryRun |
| planningMode | string | 实际采用的规划模式 |
| resolvedOrder | string[] | 服务端最终执行顺序 |
| planningWarnings | string[] | 规划级 warning |
| finalParentMappings | array | 最终有效父节点映射 |
| results | array | 逐项结果 |

### 4.2 finalParentMappings[i] 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| sourceNodeId | UUID | 源节点 |
| finalParentId | UUID | 最终有效父节点；null 表示根 |
| dependsOnResolved | string[] | 已解析依赖 |

### 4.3 results[i] 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| operationId | string | 操作标识 |
| sourceNodeId | UUID | 源节点 |
| targetParentId | UUID | 请求目标父节点 |
| effectiveSourceParentIdBefore | UUID | 该步执行前的有效父节点 |
| effectiveTargetParentId | UUID | 服务端最终采用的目标父节点 |
| success | boolean | 是否成功 |
| code | string | 结果码 |
| message | string | 结果说明 |

### 4.4 dryRun 响应示例

```json
{
  "total": 2,
  "successCount": 2,
  "failureCount": 0,
  "atomic": true,
  "dryRun": true,
  "planningMode": "TOPOLOGY_AWARE",
  "resolvedOrder": [
    "op-b-to-y",
    "op-a-to-x"
  ],
  "planningWarnings": [],
  "finalParentMappings": [
    {
      "sourceNodeId": "9df774b4-1216-4bfa-8a5f-43d35c1f4828",
      "finalParentId": "11111111-1111-1111-1111-111111111111",
      "dependsOnResolved": []
    },
    {
      "sourceNodeId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
      "finalParentId": "22222222-2222-2222-2222-222222222222",
      "dependsOnResolved": [
        "op-b-to-y"
      ]
    }
  ],
  "results": [
    {
      "operationId": "op-b-to-y",
      "sourceNodeId": "9df774b4-1216-4bfa-8a5f-43d35c1f4828",
      "targetParentId": "11111111-1111-1111-1111-111111111111",
      "effectiveSourceParentIdBefore": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
      "effectiveTargetParentId": "11111111-1111-1111-1111-111111111111",
      "success": true,
      "code": null,
      "message": null
    },
    {
      "operationId": "op-a-to-x",
      "sourceNodeId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
      "targetParentId": "22222222-2222-2222-2222-222222222222",
      "effectiveSourceParentIdBefore": null,
      "effectiveTargetParentId": "22222222-2222-2222-2222-222222222222",
      "success": true,
      "code": null,
      "message": null
    }
  ]
}
```

---

## 5. 结果码

| code | 场景 |
|---|---|
| CATEGORY_TOPOLOGY_ACTION_UNSUPPORTED | action 不是 MOVE |
| CATEGORY_NOT_FOUND | 源节点不存在 |
| CATEGORY_TARGET_PARENT_NOT_FOUND | 目标父节点不存在 |
| CATEGORY_TARGET_IS_SELF | 目标父节点就是源节点自身 |
| CATEGORY_DOMAIN_MISMATCH | 业务域不一致 |
| CATEGORY_DELETED | 源或目标已删除 |
| CATEGORY_BATCH_DEPENDENCY_CYCLE | dependsOnOperationIds 构成依赖环 |
| CATEGORY_DEPENDENCY_UNSATISFIED | 前置依赖不存在或依赖项失败 |
| CATEGORY_OPERATION_ORDER_INVALID | dependsOn 顺序非法，或祖先/后代拆分顺序非法 |
| CATEGORY_EXPECTED_PARENT_MISMATCH | expectedSourceParentId 与当前父节点不一致 |
| CATEGORY_EFFECTIVE_TARGET_IN_DESCENDANT | 有效树视角下目标位于源节点后代中 |
| ATOMIC_ROLLBACK | atomic 模式下已执行项被回滚 |
| ATOMIC_ABORTED | atomic 模式下未执行项被中止 |
| INTERNAL_ERROR | 系统异常 |

---

## 6. HTTP 语义

- 200：请求格式合法，服务端已进入规划或执行阶段；即使内部出现逐项失败也返回 200
- 400：请求体非法，或 topology 接口传入 atomic=false 等无法进入规划阶段的错误
- 404：批次级资源不存在且服务端直接拒绝进入后续处理
- 409：atomic 模式下出现整批性冲突

当前实现建议前端使用方式：

1. 先执行 dryRun=true 获取 resolvedOrder、results 和 finalParentMappings
2. 用户确认后再执行 dryRun=false 的正式请求
3. 若前端维护 virtualRelationMap，建议使用 operationId 对齐服务端 results
4. 若需要并发保护，在正式提交时带上 expectedSourceParentId

---

## 7. 与旧接口关系

- POST /api/meta/categories/batch-transfer 继续用于普通批量 MOVE/COPY
- POST /api/meta/categories/batch-transfer/topology 用于需要依赖顺序和拓扑规划的批量 MOVE
- 两者并行存在，避免影响已接入旧接口的页面

---

## 8. 关联接口

- 分类总览文档：category-api.md
- 普通批量移动/复制接口：POST /api/meta/categories/batch-transfer
- 子树查询接口：POST /api/meta/categories/nodes/subtree
- 批量删除接口：POST /api/meta/categories/batch-delete
