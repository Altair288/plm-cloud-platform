# 业务领域编码规则集后端设计草案

更新时间：2026-03-27
阶段：后端设计草案（评审版，本稿不直接改代码）

---

## 1. 目标

本稿用于解决当前统一编码后端与前端信息架构之间的结构性偏差。

前端期望的流程是：

1. 先进入某个业务领域的编码规则集配置。
2. 在该业务领域下统一维护：
   - 分类编码规则
   - 属性编码规则
   - 枚举值编码规则
3. 前端获取详情时一次性拿到整套完整规则配置。
4. 后续可以继续扩展新的业务领域，并让每个业务领域拥有自己唯一的一套颗粒化规则。

当前后端已经具备单条编码规则、版本、发布、预览、运行时生成能力，但缺少“业务领域规则集”这一聚合层。

因此本稿的目标不是推翻现有编码规则体系，而是在现有后端能力之上补出一个可长期演进的业务领域规则集模型，使后续能够：

1. 为每个业务领域绑定唯一一套编码规则。
2. 在分类、属性、枚举值生成链路中按业务领域选择规则。
3. 明确禁止跨业务领域复用单条规则。
4. 为前端提供“按业务领域管理规则集”的稳定信息架构。
5. 保留现有单条规则能力，避免一次性重写整个编码引擎。

---

## 1.1 本轮评审已确认结论

本稿以下设计边界已在评审中确认，本版草案按这些结论收口：

1. 一个业务领域只允许存在一套规则集。
2. 规则集下的三条单条规则彼此独立，但不允许跨业务领域复用。
3. 发布以“整组发布”为准，发布后规则集及其下三条规则统一为 `ACTIVE`。
4. 本轮不做规则集版本、回滚等能力。
5. 自动编码场景下如果找不到规则集或规则，不允许回退到内置规则，必须显式报错提示用户配置。
6. 前端获取规则集详情时，需要一次性返回完整的规则配置信息，而不是只返回摘要。

---

## 2. 当前后端现状提取

以下结论基于当前实际后端实现。

## 2.1 当前规则主模型是“单条规则”

当前规则主实体为 `MetaCodeRule`，核心字段为：

- `code`
- `name`
- `targetType`
- `pattern`
- `scopeType`
- `scopeValue`
- `status`

当前模型中不存在：

- `businessDomainRuleSetId`
- `businessDomain`
- `categoryRuleCode`
- `attributeRuleCode`
- `lovRuleCode`

也就是说，当前后端管理的是“一条规则”，不是“某个业务领域下的一组规则”。

## 2.2 当前实际生效的是三条内置规则

当前业务生成链路中，真实生效的规则主要是：

- `CATEGORY`
- `ATTRIBUTE`
- `LOV`

当前调用方式是固定写死的：

- 分类创建调用 `generateCode("CATEGORY", ...)`
- 属性创建调用 `generateCode("ATTRIBUTE", ...)`
- 枚举值编码调用 `generateCode("LOV", ...)`

这意味着当前系统虽然支持自定义 ruleCode 的创建、更新、发布、预览，但业务写链路并没有“根据业务领域选一套规则”的协议。

## 2.3 业务领域目前是业务数据主键的一部分，不是规则聚合维度

分类主数据 `meta_category_def` 已经引入：

- `business_domain`
- 唯一约束 `(business_domain, code_key)`

这说明业务领域已经成为分类侧的一等业务维度。

但规则中心并没有同步建立：

- 业务领域 -> 规则集
- 规则集 -> 分类/属性/枚举值规则

因此当前状态是：

- 业务数据侧已有业务领域
- 规则管理侧没有业务领域规则集

两边层级不一致。

## 2.4 scopeType / scopeValue 不能承担规则集语义

当前 `scopeType` / `scopeValue` 存在于 `MetaCodeRule` 中，但在当前实现里主要用于：

- 创建时保存
- 更新时保存
- 详情回显

它们没有真正进入：

- 规则查询选择
- 规则发布选择
- 业务生成链路选型

真正参与序列分桶的是 `ruleJson` 中的 `scopeKey`，再结合运行时 `context` 推导 `scopeValue`。

因此：

- `scopeType/scopeValue` 更像是规则自身的元信息
- 不是“业务领域下一组规则”的聚合机制

---

## 3. 当前后端与前端流程的偏差

## 3.1 前端操作对象是“规则集”，后端操作对象是“单条规则”

前端心智：

- 先选业务领域
- 再在该业务领域下管理三类规则

当前后端心智：

- 直接维护单条 `CATEGORY`、`ATTRIBUTE`、`LOV` 规则
- 规则是全局对象，不属于某个业务领域规则集

这是当前最大偏差。

## 3.2 前端期望“一个业务领域一套规则”，后端实际是“全局规则 + 上下文变量”

当前后端确实会在生成时传入：

- `BUSINESS_DOMAIN`
- `CATEGORY_CODE`
- `ATTRIBUTE_CODE`

但这只是运行时渲染上下文，不是规则归属关系。

它能表达：

- 同一条规则在不同上下文下生成不同编码

却不能表达：

- Material 用一套分类规则
- Device 用另一套分类规则
- 两个业务领域各自拥有独立、互不复用的规则集

## 3.3 当前发布粒度不适合“整组评审”

当前发布粒度是：

- 一条规则一个版本
- 一条规则单独发布

但前端和产品更容易需要的是：

- 某个业务领域的规则集统一评审
- 某个业务领域的三类规则统一启用

如果仍保持当前模型，前端会出现以下问题：

1. 页面上看起来像在编辑一个业务领域规则集。
2. 但实际保存时却要拆成三条独立规则。
3. 发布时也不是“发布整个规则集”，而是“分别发布三条规则”。

这种用户心智与后端行为不一致，后续非常容易引发误解。

## 3.4 后续扩展新业务领域时，当前模型无法自然承接

如果后续会扩展更多业务领域，例如：

- `MATERIAL`
- `DEVICE`
- `PROCESS`
- `DOCUMENT`

则每个领域都需要自己唯一的一套：

- 分类规则
- 属性规则
- 枚举值规则

当前后端如果继续只依赖全局 `CATEGORY` / `ATTRIBUTE` / `LOV` 三条规则，将不可避免地遇到：

1. 无法表达“不同领域各自不同的规则”。
2. 修改一个领域的规则会影响所有领域。
3. 前端界面虽按领域组织，但后端实际上没有领域隔离。

---

## 4. 设计目标与边界

## 4.1 设计目标

本次建议实现以下目标：

1. 在后端引入“业务领域规则集”聚合模型。
2. 允许每个业务领域绑定三类颗粒化规则：
   - 分类编码规则
   - 属性编码规则
   - 枚举值编码规则
3. 保留现有 `MetaCodeRule` / `MetaCodeRuleVersion` / `CodeRuleGenerator` 体系。
4. 让业务生成链路能够按业务领域选择规则，而不是固定写死三条全局内置规则。
5. 支持未来继续扩展新的业务领域，而不需要再次改造规则中心主结构。
6. 明确禁止跨业务领域复用单条规则，避免动态变量和层级派生跨领域引用导致语义混乱。

## 4.2 非目标

本稿暂不建议在本轮同时完成以下事项：

1. 不重写底层 `CodeRuleGenerator` 核心引擎。
2. 不废弃现有单条规则表结构。
3. 不把“业务领域规则集”做成另一套完全平行的编码引擎。
4. 不在本轮引入复杂权限流或审批流。
5. 不在本轮处理规则集版本与回滚。
6. 不在本轮处理“跨领域共享规则模板库”或规则复用能力。

---

## 5. 推荐方案

## 5.1 方案结论

推荐采用：

**在现有单条规则体系之上，新增“业务领域唯一规则集”聚合层。**

该方案的核心思路是：

1. 继续保留 `MetaCodeRule` 作为单条规则定义对象。
2. 新增 `业务领域规则集` 作为聚合对象。
3. 每个业务领域只允许存在一套规则集。
4. 每个规则集显式绑定三条颗粒化规则：
   - `categoryRuleCode`
   - `attributeRuleCode`
   - `lovRuleCode`
5. 三条单条规则必须归属当前业务领域，不允许被其他业务领域复用。
6. 业务写链路根据 `businessDomain` 找到当前唯一规则集，再选出对应 ruleCode 生成编码。

该方案的优点是：

1. 改造范围可控。
2. 与当前前端信息架构一致。
3. 与现有 `MetaCodeRuleService` / `CodeRuleGenerator` 兼容。
4. 能保证动态变量、层级派生都限定在当前业务领域内部。
5. 后续如果要做领域级版本和回滚，也有聚合对象可承载。

---

## 6. 目标模型

## 6.1 新增聚合对象

建议新增：

### `meta_code_rule_set`

表示一个业务领域下唯一的一组编码规则配置。

建议字段：

- `id`
- `business_domain`
- `name`
- `status`
- `active`
- `remark`
- `category_rule_code`
- `attribute_rule_code`
- `lov_rule_code`
- `created_at`
- `created_by`
- `updated_at`
- `updated_by`

说明：

- `business_domain`：规则集归属的业务领域，例如 `MATERIAL`
- `name`：规则集名称，例如 `Material 编码规则集`
- `status`：建议仍采用 `DRAFT / ACTIVE / ARCHIVED`
- `active`：是否当前启用
- `category_rule_code` / `attribute_rule_code` / `lov_rule_code`：直接指向该业务领域下三条单条规则

唯一约束建议：

- `business_domain` 唯一

这意味着：

- 一个业务领域只能存在一条规则集记录
- 前端也不需要在同一业务领域下管理多套候选规则集

## 6.2 单条规则的业务领域归属

为了明确禁止跨业务领域复用单条规则，建议扩展 `meta_code_rule` 增加：

- `business_domain`

并增加约束：

- `(business_domain, code)` 唯一
- `(business_domain, target_type)` 可选唯一

推荐在本轮直接收口为：

- 一个业务领域下，`CATEGORY` / `ATTRIBUTE` / `LOV` 各只有一条规则记录

如果采用该约束，则前端和后端都不再需要处理“同一业务领域下存在多条候选分类规则”的复杂度。

## 6.3 本轮不引入规则集版本表

根据本轮评审结论，本稿明确不建议在当前阶段新增：

- `meta_code_rule_set_version`

原因是：

1. 当前产品不需要规则集版本与回滚。
2. 先把“业务领域唯一规则集 + 三条独立规则 + 整组发布”跑通更重要。
3. 版本化能力可以在后续确认真实使用场景后再补。

---

## 7. 规则集与现有单条规则的关系

## 7.1 单条规则继续作为能力载体

以下能力仍由现有单条规则承担：

- `ruleJson` 结构
- 预览
- 发布校验
- 运行时生成
- 审计
- 序列作用域与周期规则

但需要补充一个新的约束：

- 单条规则必须归属某个业务领域
- 单条规则只能服务于所属业务领域的规则集
- 不允许被其他业务领域引用或复用

即：

- 规则集不替代单条规则引擎
- 规则集只负责“组织、绑定、选择”

## 7.2 规则集负责业务领域路由

规则集负责解决的是：

- 某个业务领域当前应该使用哪条分类规则
- 某个业务领域当前应该使用哪条属性规则
- 某个业务领域当前应该使用哪条枚举值规则

这正是当前后端缺失但前端已经天然需要的层次。

---

## 8. 建议接口设计

## 8.1 规则集管理接口

建议新增：

- `GET /api/meta/code-rule-sets`
- `GET /api/meta/code-rule-sets/{businessDomain}`
- `POST /api/meta/code-rule-sets`
- `PUT /api/meta/code-rule-sets/{businessDomain}`
- `POST /api/meta/code-rule-sets/{businessDomain}:publish`

### 列表接口示例返回

```json
[
  {
    "businessDomain": "MATERIAL",
    "name": "Material 编码规则集",
    "status": "ACTIVE",
    "active": true,
    "categoryRuleCode": "CATEGORY_MATERIAL",
    "attributeRuleCode": "ATTRIBUTE_MATERIAL",
    "lovRuleCode": "LOV_MATERIAL"
  }
]
```

### 创建/更新请求体建议

```json
{
  "businessDomain": "MATERIAL",
  "name": "Material 编码规则集",
  "remark": "Material 领域使用的唯一编码规则集",
  "categoryRuleCode": "CATEGORY_MATERIAL",
  "attributeRuleCode": "ATTRIBUTE_MATERIAL",
  "lovRuleCode": "LOV_MATERIAL"
}
```

### 创建/更新校验建议

- 同一 `businessDomain` 只能存在一条规则集
- `categoryRuleCode` / `attributeRuleCode` / `lovRuleCode` 必须全部存在
- 三条规则必须都归属当前 `businessDomain`
- 三条规则不允许被其他业务领域规则集引用

## 8.2 单条规则接口继续保留

现有接口继续保留：

- `GET /api/meta/code-rules`
- `GET /api/meta/code-rules/{ruleCode}`
- `POST /api/meta/code-rules`
- `PUT /api/meta/code-rules/{ruleCode}`
- `POST /api/meta/code-rules/{ruleCode}:publish`
- `POST /api/meta/code-rules/{ruleCode}:preview`

但规则接口需要补充一个关键约束：

- 规则创建时必须声明所属 `businessDomain`

建议定位调整为：

- 规则集接口：管理“业务领域唯一规则集”
- 单条规则接口：管理“当前业务领域下的单条规则内容”

也就是说，前端可以继续把单条规则编辑器嵌入规则集页面，但保存时要分两层：

1. 先保存当前业务领域下三条单条规则内容
2. 再保存该业务领域唯一规则集的绑定关系

## 8.3 规则集详情必须一次性返回完整配置

根据评审结论，规则集详情不应只返回摘要，而应一次性返回三条规则的完整配置，避免前端二次拼装。

建议返回：

```json
{
  "businessDomain": "MATERIAL",
  "name": "Material 编码规则集",
  "status": "ACTIVE",
  "active": true,
  "remark": "Material 领域使用的唯一编码规则集",
  "categoryRuleCode": "CATEGORY_MATERIAL",
  "attributeRuleCode": "ATTRIBUTE_MATERIAL",
  "lovRuleCode": "LOV_MATERIAL",
  "rules": {
    "CATEGORY": {
      "ruleCode": "CATEGORY_MATERIAL",
      "name": "Material 分类编码规则",
      "targetType": "category",
      "scopeType": "GLOBAL",
      "scopeValue": null,
      "pattern": "MAT-{SEQ}",
      "status": "ACTIVE",
      "active": true,
      "allowManualOverride": true,
      "regexPattern": "^[A-Z][A-Z0-9_-]{0,63}$",
      "maxLength": 64,
      "supportsHierarchy": true,
      "supportsScopedSequence": true,
      "supportedVariableKeys": ["BUSINESS_DOMAIN", "PARENT_CODE"],
      "latestRuleJson": {
        "pattern": "MAT-{SEQ}",
        "hierarchyMode": "APPEND_CHILD_SUFFIX",
        "subRules": {
          "category": {
            "separator": "-",
            "segments": [
              {"type": "STRING", "value": "MAT"},
              {"type": "SEQUENCE", "length": 3, "startValue": 1, "step": 1, "resetRule": "NEVER", "scopeKey": "GLOBAL"}
            ],
            "childSegments": [
              {"type": "SEQUENCE", "length": 3, "startValue": 1, "step": 1, "resetRule": "PER_PARENT", "scopeKey": "PARENT_CODE"}
            ],
            "allowedVariableKeys": ["BUSINESS_DOMAIN", "PARENT_CODE"]
          }
        },
        "validation": {
          "maxLength": 64,
          "regex": "^[A-Z][A-Z0-9_-]{0,63}$",
          "allowManualOverride": true
        }
      }
    },
    "ATTRIBUTE": {
      "ruleCode": "ATTRIBUTE_MATERIAL",
      "name": "Material 属性编码规则",
      "targetType": "attribute",
      "scopeType": "GLOBAL",
      "scopeValue": null,
      "pattern": "ATTR-{CATEGORY_CODE}-{SEQ}",
      "status": "ACTIVE",
      "active": true,
      "allowManualOverride": true,
      "regexPattern": "^[A-Z][A-Z0-9_-]{0,63}$",
      "maxLength": 64,
      "supportsHierarchy": false,
      "supportsScopedSequence": true,
      "supportedVariableKeys": ["BUSINESS_DOMAIN", "CATEGORY_CODE"],
      "latestRuleJson": {
        "pattern": "ATTR-{CATEGORY_CODE}-{SEQ}",
        "hierarchyMode": "NONE",
        "subRules": {
          "attribute": {
            "separator": "-",
            "segments": [
              {"type": "STRING", "value": "ATTR"},
              {"type": "VARIABLE", "variableKey": "CATEGORY_CODE"},
              {"type": "SEQUENCE", "length": 6, "startValue": 1, "step": 1, "resetRule": "PER_PARENT", "scopeKey": "CATEGORY_CODE"}
            ],
            "allowedVariableKeys": ["BUSINESS_DOMAIN", "CATEGORY_CODE"]
          }
        },
        "validation": {
          "maxLength": 64,
          "regex": "^[A-Z][A-Z0-9_-]{0,63}$",
          "allowManualOverride": true
        }
      }
    },
    "LOV": {
      "ruleCode": "LOV_MATERIAL",
      "name": "Material 枚举值编码规则",
      "targetType": "lov",
      "scopeType": "GLOBAL",
      "scopeValue": null,
      "pattern": "ENUM-{ATTRIBUTE_CODE}-{SEQ}",
      "status": "ACTIVE",
      "active": true,
      "allowManualOverride": true,
      "regexPattern": "^[A-Z][A-Z0-9_-]{0,63}$",
      "maxLength": 64,
      "supportsHierarchy": false,
      "supportsScopedSequence": true,
      "supportedVariableKeys": ["BUSINESS_DOMAIN", "CATEGORY_CODE", "ATTRIBUTE_CODE"],
      "latestRuleJson": {
        "pattern": "ENUM-{ATTRIBUTE_CODE}-{SEQ}",
        "hierarchyMode": "NONE",
        "subRules": {
          "enum": {
            "separator": "-",
            "segments": [
              {"type": "STRING", "value": "ENUM"},
              {"type": "VARIABLE", "variableKey": "ATTRIBUTE_CODE"},
              {"type": "SEQUENCE", "length": 2, "startValue": 1, "step": 1, "resetRule": "PER_PARENT", "scopeKey": "ATTRIBUTE_CODE"}
            ],
            "allowedVariableKeys": ["BUSINESS_DOMAIN", "CATEGORY_CODE", "ATTRIBUTE_CODE"]
          }
        },
        "validation": {
          "maxLength": 64,
          "regex": "^[A-Z][A-Z0-9_-]{0,63}$",
          "allowManualOverride": true
        }
      }
    }
  }
}
```

---

## 9. 业务写链路如何接入

## 9.1 分类创建

当前是：

- 直接调用 `generateCode("CATEGORY", ...)`

建议改为：

1. 根据请求中的 `businessDomain` 找到当前唯一规则集。
2. 取出 `categoryRuleCode`。
3. 调用 `generateCode(categoryRuleCode, ...)`。
4. 若当前业务领域未配置规则集或 `categoryRuleCode` 缺失，则在自动编码模式下直接报错，不允许回退。

## 9.2 属性创建/更新

当前是：

- 直接调用 `generateCode("ATTRIBUTE", ...)`

建议改为：

1. 从分类找到其 `businessDomain`。
2. 找到该业务领域当前唯一规则集。
3. 取出 `attributeRuleCode`。
4. 调用 `generateCode(attributeRuleCode, ...)`。
5. 若自动编码模式下找不到规则集或规则，则直接报错。

## 9.3 枚举值编码生成

当前是：

- 直接调用 `generateCode("LOV", ...)`

建议改为：

1. 从属性所属分类找到 `businessDomain`。
2. 找到该业务领域当前唯一规则集。
3. 取出 `lovRuleCode`。
4. 调用 `generateCode(lovRuleCode, ...)`。
5. 若自动编码模式下找不到规则集或规则，则直接报错。

## 9.4 导入链路

属性导入与枚举值导入必须与管理链路使用同一套规则解析逻辑，不允许另起一套映射。

建议统一抽象：

- `resolveRuleSetForBusinessDomain(...)`
- `resolveCategoryRuleCode(...)`
- `resolveAttributeRuleCode(...)`
- `resolveLovRuleCode(...)`

并建议新增统一错误：

- `CODE_RULE_SET_NOT_CONFIGURED`
- `CATEGORY_RULE_NOT_CONFIGURED`
- `ATTRIBUTE_RULE_NOT_CONFIGURED`
- `LOV_RULE_NOT_CONFIGURED`

这样用户在选择自动编码时，后端会明确提示其先完成对应业务领域规则配置。

---

## 10. 规则集状态与发布策略

本轮建议采用明确的“整组发布”策略：

- 规则集发布是主流程
- 发布时规则集及其下三条单条规则统一切换为 `ACTIVE`
- 前端不再把三条规则分别理解为独立生效对象

规则集发布时主要校验：

1. `businessDomain` 非空
2. 当前业务领域规则集已配置完整三条规则
3. 三条规则全部归属当前业务领域
4. 三条规则配置完整且通过各自发布校验
5. 规则集发布成功后，规则集与三条规则统一为 `ACTIVE`

本轮明确不设计：

- 规则集版本
- 规则集回滚
- 多套规则集之间切换

---

## 11. 向后兼容建议

## 11.1 兼容当前内置三条规则

为降低迁移成本，建议初始化时自动生成默认规则集：

### `MATERIAL`

绑定：

- `CATEGORY`
- `ATTRIBUTE`
- `LOV`

同时建议把这三条规则显式标记为 `MATERIAL` 领域所有，避免后续被其他业务领域误复用。

## 11.2 兼容当前业务写链路

迁移过程建议分两步：

### 第一步

- 新增规则集表与接口
- 初始化默认规则集
- 业务写链路增加“先查规则集，查不到则报错”的逻辑

### 第二步

- 分类、属性、枚举值写链路统一强制依赖规则集
- 不再允许直接依赖硬编码内置 ruleCode

## 11.3 兼容前端逐步迁移

前端可以分阶段改：

### 阶段 A

- 保留当前单条规则页面
- 新增规则集页面用于业务领域统一管理

### 阶段 B

- 在规则集详情页中嵌入三条单条规则编辑能力
- 形成“按业务领域维护规则集”的主入口

---

## 12. 风险与注意事项

## 12.1 规则集不是规则内容本身

规则集只解决：

- 一个业务领域下使用哪三条规则

它不替代：

- 单条规则的 `ruleJson` 结构
- 单条规则的 preview
- 单条规则的 publish 校验

如果把两者混为一谈，容易导致模型继续膨胀。

## 12.2 分类查询链路仍需进一步统一 businessDomain 主键语义

当前分类表已经是 `(business_domain, code_key)` 唯一，但部分服务仍保留按 `codeKey` 直接查分类的路径。

如果未来真的支持多个业务领域使用相同 `codeKey`，则这些旧查询路径会产生歧义。

因此规则集方案推进时，建议同步审查：

- 分类读取接口
- 属性写链路中的分类定位逻辑
- 导入链路中的分类匹配逻辑

确保最终业务领域不仅是规则路由维度，也是对象定位维度。

## 12.3 LOV 规则语义必须继续保持明确

当前 `LOV` 规则已被收口为：

- 枚举值项编码规则

不应在规则集模型下再次被误解释为：

- lov_def 绑定 key 规则

规则集中的 `lovRuleCode` 应明确表示：

- 该业务领域默认使用的枚举值编码规则

## 12.4 不允许跨业务领域复用单条规则

这是本轮评审明确确认的边界。

原因是：

1. 自动层级派生依赖当前业务领域内的分类编码语义。
2. 动态变量引用必须限定在当前业务领域的对象上下文中。
3. 一旦跨领域复用规则，会让 `BUSINESS_DOMAIN`、`CATEGORY_CODE`、`ATTRIBUTE_CODE` 的语义边界失控。

因此本稿明确建议：

- 单条规则必须归属于一个且仅一个业务领域
- 单条规则不能被多个业务领域共享
- 规则集绑定时必须校验单条规则归属是否一致

---

## 13. 推荐实施顺序

建议分 3 个阶段推进。

## 阶段 1：补聚合层

1. 新增 `meta_code_rule_set`
2. 给 `meta_code_rule` 增加 `business_domain`
3. 新增规则集管理接口
4. 初始化默认 `MATERIAL` 规则集

目标：

- 让后端具备“业务领域 -> 三类规则”的正式绑定模型

## 阶段 2：补运行时选择链路

1. 分类创建按 `businessDomain` 选择 `categoryRuleCode`
2. 属性创建按分类所属 `businessDomain` 选择 `attributeRuleCode`
3. 枚举值编码按属性所属分类的 `businessDomain` 选择 `lovRuleCode`
4. 导入链路同步切换
5. 自动编码缺少规则时统一报错，不允许回退

目标：

- 让规则集真正生效，而不只是一个管理台对象

## 阶段 3：补发布后治理能力

1. 规则集支持整组预览
2. 评估是否补充停用、重新编辑、再次发布流程
3. 后续如确有需求，再单独设计规则集版本与回滚

目标：

- 在不引入版本体系的前提下，先把规则集主流程跑通

---

## 14. 评审结论收口

本稿按以下已确认结论收口：

1. 一个业务领域只允许存在一套规则集。
2. 规则集下的单条规则不允许跨业务领域复用。
3. 发布采用整组发布，发布后规则集及其下三条规则统一为 `ACTIVE`。
4. 本轮不做规则集版本与回滚。
5. 自动编码场景下，找不到规则集或规则时直接报错，不允许回退。
6. 规则集详情接口需要一次性返回完整规则配置。

---

## 15. 结论

当前统一编码后端已经具备单条规则治理能力，但与前端“按业务领域维护唯一规则集”的产品形态之间存在明确结构性偏差。

为了支撑后续继续扩展多个业务领域，推荐在现有单条规则体系之上新增“业务领域唯一规则集”聚合层，而不是继续依赖全局 `CATEGORY / ATTRIBUTE / LOV` 三条内置规则硬编码路由。

该方案能够：

1. 与前端流程对齐。
2. 保留当前后端规则引擎投资。
3. 为未来多业务领域扩展预留稳定模型。
4. 避免跨业务领域复用规则带来的变量语义混乱。
5. 控制本轮改造风险，避免一次性重写整个规则中心。