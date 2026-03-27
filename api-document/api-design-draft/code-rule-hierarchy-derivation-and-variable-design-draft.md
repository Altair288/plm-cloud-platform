# 编码规则后端增强设计草案：层级派生、按父级重置与动态变量引用

更新时间：2026-03-27
阶段：后端设计草案（评审版，本稿不涉及前端）

---

## 1. 目标

本稿仅从后端角度定义统一编码规则的新增能力，目标是在当前已落地的统一编码服务基础上，补齐以下三类能力：

1. 分类编码支持层级派生。
2. 流水号支持不重置、按日重置、按月重置、按年重置，以及按父级重置。
3. 属性编码、枚举值编码支持引用上层对象编码等动态变量。

本稿的出发点不是重新设计一套新系统，而是在当前已存在的规则管理接口、规则服务、生成器与业务接入点上做增强，并确保新增能力能够自然接入已有写接口。

---

## 2. 当前后端现状提取

以下内容基于当前实际后端代码提取。

## 2.1 当前规则管理接口

当前已存在统一规则管理控制器：

- `GET /api/meta/code-rules`
- `GET /api/meta/code-rules/{ruleCode}`
- `POST /api/meta/code-rules`
- `PUT /api/meta/code-rules/{ruleCode}`
- `POST /api/meta/code-rules/{ruleCode}:publish`
- `POST /api/meta/code-rules/{ruleCode}:preview`

当前控制器与服务入口：

- `MetaCodeRuleController`
- `MetaCodeRuleService`

当前接口能力已经覆盖：

- 规则列表
- 规则详情
- 规则创建
- 规则更新
- 规则发布
- 规则预览

因此本次增强建议优先扩展现有接口语义，而不是新增另一套平行的规则接口。

## 2.2 当前规则服务能力

当前 `MetaCodeRuleService` 已具备：

- 规则草稿创建与更新
- 最新版本写入 `meta_code_rule_version`
- 发布时切换 `ACTIVE`
- 预览时基于 pattern 与 context 渲染示例
- 生成时调用 `CodeRuleGenerator` 并落审计表 `meta_code_generation_audit`

当前 `MetaCodeRuleService.generateCode(...)` 的核心输入为：

- `ruleCode`
- `targetType`
- `targetId`
- `context`
- `manualCode`
- `operator`
- `freezeAfterGenerate`

这说明运行时已经具备“基于上下文生成编码”的入口，但当前上下文模型还比较简单。

## 2.3 当前底层生成器能力

当前 `CodeRuleGenerator` 已具备：

- 从 `meta_code_rule` 读取 pattern
- 替换 `{DATE}`、`{SEQ}`
- 替换 context 中的动态变量，例如：
  - `{CATEGORY_CODE}`
  - `{ATTRIBUTE_CODE}`
  - `{PARENT_CODE}`
- 在 `inherit_prefix=true` 时做父级前缀兜底拼接

当前限制也很明确：

1. 序列仍然只从 `meta_code_sequence(rule_code)` 读取和更新。
2. 作用域还不支持按父级或按上层对象分桶。
3. 生成逻辑仍然主要基于单个 pattern，而不是结构化的多层 ruleJson。

## 2.4 当前业务接入点

当前已有统一编码接入点：

### 分类创建

`MetaCategoryCrudService.create(...)`

- 创建分类时调用 `resolveCategoryCodeForCreate(...)`
- 最终调用 `MetaCodeRuleService.generateCode("CATEGORY", ...)`
- 当前只传入：
  - `BUSINESS_DOMAIN`

当前限制：

- 尚未把 `PARENT_CODE` 纳入分类创建时的生成上下文
- 因此分类编码虽然已经统一接入，但还不支持层级派生语义

### 属性创建与更新

`MetaAttributeManageService`

- 属性创建已调用 `MetaCodeRuleService.generateCode("ATTRIBUTE", ...)`
- LOV 定义创建已调用 `MetaCodeRuleService.generateCode("LOV", ...)`
- 当前上下文已支持：
  - `BUSINESS_DOMAIN`
  - `CATEGORY_CODE`
  - `ATTRIBUTE_CODE`

当前限制：

- 动态变量虽可在生成器中替换，但规则模型尚未正式约束“哪些对象允许引用哪些变量”
- preview 接口也尚未形成面向分类、属性、枚举三种目标的标准化上下文契约

### 属性导入

`MetaAttributeImportService`

- 导入属性定义时已调用 `MetaCodeRuleService.generateCode("ATTRIBUTE", ...)`
- 导入 LOV 定义时已调用 `MetaCodeRuleService.generateCode("LOV", ...)`
- 当前已经传入：
  - `BUSINESS_DOMAIN`
  - `CATEGORY_CODE`
  - `ATTRIBUTE_CODE`

当前限制：

- 与属性管理相同，变量替换能力已存在，但缺少正式的规则结构、作用域序列与发布校验

---

## 3. 当前缺口

结合现状代码，当前真正缺失的是以下后端能力。

## 3.1 分类层级派生未进入正式规则模型

当前分类编码已经统一走规则服务，但分类创建时上下文只有 `BUSINESS_DOMAIN`，没有把：

- 父级编码
- 当前是否为根分类
- 当前是否套用子级派生规则

纳入规则生成决策。

因此现阶段的 `CATEGORY` 规则仍然只能表达“全局分类编码规则”，不能表达：

- 根分类编码规则
- 子级分类后缀规则
- 父级编码拼接语义

## 3.2 重置规则还没有真正变成后端序列能力

当前数据库序列模型仍是：

- 一个 `rule_code` 对应一个 `current_value`

这只能支持单一全局递增，无法覆盖以下重置语义：

- `NEVER`：不重置
- `DAILY`：按日重置
- `MONTHLY`：按月重置
- `YEARLY`：按年重置
- `PER_PARENT`：按父级重置

其中 `PER_PARENT` 场景下无法支持如下编码：

- `MAT-001-001`
- `MAT-001-002`
- `MAT-002-001`

因为 `MAT-001` 与 `MAT-002` 的子级必须拥有独立的计数器，而不是共享 `CATEGORY` 下的同一全局序列。

同样，时间维度重置也无法稳定支持如下场景：

- `ORD-20260327-001` 与次日重新从 `001` 开始
- `ORD-202603-001` 与次月重新从 `001` 开始
- `ORD-2026-001` 与次年重新从 `001` 开始

## 3.3 动态变量缺少对象级白名单与发布校验

当前生成器允许替换上下文变量，但系统尚未正式定义：

- 分类规则允许引用哪些变量
- 属性规则允许引用哪些变量
- 枚举值规则允许引用哪些变量
- 不同对象能否跨层引用上游编码

这会导致两个风险：

1. 规则可配能力与后端真实支持范围不一致。
2. 发布后才发现变量缺失或形成无效引用。

## 3.4 现有 pattern 表达能力不足以承载复杂派生规则

当前规则的核心摘要仍然是单个 `pattern`。这适合：

- `{BUSINESS_DOMAIN}-{SEQ}`
- `ATTR_{SEQ}`
- `{ATTRIBUTE_CODE}_LOV`

但不适合完整表达：

- 分类根规则
- 分类子级规则
- 属性规则
- 枚举值规则
- 变量白名单
- 序列作用域
- 派生模式

因此本次增强必须把 `ruleJson` 升级为真正的事实源。

---

## 4. 设计原则

本次增强建议遵循以下后端原则。

## 4.1 在已有接口上扩展，不新开并行规则体系

规则管理、预览、发布能力均应基于现有 `/api/meta/code-rules` 体系扩展。

不建议：

- 新增另一组 hierarchy rule 接口
- 新增另一组 variable rule 接口

原因：

- 当前规则服务已经是统一事实源
- 再开平行接口只会让规则治理再次分散

## 4.2 `ruleJson` 升级为唯一事实源

本次增强后：

- 顶层 `pattern` 保留为摘要字段，用于列表、兼容旧逻辑与简单场景
- `ruleJson` 承担完整语义表达

包括但不限于：

- 子规则定义
- 层级派生模式
- 序列作用域
- 重置规则
- 变量白名单
- 预览模板
- 发布校验所需元数据

## 4.3 分类、属性、枚举值仍然走现有业务写接口

本次增强不建议引入单独的“生成后再落库”的前置调用流程。

仍然建议由业务写接口内部统一调用编码服务：

- 分类创建：`MetaCategoryCrudService.create(...)`
- 属性创建/更新：`MetaAttributeManageService`
- 属性导入：`MetaAttributeImportService`

新增能力应作为这些现有服务的内部增强，而不是要求业务调用方先手动调用 preview 或 generate。

## 4.4 发布校验必须前置到规则层

复杂规则一旦允许上线，错误会直接体现在业务对象编码上。

因此以下校验必须放在发布阶段，而不是在业务写入失败后才暴露：

- 变量引用是否合法
- 子级规则是否会生成空后缀
- 重置规则是否声明了合法作用域或时间周期
- 预览结果是否满足长度、正则、分隔符约束

---

## 5. 建议的后端规则模型

## 5.1 规则层级

建议继续保留一个统一规则对象，但在 `ruleJson` 内显式拆分子规则：

- `category`
- `attribute`
- `enum`

这里的含义不是新增三条平行数据库规则，而是：

- 一个业务对象级规则版本
- 内部包含多个可选子规则配置

这样更适合当前“编码规则配置页对应一个业务对象”的现状，也更符合统一发布、统一预览、统一审计的要求。

## 5.2 建议的 ruleJson 结构

建议新增如下结构能力：

```json
{
  "businessObject": "MATERIAL_CATEGORY",
  "scopeType": "GLOBAL",
  "hierarchyMode": "APPEND_CHILD_SUFFIX",
  "subRules": {
    "category": {
      "separator": "-",
      "segments": [
        { "type": "STRING", "value": "MAT" },
        { "type": "SEQUENCE", "length": 3, "startValue": 1, "step": 1, "resetRule": "YEARLY", "scopeKey": "GLOBAL" }
      ],
      "childSegments": [
        { "type": "SEQUENCE", "length": 3, "startValue": 1, "step": 1, "resetRule": "PER_PARENT", "scopeKey": "PARENT_CODE" }
      ],
      "allowedVariableKeys": ["BUSINESS_DOMAIN", "PARENT_CODE"]
    },
    "attribute": {
      "separator": "-",
      "segments": [
        { "type": "STRING", "value": "ATTR" },
        { "type": "VARIABLE", "variableKey": "CATEGORY_CODE" },
        { "type": "SEQUENCE", "length": 3, "startValue": 1, "step": 1, "resetRule": "PER_PARENT", "scopeKey": "CATEGORY_CODE" }
      ],
      "allowedVariableKeys": ["CATEGORY_CODE", "BUSINESS_DOMAIN"]
    },
    "enum": {
      "separator": "-",
      "segments": [
        { "type": "STRING", "value": "ENUM" },
        { "type": "VARIABLE", "variableKey": "ATTRIBUTE_CODE" },
        { "type": "SEQUENCE", "length": 3, "startValue": 1, "step": 1, "resetRule": "PER_PARENT", "scopeKey": "ATTRIBUTE_CODE" }
      ],
      "allowedVariableKeys": ["ATTRIBUTE_CODE", "CATEGORY_CODE"]
    }
  },
  "validation": {
    "maxLength": 128,
    "regex": "^[A-Z][A-Z0-9_-]{0,127}$",
    "allowManualOverride": true
  }
}
```

## 5.3 关键字段说明

### `hierarchyMode`

建议值：

- `NONE`
- `APPEND_CHILD_SUFFIX`

当前分类场景建议只支持两种：

- `NONE`：根分类和子分类都走同一规则
- `APPEND_CHILD_SUFFIX`：子分类完整编码 = 父级完整编码 + 分隔符 + 子级后缀规则

### `childSegments`

仅在分类子规则中生效。

建议语义：

- `segments`：根分类编码规则
- `childSegments`：子分类相对父级新增的后缀规则

不建议让 `childSegments` 自己拼完整编码，以避免父级部分重复定义。

### `allowedVariableKeys`

建议在规则模型中显式记录变量白名单，而不是让预览或生成时再去猜。

### `scopeKey`

建议用于声明序列按什么上下文分桶，典型值包括：

- `GLOBAL`
- `PARENT_CODE`
- `CATEGORY_CODE`
- `ATTRIBUTE_CODE`

### `resetRule`

建议显式支持以下值：

- `NEVER`
- `DAILY`
- `MONTHLY`
- `YEARLY`
- `PER_PARENT`

建议语义如下：

- `NEVER`：始终沿同一个序列桶递增，不因时间或父级变化而重置
- `DAILY`：按自然日分桶，同一天共享计数器，跨天重新起号
- `MONTHLY`：按自然月分桶，同一月份共享计数器，跨月重新起号
- `YEARLY`：按自然年分桶，同一年共享计数器，跨年重新起号
- `PER_PARENT`：按父级对象分桶，同一父级共享计数器，更换父级重新起号

时间维度重置与作用域分桶可以组合使用。例如属性编码既可以按 `CATEGORY_CODE` 分桶，也可以在每个月内重新从起始值开始。

---

## 6. 序列模型设计

## 6.1 当前模型的问题

当前表：

- `meta_code_sequence(rule_code, current_value)`

只适合按规则全局递增，不适合按父级重置或按分类独立计数。

## 6.2 建议方案

建议不要直接修改现有 `meta_code_sequence` 主键，而是新增一张作用域序列表。
只适合按规则全局递增，不适合按父级重置、按分类独立计数，或按日、月、年分期开新桶。
建议表名：

- `meta_code_sequence_scope`
结合本轮评审结论，建议直接升级现有 `meta_code_sequence` 结构，一步到位承接作用域与周期维度，而不是新增 `meta_code_sequence_scope`。
建议字段：
采用直接改造现有表的前提是：
- `id`
- 当前尚无任何已经启用的历史规则数据需要兼容迁移
- 当前不存在必须同时维护“旧全局序列模型”和“新作用域序列模型”的线上包袱

因此更适合一次性收敛结构，避免新旧并存。
- `sub_rule_key`
- `scope_key`
- `scope_value`
- `current_value`
- `created_at`
- `sub_rule_key`
- `scope_key`
- `scope_value`
- `reset_rule`
- `period_key`
- `updated_at`

建议唯一键：

- `(rule_code, sub_rule_key, scope_key, scope_value)`

- `(rule_code, sub_rule_key, scope_key, scope_value, period_key)`

这样处理的原因是：

1. 不破坏当前已经生效的全局序列逻辑。
2. 简单规则仍然可以继续走旧表。
1. 避免 `meta_code_sequence` 与 `meta_code_sequence_scope` 并存，降低维护复杂度。
2. 所有序列查询、加锁、递增逻辑只保留一条实现路径。
3. 同一张表即可表达：
  - 全局序列
  - 按父级重置序列
  - 按分类独立序列
  - 按属性独立序列
  - 按日、月、年分期开桶序列
4. 后续发布校验与 preview 也只需要解释一套序列模型。

建议：

- 当 `scopeKey = GLOBAL` 时，继续走 `meta_code_sequence`
- 当 `scopeKey != GLOBAL` 时，走 `meta_code_sequence_scope`
- `scope_key` 用于表达序列按谁分桶，例如 `GLOBAL`、`PARENT_CODE`、`CATEGORY_CODE`、`ATTRIBUTE_CODE`
- `scope_value` 用于表达当前实际分桶值，例如 `GLOBAL`、`MAT-001`、`ATTR-MAT-001-001`
- `period_key` 用于表达时间周期桶：
  - `NEVER` 或 `PER_PARENT` 时可固定为 `NONE`
  - `DAILY` 时格式建议为 `yyyyMMdd`
  - `MONTHLY` 时格式建议为 `yyyyMM`
  - `YEARLY` 时格式建议为 `yyyy`
- 运行时按 `(rule_code, sub_rule_key, scope_key, scope_value, period_key)` 定位唯一计数器并递增

这样既能支持按父级重置，也能支持日、月、年的自然周期重置，同时保留简单全局规则的统一查询路径。

## 7. 动态变量模型设计

## 7.1 变量白名单建议

建议按对象类型定义允许变量。

### 分类编码

允许：

- `BUSINESS_DOMAIN`
- `PARENT_CODE`（仅子级派生规则）

不允许：

- `ATTRIBUTE_CODE`

### 属性编码

允许：

- `CATEGORY_CODE`
- `BUSINESS_DOMAIN`

不建议：

- `ATTRIBUTE_CODE` 作为自身生成时的输入变量

### 枚举值编码

允许：

- `ATTRIBUTE_CODE`
- `CATEGORY_CODE`

根据实际需要可扩展：

- `PARENT_CODE`

## 7.2 变量上下文来源

建议统一在编码服务层生成上下文，而不是让控制器或页面直接拼装复杂上下文。

建议做法：

- 分类创建时：
  - 根分类传入 `BUSINESS_DOMAIN`
  - 子分类额外传入 `PARENT_CODE`
- 属性创建时：
  - 传入 `BUSINESS_DOMAIN`
  - 传入 `CATEGORY_CODE`
- 枚举值或 LOV 定义创建时：
  - 传入 `BUSINESS_DOMAIN`
  - 传入 `CATEGORY_CODE`
  - 传入 `ATTRIBUTE_CODE`

---

## 8. 对现有接口的增强建议

本次建议仅增强现有接口，不新增平行的 rule 接口族。

## 8.1 `GET /api/meta/code-rules`

建议新增返回摘要字段：

- `supportsHierarchy`
- `supportsScopedSequence`
- `supportedVariableKeys`

如果不希望污染顶层 DTO，也可以继续仅返回 `latestRuleJson`，但建议至少在文档中明确这些语义由 `latestRuleJson` 承载。

## 8.2 `GET /api/meta/code-rules/{ruleCode}`

建议：

- 返回完整增强后的 `latestRuleJson`
- 明确包含：
  - `hierarchyMode`
  - `subRules`
  - `allowedVariableKeys`
  - `scopeKey`

## 8.3 `POST /api/meta/code-rules`

建议：

- 沿用现有接口
- `CodeRuleSaveRequestDto.ruleJson` 支持提交增强后的复杂结构
- 若 body 未提供 `ruleJson`，后端继续可以生成简单版 `ruleJson`
- 若 body 提供了复杂版 `ruleJson`，则 `pattern` 只作为摘要，不再尝试还原完整逻辑

## 8.4 `PUT /api/meta/code-rules/{ruleCode}`

建议：

- 沿用现有接口
- 仅允许更新 `DRAFT`
- 增加复杂规则结构的服务端校验

## 8.5 `POST /api/meta/code-rules/{ruleCode}:publish`

建议增强发布校验，至少覆盖：

1. 分类启用层级派生时，必须有有效 `childSegments`
2. 子级规则不能生成空后缀
3. 变量必须在当前对象允许范围内
4. `PER_PARENT` 必须带合法 `scopeKey`
5. `DAILY`、`MONTHLY`、`YEARLY` 必须能够解析出唯一 `periodKey`
6. `NEVER`、`DAILY`、`MONTHLY`、`YEARLY` 不得错误依赖父级上下文
7. 预览样例必须满足长度和正则约束
8. 不能出现明显循环引用或自引用

## 8.6 `POST /api/meta/code-rules/{ruleCode}:preview`

当前 preview 已支持 `context`。

建议继续沿用该接口，但扩展 `context` 支持以下键：

- `BUSINESS_DOMAIN`
- `PARENT_CODE`
- `CATEGORY_CODE`
- `ATTRIBUTE_CODE`
- `SUB_RULE_KEY`
- `HIERARCHY_MODE`

并建议增强响应：

- `examples`
- `warnings`
- `resolvedContext`
- `resolvedSequenceScope`
- `resolvedPeriodKey`

如果当前不希望修改响应 DTO，至少应在 `warnings` 中体现：

- 缺失哪些上下文
- 当前按哪个 scope 预览序列

---

## 9. 对现有业务接口的接入建议

## 9.1 分类创建接口

当前分类创建已经统一走 `MetaCodeRuleService.generateCode("CATEGORY", ...)`。

建议增强：

- 当 `parentId` 非空时，在 `resolveCategoryCodeForCreate(...)` 中把父级编码注入 `context`
- 若规则启用了层级派生，则按以下方式生成：

```text
根分类：按 category.segments 生成
子分类：父级完整编码 + 分隔符 + category.childSegments 生成的后缀
```

这意味着分类创建接口本身不需要新增新的对外路径，但需要增强内部生成上下文和规则解释逻辑。

## 9.2 属性创建接口

当前属性创建已传入：

- `BUSINESS_DOMAIN`
- `CATEGORY_CODE`

建议增强：

- 按对象白名单校验 `CATEGORY_CODE` 是否被规则合法引用
- 若属性规则声明按分类独立计数，则按 `CATEGORY_CODE` 走作用域序列

## 9.3 属性导入接口

当前导入服务已经能够传入分类与属性上下文。

建议增强：

- 复用与属性创建完全一致的规则解释逻辑
- 导入场景不得使用另一套序列或变量解析逻辑

## 9.4 LOV / 枚举相关接口

当前 LOV 定义已统一走 `LOV` 规则。

建议增强：

- LOV 定义规则继续支持：
  - `CATEGORY_CODE`
  - `ATTRIBUTE_CODE`
- 若未来枚举值编码从 JSON 中进一步结构化治理，则应沿用同一套变量与 scope 模型，而不是单独另起一套项级编码机制

---

## 10. 生成示例

## 10.1 分类编码

根规则：

- 固定字符：`MAT`
- 三位流水号

生成：

- `MAT-001`
- `MAT-002`

## 10.2 子分类编码

子级后缀规则：

- 三位流水号
- 按父级重置

生成：

- 在 `MAT-001` 下：
  - `MAT-001-001`
  - `MAT-001-002`
- 在 `MAT-002` 下：
  - `MAT-002-001`

## 10.3 按日重置编码

规则：

- 固定字符：`ORD`
- 日期段：`yyyyMMdd`
- 三位流水号
- `resetRule = DAILY`

生成：

- `ORD-20260327-001`
- `ORD-20260327-002`
- 次日重新开始：`ORD-20260328-001`

## 10.4 按月重置编码

规则：

- 固定字符：`DOC`
- 月份段：`yyyyMM`
- 三位流水号
- `resetRule = MONTHLY`

生成：

- `DOC-202603-001`
- `DOC-202603-002`
- 次月重新开始：`DOC-202604-001`

## 10.5 按年重置编码

规则：

- 固定字符：`PRJ`
- 年份段：`yyyy`
- 四位流水号
- `resetRule = YEARLY`

生成：

- `PRJ-2026-0001`
- `PRJ-2026-0002`
- 次年重新开始：`PRJ-2027-0001`

## 10.6 属性编码

规则：

- 固定字符：`ATTR`
- 变量：`CATEGORY_CODE`
- 三位流水号

生成：

- `ATTR-MAT-001-001`
- `ATTR-MAT-001-002`

## 10.7 枚举值编码

规则：

- 固定字符：`ENUM`
- 变量：`ATTRIBUTE_CODE`
- 三位流水号

生成：

- `ENUM-ATTR-MAT-001-001-001`
- `ENUM-ATTR-MAT-001-001-002`

---

## 11. 迁移建议

建议分三阶段落地。

## 11.1 第一阶段：规则模型增强

范围：

- 扩展 `ruleJson` 结构
- 扩展 detail/create/update/publish/preview 的服务端校验与解释能力
- 不立即修改所有业务写入逻辑

目标：

- 先让规则本身能够表达层级派生、作用域序列与动态变量

## 11.2 第二阶段：现有序列表结构升级

范围：

- 直接升级 `meta_code_sequence`
- 补齐 `sub_rule_key`、`scope_key`、`scope_value`、`reset_rule`、`period_key`
- 更新生成器或生成服务，统一按新唯一键选择序列桶

目标：

- 真正实现 `PER_PARENT`
- 真正实现 `NEVER`、`DAILY`、`MONTHLY`、`YEARLY`
- 真正实现按分类、按属性独立计数

## 11.3 第三阶段：业务写接口增强

范围：

- 分类创建注入 `PARENT_CODE`
- 属性创建/更新与导入按增强规则运行
- LOV / 枚举相关生成逻辑统一收口

目标：

- 新规则不只可配置，也能真实驱动业务写入

---

## 12. 本稿建议评审点

建议重点确认以下问题。

1. 是否确认分类层级派生采用“父级完整编码 + 子级后缀规则”的后端模型。
2. 是否确认重置规则正式支持 `NEVER`、`DAILY`、`MONTHLY`、`YEARLY`、`PER_PARENT`。
3. 是否确认属性编码允许引用 `CATEGORY_CODE`。
4. 是否确认枚举值编码允许引用 `ATTRIBUTE_CODE`。
5. 是否确认复杂规则以 `ruleJson` 作为唯一事实源。
6. 是否确认直接升级现有 `meta_code_sequence` 结构，而不是新增并行的 `meta_code_sequence_scope`。
7. 是否确认现有 `/api/meta/code-rules` 接口族继续沿用，仅做语义增强。

---

## 13. 本稿结论

从后端实现现状看，统一编码能力已经具备基础框架，但以下三项还没有真正落地：

1. 分类层级派生
2. 覆盖不重置、按日、按月、按年、按父级的重置规则体系
3. 属性/枚举值对上层编码的动态引用

因此建议明确采用以下后端方案：

1. 在现有规则管理接口上扩展复杂规则能力，不另开平行规则体系。
2. 以 `ruleJson` 作为层级派生、变量白名单、作用域序列的唯一事实源。
3. 直接升级现有 `meta_code_sequence` 结构，统一承接 `NEVER`、`DAILY`、`MONTHLY`、`YEARLY`、`PER_PARENT` 等计数场景。
4. 继续通过现有分类创建、属性创建/更新、属性导入等业务接口内部调用统一编码服务完成落地。

该方案与现有后端结构兼容、与当前统一编码服务方向一致，也最符合后端演进上的收敛原则。