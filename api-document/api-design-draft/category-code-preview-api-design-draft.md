# 分类编码预计算接口设计草案

更新时间：2026-03-30
阶段：设计草案 + 落地实现基线

---

## 1. 目标

本文用于定义“创建分类时，根据业务领域绑定的编码规则自动计算建议编码，并返回前端自动填入输入框”的实现方案。

覆盖范围：

- 分类创建弹窗自动预填编码
- 基于业务领域规则集的分类编码预览
- 动态变量上下文组装
- 基本防重校验
- 避免虚假占用
- 深层树场景下的 Redis 缓存扩展方案

本稿同时作为本轮代码落地的事实基线。

---

## 2. 设计结论

本轮采用以下主方案：

1. 前端在新增分类弹窗中调用专用接口预览建议编码。
2. 后端不信任前端传入的动态变量上下文，只接受业务参数：`businessDomain`、`parentId`、`manualCode`。
3. 后端根据当前业务领域的活动规则集，解析分类规则并自行组装 preview 所需上下文。
4. preview 只返回建议编码，不占用正式序列，不做预留。
5. 真正创建分类时仍走 `POST /api/meta/categories`，由后端重新解析规则并正式生成编码。
6. Redis 缓存不作为本轮功能前置依赖，本轮先交付无缓存正确实现；Redis 作为深层树和复杂上下文的二阶段优化。

---

## 3. 为什么不直接复用 code-rules preview 给前端

现有后端已经有：

- `GET /api/meta/code-rule-sets/{businessDomain}`
- `POST /api/meta/code-rules/{ruleCode}:preview`
- `POST /api/meta/categories`

但分类创建页如果直接调用 `code-rules preview`，会把以下问题泄露给前端：

1. 前端需要自己解析当前业务领域用哪条 `categoryRuleCode`。
2. 前端需要自己拼动态变量上下文，例如 `BUSINESS_DOMAIN`、`PARENT_CODE`。
3. 后续如果分类规则扩展到 `ROOT_CODE`、`LEVEL`、祖先链变量，前端需要继续理解后端规则细节。

这会导致创建分类页面与规则设计页耦合过深。

因此建议新增业务语义明确的接口：

- `POST /api/meta/categories/code-preview`

由后端统一完成：

- 规则集解析
- 上下文装配
- preview 结果裁剪

---

## 4. 接口设计

## 4.1 分类编码预览

- 方法：`POST`
- 路径：`/api/meta/categories/code-preview`

### 请求体

```json
{
  "businessDomain": "MATERIAL",
  "parentId": "cae7a410-f951-4780-bad1-3c15ebed4dd4",
  "manualCode": null,
  "count": 1
}
```

### 字段说明

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| businessDomain | string | 是 | 当前分类所属业务领域 |
| parentId | UUID | 否 | 父级分类 ID；为空表示根分类 |
| manualCode | string | 否 | 若传入则按手工编码模式校验 |
| count | int | 否 | 预览候选数量，默认 1，最大 5 |

### 响应体

```json
{
  "businessDomain": "MATERIAL",
  "ruleCode": "CATEGORY",
  "generationMode": "AUTO",
  "allowManualOverride": true,
  "suggestedCode": "MATERIAL-0001",
  "examples": [
    "MATERIAL-0001"
  ],
  "warnings": [],
  "resolvedContext": {
    "BUSINESS_DOMAIN": "MATERIAL"
  },
  "resolvedSequenceScope": "CATEGORY#1:GLOBAL=GLOBAL",
  "resolvedPeriodKey": "NONE",
  "previewStale": false
}
```

### 响应字段说明

| 字段 | 类型 | 说明 |
|---|---|---|
| businessDomain | string | 当前业务领域 |
| ruleCode | string | 当前使用的分类规则编码 |
| generationMode | string | `AUTO` 或 `MANUAL` |
| allowManualOverride | boolean | 当前规则是否允许手工覆盖 |
| suggestedCode | string | 首选建议编码；等于 `examples[0]` |
| examples | string[] | 预览候选列表 |
| warnings | string[] | preview 警告 |
| resolvedContext | map | 实际参与渲染的上下文 |
| resolvedSequenceScope | string | 当前预览落入的序列桶 |
| resolvedPeriodKey | string | 当前预览落入的周期桶 |
| previewStale | boolean | 是否标记为“可能过时”；本轮固定返回 false |

---

## 5. 创建接口配合语义

分类创建继续沿用：

- `POST /api/meta/categories`

但前后端需要统一以下规则：

### AUTO 模式

```json
{
  "code": null,
  "generationMode": "AUTO",
  "name": "电子元器件",
  "businessDomain": "MATERIAL",
  "parentId": null,
  "status": "CREATED",
  "description": "..."
}
```

要求：

- 前端不提交最终 code
- 后端在事务内重新根据规则集正式生成编码

### MANUAL 模式

```json
{
  "code": "MAT-CUSTOM-001",
  "generationMode": "MANUAL",
  "name": "电子元器件",
  "businessDomain": "MATERIAL",
  "parentId": null,
  "status": "CREATED",
  "description": "..."
}
```

要求：

- 后端仍需走统一编码规则校验
- 仅当规则允许手工覆盖时才允许成功

---

## 6. 动态变量上下文策略

本轮后端统一负责组装上下文，不接受前端直接传入任意变量。

### 6.1 根分类

```json
{
  "BUSINESS_DOMAIN": "MATERIAL"
}
```

### 6.2 子分类

```json
{
  "BUSINESS_DOMAIN": "MATERIAL",
  "PARENT_CODE": "MAT-001"
}
```

### 6.3 二阶段可扩展变量

若未来分类规则需要下列变量，由后端在 resolver 中扩展：

- `ROOT_CODE`
- `LEVEL`
- `PATH`
- `ANCESTOR_CODES`

前端不应感知这些变量如何被推导。

---

## 7. 防重校验策略

## 7.1 预览阶段

- `AUTO`：返回建议编码，不承诺最终仍然可用。
- `MANUAL`：先做规则合法性校验，并可选做一次存在性预检。

## 7.2 创建阶段

- `AUTO`：正式生成编码后做一次唯一性兜底校验。
- `MANUAL`：对 `(businessDomain, code)` 做最终存在性校验。
- 任意并发场景下，以后端事务内校验和数据约束为准。

说明：

- preview 允许“读到旧值”，但 create 不允许写出重复值。

---

## 8. 虚假占用处理

本轮明确不做以下机制：

- 预留编码 reservation token
- 占号超时回收
- 打开弹窗即占号

理由：

1. 现有编码规则 preview 已天然不占用正式序列。
2. 创建时正式 generate 才更新序列桶，语义清晰。
3. 引入占号会放大并发补偿、过期清理与误占用复杂度。

产品语义统一为：

- “预览值仅供展示，保存时以后端最终生成结果为准。”

---

## 9. Redis 深层树缓存设计（二阶段）

当分类编码规则需要更多树上下文，且父链查询成本明显上升时，引入 Redis。

### 9.1 推荐缓存键

#### 节点上下文快照

- key：`category:ctx:{categoryId}`

value 示例：

```json
{
  "id": "...",
  "businessDomain": "MATERIAL",
  "code": "MAT-001",
  "parentId": "...",
  "parentCode": "MAT",
  "rootCode": "MAT",
  "depth": 3,
  "path": "/MAT/MAT-001",
  "modifiedAt": "2026-03-30T10:00:00Z"
}
```

#### 祖先链映射

- key：`category:chain:{categoryId}`

value 示例：

```json
{
  "ancestorIds": ["...", "..."],
  "ancestorCodes": ["MAT", "MAT-001"]
}
```

### 9.2 读取顺序

1. 优先读 Redis
2. miss 时回源 DB / 闭包表 / path 查询
3. 回填 Redis

### 9.3 失效策略

- 创建节点：写入新节点缓存
- 移动节点：删除当前节点及子树缓存
- 删除节点：删除当前节点及子树缓存
- 设置 TTL：10~30 分钟，避免脏缓存长期滞留

### 9.4 设计边界

- Redis 只做加速，不做真相源
- 即使 Redis 不可用，分类编码预览和创建也必须能回源成功

---

## 10. 前端交互方案

## 10.1 弹窗打开时

1. 填充 `businessDomain` 和 `parentId`
2. 调用 `POST /api/meta/categories/code-preview`
3. 将 `suggestedCode` 自动填入编码输入框

## 10.2 默认行为

- 默认 `generationMode=AUTO`
- 编码输入框只读
- 标注“系统预计算，保存时以后端最终结果为准”

## 10.3 手工模式

当 `allowManualOverride=true` 时：

- 显示“手工指定编码”开关
- 开启后切换为 `generationMode=MANUAL`
- 输入框改为可编辑
- 输入值变化后重新请求 preview，用 `manualCode` 做规则校验

## 10.4 提交行为

- `AUTO`：不提交 code
- `MANUAL`：提交 code
- 保存成功后以前端收到的 `created.code` 为准

---

## 11. 本轮实现边界

本轮代码落地仅包含：

1. 新增分类编码预览接口
2. 创建分类弹窗接入预览能力
3. 分类创建请求补齐 `generationMode` 语义
4. 保留当前正式生成逻辑不变

本轮不包含：

1. Redis 接入
2. 预留编码机制
3. 复杂祖先链变量扩展
4. 批量创建分类时的编码批量预估

---

## 12. 落地清单

后端：

- `CreateCategoryCodePreviewRequestDto`
- `CreateCategoryCodePreviewResponseDto`
- `POST /api/meta/categories/code-preview`
- `MetaCategoryCrudService.previewCreateCode(...)`

前端：

- `metaCategoryApi.previewCreateCode(...)`
- `CreateCategoryModal` 自动预填编码
- `CreateCategoryRequestDto` 补 `generationMode` / `freezeCode`
- 创建提交切换 AUTO / MANUAL 语义
