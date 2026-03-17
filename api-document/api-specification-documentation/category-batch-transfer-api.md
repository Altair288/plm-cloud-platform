# 分类批量移动/复制接口专项文档

更新时间：2026-03-17
适用模块：plm-attribute-service

---

## 1. 接口概览

- 方法：POST
- 路径：/api/meta/categories/batch-transfer
- 目标：统一承载分类批量 MOVE / COPY 操作

适用场景：

- 前端批量移动多个分类节点到同一目标父节点
- 前端批量复制多个分类节点到同一目标父节点或不同目标父节点
- 前端在正式提交前先执行 dryRun 预检
- 前端需要按 operation 维度展示成功、失败、归一化、回滚结果

---

## 2. 请求模型

### 2.1 请求体字段

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| businessDomain | string | 是 | - | 本次批处理所属业务域 |
| action | string | 是 | - | MOVE 或 COPY |
| targetParentId | UUID | 否 | null | 批次默认目标父节点；单项可覆盖 |
| dryRun | boolean | 否 | false | true 仅校验，不执行写入 |
| atomic | boolean | 否 | false | true 任一失败整批回滚 |
| operator | string | 否 | null | 操作人 |
| copyOptions | object | 否 | null | 仅 COPY 生效 |
| operations | array | 是 | - | 批处理操作列表，最多 200 条 |

operations[i] 字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| clientOperationId | string | 否 | 前端侧操作流水号，用于结果回填 |
| sourceNodeId | UUID | 是 | 源节点 ID |
| targetParentId | UUID | 否 | 单项目标父节点；优先级高于批次 targetParentId |

copyOptions 字段：

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| versionPolicy | string | 否 | CURRENT_ONLY | 首期仅支持当前快照复制 |
| codePolicy | string | 否 | AUTO_SUFFIX | 编码冲突策略 |
| namePolicy | string | 否 | KEEP | 名称策略 |
| defaultStatus | string | 否 | DRAFT | 复制后默认状态 |

### 2.2 请求示例

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

---

## 3. 行为语义

### 3.1 source overlap 归一化

- 父子同时选中且目标相同：保留父节点，子节点返回 SOURCE_OVERLAP_NORMALIZED
- 父子同时选中且目标不同：失败，返回 CATEGORY_SOURCE_OVERLAP_TARGET_CONFLICT
- 兄弟节点同时选中：允许，逐项独立处理

### 3.2 MOVE

- 节点 id 不变
- 版本链不变
- code 不变
- 更新 parent_def_id、path、depth、full_path_name、sort_order、is_leaf
- 重建 category_hierarchy 中“外部祖先 -> 被移动子树”关系
- 原父节点和新父节点同级顺序自动连续重排

### 3.3 COPY

- 首期仅复制 latest version，对应 versionPolicy=CURRENT_ONLY
- 新对象生成全新 id
- 新版本从 versionNo=1 开始
- codePolicy=AUTO_SUFFIX，按当前实现自动派生 `-COPY-001`、`-COPY-002`...
- namePolicy=KEEP，默认沿用原名称
- defaultStatus 默认写为 draft
- 每个复制出的新分类都会写入 copiedFromCategoryId

复制来源字段：

- 落库字段：meta_category_def.copied_from_category_id
- 语义：记录当前新节点直接复制来源的原分类节点 id

---

## 4. 响应模型

### 4.1 顶层响应字段

| 字段 | 类型 | 说明 |
|---|---|---|
| total | int | 提交操作总数 |
| successCount | int | 成功项数 |
| failureCount | int | 失败项数 |
| normalizedCount | int | 被祖先归一化跳过的项数 |
| movedCount | int | MOVE 成功累计影响节点数 |
| copiedCount | int | COPY 成功累计创建节点数 |
| atomic | boolean | 是否 atomic 模式 |
| dryRun | boolean | 是否 dryRun 模式 |
| warnings | array | 批次级 warning |
| results | array | 逐项结果 |

### 4.2 results[i] 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| clientOperationId | string | 对应前端提交的操作流水号 |
| sourceNodeId | UUID | 原始源节点 |
| normalizedSourceNodeId | UUID | 归一化后实际生效的源节点 |
| targetParentId | UUID | 最终目标父节点 |
| action | string | MOVE 或 COPY |
| success | boolean | 是否成功 |
| affectedNodeCount | int | 影响节点数 |
| movedIds | UUID[] | MOVE 成功涉及的节点 id 列表 |
| createdRootId | UUID | COPY 后新根节点 id |
| createdIds | UUID[] | COPY 后整棵新树 id 列表 |
| copiedFromCategoryId | UUID | 当前结果关联的直接来源分类 |
| sourceMappings | array | sourceNodeId -> createdNodeId -> copiedFromCategoryId 映射 |
| codeMappings | array | oldCode -> newCode 映射 |
| code | string | 结果码 |
| message | string | 结果消息 |
| warning | array | 项级 warning |

### 4.3 响应示例

```json
{
  "total": 2,
  "successCount": 2,
  "failureCount": 0,
  "normalizedCount": 1,
  "movedCount": 3,
  "copiedCount": 0,
  "atomic": false,
  "dryRun": false,
  "warnings": [
    "1 child operation normalized because ancestor already included"
  ],
  "results": [
    {
      "clientOperationId": "OP-MOVE-ROOT",
      "sourceNodeId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
      "normalizedSourceNodeId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
      "targetParentId": "cae7a410-f951-4780-bad1-3c15ebed4dd4",
      "action": "MOVE",
      "success": true,
      "affectedNodeCount": 3,
      "movedIds": [
        "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
        "9df774b4-1216-4bfa-8a5f-43d35c1f4828",
        "f40e8e8e-b2f6-4f75-a6d2-f4254e91dbf7"
      ],
      "createdRootId": null,
      "createdIds": null,
      "copiedFromCategoryId": null,
      "sourceMappings": null,
      "codeMappings": null,
      "code": null,
      "message": null,
      "warning": null
    },
    {
      "clientOperationId": "OP-MOVE-CHILD",
      "sourceNodeId": "9df774b4-1216-4bfa-8a5f-43d35c1f4828",
      "normalizedSourceNodeId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
      "targetParentId": "cae7a410-f951-4780-bad1-3c15ebed4dd4",
      "action": "MOVE",
      "success": true,
      "affectedNodeCount": 0,
      "movedIds": null,
      "createdRootId": null,
      "createdIds": null,
      "copiedFromCategoryId": null,
      "sourceMappings": null,
      "codeMappings": null,
      "code": "SOURCE_OVERLAP_NORMALIZED",
      "message": "source node skipped because ancestor already covers subtree",
      "warning": [
        "normalized by ancestor operation"
      ]
    }
  ]
}
```

---

## 5. 结果码

| code | 场景 |
|---|---|
| CATEGORY_NOT_FOUND | 源节点不存在 |
| CATEGORY_TARGET_PARENT_NOT_FOUND | 目标父节点不存在 |
| CATEGORY_TARGET_IS_SELF | 目标父节点就是源节点自身 |
| CATEGORY_TARGET_IN_DESCENDANT | 目标父节点位于源节点后代中 |
| CATEGORY_DOMAIN_MISMATCH | 业务域不一致 |
| CATEGORY_DELETED | 源或目标已删除 |
| CATEGORY_SOURCE_OVERLAP_TARGET_CONFLICT | 父子同时选中且目标不同 |
| SOURCE_OVERLAP_NORMALIZED | 父子同时选中且被祖先归一化 |
| CATEGORY_CODE_CONFLICT | copy 派生 code 失败 |
| ATOMIC_ROLLBACK | atomic 模式下已执行项被回滚 |
| ATOMIC_ABORTED | atomic 模式下未执行项被中止 |
| INVALID_ARGUMENT | 参数非法 |
| INTERNAL_ERROR | 系统异常 |

---

## 6. HTTP 语义

- 200：请求格式合法，服务端已进入逐项处理阶段；即使内部有部分失败也返回 200
- 400：请求体非法，整批无法开始处理
- 404：批次级目标节点不存在等无法进入逐项执行的错误
- 409：atomic 模式下出现整批性冲突

当前实现建议前端使用方式：

1. 先执行 dryRun=true 做预检
2. 用户确认后再执行正式请求
3. 按 results[i].clientOperationId 回填前端操作结果

---

## 7. 关联接口

- 分类总览文档：category-api.md
- 子树查询接口：POST /api/meta/categories/nodes/subtree
- 批量删除接口：POST /api/meta/categories/batch-delete
