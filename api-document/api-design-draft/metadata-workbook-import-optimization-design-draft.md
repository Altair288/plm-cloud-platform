# 元数据工作簿导入优化草案

更新时间：2026-04-01
阶段：优化草案（独立于现有导入草案，不替换原文档）

---

## 1. 背景

当前元数据工作簿导入已经具备以下能力：

- 支持 dry-run 解析与校验。
- 支持正式导入任务。
- 支持任务状态查询、分页日志查询与 SSE 进度流。
- 导入顺序按业务模型执行：分类 -> 属性 -> 枚举值。

但在大工作簿场景下，现有实现暴露出两个核心问题：

- dry-run 仍为同步接口，前端需要等待完整预览返回，等待时间长时容易触发超时或出现长时间空白页面。
- dry-run 与 import 的内部处理仍存在较明显的逐行校验、逐行查库、逐行规则计算特征，整体吞吐不理想。

本草案目标不是推翻现有 workbook 导入体系，而是在保持现有业务顺序与核心模型不变的前提下，给出一版可分阶段落地的优化方案。

---

## 2. 当前问题总结

### 2.1 前端体验问题

- dry-run 接口为同步 multipart 上传接口，前端必须等待整个识别与校验流程执行完毕才能拿到结果。
- 当工作簿较大、编码规则计算较多、数据库存量较大时，前端等待时间过长。
- 前端在 dry-run 阶段没有连续进度反馈，只能等待最终响应，容易误判为卡死或超时。

### 2.2 服务端性能问题

- 分类、属性、枚举值均存在逐条处理、逐条校验、逐条查库的情况。
- 规则预览与编码生成存在按行调用倾向，缺乏同 scope 的批量保留号段机制。
- import 执行前仍需要做较多 plan 计算，导致 dry-run 与正式导入之间有重复工作。
- 闭包表刷新位于整体导入链路末尾，若直接全量重建，会进一步拉长任务耗时。

### 2.3 架构层问题

- 当前只有正式导入具备完整 job 化能力，dry-run 仍是同步返回。
- 导入预览结果与执行计划没有形成稳定的持久化快照，正式导入阶段难以完全复用 dry-run 成果。
- 事务边界偏粗，若未来继续扩大批量写入规模，整批长事务的风险会显著上升。

---

## 3. 优化目标

本期优化重点限定为两项：

1. 优化导入识别与执行速度。
2. 在 dry-run 和 import 两个阶段都向前端返回连续 progress，避免空白等待。

补充目标：

- 保持业务导入顺序不变：分类 -> 属性 -> 枚举值。
- 保持现有 workbook 模板结构不变。
- 保持现有编码模式、重复策略、日志模型的业务语义不变。
- 优先采用可渐进演进方案，避免一次性重写所有导入逻辑。

---

## 4. 优化原则

### 4.1 保持阶段顺序，不保持逐行串行

导入顺序仍然必须是：

1. 分类
2. 属性
3. 枚举值

但每个阶段内部不应继续以“逐条查库 + 逐条判定 + 逐条规则调用”为主要执行方式，而应转换为“批量预加载 + 内存索引 + 批量写入”。

### 4.2 dry-run 与 import 都要 job 化

本期不再只对正式导入提供进度模型。

dry-run 也应进入统一 job 生命周期：

- 创建任务
- 异步解析
- 进度查询
- 结果查询
- SSE 订阅

### 4.3 先产出执行计划，再执行

dry-run 的核心产物不应只是 preview JSON，还应包含可被正式导入直接消费的 plan snapshot。

正式导入阶段应尽量复用 dry-run 产出的：

- resolvedFinalCode
- resolvedAction
- existing record identity
- oldHash/newHash
- shouldWrite
- dependency order

### 4.4 优先缩短前端等待时间，再逐步压缩总耗时

前端感知问题最严重的是“没有反馈”。

因此第一优先级是：

- 把 dry-run 异步化。
- 给前端持续进度和日志。

在此基础上再持续优化数据库访问、批量编码生成和批量写入效率。

---

## 5. 推荐优化流水线

建议将现有流程统一抽象为以下 7 个阶段。

### 5.1 Parse Workbook

读取全部 sheet，将模板输入列一次性解析为内存 DTO 列表：

- CategoryWorkbookRowDto
- AttributeWorkbookRowDto
- EnumWorkbookRowDto

要求：

- 只读取真实输入列。
- 忽略辅助列、公式列、隐藏列与参考字典列。
- 解析阶段不做逐条数据库查询。

### 5.2 Preload Existing Data

根据 workbook 中出现的业务域、分类编码、属性编码、枚举编码，一次性预加载存量数据并建立内存索引：

- 分类定义与最新版本
- 属性定义与最新版本
- LOV 定义与最新版本
- 必要的 path/code/key/optionCode 索引
- 当前生效的规则集与规则版本

典型索引示例：

- categoryByBusinessDomainAndCode
- categoryByBusinessDomainAndPath
- attributeByBusinessDomainAndKey
- attributeByBusinessDomainAndCategoryCodeAndField
- enumByBusinessDomainAndOptionCode

目标：

- 将后续大部分 exists/conflict/update 判定从数据库查询转为内存查表。

### 5.3 Validate And Normalize

在内存中一次性完成：

- sheet 内字段格式校验
- 分类树父子引用与路径校验
- 属性对分类引用校验
- 枚举值对属性引用校验
- 批次内唯一性校验
- 与数据库存量数据的冲突判定
- manual/auto 模式下的最终编码推导
- duplicate policy 下的最终 action 判定
- 结构 hash 与值 hash 计算

输出：

- preview result
- error list
- warning list
- normalized execution plan

### 5.4 Build Execution Plan

为正式导入生成可执行计划，计划项至少包含：

- source row info
- entity type
- businessDomain
- resolvedFinalCode
- resolvedAction
- existingId
- newHash
- oldHash
- shouldWrite
- dependency pointers

建议将计划拆为三组有序集合：

- categoryPlan
- attributePlan
- enumPlan

### 5.5 Persist Plan Snapshot

dry-run 完成后，把预览与执行计划固化为 plan snapshot。

建议至少持久化以下内容：

- dryRunJobId
- importSessionId
- normalized options
- preview summary
- categoryPlan
- attributePlan
- enumPlan
- createdAt / expiresAt

目标：

- 正式导入不再重新解析 Excel。
- 正式导入不再重新执行整套重型校验。

### 5.6 Execute Plan By Stage

正式导入只消费 plan snapshot，并按固定阶段执行：

1. Insert / Update Categories
2. Insert / Update Attributes
3. Insert / Update LOVs

每个阶段内部采用批量 SQL 或最少 round-trip 的 service 执行模式。

### 5.7 Post Process

正式导入后执行：

- 闭包表刷新
- 相关缓存清理
- 导入汇总生成
- job 结束事件

当前实现对齐补充：

- workbook import 分类创建/改挂父节点已经复用分类 CRUD 的增量闭包维护逻辑。
- 因此现阶段 FINALIZING 不应再默认触发一次全量 rebuild closure。
- FINALIZING 更适合作为策略记录、缓存清理、汇总审计与可选补偿阶段。

---

## 6. 关于你提出的初步方案的适配性判断

用户提出的方案如下：

1. Read All Sheets -> 存入内存 DTO 列表。
2. Validate Tree -> 校验引用、格式、编码规则。
3. Prepare Batch -> 准备 SQL 批量数据，计算 Hash。
4. Execute SQL (In One Transaction)。
5. Post-Process -> 刷新闭包表、清理缓存。

结论：整体方向正确，但建议做三处调整。

### 6.1 增加预加载阶段

在 Validate Tree 之前必须增加 Preload Existing Data。

原因：

- 否则 Validate Tree 仍会落回逐条查库。
- 预加载是批量判断 create/update/conflict 的基础。

### 6.2 Execute SQL 不建议默认使用一个超长事务

“In One Transaction” 只适合中小数据量，不适合作为默认方案。

原因：

- 事务时间过长。
- 锁持有时间长。
- 回滚代价高。
- 闭包表刷新会进一步放大事务压力。

建议：

- 默认模式：分类、属性、枚举值、后处理分阶段事务执行。
- 严格原子模式：后续如确有需要，可通过 staging + merge 方式支持，而不是将整个导入链路包进一个事务。

### 6.3 Prepare Batch 不只准备 SQL，还要准备 plan snapshot

Prepare Batch 的核心不应只是“组装 SQL 参数”，还应生成稳定的可复用执行计划。

否则：

- dry-run 与 import 之间仍会有重复计算。
- 前端确认导入后，服务端仍要再算一次 action 与 hash。

---

## 7. 速度优化建议

### 7.1 优先级 P0：dry-run 异步化

这是最直接改善前端体验的优化点。

即使单次 dry-run 仍需要 20 到 40 秒，只要前端可以立即拿到 jobId 并看到进度，体验就会明显改善。

### 7.2 优先级 P0：数据库预加载索引化

建议把以下逐条查询改为批量查询 + 内存索引：

- 分类是否存在
- 父分类是否存在
- 属性 key 是否存在
- 属性字段是否冲突
- 枚举值 code 是否存在
- 枚举值所属属性是否存在

### 7.3 优先级 P1：编码规则批量保留号段

当前 auto 模式下编码生成的主要优化方向不是改公式，而是改调用方式。

建议规则服务增加批量保留号段接口，例如：

- reserveCategoryCodes(businessDomain, parentCode, count)
- reserveAttributeCodes(businessDomain, categoryCode, count)
- reserveEnumCodes(businessDomain, attributeCode, count)

这样可将每组 scope 内的 N 次 generate 调用压缩为 1 次 reserve 调用。

### 7.4 优先级 P1：hash 与 action 在 dry-run 阶段完成

当前 import 阶段仍有较多“再判断一次”的空间。

建议在 dry-run 完成时直接得到：

- CREATE
- UPDATE_HASH_CHANGED
- KEEP_EXISTING
- CONFLICT
- SKIP_NO_CHANGE

正式导入阶段只执行，不重新推导。

当前实现对齐补充：

- preview 仍保留 CREATE / UPDATE / KEEP_EXISTING / CONFLICT / SKIP_NO_CHANGE 这组用户可理解动作。
- execution plan 内部建议继续收紧为 entity-specific write mode，例如 CATEGORY_UPDATE / ATTRIBUTE_UPDATE / ENUM_UPDATE，避免 import 执行层再根据通用 UPDATE 猜测实体语义。
- import 应优先消费 write mode，resolvedAction 主要保留给 preview 与日志。

### 7.5 优先级 P2：闭包表从全量重建优化为增量刷新

短期方案：

- 保留全量 rebuild closure，但从主写入事务中拆出。

补充：如果正式导入链路已经统一经过支持增量 closure 维护的分类 CRUD 服务，则短期阶段可以直接跳过全量 rebuild，只在 FINALIZING 记录“本次采用增量维护”并预留补偿入口。

中期方案：

- 仅刷新本次受影响分类子树。

长期方案：

- 分类新增/移动时实时维护闭包表，不再依赖导入后全量 rebuild。

---

## 8. 进度模型建议

### 8.1 dry-run 进度

建议新增 dry-run job，阶段如下：

1. PARSING
2. PRELOADING
3. VALIDATING_CATEGORIES
4. VALIDATING_ATTRIBUTES
5. VALIDATING_ENUMS
6. BUILDING_PREVIEW
7. COMPLETED
8. FAILED

### 8.2 import 进度

建议复用现有 job 模型并细化阶段：

1. PREPARING
2. IMPORTING_CATEGORIES
3. IMPORTING_ATTRIBUTES
4. IMPORTING_ENUM_OPTIONS
5. FINALIZING
6. COMPLETED
7. FAILED

### 8.3 前端需要的进度字段

每次 progress snapshot 建议至少返回：

- jobId
- status
- currentStage
- stagePercent
- overallPercent
- processedRows
- totalRows
- processedCategories
- processedAttributes
- processedEnums
- latestLogCursor
- latestLogs
- updatedAt

可选字段：

- estimatedRemainingSeconds
- currentEntityType
- currentBusinessDomain

### 8.4 进度展示建议

前端建议不再以“等待 dry-run 响应完成”为主流程，而改为：

1. 上传文件，创建 dry-run job。
2. 立即进入“解析中”页面。
3. 通过 polling 或 SSE 订阅进度。
4. 完成后拉取 preview result。
5. 用户确认后发起 import job。
6. 继续通过 polling 或 SSE 展示正式导入进度。

---

## 9. 推荐接口调整

### 9.1 dry-run 接口建议从同步改为异步

建议新增：

- POST /api/meta/imports/workbook/dry-run-jobs
  - 创建 dry-run 任务
  - 返回 dryRunJobId

- GET /api/meta/imports/workbook/dry-run-jobs/{jobId}
  - 查询 dry-run 状态

- GET /api/meta/imports/workbook/dry-run-jobs/{jobId}/result
  - 查询 dry-run 结果与 preview

- GET /api/meta/imports/workbook/dry-run-jobs/{jobId}/logs
  - 查询 dry-run 日志

- GET /api/meta/imports/workbook/dry-run-jobs/{jobId}/stream
  - 订阅 dry-run SSE

### 9.2 import 接口建议直接消费 plan snapshot

正式导入接口不再重新上传 workbook 文件。

建议形式：

- POST /api/meta/imports/workbook/import-jobs

请求体中只需要：

- dryRunJobId 或 importSessionId
- operator
- atomic / executionMode

### 9.3 保持现有接口的兼容过渡方案

若短期内不能直接废弃旧 dry-run 同步接口，则建议：

- 保留现有同步接口作为兼容入口。
- 新增异步 dry-run 接口作为推荐入口。
- 前端新版本优先接异步接口。

---

## 10. 执行模式建议

### 10.1 默认模式：分阶段事务执行

建议默认策略：

- 分类阶段一个事务
- 属性阶段一个事务
- 枚举阶段一个事务
- 后处理阶段一个事务

优点：

- 降低长事务风险
- 降低回滚成本
- 更利于输出阶段级进度

### 10.2 可选模式：严格原子导入

如果后续业务要求“全部成功或全部失败”，建议通过 staging 表支持。

思路：

- dry-run 结束后将 normalized plan 写入 staging。
- import 时用较短事务从 staging merge 到正式表。
- 闭包表刷新可作为同一原子阶段的最后步骤，或作为受控后置步骤。

本草案不建议在第一阶段直接实现 staging，优先落地异步 dry-run 与批量预加载。

---

## 11. 建议落地顺序

### Phase 1：前端体验优先

- dry-run job 化
- dry-run progress/log/SSE
- import 继续复用现有 job 模型

收益：

- 立即解决前端长时间空白等待问题

### Phase 2：性能优先

- 预加载存量数据
- 内存索引化校验
- 统一生成 execution plan
- import 复用 dry-run snapshot

收益：

- 明显降低数据库 round-trip
- 降低重复计算

### Phase 3：深度优化

- 规则批量保留号段
- 批量 upsert
- 闭包表增量刷新
- 可选 staging 原子模式

当前实现对齐补充：

- 规则批量保留号段已覆盖分类、属性、枚举值编码预留。
- attribute 与 LOV 写入在 atomic 导入模式下已切换到批量 upsert/saveAll 路径，减少 workbook import 执行阶段逐条 service 调用。
- non-atomic 导入仍保留逐条写入语义，以维持部分成功时的逐行容错与日志行为。
- category 阶段当前仍优先复用分类 CRUD 服务，原因是父子挂接、排序与闭包维护语义仍集中在该层，尚未替换为 workbook 专用批量 SQL。
- staging 原子模式已落地为基于 snapshot 的 staged plan prepare + merge：预处理阶段先预留编码并把 prepared plan 持久化到 snapshot，再以单事务执行 merge。
- 当前 staging 原子模式仍复用现有 category CRUD / attribute batch upsert / LOV batch upsert 语义，不额外引入独立业务 staging 表。
- staging reuse 策略已补齐：成功后清理 staged plan，失败后保留 staged plan 以供重试，同一 importSessionId 的并发重复导入会被拒绝。

收益：

- 提升大数据量 workbook 导入吞吐
- 控制超长事务风险

---

## 12. 风险与注意事项

### 12.1 规则批量保留号段需要和现有规则服务对齐

必须保证：

- 预留号段和正式落库使用同一套 sequence 语义。
- dry-run 与 import 之间不会发生编码漂移。

### 12.2 dry-run 结果缓存需要生命周期管理

plan snapshot 不能无限保留。

建议配置：

- 默认 30 分钟到 2 小时过期。
- 超时后需要重新 dry-run。

### 12.3 分阶段事务执行要明确部分成功语义

若默认不是全局单事务，则必须在接口契约中明确：

- 哪些阶段可能已落库。
- job failed 时前端应如何展示部分完成状态。

### 12.4 闭包表刷新策略需要和分类移动能力统一

导入侧闭包表优化不应与分类移动、批量移动、拓扑移动的闭包维护策略冲突。

---

## 13. 本草案的结论

本草案对 workbook 导入优化的核心判断如下：

- 当前最先要解决的不是单点 SQL 优化，而是 dry-run 同步等待过长带来的前端体验问题。
- 当前最值得投入的性能优化不是先做全量重写，而是先做批量预加载、内存索引、计划复用。
- 用户提出的“Read All Sheets -> Validate -> Prepare Batch -> Execute -> Post-Process”方向正确，但应补充“预加载阶段”和“plan snapshot 持久化”，并避免默认使用超长单事务。

推荐的一期落地方向是：

- dry-run 异步 job 化
- dry-run / import 全链路进度回传
- 批量预加载与计划复用

推荐的二期落地方向是：

- 编码规则批量号段预留
- 批量 upsert
- 闭包表增量刷新

推荐的三期落地方向是：

- staging 原子模式
- 更细粒度的执行计划复用与恢复能力

---

## 14. 推荐文件定位

本文件是独立优化草案。

与现有文档的关系如下：

- 原始设计草案：metadata-workbook-import-api-design-draft.md
- 本优化草案：metadata-workbook-import-optimization-design-draft.md

后续若方案确认，可再单独形成：

- workbook-import-runtime-job-api-design-draft.md
- workbook-import-plan-snapshot-design-draft.md
- workbook-import-batch-execution-design-draft.md
