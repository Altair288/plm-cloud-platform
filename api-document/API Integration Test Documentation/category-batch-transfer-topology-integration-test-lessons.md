# 分类拓扑感知批量移动接口集成测试经验总结

更新时间：2026-03-18
适用模块：plm-attribute-service
适用接口：POST /api/meta/categories/batch-transfer/topology

## 1. 背景

本次补充的是分类 topology 批量移动接口的执行期问题排查与测试经验。

本轮重点不是普通批量 MOVE / COPY，而是拓扑感知 MOVE 场景：

- 批内存在 dependsOnOperationIds
- 允许“后代先、祖先后”的祖先链拆分
- dryRun 先返回 resolvedOrder 和 finalParentMappings
- 正式执行阶段在单事务内按同一计划顺序落库

本次实际压测暴露的问题来自一个真实业务链路：

1. 先把两个节点分别移动到目标子树内部不同分支
2. 再把包含这两个目标分支的父分类整体移动到另一棵树下
3. dryRun 通过，正式执行有 SQL，但最终库里没有变化
4. 前端最开始也没有拿到足够明确的失败信息

---

## 2. 问题现象

### 2.1 典型现象

- dryRun 返回 successCount 正常
- resolvedOrder 与前端 virtualRelationMap 对账也正常
- 点击确认移动后，后端能看到 SQL 执行
- 最终数据库结果没有变化
- 现象上看像“执行了但提交时回滚了”

### 2.2 这类场景为什么重要

这不是一个边缘 case，而是 topology 接口的核心价值场景之一：

- 先把节点从原祖先树中拆出来
- 再把原祖先整棵树搬迁到新位置

如果这类链式重构不能稳定提交，那么 topology 接口就只剩 dryRun 价值，无法真正承载前端工作区的批量重排。

---

## 3. 最终根因分析

本次问题最终不是单一原因，而是执行链上连续暴露了三层问题。

### 3.1 第一层：事务提交期失败没有稳定映射成逐项失败结果

现象：

- 正式执行阶段事务回滚了
- 但 failedItem 不一定能在循环体内被准确记录
- 导致 response 可能不够明确，前端只能看到“像是没成功”，但失败归因不稳定

原因：

- topology 正式执行在单事务内逐条 move
- 如果异常发生在 flush / commit 边界，而不是某条 operation 的 try-catch 内部
- failedAt 可能仍为 null

结论：

- topology 响应构造不能只依赖 failedAt 是否已填充
- 提交期异常也必须映射为可识别的失败项和 failureCount

### 3.2 第二层：closure 表批量删除后，持久化上下文与数据库状态脱节

现象：

- 正式执行期出现 category_hierarchy 相关异常
- 典型报错是批量更新返回行数异常：
  - Batch update returned unexpected row count from update [0]

原因：

- `deleteExternalLinksForDescendants(...)` 是 JPQL bulk delete
- 这类删除会直接打到数据库，不会自动同步当前持久化上下文里的实体状态
- 同一事务后续又对同主键 closure 记录执行 `saveAll(...)`
- Hibernate 可能误判这些 closure 行应走 update 而不是 insert
- 最终在数据库里找不到对应旧行，触发 row count = 0

结论：

- bulk delete 之后必须显式 flush / clear，避免 persistence context 持有过期的 closure 状态

### 3.3 第三层：clear 之后旧父节点 / 子树节点对象变成 detached proxy

现象：

- 修掉 row count 问题后，执行期又出现：
  - Could not initialize proxy ... no session

原因：

- 为了解决 bulk delete 与上下文脱节问题，repository 删除语句增加了 clearAutomatically=true
- 这会把当前事务里的旧实体对象一起清掉
- 后续 `rebuildSubtreeClosure(...)`、`refreshParentLeafIfNeeded(...)` 仍继续使用先前持有的 oldParent / newParent / subtree 对象
- 这些对象已经不再处于可安全懒加载的 managed 状态

结论：

- clear 之后，后续 closure 重建和 leaf 刷新必须重新拿 managed reference / managed entity
- 不能继续直接使用 clear 前缓存下来的实体对象

---

## 4. 本次修复策略

### 4.1 补强 topology 执行失败回包

修复目标：

- 即使异常发生在事务提交边界，也要稳定返回 failureCount > 0
- 前端能明确看到 ATOMIC_ROLLBACK / ATOMIC_ABORTED / INTERNAL_ERROR 等结果

处理方式：

- 若 failedAt 为空，则回退到：
  1. 最后一个已执行项
  2. 或首个计划项
- 确保 `buildTopologyResponse(...)` 总能拿到可定位的 failedItem

### 4.2 修复 closure bulk delete 后的持久化上下文问题

处理方式：

- 在 `CategoryHierarchyRepository.deleteExternalLinksForDescendants(...)` 上使用：
  - `flushAutomatically = true`
  - `clearAutomatically = true`

收益：

- 删除外链 closure 后，当前事务不会再持有过期的 category_hierarchy 状态

### 4.3 closure 重建阶段改用重新获取的 managed reference

处理方式：

- `rebuildSubtreeClosure(...)` 不再直接把 clear 前的 subtree 节点对象塞回 `CategoryHierarchy`
- 改为按 id 重新获取 managed reference：
  - `defRepository.getReferenceById(...)`

收益：

- 避免 detached proxy 参与 closure 持久化

### 4.4 刷新父节点 leaf 状态时重新加载 managed parent

处理方式：

- `refreshParentLeafIfNeeded(...)` 内部先按 id 重新 `findById(...)`
- 再做 deleted 判断和 leaf 更新

收益：

- 避免访问 oldParent / newParent 的懒加载字段时出现 no session

---

## 5. 本次新增测试

### 5.1 新增复杂 topology dryRun 用例

覆盖场景：

1. 先把 rootA 下的 sibling C 挂到 targetX 的子节点下
2. 再把 rootA 下的 child B 挂到 targetY 的子节点下
3. 最后把 rootA 整棵树挂到 targetX 下
4. 验证 resolvedOrder 与 finalParentMappings 正常生成

目标：

- 确认服务端 effectiveParentMap 规划能承载“先导入子树分支，再整体搬祖先树”的复杂链路

### 5.2 新增复杂 topology execute 用例

覆盖场景：

- 与上面 dryRun 相同，但要求真实提交
- 额外验证：
  - B 与 C 的叶子节点仍保留在各自子树下
  - 根节点与被拆出的子节点最终父子关系都正确

目标：

- 确认 topology 接口不只是 dryRun 可过，而是正式执行也能稳定提交

### 5.3 旧 topology execute 用例回归

除了新复杂用例，本次还验证了原有简单 execute 场景：

- 先 B -> Y，再 A -> X

这一步很重要，因为 closure / parent leaf 刷新修复涉及共享底层逻辑，必须同时确认没有把原 execute 主路径带坏。

---

## 6. 最终测试结果

本轮修复后，结果如下：

- `MetaCategoryCrudServiceBatchTransferTopologyIT`：9 通过，0 失败
- `MetaCategoryCrudServiceBatchTransferIT` + `MetaCategoryCrudServiceBatchTransferTopologyIT` 联合回归：14 通过，0 失败

这说明：

- topology 新增复杂链式移动场景已能稳定提交
- 普通 batch-transfer MOVE / COPY 旧能力未被此次修复带坏

---

## 7. PowerShell 下的稳定测试命令

本轮继续验证了 PowerShell 下 Maven `-D` 参数的兼容写法。

推荐命令：

```bash
mvn --% -pl plm-attribute-service -am clean test -Dtest=MetaCategoryCrudServiceBatchTransferTopologyIT -Dsurefire.failIfNoSpecifiedTests=false
```

说明：

- `--%` 可避免 pwsh 把 `-Dxxx` 错误拆分
- `-pl plm-attribute-service -am` 可保证依赖模块一起编译
- `clean test` 可避免 DTO / repository / entity 联动改动命中陈旧编译产物

如果需要同时回归普通 batch-transfer 与 topology：

```bash
mvn --% -pl plm-attribute-service -am clean test -Dtest=MetaCategoryCrudServiceBatchTransferIT,MetaCategoryCrudServiceBatchTransferTopologyIT -Dsurefire.failIfNoSpecifiedTests=false
```

---

## 8. 最终经验结论

1. topology dryRun 通过，不代表正式提交一定能过，必须补 execute 集成测试。
2. closure 表上的 bulk delete 是高风险点，若不处理持久化上下文同步，极易在提交期出错。
3. 一旦使用 clearAutomatically 清理上下文，后续所有 oldParent / newParent / subtree 引用都要重新拿 managed 对象。
4. topology 接口的失败回包不能只依赖循环内部捕获的 failedAt，提交期异常也必须能映射成明确的 failure result。
5. 复杂链式移动测试必须同时校验：
   - successCount / failureCount
   - 最终 parent 关系
   - 子树内部叶子节点从属关系
   - 普通 topology execute 主路径没有回归

---

## 9. 可复用检查清单

- [ ] PowerShell 下使用 `mvn --%`
- [ ] 运行 topology 测试时带 `-pl plm-attribute-service -am clean test`
- [ ] execute 测试必须覆盖真实事务提交，不只测 dryRun
- [ ] closure bulk delete 后已处理 persistence context 同步
- [ ] clear 后的实体引用不再直接复用为 managed entity
- [ ] topology 回包在 commit 边界异常下也能返回明确失败项
- [ ] 至少保留一条复杂链式移动 execute 用例做回归