# 统一编码管理与设计器设计草案

更新时间：2026-03-23  
阶段：设计草案（本阶段不改代码）

---

## 1. 背景与目标

当前系统中，以下几类编码还没有进入统一、可配置、可审计的治理体系：

- 分类编码：`meta_category_def.code_key`
- 属性编码：`meta_attribute_def.key`
- 枚举定义编码：`meta_lov_def.key`
- 枚举项编码：当前主要位于 `meta_lov_version.value_json` 内部

虽然数据库中已经存在：

- `plm_meta.meta_code_rule`
- `plm_meta.meta_code_rule_version`
- `plm_meta.meta_code_sequence`

以及基础生成器：

- `CodeRuleGenerator`

但现状仍然是“基础设施存在，业务对象未统一接入，设计能力缺失，规则治理不完整”。

本草案目标是定义一套标准的“编码管理与设计器”方案，使后续分类、属性、LOV 及实例类对象都可在同一规则体系下生成、校验、审计与演进。

---

## 2. 设计目标

本方案目标：

1. 为分类、属性、枚举定义、枚举项建立统一编码治理模型。
2. 让编码规则具备可配置、可版本化、可预览、可发布、可回滚能力。
3. 提供设计器支持，避免未来继续以代码硬编码规则。
4. 支持规则生成与人工覆盖双轨制，但人工覆盖必须被显式治理。
5. 在不破坏现有存量数据的前提下平滑迁移。

本方案非目标：

1. 本阶段不直接改造所有现有写接口实现。
2. 本阶段不重构运行时业务实例表。
3. 本阶段不强制把所有旧编码重算或重发。
4. 本阶段不直接引入复杂权限系统，只预留角色与操作边界。

---

## 3. 现状调研结论

## 3.1 现有数据库基础设施

从迁移脚本可确认，系统自 V1 起已经提供编码规则基础表：

- `plm_meta.meta_code_rule`
  - 规则主表
  - 当前关键字段：`code`、`target_type`、`pattern`
- `plm_meta.meta_code_rule_version`
  - 规则版本表
  - 当前关键字段：`version_no`、`rule_json`、`is_latest`
- `plm_meta.meta_code_sequence`
  - 简化序列表
  - 当前粒度仅到 `rule_code`

V2 又补充了：

- `parent_rule_id`
- `inherit_prefix`

说明数据库层已经考虑过“继承式规则”的方向，但应用层并没有完整消费这部分能力。

## 3.2 当前几类编码的实际情况

### 3.2.1 分类编码

现状：

- 存储字段：`meta_category_def.code_key`
- 唯一性：当前为 `business_domain + code_key` 维度唯一
- 创建方式：当前分类创建接口仍以请求中的 `code` 为主，没有统一接入 `CodeRuleGenerator`

结论：

- 分类编码目前本质仍是“人工主导”。
- 数据库已有编码规则基础设施，但分类创建尚未真正纳管。

### 3.2.2 属性编码

现状：

- 存储字段：`meta_attribute_def.key`
- 生成方式：属性导入场景调用 `CodeRuleGenerator.generate("ATTRIBUTE")`
- 当前规则来源：`V10__attribute_lov_global_sequence.sql`
  - `ATTRIBUTE` pattern = `ATTR_{SEQ}`
- 当前序列位宽：代码中为 6 位

结论：

- 属性编码已经部分纳入规则生成。
- 但其规则仍然是后端硬编码解释，不具备完整设计器能力。

### 3.2.3 枚举定义编码（LOV 定义）

现状：

- 存储字段：`meta_lov_def.key`
- 导入场景：`MetaAttributeImportService` 中使用 `CodeRuleGenerator.generate("LOV", Map.of("ATTRIBUTE_CODE", attrKey))`
- 手工管理场景：`MetaAttributeManageService` 中若未显式传 `lovKey`，会使用 `AttributeLovImportUtils.generateLovKey(categoryCodeKey, attributeKey)` 自动推导

当前存在两套来源：

1. 规则生成器模式
2. 工具函数拼装模式

结论：

- LOV 定义编码当前不是单一事实源。
- 这是编码治理中最典型的不统一点之一。

### 3.2.4 枚举项编码

现状：

- 当前主要位于 `meta_lov_version.value_json` 中的项级 `code`
- 没有统一表结构治理
- 没有独立的编码规则发布模型
- 没有序列与冲突审计机制

结论：

- 枚举项编码是当前统一编码体系里缺口最大的部分。

---

## 4. 现状问题清单

## 4.1 规则分散

- 分类编码靠接口入参。
- 属性编码靠生成器。
- LOV 定义编码同时存在生成器与工具函数两种来源。
- LOV 项编码主要停留在 JSON 内容层。

## 4.2 规则能力没有真正产品化

数据库有规则表，但还缺少：

- 可视化设计入口
- 规则发布流程
- 规则回滚流程
- 规则预览与冲突校验
- 规则使用统计与审计

## 4.3 作用域太粗

当前 `meta_code_sequence` 粒度仅到 `rule_code`，无法天然支撑：

- 按业务域分配序列
- 按分类分配序列
- 按属性类型分配序列
- 按租户或组织分配序列

## 4.4 人工覆盖缺少治理

当前只有分类编码天然允许手填，但缺少：

- 是否允许手填的规则开关
- 手填后的审计字段
- 编码冻结策略
- 人工覆盖后的冲突验证

## 4.5 枚举项缺少结构化治理

当前 LOV 项编码未形成统一规则模型，导致：

- 难以查询“某个项编码是否已存在”
- 难以做项级统计
- 难以沉淀复用型枚举设计模式

---

## 5. 统一术语定义

为避免后续设计歧义，统一采用以下术语：

| 术语 | 定义 | 当前承载位置 |
|---|---|---|
| 分类编码 | 分类定义对象的稳定标识 | `meta_category_def.code_key` |
| 属性编码 | 属性定义对象的稳定标识 | `meta_attribute_def.key` |
| 枚举定义编码 | 某个 LOV 定义的稳定标识 | `meta_lov_def.key` |
| 枚举项编码 | LOV 中每个值项的稳定标识 | 当前主要在 `meta_lov_version.value_json` |
| 外部编码 | 来自外部系统/导入源的编码 | 例如 `meta_category_def.external_code` |
| 展示名 | 对用户展示的名称，不等于编码 | 各 version 或 JSON 中的名称字段 |

补充约定：

- “编码”默认指稳定业务键，不等于显示名称。
- 版本化对象的编码默认在定义层稳定，不随 version 变化。

---

## 6. 总体设计原则

## 6.1 单一规则入口

所有编码都应由统一编码服务生成或校验，不再允许不同模块各自拼接。

## 6.2 定义层稳定，版本层承载内容变化

- 分类编码固定在 `meta_category_def`
- 属性编码固定在 `meta_attribute_def`
- LOV 定义编码固定在 `meta_lov_def`

规则变化影响“未来新建对象如何取码”，不反向改写历史定义编码。

## 6.3 规则支持双轨制

统一支持：

1. 自动生成
2. 人工覆盖

但是否允许人工覆盖，必须由规则本身控制。

## 6.4 先补治理，再谈复杂规则

本轮重点优先级应是：

1. 统一规则模型
2. 统一生成/预览/校验 API
3. 统一设计器工作流
4. 平滑迁移现有 CATEGORY/ATTRIBUTE/LOV

而不是一开始就引入过多动态 DSL。

---

## 7. 规则模型设计

## 7.1 规则对象

建议把统一编码规则抽象为：

- `ruleCode`
  - 例如：`CATEGORY`、`ATTRIBUTE`、`LOV_DEFINITION`、`LOV_ITEM`
- `targetType`
  - category / attribute / lov-definition / lov-item / instance
- `scopeType`
  - GLOBAL / BUSINESS_DOMAIN / CATEGORY / ATTRIBUTE_TYPE / CUSTOM
- `scopeValue`
  - 当 scopeType 非 GLOBAL 时用于表达作用域值
- `pattern`
  - 例如：`{BUSINESS_DOMAIN}-{SEQ}`、`ATTR_{SEQ}`、`{ATTRIBUTE_CODE}_LOV`
- `sequencePolicy`
  - 是否用序列、序列位数、步长、是否可重置
- `validationPolicy`
  - 正则、最大长度、保留字、大小写规范
- `overridePolicy`
  - 是否允许人工覆盖、谁可以覆盖、覆盖后是否必须冻结
- `status`
  - DRAFT / ACTIVE / ARCHIVED

## 7.2 Token 模型

建议设计器和规则引擎统一支持以下 token：

- `{SEQ}`
- `{DATE}`
- `{YEAR}`
- `{MONTH}`
- `{DAY}`
- `{BUSINESS_DOMAIN}`
- `{CATEGORY_CODE}`
- `{ATTRIBUTE_CODE}`
- `{LOV_CODE}`
- `{PARENT_CODE}`
- `{USER_INPUT}`

其中：

- `{USER_INPUT}` 用于支持“规则模板 + 人工输入片段”的混合模式。
- `{LOV_CODE}` 是 LOV 项编码设计必须补上的上下文 token。

## 7.3 规则版本

当前已有 `meta_code_rule_version`，建议正式启用其职责：

- 每次发布规则生成新版本
- 规则查询默认返回 latest
- 历史版本可回看、可回滚
- 对象创建时记录“使用了哪一个规则版本”

建议新增“对象侧追溯字段”或单独审计表记录：

- `generated_rule_code`
- `generated_rule_version_no`
- `manual_override_flag`
- `frozen_flag`

---

## 8. 各类编码的统一策略建议

## 8.1 分类编码

建议：

- 从“默认手填”调整为“默认规则生成，可配置人工覆盖”。

推荐默认规则：

```text
{BUSINESS_DOMAIN}-{SEQ}
```

例如：

- `MATERIAL-0001`
- `PRODUCT-0001`

同时保留：

- `allowManualOverride=true` 的规则模式

适用于需要人工语义码的场景，例如：

- `ELEC_POWER`
- `FASTENER_STANDARD`

结论：

- 分类编码不建议完全禁止手填。
- 最适合采用“双轨制”，但默认入口应转向规则生成。

## 8.2 属性编码

建议：

- 保持统一由编码引擎生成。
- 默认规则可继续沿用全局序列风格：

```text
ATTR_{SEQ}
```

但设计器应允许按分类或业务域扩展为定制规则，例如：

```text
{CATEGORY_CODE}_ATTR_{SEQ}
```

使用原则：

- 属性编码一旦生成并被外部引用，应默认冻结。

## 8.3 枚举定义编码（LOV 定义编码）

建议统一名称为：

- `LOV_DEFINITION`

默认规则建议：

```text
{ATTRIBUTE_CODE}_LOV
```

原因：

- 能直接表达“该 LOV 绑定哪个属性”。
- 与当前 V10 演进方向一致。
- 比工具函数 `category + attribute + __lov` 更统一，也更可配置。

结论：

- 后续不建议继续把 `AttributeLovImportUtils.generateLovKey(...)` 作为长期事实源。
- 它可以作为过渡兼容逻辑，但目标应是统一归并到规则引擎。

## 8.4 枚举项编码

建议新增独立规则概念：

- `LOV_ITEM`

短期默认规则建议：

```text
{LOV_CODE}_{SEQ}
```

例如：

- `COLOR_LOV_01`
- `COLOR_LOV_02`

但对于业务上天然稳定的枚举项，也应允许：

- 人工定义常量编码
  - 例如 `RED`、`BLUE`、`GREEN`

因此 LOV 项最适合支持两种模式：

1. 顺序型规则生成
2. 业务语义型人工编码

---

## 9. LOV 定义编码与 LOV 项编码的建模区分

这是本方案必须明确回答的问题。

## 9.1 LOV 定义编码

LOV 定义编码指“一个枚举集合本身的编码”，例如：

- `COLOR_LOV`
- `SIZE_LOV`
- `ATTR_000123_LOV`

其承载对象是：

- `meta_lov_def.key`

用途：

- 标识一个枚举定义对象
- 供属性绑定
- 供版本表和设计器引用

## 9.2 LOV 项编码

LOV 项编码指“该枚举集合中的某一个值项编码”，例如：

- `RED`
- `BLUE`
- `SIZE_S`

其承载对象当前是：

- `meta_lov_version.value_json[].code`

用途：

- 作为实际属性值的稳定候选键
- 供集成映射、导入导出、规则校验使用

设计结论：

- LOV 定义编码与 LOV 项编码必须视为两层模型，不能继续混用成“一个 lovKey 解决全部语义”。

---

## 10. 枚举项编码存储策略

本项在评审后已明确：

- LOV 项继续保留在 `meta_lov_version.value_json` 中
- 本期不考虑拆分结构化表

因此本节不再把“是否拆表”作为开放问题，而是直接采用 JSON 方案并补足治理约束。

## 10.1 本期确定方案：继续保留在 JSON 中，但纳入规则治理

本期确定方案：

- 继续保留 `meta_lov_version.value_json`
- 但要求项结构标准化，至少包含：
  - `code`
  - `label`
  - `value`
  - `sort`
  - `enabled`

优点：

1. 对现有系统改动最小。
2. 不会打断当前 LOV 版本化逻辑。
3. 适合快速把“项编码规则”纳入统一设计器。

约束与注意点：

1. 项级查询能力弱。
2. 项级唯一性和统计更多依赖应用层校验。
3. 项级审计和复用能力有限。

结论：

- 本期按 JSON 方案推进即可。
- 只要不引入 LOV 项独立搜索与高频项级分析，就没有必要为此拆表。

---

## 11. 数据库扩展建议

## 11.1 现有表保留

建议保留并继续复用：

- `meta_code_rule`
- `meta_code_rule_version`
- `meta_code_sequence`

因为这些表已经能承载：

- 规则主数据
- 规则版本
- 序列状态

## 11.2 建议新增或补充的字段/表

### 方案 A：最小扩展方案（推荐首期）

在现有基础上扩展：

- `meta_code_rule`
  - 增加：
    - `scope_type`
    - `scope_value`
    - `allow_manual_override`
    - `regex_pattern`
    - `max_length`
    - `status`

- `meta_code_rule_version`
  - 在 `rule_json` 中正式承载：
    - token 列表
    - preview 示例
    - validation 配置

- 新增 `meta_code_generation_audit`
  - 记录：
    - `rule_code`
    - `rule_version_no`
    - `generated_code`
    - `target_type`
    - `target_id`
    - `context_json`
    - `manual_override_flag`
    - `created_at`
    - `created_by`

### 方案 B：完整设计器增强方案

如果首期就要支持较强设计器能力，可再增加：

- `meta_code_rule_token`
  - 维护 token 元数据
- `meta_code_conflict_check_log`
  - 维护发布前校验结果
- `meta_code_publish_record`
  - 维护发布、回滚记录

建议：

- 首期优先使用方案 A。
- 避免一开始把设计器元数据拆得过细，导致后台复杂度过高。

## 11.2.1 首期推荐的最小数据库落地方案

为了保证下一轮可以直接落代码，建议首期数据库改造严格控制在“够用且低风险”的范围：

### 一、扩展 `meta_code_rule`

新增字段建议：

- `scope_type VARCHAR(32) NOT NULL DEFAULT 'GLOBAL'`
- `scope_value VARCHAR(128)`
- `allow_manual_override BOOLEAN NOT NULL DEFAULT FALSE`
- `regex_pattern VARCHAR(255)`
- `max_length INT NOT NULL DEFAULT 64`
- `status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'`
- `updated_at TIMESTAMPTZ`
- `updated_by VARCHAR(64)`

说明：

- 首期仍复用 `pattern` 作为最新生效模板，避免一次性把读取逻辑全部切到 version 表。
- `status` 仅用于规则治理，不影响历史已生成编码。

### 二、扩展 `meta_code_rule_version`

继续复用 `rule_json`，但约定内部结构固定化，至少包含：

```json
{
  "pattern": "{BUSINESS_DOMAIN}-{SEQ}",
  "tokens": ["BUSINESS_DOMAIN", "SEQ"],
  "sequence": {
    "enabled": true,
    "width": 4,
    "step": 1
  },
  "validation": {
    "maxLength": 64,
    "regex": "^[A-Z][A-Z0-9_-]{0,63}$",
    "allowManualOverride": true
  },
  "preview": {
    "BUSINESS_DOMAIN": "MATERIAL"
  }
}
```

首期不额外拆 token 表，先保证版本可追溯、结构统一。

### 三、新增编码生成审计表

建议新增：`plm_meta.meta_code_generation_audit`

字段建议：

- `id UUID PRIMARY KEY`
- `rule_code VARCHAR(64) NOT NULL`
- `rule_version_no INT NOT NULL`
- `generated_code VARCHAR(128) NOT NULL`
- `target_type VARCHAR(32) NOT NULL`
- `target_id UUID`
- `context_json JSONB`
- `manual_override_flag BOOLEAN NOT NULL DEFAULT FALSE`
- `frozen_flag BOOLEAN NOT NULL DEFAULT FALSE`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
- `created_by VARCHAR(64)`

用途：

- 规则追溯
- 冲突排查
- 后续设计器使用统计

### 四、首期不直接改造 `meta_code_sequence` 主键

虽然长期建议把序列扩到 scope 粒度，但首期直接修改现有主键风险较高。

因此建议：

- 保留现有 `meta_code_sequence(rule_code)` 不动
- 首期先按全局序列落地
- scope 化序列延后到二期

这样下一轮实现可以更聚焦：

1. 规则管理
2. 统一生成入口
3. 分类/属性/LOV 定义接入

而不在首期引入复杂的序列路由逻辑

## 11.2.2 首期建议新增的对象侧治理字段

为支持“自动生成优先 + 允许人工维护”，建议补以下字段：

### `meta_category_def`

- `code_key_manual_override BOOLEAN NOT NULL DEFAULT FALSE`
- `code_key_frozen BOOLEAN NOT NULL DEFAULT FALSE`
- `generated_rule_code VARCHAR(64)`
- `generated_rule_version_no INT`

### `meta_attribute_def`

- `key_manual_override BOOLEAN NOT NULL DEFAULT FALSE`
- `key_frozen BOOLEAN NOT NULL DEFAULT FALSE`
- `generated_rule_code VARCHAR(64)`
- `generated_rule_version_no INT`

### `meta_lov_def`

- `key_manual_override BOOLEAN NOT NULL DEFAULT FALSE`
- `key_frozen BOOLEAN NOT NULL DEFAULT FALSE`
- `generated_rule_code VARCHAR(64)`
- `generated_rule_version_no INT`

说明：

- 首期不建议为 LOV 项补独立字段，因为 LOV 项仍保留在 JSON 中。
- LOV 项的手工/自动来源信息可暂时写入 `value_json` 结构或审计表的 `context_json`。

## 11.3 序列粒度建议

当前 `meta_code_sequence` 以 `rule_code` 为主键，过粗。

建议扩展为支持作用域：

- 保留 `rule_code`
- 增加可选：
  - `scope_type`
  - `scope_value`

唯一键建议：

```text
(rule_code, scope_type, scope_value)
```

这样才能支持：

- 分类内独立序列
- 业务域独立序列
- 特定规则范围内独立序列

补充落地建议：

- 首期实现：继续使用全局序列
- 二期增强：再引入 scope 维度序列

原因：

- 当前代码生成器、数据库和现有测试都默认 `rule_code` 全局自增
- 若首期直接切 scope 序列，会显著扩大实现和测试面

---

## 12. API 设计建议

## 12.1 规则管理 API

建议新增统一接口前缀：

- `/api/meta/code-rules`

建议接口：

1. `GET /api/meta/code-rules`
   - 查询规则列表
2. `GET /api/meta/code-rules/{ruleCode}`
   - 查询规则详情
3. `POST /api/meta/code-rules`
   - 新建规则草稿
4. `PUT /api/meta/code-rules/{ruleCode}`
   - 更新草稿
5. `POST /api/meta/code-rules/{ruleCode}:publish`
   - 发布规则
6. `POST /api/meta/code-rules/{ruleCode}:rollback`
   - 以历史版本回滚并形成新版本
7. `GET /api/meta/code-rules/{ruleCode}/versions`
   - 查询历史版本

## 12.1.1 首期必须实现的规则管理接口

为保证下一轮实现可控，建议首期只做以下 P0 接口：

1. `GET /api/meta/code-rules`
2. `GET /api/meta/code-rules/{ruleCode}`
3. `POST /api/meta/code-rules`
4. `PUT /api/meta/code-rules/{ruleCode}`
5. `POST /api/meta/code-rules/{ruleCode}:publish`
6. `POST /api/meta/code-rules/{ruleCode}:preview`

以下接口可延后：

- rollback
- usage
- conflicts 查询
- 批量生成

## 12.2 规则预览与校验 API

建议新增：

1. `POST /api/meta/code-rules/{ruleCode}:preview`
   - 输入 pattern 上下文，返回样例编码
2. `POST /api/meta/code-rules/{ruleCode}:validate`
   - 校验规则合法性、token 完整性、长度、保留字、冲突风险
3. `POST /api/meta/code-rules/{ruleCode}:simulate-generate`
   - 模拟生成多个候选编码，不入库

## 12.2.1 `POST /api/meta/code-rules/{ruleCode}:preview`

请求建议：

```json
{
  "context": {
    "BUSINESS_DOMAIN": "MATERIAL",
    "CATEGORY_CODE": "MATERIAL-0001",
    "ATTRIBUTE_CODE": "ATTR_000001",
    "LOV_CODE": "ATTR_000001_LOV",
    "USER_INPUT": "FASTENER"
  },
  "manualCode": null,
  "count": 3
}
```

响应建议：

```json
{
  "ruleCode": "CATEGORY",
  "ruleVersion": 2,
  "pattern": "{BUSINESS_DOMAIN}-{SEQ}",
  "examples": [
    "MATERIAL-0001",
    "MATERIAL-0002",
    "MATERIAL-0003"
  ],
  "warnings": []
}
```

校验原则：

- preview 不占用正式序列
- 仅做语法与上下文验证
- 若 `manualCode` 非空，则同步校验是否满足规则约束

## 12.3 编码生成 API

建议新增统一生成入口：

1. `POST /api/meta/codes:generate`

请求建议：

```json
{
  "ruleCode": "ATTRIBUTE",
  "targetType": "attribute",
  "context": {
    "BUSINESS_DOMAIN": "MATERIAL",
    "CATEGORY_CODE": "MATERIAL-0001"
  },
  "manualCode": null,
  "freezeAfterGenerate": true
}
```

返回建议：

```json
{
  "code": "ATTR_000123",
  "ruleCode": "ATTRIBUTE",
  "ruleVersion": 3,
  "manualOverride": false,
  "frozen": true
}
```

## 12.4 使用情况与冲突分析 API

建议新增：

1. `GET /api/meta/code-rules/{ruleCode}/usage`
2. `GET /api/meta/codes/{code}`
3. `GET /api/meta/codes/{code}/conflicts`

## 12.3.1 `POST /api/meta/codes:generate` 首期字段收敛

为避免首期过宽，建议生成接口只支持单条生成，不做批量生成：

请求字段：

- `ruleCode`：必填
- `targetType`：必填，`CATEGORY` / `ATTRIBUTE` / `LOV_DEFINITION` / `LOV_ITEM`
- `targetId`：可选，若对象已存在则用于审计绑定
- `context`：可选，token 上下文
- `manualCode`：可选，人工填写编码
- `operator`：可选，操作人
- `freezeAfterGenerate`：可选，默认 `false`

生成逻辑：

1. 读取 active 规则
2. 若 `manualCode` 非空：
   - 校验规则是否允许人工覆盖
   - 校验唯一性、正则、保留字、长度
   - 记录 `manual_override_flag=true`
3. 若 `manualCode` 为空：
   - 按规则生成
   - 若 pattern 包含 `{SEQ}`，才真正占用序列
4. 写审计记录
5. 返回生成结果

## 12.3.2 首期对象写接口如何对接生成接口

下一轮实际落代码时，建议不是让前端先单独调生成接口，而是后端内部先统一调用编码服务：

- 分类创建：`MetaCategoryCrudService.create(...)`
- 属性创建：`MetaAttributeManageService.create(...)`
- 属性导入：`MetaAttributeImportService`
- LOV 定义生成：统一走编码服务

这样可以保证：

- 首期前端无需联调太多新接口
- 后端先把事实源统一起来
- 后续再开放设计器与独立生成接口

---

## 12.5 首期 DTO 草案

为便于下一轮直接实现，建议首期先定义以下 DTO：

### 规则详情 DTO

- `CodeRuleDetailDto`
  - `ruleCode`
  - `name`
  - `targetType`
  - `scopeType`
  - `scopeValue`
  - `pattern`
  - `status`
  - `allowManualOverride`
  - `regexPattern`
  - `maxLength`
  - `latestVersionNo`
  - `latestRuleJson`

### 规则保存 DTO

- `CodeRuleSaveRequestDto`
  - `ruleCode`
  - `name`
  - `targetType`
  - `scopeType`
  - `scopeValue`
  - `pattern`
  - `allowManualOverride`
  - `regexPattern`
  - `maxLength`
  - `ruleJson`

### 规则预览 DTO

- `CodeRulePreviewRequestDto`
  - `context`
  - `manualCode`
  - `count`

- `CodeRulePreviewResponseDto`
  - `ruleCode`
  - `ruleVersion`
  - `pattern`
  - `examples`
  - `warnings`

### 编码生成 DTO

- `CodeGenerateRequestDto`
  - `ruleCode`
  - `targetType`
  - `targetId`
  - `context`
  - `manualCode`
  - `operator`
  - `freezeAfterGenerate`

- `CodeGenerateResponseDto`
  - `code`
  - `ruleCode`
  - `ruleVersion`
  - `manualOverride`
  - `frozen`
  - `warnings`

---

## 13. 设计器页面建议

设计器建议至少提供以下能力：

## 13.1 规则列表页

展示字段：

- 规则编码
- 规则名称
- 目标对象类型
- 作用域
- 当前状态
- 最新版本号
- 最后发布时间

操作：

- 查看
- 编辑草稿
- 预览
- 发布
- 查看历史
- 回滚

## 13.2 规则编辑页

应包含：

1. 基本信息区
   - 规则编码
   - 名称
   - 说明
   - 目标类型
   - 作用域

2. Pattern 设计区
   - 可视化插入 token
   - 输入 pattern
   - 实时高亮 token

3. 校验规则区
   - 最大长度
   - 正则规则
   - 保留字列表
   - 是否允许人工覆盖

4. 预览区
   - 手动填写上下文
   - 即时生成样例

## 13.3 发布与回滚页

发布前展示：

- 与当前版本的差异
- 预期生成样例
- 潜在冲突
- 受影响对象范围

回滚时：

- 不建议直接覆盖老版本
- 建议基于历史版本生成一个新的 draft / active 版本

## 13.4 本期设计器实现边界

虽然本文是“编码管理与设计器”方案，但下一轮代码落地建议优先后端，前端设计器只保留范围定义：

首期建议后端先行，前端设计器不作为必须交付项。

本期设计器边界：

1. 文档层完成页面能力定义
2. 后端先提供规则管理 API
3. 真正的管理后台页面延后到下一阶段

这样可以避免本轮同时开启：

- Flyway 迁移
- Domain / Repository 改造
- Service 改造
- Controller / DTO 新增
- 前端管理页联动

导致实现范围过大

---

## 14. 对现有写流程的接入建议

## 14.1 分类创建

建议新增生成模式字段：

- `generationMode`
  - `AUTO`
  - `MANUAL`

推荐默认：

- `AUTO`

当规则允许手工覆盖时，`MANUAL` 才可用。

建议创建接口内部处理顺序：

1. 读取 CATEGORY active 规则
2. 若请求带 `generationMode=MANUAL`：
  - 校验规则允许手填
  - 校验 `code`
3. 若请求未显式指定或为 `AUTO`：
  - 通过统一编码服务生成分类编码
4. 将生成结果落到 `meta_category_def.code_key`
5. 同步写入治理字段：
  - `code_key_manual_override`
  - `code_key_frozen`
  - `generated_rule_code`
  - `generated_rule_version_no`

## 14.2 属性创建/导入

建议：

- 统一改成走编码服务，而不是直接调用 `CodeRuleGenerator`。
- `MetaAttributeImportService` 与 `MetaAttributeManageService` 最终都应收口到同一编码入口。

建议首期处理顺序：

1. 属性管理 create/update 先统一为编码服务调用
2. 属性导入再从直接生成器切换到编码服务

原因：

- 管理接口更容易验证和回归
- 导入链路更长，适合在第二步改

## 14.3 LOV 定义创建

建议：

- 统一由 `LOV_DEFINITION` 规则生成。
- `AttributeLovImportUtils.generateLovKey(...)` 保留为过渡兼容逻辑，不再作为长期主方案。

建议首期兼容策略：

1. 若已有 `lovKey` 显式传入，则按手工覆盖校验
2. 若无 `lovKey`：
  - 新逻辑优先走统一编码服务
  - 保留工具函数作为回退逻辑，仅用于迁移期间兜底

回退逻辑应在文档与代码中标记为“临时兼容”，避免固化为长期行为

## 14.4 LOV 项维护

建议：

- 当用户录入项时，可选择：
  - 自动生成项编码
  - 手工填写项编码
- 保存前必须统一走校验接口。

建议首期只实现两项治理：

1. 保存前统一校验项级 `code` 唯一性
2. 若前端未填 `code`，则按 `LOV_ITEM` 规则生成

首期不做：

- LOV 项单独查询 API
- LOV 项独立审计页面
- LOV 项结构化表迁移

---

## 14.5 首期代码改造清单

为保证下一轮可以直接进入实现，建议按以下顺序改造：

1. Flyway
  - 扩展 `meta_code_rule`
  - 扩展 `meta_code_rule_version`
  - 新增 `meta_code_generation_audit`
  - 扩展 category/attribute/lov def 治理字段

2. Domain / Repository
  - 补实体字段映射
  - 新增规则查询与审计 repository

3. Service
  - 新增统一编码服务，例如 `MetaCodeService`
  - `CodeRuleGenerator` 作为其底层生成组件保留

4. Controller / DTO
  - 新增规则管理接口
  - 新增 preview 接口

5. 接入业务服务
  - 分类创建
  - 属性创建
  - LOV 定义生成
  - 最后再处理属性导入与 LOV 项

---

## 15. 迁移策略建议

## 15.1 第一阶段：数据库扩展与规则梳理

目标：

- 不改业务写逻辑
- 先把统一规则模型准备好

动作：

1. 扩展 `meta_code_rule`、`meta_code_rule_version`、`meta_code_sequence`
2. 新增审计表
3. 补录现有 CATEGORY / ATTRIBUTE / LOV 规则元数据

## 15.2 第二阶段：统一编码服务上线

目标：

- 新增规则预览/校验/生成 API
- 保持现有业务接口兼容

动作：

1. 封装统一编码服务
2. 让 `CodeRuleGenerator` 退化为底层组件
3. 业务层逐步改用统一编码服务

## 15.3 第三阶段：设计器上线

目标：

- 让管理员可以维护规则，而不是继续改 Java/SQL

动作：

1. 上线规则列表、编辑、预览、发布页面
2. 规范发布审批与回滚流程

## 15.4 第四阶段：分类/属性/LOV 全面接入

目标：

- 分类、属性、LOV 定义、LOV 项全部统一纳入编码治理

动作：

1. 分类创建改默认自动生成
2. 属性创建与导入都改为走统一服务
3. LOV 定义与 LOV 项统一接入

## 15.5 下一轮代码落地的推荐范围

为了确保下一轮对话可以稳定完成，建议实现范围控制在后端 P0：

### 必做

1. Flyway 迁移
2. `meta_code_rule` / `meta_code_rule_version` / 审计表的实体与 repository
3. 统一编码服务
4. 规则管理基础接口
5. 规则 preview 接口
6. 分类创建接入统一编码服务

### 可选

1. 属性创建接入统一编码服务
2. LOV 定义创建接入统一编码服务

### 暂缓

1. 设计器前端页面
2. 回滚接口
3. 使用统计接口
4. LOV 项更深层治理
5. scope 化序列

---

## 16. 风险与开放问题

## 16.1 主要风险

1. 现有规则来源分散，首期统一接入时容易出现“新规则入口”和“旧生成逻辑”并存。
2. 分类编码虽然已明确自动编码优先，但仍允许手工填写，后续要重点控制人工覆盖的审计与冲突校验。
3. 若序列粒度不扩展，设计器支持“按作用域配置规则”会落空。

补充说明：

- 当前环境中的历史数据均为测试数据，不构成存量迁移风险。
- 因此“旧数据并存影响生产”的问题，本期不作为主要风险项。

## 16.2 评审已确认的边界结论

本轮评审已明确以下结论，后续设计与开发均按此边界执行：

1. 现有数据均为测试数据，允许旧规则与新规则阶段性并存，不需要额外设计存量兼容治理方案。
2. 分类编码采用“自动编码优先，同时允许用户手工填写维护”的双轨制。
3. LOV 项继续保留在 JSON 中，本期不考虑拆表。
4. 外部编码本期不纳入统一编码设计范围。
5. 审批流属于中后期能力，本期不纳入设计范围。
6. 本期不考虑按业务域隔离规则维护权限。

## 16.3 下一轮实现前的默认假设

为避免下一轮实现时反复确认，本草案先固化以下默认假设：

1. 首期只做后端，不做管理端页面。
2. 首期以分类编码为第一落地点。
3. 首期规则状态流转采用最简模型：`DRAFT -> ACTIVE -> ARCHIVED`。
4. 首期 preview 不占用正式序列。
5. 首期 generate 接口仅支持单次生成，不做批量事务编排。

---

## 17. 本轮建议结论

结合当前数据库结构与实现现状，建议本项目按以下路线推进：

1. 首期采用“统一规则模型 + 统一编码服务 + 设计器草稿/发布能力”的方案。
2. 分类编码采用“自动编码优先，同时允许人工填写维护”的双轨制。
3. 属性编码继续由统一规则生成，并逐步替换直接调用生成器的实现。
4. LOV 定义编码统一收口为 `LOV_DEFINITION` 规则，不再长期依赖工具函数拼接。
5. LOV 项编码本期继续放在 JSON 中，但必须纳入统一规则和校验机制。
6. 外部编码、审批流、按业务域隔离权限均不纳入本期范围。
7. `meta_code_sequence` 需要补足作用域粒度，否则设计器只能停留在全局规则层。
8. 下一轮代码实现建议先完成后端 P0，优先打通“规则治理基础能力 + 分类自动编码接入”。

---

## 18. 状态流转与实现口径

为便于下一轮直接编码，这里统一首期状态流转口径。

## 18.1 规则状态流转

首期仅支持：

```text
DRAFT -> ACTIVE -> ARCHIVED
```

约束：

1. 新建规则默认 `DRAFT`
2. 只有 `DRAFT` 可编辑
3. 发布后变为 `ACTIVE`
4. 同一 `ruleCode` 在同一作用域下仅允许一个 `ACTIVE`
5. 历史 active 版本在新版本发布后转为 `ARCHIVED`

## 18.2 编码生成模式

首期统一：

```text
AUTO / MANUAL
```

约束：

1. `AUTO` 为默认模式
2. `MANUAL` 仅在规则 `allowManualOverride=true` 时可用
3. `MANUAL` 必须经过唯一性、正则、保留字校验
4. 是否冻结由对象侧字段决定，不由规则状态隐式代替

---

## 19. 建议下一步

若本草案评审通过，建议下一步分两件事继续：

1. 下一轮直接按本草案的 P0 范围开始后端代码落地。
2. 代码落地完成后，再补设计器页面原型与前端接入方案。
