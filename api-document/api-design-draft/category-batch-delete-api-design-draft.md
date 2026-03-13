# 分类批量删除接口设计草案

更新时间：2026-03-13
阶段：设计草案（本阶段不改代码）

---

## 1. 背景

当前已实现删除接口为单节点入口：

- DELETE /api/meta/categories/{id}

该接口支持级联删除（cascade=true&confirm=true），但业务在“批量选中多个节点删除”场景下需要一次请求完成多目标删除，并返回逐项处理结果。

---

## 2. 目标

- 提供批量删除入口，支持一次提交多个分类节点。
- 与现有单删语义保持一致（软删除、可选级联、冲突校验）。
- 返回逐项结果，便于前端展示成功/失败明细。
- 保持向后兼容，不影响现有单删接口。

---

## 3. 接口方案（推荐）

### 3.1 路由与方法

- 方法：POST
- 路径：/api/meta/categories/batch-delete

说明：

- 批量删除属于“动作型操作”，采用动词后缀路由更清晰。
- 避免在 DELETE 方法中携带复杂 body 的兼容性问题。

### 3.2 请求体

```json
{
  "ids": [
    "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
    "cae7a410-f951-4780-bad1-3c15ebed4dd4"
  ],
  "cascade": false,
  "confirm": false,
  "operator": "admin",
  "atomic": false,
  "dryRun": false
}
```

字段说明：

- ids：必填，去重后处理，建议上限 200（可配置）。
- cascade：是否允许级联删除子树。
- confirm：是否确认删除（用于防误删）。
- operator：操作人（可选，未传默认 system）。
- atomic：是否要求全有或全无。
  - false：逐条处理，部分成功允许提交。
  - true：任意一条失败则整体回滚。
- dryRun：是否仅校验不落库。

### 3.3 响应体

```json
{
  "total": 2,
  "successCount": 1,
  "failureCount": 1,
  "deletedCount": 4,
  "atomic": false,
  "results": [
    {
      "id": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
      "success": true,
      "deletedCount": 4
    },
    {
      "id": "cae7a410-f951-4780-bad1-3c15ebed4dd4",
      "success": false,
      "code": "CATEGORY_HAS_CHILDREN",
      "message": "category has children, please confirm cascade deletion with cascade=true&confirm=true"
    }
  ]
}
```

返回语义：

- deletedCount：所有成功项累计影响节点数（级联时可能大于 successCount）。
- results：逐项结果，前端可直接展示列表。

---

## 4. 处理规则

### 4.1 通用校验

- ids 不能为空。
- ids 数量不能超过上限。
- ids 存在非法 UUID 时直接返回 400。
- ids 去重后为空返回 400。

### 4.2 删除语义

- 与单删保持一致：软删除（status = deleted）。
- cascade=false 且目标有子节点：
  - 返回 CATEGORY_HAS_CHILDREN。
- cascade=true 但 confirm=false：
  - 返回 CATEGORY_HAS_CHILDREN（保持同一确认机制）。

### 4.3 幂等性

- 已删除节点视为可幂等处理：
  - 建议返回 success=true, deletedCount=0, code=ALREADY_DELETED。
- 同一 id 在同次请求重复出现：按去重后一次处理。

### 4.4 原子策略

- atomic=false（默认）：
  - 每个 id 独立执行，失败不影响其他项。
  - HTTP 状态返回 200，失败细节在 results。
- atomic=true：
  - 任意一项失败则整体回滚。
  - HTTP 状态返回 409（或 400，按统一错误规范）。
  - results 可返回首个失败原因，或完整预检失败清单。

### 4.5 dryRun 策略

- dryRun=true：
  - 执行所有校验与影响范围计算，不实际写入。
  - 返回结果中标记 wouldDeleteCount（可选扩展字段）。

---

## 5. 与现有接口关系

保留现有单删接口不变：

- DELETE /api/meta/categories/{id}

新增批量入口后的调用建议：

- 单条删除：继续用单删接口。
- 多条删除：使用 batch-delete。
- 前端若只维护一个入口，也可统一走 batch-delete（ids 长度为 1）。

---

## 6. 事务与实现建议

### 6.1 Service 设计

建议新增服务方法：

- batchDelete(List<UUID> ids, boolean cascade, boolean confirm, String operator, boolean atomic, boolean dryRun)

内部可复用现有 delete(UUID id, ...) 逻辑。

### 6.2 事务边界

- atomic=false：
  - 外层不包大事务，每个 id 子事务执行，避免大事务长时间锁表。
- atomic=true：
  - 外层单事务，失败即回滚。

### 6.3 性能与并发

- 单次 ids 建议上限 200。
- 对大子树级联删除需做耗时监控。
- 可按需增加异步任务版接口（后续阶段）。

---

## 7. 错误码建议

- INVALID_ARGUMENT：参数不合法。
- CATEGORY_NOT_FOUND：节点不存在。
- CATEGORY_HAS_CHILDREN：存在子节点但未确认级联。
- ALREADY_DELETED：节点已删除（幂等提示码）。
- BATCH_DELETE_PARTIAL_SUCCESS：部分成功（可选业务码）。

---

## 8. 前端交互建议

- 默认先 dryRun=true 预检查并展示影响范围。
- 用户二次确认后发起正式删除（dryRun=false）。
- 结果面板展示：成功数、失败数、失败原因与可重试项。

---

## 9. DoD（评审通过标准）

- 接口契约明确（请求/响应/错误码）。
- 原子与非原子策略明确。
- 与单删语义一致且兼容。
- 能满足“批量选中删除”业务场景。
- 文档可直接指导后端与前端联调。
