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