# 分类拓扑感知批量移动接口升级草案

更新时间：2026-03-18  
阶段：设计草案（本阶段不改代码）

---

## 1. 背景

当前分类批量移动/复制接口已支持：

- 多条 source -> target 批量提交。
- dryRun 预检。
- atomic / non-atomic 执行。
- 父子同目标归一化。
- 父子不同目标冲突拦截。

当前正式接口：

- POST /api/meta/categories/batch-transfer

前端批量移动工作区现已引入 virtualRelationMap 机制，对批内拖拽操作进行“虚拟父子关系”模拟，支持：

- 在一次工作区操作中连续编排多条移动动作。
- 基于虚拟父节点关系实时更新可放置目标。
- 在目标树中预览批内移动后的中间结果。

这说明前端交互目标已从“平铺批处理”升级为“带依赖顺序的树重构编排”。

典型目标场景：

- A -> B，且 B -> C
- 先 B -> Y，再 A -> X
- 一次批处理中完成一整轮嵌套重构，而不是拆成多次提交

---

## 2. 当前接口不足

当前后端实现的核心特征是：

- 基于初始数据库树加载 descendantMap。
- 在真正执行前做整批预校验。
- 基于初始祖先后代关系执行 source overlap 规则。
- 最终只是按请求顺序逐项执行。

因此当前接口存在以下局限：

### 2.1 只理解“初始树”，不理解“批内有效树”

如果某条操作只有在前一条操作生效后才合法，当前接口不会接受。

例如：

- 初始结构：A 包含 B
- 目标：先 B -> Y，再 A -> X

当前接口仍会把 A/B 视为“父子且目标不同”，直接判为冲突。

### 2.2 不支持祖先链拆分重构

当前接口规则：

- 父子同目标：归一化
- 父子不同目标：直接冲突

这对“从下向上拆分”场景过于严格。

### 2.3 不支持批内依赖显式表达

当前 operations 只有：

- clientOperationId
- sourceNodeId
- targetParentId

缺少：

- 前置依赖声明
- 执行顺序语义
- 规划模式标记
- 并发保护字段

### 2.4 环检测发生得太晚

像 A -> B，再 B -> A 这类场景，当前更偏向在逐项执行阶段暴露问题，而不是在批量规划阶段就作为“不可执行计划”直接拒绝。

---

## 3. 目标

新增一套“拓扑感知批量移动”接口语义，以支持前端一次性大批量位置变换。

目标包括：

- 支持批内依赖顺序。
- 支持“后代先、祖先后”的祖先链拆分重构。
- 明确拒绝“祖先先、后代后”的反向拆分。
- 在 dryRun 阶段返回服务端解析后的最终执行计划。
- 在正式执行阶段严格按同一计划落库。
- 保持现有 batch-transfer 接口兼容，不破坏已有前端。

---

## 4. 设计结论

不建议直接修改现有接口语义，建议新增升级版接口。

推荐路径：

- 方法：POST
- 路径：/api/meta/categories/batch-transfer/topology

推荐原因：

- 现有 `/batch-transfer` 语义已经稳定，属于“初始树预检 + 顺序执行”。
- 新需求属于“批内拓扑规划 + 有效树模拟 + 依赖执行”。
- 两者不是参数小差异，而是执行模型不同。
- 新旧接口并行可避免误伤已接入调用方。

---

## 5. 首期范围

首期确认只支持 MOVE，不把 COPY 一起纳入。

原因：

- MOVE 只涉及现有节点重挂载，拓扑问题相对清晰。
- COPY 若允许批内依赖，后续操作可能引用“本批新生成节点”，需要引入临时引用语义。
- COPY 的 code 生成、id 分配、版本克隆会显著放大复杂度。

首期纳入：

- 拓扑感知 MOVE
- dryRun
- atomic=true 的正式执行
- client order + dependsOn 并存
- 祖先链“后代先、祖先后”拆分
- 批量环检测

首期不纳入：

- COPY
- non-atomic
- beforeSiblingId / afterSiblingId 精确排序
- 引用本批新建节点作为目标

---

## 6. 与前端 virtualRelationMap 对齐

前端当前已做的事情：

- 用 virtualRelationMap 维护批内有效父节点。
- 对拖拽目标执行实时禁用校验。
- 对目标树执行批内预览重放。

升级后的后端应与此前端规则对齐：

### 6.1 允许的批内祖先链拆分

- 允许：先 B -> Y，再 A -> X

成立前提：

- B 是 A 的后代
- B 的移动先于 A
- A 的执行时，B 已不再属于 A 的有效子树

### 6.2 不允许的批内祖先链拆分

- 不允许：先 A -> X，再 B -> Y

原因：

- 祖先先移动后，后代的原从属关系已被打断
- 该语义易产生理解分裂，且前端已明确拟禁止

### 6.3 不允许批内成环

- 不允许：A -> B，再 B -> A
- 不允许：A -> B，B -> C，C -> A

这类场景必须在规划阶段直接拒绝，不能留到执行期才暴露。

---

## 7. 请求模型草案

### 7.1 请求体示例

```json
{
  "businessDomain": "MATERIAL",
  "action": "MOVE",
  "dryRun": false,
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

### 7.2 顶层字段定义

| 字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | ---: | --- | --- |
| businessDomain | string | 是 | - | 本次批处理所属业务域 |
| action | string | 是 | MOVE | 首期固定 MOVE |
| dryRun | boolean | 否 | false | true 仅规划和校验，不落库 |
| atomic | boolean | 否 | true | 首期建议固定 true |
| operator | string | 否 | null | 操作人 |
| planningMode | string | 否 | TOPOLOGY_AWARE | 规划模式标识 |
| orderingStrategy | string | 否 | CLIENT_ORDER | 执行顺序解析策略 |
| strictDependencyValidation | boolean | 否 | true | 是否严格验证依赖与顺序 |
| operations | array | 是 | - | 批量移动操作列表 |

### 7.3 operations[i] 字段定义

| 字段 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | ---: | --- | --- |
| operationId | string | 是 | - | 批内唯一操作标识 |
| sourceNodeId | UUID | 是 | - | 源节点 ID |
| targetParentId | UUID | 否 | null | 目标父节点；null 表示移动到根 |
| dependsOnOperationIds | string[] | 否 | [] | 显式前置依赖 |
| allowDescendantFirstSplit | boolean | 否 | false | 是否允许后代先拆出后再移动祖先 |
| expectedSourceParentId | UUID | 否 | null | 客户端规划时看到的原父节点，用于并发保护 |

### 7.4 orderingStrategy 建议值

- CLIENT_ORDER
  - 按前端提交顺序执行
  - 若 dependsOn 与提交顺序冲突，则报错

- TOPOLOGICAL_BOTTOM_UP
  - 服务端可在满足显式依赖的前提下，自动优先后代、再祖先
  - 首期可先不实现，仅保留枚举位

首期确认：

- 实现 CLIENT_ORDER
- 同时支持 dependsOnOperationIds
- 服务端必须校验 dependsOn 与提交顺序一致

---

## 8. 执行语义草案

### 8.1 不再基于初始树一次性判死

升级后的规划流程不再复用当前 `/batch-transfer` 的 source overlap 逻辑。

服务端应：

1. 加载当前树快照。
2. 构建批内有效父子关系映射 `effectiveParentMap`。
3. 按顺序逐条模拟操作。
4. 每模拟一步后更新 effectiveParentMap。
5. 后续操作基于更新后的有效树继续判定。

### 8.2 祖先链拆分规则

当两条操作 source 存在祖先后代关系且目标不同：

- 后代先、祖先后：允许
- 祖先先、后代后：拒绝

判定原则：

- 若前一条执行后，后代已脱离祖先的有效子树，则允许祖先后续继续移动
- 若祖先先动，导致后代操作语义依赖祖先移动后的中间态，则视为不支持

### 8.3 环检测规则

在每一步 effectiveParentMap 更新后执行环检测：

- 若 source 最终可通过 parent 链回到自身，直接拒绝
- 不进入正式执行阶段

### 8.4 原子执行规则

首期确认默认 atomic=true：

- dryRun 返回 resolvedPlan
- 正式执行必须按 resolvedPlan 顺序落库
- 中途任何一步失败，整批回滚

原因：

- 大批量树重构的核心诉求是“整轮结构变换一致成功”
- non-atomic 会使 effective plan 与真实树分叉，前端难以恢复

补充结论：

- 新接口默认 atomic=true
- 旧接口后续设计方向也统一默认 atomic=true
- 首期 topology 接口不提供 non-atomic 模式

---

## 9. dryRun 响应增强草案

### 9.1 顶层字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| total | int | 提交操作总数 |
| successCount | int | 规划成功项数 |
| failureCount | int | 规划失败项数 |
| atomic | boolean | 是否 atomic |
| dryRun | boolean | 是否 dryRun |
| planningMode | string | 实际采用的规划模式 |
| resolvedOrder | string[] | 服务端最终执行顺序（operationId 列表） |
| planningWarnings | string[] | 规划级 warning |
| finalParentMappings | array | 最终有效父节点映射 |
| results | array | 逐项规划结果 |

### 9.2 finalParentMappings[i]

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| sourceNodeId | UUID | 源节点 |
| finalParentId | UUID | 最终有效父节点；null 表示根 |
| dependsOnResolved | string[] | 已解析的前置依赖 |

### 9.3 results[i] 必须返回字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| operationId | string | 操作标识 |
| sourceNodeId | UUID | 源节点 |
| targetParentId | UUID | 请求目标父节点 |
| effectiveSourceParentIdBefore | UUID | 该步执行前的有效父节点 |
| effectiveTargetParentId | UUID | 服务端最终采用的目标父节点 |
| success | boolean | 是否规划成功 |
| code | string | 结果码 |
| message | string | 说明 |

---

## 10. 错误码草案

| code | 场景 |
| --- | --- |
| CATEGORY_BATCH_DEPENDENCY_CYCLE | dependsOnOperationIds 构成依赖环 |
| CATEGORY_BATCH_TREE_CYCLE | 批内有效树形成结构环 |
| CATEGORY_OPERATION_ORDER_INVALID | 祖先链拆分顺序非法 |
| CATEGORY_DEPENDENCY_UNSATISFIED | 前置依赖不存在或未满足 |
| CATEGORY_EFFECTIVE_TARGET_IN_DESCENDANT | 在有效树视角下目标位于源节点后代中 |
| CATEGORY_EXPECTED_PARENT_MISMATCH | 正式执行时原父节点与 dryRun 快照不一致 |
| CATEGORY_TOPOLOGY_ACTION_UNSUPPORTED | 当前 planningMode 不支持该 action |
| CATEGORY_TARGET_PARENT_NOT_FOUND | 目标父节点不存在 |
| CATEGORY_NOT_FOUND | 源节点不存在 |
| CATEGORY_DOMAIN_MISMATCH | 业务域不一致 |
| CATEGORY_DELETED | 源或目标已删除 |

---

## 11. 推荐服务端流程

### 11.1 dryRun

1. 校验请求格式。
2. 加载 source、target 及必要祖先链。
3. 构建 operationId 索引。
4. 验证 dependsOn 引用。
5. 解析执行顺序。
6. 基于 effectiveParentMap 逐条模拟。
7. 每步执行：
   - 校验目标存在与业务域一致
   - 校验祖先链拆分顺序
   - 校验不会成环
   - 更新 effectiveParentMap
8. 输出 resolvedPlan。

dryRun 返回要求：

- 必须返回逐项 results，供前端直接展示给用户
- 必须返回 resolvedOrder，供前端与 virtualRelationMap 结果对账
- 必须返回 finalParentMappings，供前端展示最终落点

### 11.2 正式执行

1. 重新加载本批 source 最新状态。
2. 校验 expectedSourceParentId 是否匹配。
3. 开启单事务。
4. 按 resolvedOrder 顺序逐条执行 move。
5. 完成 path/depth/full_path_name/closure/sort/leaf 更新。
6. 成功提交；任一异常整批回滚。

---

## 12. 与现有接口关系

### 12.1 现有接口继续保留的场景

- 普通批量 MOVE
- 普通批量 COPY
- 无祖先链拆分需求的批处理
- 仅需“平铺 source -> target”提交的前端页面

### 12.2 新接口适用场景

- 一次性大批量位置变换
- 连环移动
- 嵌套重构
- 前端已做虚拟树规划并希望后端严格对齐

---

## 13. 评审结论

本轮评审结论如下：

1. 首期只支持 MOVE，不考虑 COPY。
2. 祖先链拆分百分百只允许“后代先、祖先后”。
3. 新旧接口默认都为 atomic=true。
4. 首期必须支持 dependsOnOperationIds。
5. dryRun 必须返回结果，供前端直接告知用户。

---

## 14. 本草案结论

结论建议如下：

- 保留现有 `/api/meta/categories/batch-transfer` 不动。
- 新增 `/api/meta/categories/batch-transfer/topology` 作为升级版接口。
- 首期仅支持 MOVE。
- 引入 `operationId / dependsOnOperationIds / expectedSourceParentId / planningMode`。
- 服务端从“初始树预检”升级为“批内有效树模拟 + 拓扑感知执行”。
- 明确支持：先 B -> Y，再 A -> X。
- 明确不支持：先 A -> X，再 B -> Y。
- 明确不支持：A -> B，再 B -> A。
- dryRun 必须返回逐项结果、resolvedOrder 与 finalParentMappings。
- 新旧接口默认 atomic=true。
