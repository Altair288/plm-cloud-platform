# 分类子树一次性查询接口设计草案

更新时间：2026-03-17  
阶段：设计草案（本阶段不改代码）

---

## 1. 背景

当前分类查询接口以树懒加载为主，前端在分类移动/复制场景中改为复选框批量选择后，存在以下问题：

- 未展开的节点不会触发加载。
- 无法一次获取父分类下完整后代集合。
- 批量移动/复制前的冲突校验（如目标节点不能落在源节点后代中）不完整。

因此需要新增一个“子树一次性查询”接口，专用于批量选择场景。

---

## 2. 目标与范围

目标：

- 支持通过父分类 id 一次性获取其子分类集合。
- 支持深度限制、状态过滤、是否包含根节点。
- 支持前端复选框场景高效消费的数据结构。

范围：

- 仅新增查询接口草案，不修改现有懒加载接口。
- 不在本阶段实现异步大任务导出。

---

## 3. 与现有接口分工

继续使用懒加载接口的场景：

- 分类树浏览与逐级展开。
- 首屏轻量加载。

新增子树接口的场景：

- 批量移动。
- 批量复制。
- 批量勾选与后代完整性校验。

建议策略：

- 页面默认仍用懒加载。
- 进入批量模式或触发“全选子树”时调用子树接口。

---

## 4. 接口草案

### 4.1 路径与方法

- 方法：POST
- 路径：/api/meta/categories/nodes/subtree

说明：

- 使用 POST 便于承载复杂查询参数（深度、状态、返回模式、大小上限等）。
- 避免 GET 查询串过长。

### 4.2 请求体

```json
{
  "parentId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
  "includeRoot": true,
  "maxDepth": -1,
  "status": "ALL",
  "mode": "FLAT",
  "nodeLimit": 2000
}
```

字段定义：

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---:|---|---|
| parentId | UUID | 是 | - | 子树起点分类 ID |
| includeRoot | boolean | 否 | true | 是否包含 parentId 对应节点 |
| maxDepth | int | 否 | -1 | 最大向下深度；-1 表示不限制（受 nodeLimit 约束） |
| status | string | 否 | ACTIVE | 状态过滤，支持 ACTIVE/INACTIVE/DRAFT/ALL |
| mode | string | 否 | FLAT | 返回结构：FLAT/TREE |
| nodeLimit | int | 否 | 2000 | 结果节点上限，建议最大 10000 |

---

## 5. 响应结构

统一外层：

```json
{
  "parentId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
  "mode": "FLAT",
  "totalNodes": 5,
  "truncated": false,
  "depthReached": 2,
  "data": []
}
```

### 5.1 FLAT 模式

```json
{
  "parentId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
  "mode": "FLAT",
  "totalNodes": 3,
  "truncated": false,
  "depthReached": 2,
  "data": [
    {
      "id": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
      "parentId": null,
      "code": "C",
      "name": "C大类",
      "status": "ACTIVE",
      "depth": 0,
      "path": "/C",
      "hasChildren": true
    },
    {
      "id": "9df774b4-1216-4bfa-8a5f-43d35c1f4828",
      "parentId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
      "code": "98000000",
      "name": "二级分类状态冲突测试",
      "status": "DRAFT",
      "depth": 1,
      "path": "/C/98000000",
      "hasChildren": false
    }
  ]
}
```

### 5.2 TREE 模式（首期实现）

```json
{
  "parentId": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
  "mode": "TREE",
  "totalNodes": 2,
  "truncated": false,
  "depthReached": 1,
  "data": {
    "id": "8bfe9f28-3f1a-4bb8-a2fd-f033a7a7f0d1",
    "code": "C",
    "name": "C大类",
    "status": "ACTIVE",
    "children": [
      {
        "id": "9df774b4-1216-4bfa-8a5f-43d35c1f4828",
        "code": "98000000",
        "name": "二级分类状态冲突测试",
        "status": "DRAFT",
        "children": []
      }
    ]
  }
}
```

---

## 6. 性能与约束建议

- 子树接口不建议传统分页（page/size），会破坏树完整性。
- 使用 nodeLimit 控制单次结果体积。
- 当结果超限时返回：
  - truncated=true
  - totalNodes 为实际返回量
  - message 建议提示“结果过大，请缩小范围或降低深度”。
- 建议数据库侧保证：
  - parent_def_id 索引
  - status + parent_def_id 组合索引
  - 复用闭包表 category_hierarchy 做 descendants 查询

---

## 7. 错误码草案

| HTTP | code | 场景 |
|---|---|---|
| 400 | INVALID_ARGUMENT | parentId 缺失、maxDepth 非法、nodeLimit 非法 |
| 404 | CATEGORY_NOT_FOUND | parentId 对应节点不存在 |
| 403 | CATEGORY_ACCESS_DENIED | 无访问权限（若后续接入权限控制） |
| 409 | CATEGORY_STATUS_CONFLICT | 请求状态与节点状态策略冲突 |
| 429 | TOO_MANY_REQUESTS | 高频调用限流 |
| 500 | INTERNAL_ERROR | 服务内部异常 |
| 504 | QUERY_TIMEOUT | 子树查询超时 |

---

## 8. 首期交付范围（一次性完成）

评审结论：首期不分阶段，直接完成整轮迭代。

首期交付清单：

- 接口路径统一采用：`POST /api/meta/categories/nodes/subtree`。
- 完整支持两种返回模式：`mode=FLAT` 与 `mode=TREE`。
- 请求参数一次到位：`parentId/includeRoot/maxDepth/status/mode/nodeLimit`。
- 状态过滤沿用现有语义：`ACTIVE/INACTIVE/DRAFT/ALL`。
- 默认 `nodeLimit=2000`，并保留服务端最大上限保护（建议 10000）。
- 响应统一返回：`totalNodes/truncated/depthReached`，支持前端兜底提示。
- 错误码按第 7 节一次到位。

本轮不纳入范围：

- 异步大子树导出任务。
- 字段裁剪（includeFields）与返回字段模板。

---

## 9. 与批量移动/复制联动建议

- 前端在“选择源分类”后，调用 subtree 接口拉取完整集合。
- 使用返回的 FLAT 数据进行：
  - 勾选汇总。
  - 去重。
  - 前置冲突校验（目标是否位于源后代）。
- 提交移动/复制时仅提交最终勾选 id 列表，避免二次不一致。

---

## 10. 评审结论

- 路径命名：统一采用 `/api/meta/categories/nodes/subtree`。
- `nodeLimit`：默认值 2000 通过评审。
- 状态过滤：沿用 `ACTIVE/INACTIVE/DRAFT/ALL` 通过评审。
- 交付策略：首期一步到位完成整轮迭代（FLAT + TREE 同步实现）。
