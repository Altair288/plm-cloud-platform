# 分类批量移动/复制接口设计草案

更新时间：2026-03-17
阶段：设计草案（本阶段不改代码）

---

## 1. 背景

当前前端已初步实现分类批量移动/复制交互，用户可以在批量操作工作区中：

- 选择多个源分类对象。
- 将不同源节点拖拽到不同目标父节点下。
- 统一以 move 或 copy 的方式提交本次批处理。

为支撑该交互，本轮前置能力已补充“子树一次性查询接口”：

- POST /api/meta/categories/nodes/subtree

该接口已解决“懒加载未展开节点无法获取完整后代”的问题。下一步需要设计真正落库的“批量移动/复制接口”，将这些源对象应用到目标分类树中。

---

## 2. 目标

- 提供统一的批量移动/复制接口，支持一次提交多条 source -> target 操作。
- 与前端现有 batch-transfer 交互模型直接对齐。
- 支持 dryRun 预检、atomic 原子批处理、逐项结果返回。
- 明确 move 与 copy 的结构变更规则、冲突校验规则与复制语义。
- 兼容当前分类树、闭包表、版本模型，不破坏既有查询接口。

---

## 3. 设计结论

推荐采用统一接口，而不是拆分两个外部接口。

推荐路径：

- 方法：POST
- 路径：/api/meta/categories/batch-transfer

推荐原因：

- 前端 move/copy 共用同一套 pendingOperations 结构，差异仅在 actionType。
- move 与 copy 在预检阶段的大部分规则相同：
  - 源节点归一化
  - 目标合法性校验
  - 业务域一致性校验
  - dryRun 与 atomic 语义
- 接口统一后，前端接入、结果展示、重试逻辑更简单。
- 后端内部仍可拆为 move executor 与 copy executor，保证实现清晰。

说明：

- 若后续 move 与 copy 的权限、异步策略、参数复杂度明显分叉，可再补：
  - POST /api/meta/categories/batch-move
  - POST /api/meta/categories/batch-copy
- 但首期不建议对外拆分。

---

## 4. 请求模型草案

### 4.1 请求体

```json
{
  "businessDomain": "MATERIAL",
  "action": "COPY",
  "targetParentId": "cae7a410-f951-4780-bad1-3c15ebed4dd4",
  "dryRun": false,
  "atomic": false,
  "operator": "admin",
  "copyOptions": {
    "versionPolicy": "CURRENT_ONLY",
    "codePolicy": "AUTO_SUFFIX",
    "namePolicy": "KEEP",
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

### 4.2 字段定义

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| businessDomain | string | 是 | - | 本次批处理所属业务域 |
| action | string | 是 | - | MOVE 或 COPY |
| targetParentId | UUID | 否 | null | 批次默认目标父节点；可被单项覆盖 |
| dryRun | boolean | 否 | false | true 仅预检，不落库 |
| atomic | boolean | 否 | false | true 表示任一失败整批回滚 |
| operator | string | 否 | null | 操作人 |
| copyOptions | object | 否 | null | 仅 COPY 生效 |
| operations | array | 是 | - | 批处理操作列表 |

operations[i] 字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| clientOperationId | string | 否 | 前端操作流水号，用于结果回填 |
| sourceNodeId | UUID | 是 | 源根节点 ID |
| targetParentId | UUID | 否 | 单项目标父节点，优先级高于批次默认 targetParentId |

copyOptions 字段：

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| versionPolicy | string | 否 | CURRENT_ONLY | 是否复制历史版本 |
| codePolicy | string | 否 | AUTO_SUFFIX | 编码冲突策略 |
| namePolicy | string | 否 | KEEP | 名称冲突策略 |
| defaultStatus | string | 否 | DRAFT | 复制后默认状态 |

补充字段建议：

- COPY 成功后，系统需为新分类记录“复制来源分类”。
- 建议增加字段：`copiedFromCategoryId`，用于标识该新分类由哪个源分类复制而来。

### 4.3 请求约束

- action 只允许单批单动作：MOVE 或 COPY。
- operations 不能为空。
- operations 去重后建议上限 200，与 batch-delete 保持一致。
- 若 operation.targetParentId 未传，则回退使用 request.targetParentId。
- 若两处均为空，表示移动/复制到根节点（是否允许需由业务规则决定）。

---

## 5. source 节点归一化规则

这是接口设计中的核心规则，必须由后端执行，不能只依赖前端处理。

归一化顺序建议：

1. 按 sourceNodeId 去重。
2. 结合目标节点，计算每条操作的最终 targetParentId。
3. 基于闭包表判断 source 之间是否存在祖先后代关系。
4. 按以下规则归一化：

### 5.1 父子同时选中且目标相同

- 保留最上层父节点。
- 子节点操作不再独立执行。
- 子节点结果项标记为：SOURCE_OVERLAP_NORMALIZED。

原因：

- 父节点整棵子树已覆盖其后代，再执行子节点会重复。

### 5.2 父子同时选中且目标不同

- 直接失败。
- 错误码：CATEGORY_SOURCE_OVERLAP_TARGET_CONFLICT。

原因：

- 语义不确定，不应由后端猜测优先级。

### 5.3 兄弟节点同时选中

- 允许。
- 独立执行。

---

## 6. move 语义设计

move 的本质是“对象身份不变，仅调整树位置”。

### 6.1 保持不变的内容

- 源节点及其子树的 id 不变。
- 已有版本链不变。
- 分类编码 code 不变。
- 当前状态不变。

### 6.2 需要更新的结构字段

- parent_def_id
- path
- depth
- full_path_name
- sort_order
- is_leaf
- 闭包表 category_hierarchy

### 6.3 处理规则

1. 更新源根节点 parentId 到目标父节点。
2. 重算源根节点及其整棵子树的 path/depth/full_path_name。
3. 重建“外部祖先 -> 被移动子树”的闭包关系。
4. 保留“子树内部 ancestor-descendant”关系。
5. 原父节点与新父节点的 sort/leaf 重新计算。

### 6.4 排序规则建议

首期建议采用简单稳定规则：

- move 后插入目标父节点子列表末尾。
- 目标父节点下所有子节点重新连续排序。
- 原父节点下剩余子节点重新连续排序。

说明：

- 首期不做 beforeSiblingId、afterSiblingId、targetIndex 等精确定位能力。

---

## 7. copy 语义设计

copy 的本质是“创建一棵新对象树”。

### 7.1 首期复制策略

首期建议：

- 仅复制当前快照。
- 不复制历史版本。
- 每个复制节点生成全新 id。
- 最新版本内容基于源节点 latest version 克隆生成。
- 新节点版本号从 1 开始。
- 每个复制生成的新节点需记录 `copiedFromCategoryId`。

### 7.2 是否复制历史版本

首期结论：不复制历史版本。

原因：

- 历史版本属于旧对象的演化轨迹，不应整体继承到新对象。
- 实现复杂度高，业务收益低。
- 当前前端没有版本选择交互。

versionPolicy 首期只支持：

- CURRENT_ONLY

### 7.2.1 复制来源标识（新增）

评审补充结论：复制后的分类需要能明确告知用户“该分类由哪条分类复制而来”。

建议设计：

- 新增字段：`copiedFromCategoryId`
- 类型：UUID
- 语义：记录当前新分类节点直接复制来源的原分类节点 id

建议落点：

方案 A：落在 `meta_category_def`

- 字段：`copied_from_category_id`
- 优点：
  - 复制来源属于对象身份级元信息，不属于某个版本内容。
  - 查询详情、列表、审计时更容易直接使用。
  - 适合表达“该对象最初由谁复制而来”。

方案 B：落在 `meta_category_version`

- 不推荐作为首选。
- 原因：复制来源不是版本差异字段，而是对象来源字段。

本草案建议：

- 首期采用方案 A，在 `meta_category_def` 新增 `copied_from_category_id`。

复制规则：

- 若根节点 A 被复制生成 A'，则 A'.copiedFromCategoryId = A.id。
- 若 A 的子节点 B 被复制生成 B'，则 B'.copiedFromCategoryId = B.id。
- 即整棵复制树中，每个新节点都记录自己对应的直接来源节点，而不是统一都指向根节点。

接口返回建议：

- 在 copy 成功结果中增加来源映射，便于前端展示。
- 在分类详情接口中，后续可补充返回 `copiedFromCategoryId` 及其来源节点摘要信息。

### 7.3 code 冲突策略

由于现有主标识语义为 businessDomain + code，copy 后若保留原 code，通常会与原节点冲突。

首期建议：

- codePolicy 仅支持 AUTO_SUFFIX。

建议规则：

- 根节点新 code = 原 code + -COPY-001；若冲突则递增。
- 子节点同样按各自原 code 独立派生。
- 响应中返回 codeMappings，供前端展示映射关系。

评审结论：

- code suffix 确认采用当前既有约定。

### 7.4 name 冲突策略

首期建议：

- 默认保持原 name（namePolicy=KEEP）。
- 若后续业务要求同父同名唯一，可再支持 AUTO_SUFFIX。

### 7.5 复制后默认状态

首期建议：

- 新复制节点默认状态固定为 DRAFT。

原因：

- copy 生成的是新对象，不应自动进入 ACTIVE。
- 避免批量复制后直接污染正式分类树。

---

## 8. 冲突校验规则

建议区分“请求级错误”和“结果级错误”。

### 8.1 请求级错误

- action 非法。
- operations 为空。
- businessDomain 缺失。
- copyOptions 非法。
- 同批中 sourceNodeId 数量超过上限。

### 8.2 结果级错误

| code | 场景 |
|---|---|
| CATEGORY_NOT_FOUND | 源节点不存在 |
| CATEGORY_TARGET_PARENT_NOT_FOUND | 目标父节点不存在 |
| CATEGORY_TARGET_IS_SELF | 目标父节点就是源节点自身 |
| CATEGORY_TARGET_IN_DESCENDANT | 目标父节点位于源节点后代中 |
| CATEGORY_DOMAIN_MISMATCH | 源与目标业务域不一致 |
| CATEGORY_DELETED | 源或目标已删除 |
| CATEGORY_TARGET_PARENT_INVALID | 不满足根节点/父节点挂载规则 |
| CATEGORY_CODE_CONFLICT | copy 自动派生 code 失败 |
| CATEGORY_NAME_CONFLICT | 名称策略冲突（预留） |
| CATEGORY_SOURCE_OVERLAP_TARGET_CONFLICT | 父子同时选中且目标不同 |
| SOURCE_OVERLAP_NORMALIZED | 父子同时选中且已归一化 |
| ATOMIC_ROLLBACK | atomic 模式下已执行项被整体回滚 |
| ATOMIC_ABORTED | atomic 模式下未执行项因回滚被中止 |
| INTERNAL_ERROR | 系统内部异常 |

---

## 9. 响应结构草案

```json
{
  "total": 3,
  "successCount": 2,
  "failureCount": 1,
  "normalizedCount": 1,
  "movedCount": 4,
  "copiedCount": 0,
  "atomic": false,
  "dryRun": false,
  "warnings": [
    "1 child operation normalized because ancestor already included"
  ],
  "results": [
    {
      "clientOperationId": "OP_1",
      "sourceNodeId": "A",
      "normalizedSourceNodeId": "A",
      "targetParentId": "T1",
      "action": "MOVE",
      "success": true,
      "affectedNodeCount": 4,
      "movedIds": ["A", "A1", "A2", "A21"],
      "createdRootId": null,
      "createdIds": null,
      "code": null,
      "message": null,
      "warning": null
    },
    {
      "clientOperationId": "OP_2",
      "sourceNodeId": "A1",
      "normalizedSourceNodeId": "A",
      "targetParentId": "T1",
      "action": "MOVE",
      "success": true,
      "affectedNodeCount": 0,
      "movedIds": null,
      "createdRootId": null,
      "createdIds": null,
      "code": "SOURCE_OVERLAP_NORMALIZED",
      "message": "source node skipped because ancestor already covers subtree",
      "warning": ["normalized by ancestor operation"]
    },
    {
      "clientOperationId": "OP_3",
      "sourceNodeId": "B",
      "normalizedSourceNodeId": "B",
      "targetParentId": "B2",
      "action": "MOVE",
      "success": false,
      "affectedNodeCount": 0,
      "movedIds": null,
      "createdRootId": null,
      "createdIds": null,
      "code": "CATEGORY_TARGET_IN_DESCENDANT",
      "message": "target parent is inside source subtree"
    }
  ]
}
```

### 9.1 响应字段说明

| 字段 | 类型 | 说明 |
|---|---|---|
| total | int | 提交的总操作数 |
| successCount | int | 成功项数 |
| failureCount | int | 失败项数 |
| normalizedCount | int | 被归一化跳过的项数 |
| movedCount | int | move 成功累计影响节点数 |
| copiedCount | int | copy 成功累计创建节点数 |
| atomic | boolean | 是否原子模式 |
| dryRun | boolean | 是否预检模式 |
| warnings | array | 批次级 warning |
| results | array | 逐项结果 |

results[i] 关键字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| clientOperationId | string | 对应前端操作流水号 |
| sourceNodeId | UUID | 原始源节点 |
| normalizedSourceNodeId | UUID | 归一化后实际生效的源节点 |
| targetParentId | UUID | 最终目标父节点 |
| action | string | MOVE 或 COPY |
| success | boolean | 是否成功 |
| affectedNodeCount | int | 实际影响节点数 |
| movedIds | UUID[] | move 成功涉及的节点 id 列表 |
| createdRootId | UUID | copy 后新根节点 id |
| createdIds | UUID[] | copy 后整棵新子树所有 id |
| copiedFromCategoryId | UUID | copy 场景下当前结果对应的直接来源分类 id |
| sourceMappings | array | sourceNodeId -> createdNodeId -> copiedFromCategoryId 映射 |
| codeMappings | array | oldCode -> newCode 映射 |
| code | string | 结果码 |
| message | string | 结果说明 |
| warning | array | 项级 warning |

sourceMappings[i] 建议结构：

```json
{
  "sourceNodeId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
  "createdNodeId": "1b139ef2-c3ee-4a9a-9f9d-37608e5270a1",
  "copiedFromCategoryId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1"
}
```

---

## 10. HTTP 语义建议

- 200：请求格式合法，批处理已完成，即使内部存在部分失败也返回 200。
- 400：请求体非法，整批无法开始处理。
- 404：批次默认 targetParentId 不存在等整批性错误。
- 409：atomic=true 且整批冲突导致无法执行。

建议：

- 只要服务端已进入逐项处理，优先返回 200，并把失败原因写入 results。

---

## 11. dryRun 与 atomic 语义

### 11.1 dryRun

- dryRun=true 时：
  - 执行 source 归一化。
  - 执行目标合法性校验。
  - 执行 move/copy 冲突校验。
  - 计算 affectedNodeCount / wouldCreateCount。
  - 不做任何写入。

建议前端交互：

- 点击确认前先走一轮 dryRun=true。
- 用户确认后再发正式请求。

### 11.2 atomic

- atomic=false：
  - 每个 operation 独立事务。
  - 允许部分成功。
- atomic=true：
  - 整批单事务。
  - 任一失败则整体回滚。
  - 已执行项结果码标记为 ATOMIC_ROLLBACK。
  - 未执行项结果码标记为 ATOMIC_ABORTED。

---

## 12. 与现有接口关系

保留现有接口不变：

- GET /api/meta/categories/nodes
- POST /api/meta/categories/nodes/subtree
- POST /api/meta/categories/batch-delete

新增后建议分工：

- 查询树与逐级展开：继续使用懒加载接口。
- 获取完整源对象树：使用 subtree 接口。
- 真正执行移动/复制：使用 batch-transfer 接口。

---

## 13. 前端对接映射建议

前端现有 pendingOperations 已包含：

- sourceNode
- targetKey
- actionType

建议映射：

- actionType -> request.action
- pendingOperations[i].sourceNode.key -> operations[i].sourceNodeId
- pendingOperations[i].targetKey -> operations[i].targetParentId
- 前端当前生成的 OP_xxx -> clientOperationId

说明：

- 前端不应提交 isContextOnly 的上下文节点。
- 真正提交的应是“实体源节点 root id 列表”。

---

## 14. MVP 建议

首期建议一次到位完成以下能力：

- 统一接口：POST /api/meta/categories/batch-transfer
- 单批单动作：MOVE 或 COPY
- dryRun 与 atomic
- source 重叠归一化
- move 整棵子树移动
- copy 仅复制当前快照
- copy 编码自动派生（AUTO_SUFFIX）
- copy 默认状态 DRAFT
- copy 为每个新节点记录 copiedFromCategoryId
- 逐项结果返回
- 关键冲突码一次到位

本轮不纳入：

- 异步大任务版 transfer
- 精确目标插入位置（targetIndex / beforeSiblingId）
- 自定义 rename map / custom code map
- 历史版本整链复制

---

## 15. DoD

- dryRun 与正式执行的冲突判定结果一致。
- atomic=false 时允许部分成功。
- atomic=true 时任一失败整批回滚。
- 父子同时选中且目标相同会被正确归一化。
- 父子同时选中且目标不同会被正确拦截。
- move 后 parent/path/depth/closure/sort/leaf 正确。
- copy 后整棵新子树 id 全新，createdIds 可追踪。
- copy 后 code 自动派生且在响应中回显映射。
- 关键冲突均有稳定错误码。
- 文档可直接指导前后端联调。

---

## 16. 风险点

### 16.1 copy 的 code 派生策略

这是首版最大风险点。

建议：

- 评审确认 AUTO_SUFFIX 可接受。
- 若业务不接受自动改码，则 copy 需要额外的人机交互，不适合本轮直接上线。

评审结论：

- 确认使用当前既有 suffix 约定。

### 16.2 move 的闭包表更新

若 move 只改 parentId 而未同步重建外部 closure，会导致：

- 子树查询错误
- 路径查询错误
- 祖先后代冲突判断错误

因此 move 必须与闭包表重建一起设计与实现。

评审结论：

- 确认 move 与闭包表更新一起设计、一起实现。

### 16.3 overlap 规则不清

父子同时选中但目标不同，必须明确失败，不能按顺序猜测执行。

评审结论：

- 该规则通过评审，作为正式实现约束。
