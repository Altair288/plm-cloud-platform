# 分类模型重构草案：移除 taxonomy，改为业务领域归属

更新时间：2026-03-12  
状态：已实施并完成代码落地（保留为变更记录）

---

## 1. 背景

当前分类接口体系仍保留 taxonomy 语义（例如 taxonomy=UNSPSC、/api/meta/taxonomies/{code}）。

但在当前业务场景中：

- 分类的实际归属维度已是 businessDomain。
- taxonomy 概念不再承担业务分流价值。
- taxonomy 的继续保留会造成：
  - 参数冗余与心智负担。
  - 数据语义割裂（businessDomain 与 taxonomy 双轨并存）。
  - 前端展示与联调复杂度增加。

因此，拟将分类体系主归属从 taxonomy 切换为 businessDomain。

---

## 2. 目标

- 从业务语义层面移除 taxonomy。
- 统一采用 businessDomain 作为分类归属与查询分区维度。
- 确保分类 CRUD、树查询、搜索、详情、字典体系在同一语义下闭环。

---

## 3. 重构范围

### 3.1 接口层

涉及接口：

- GET /api/meta/categories/nodes
- GET /api/meta/categories/nodes/{id}/path
- GET /api/meta/categories/search
- POST /api/meta/categories/nodes:children-batch
- GET /api/meta/taxonomies/{code}（拟下线）

拟变更方向：

- taxonomy 参数移除或废弃。
- 引入或强化 businessDomain 参数（作为查询必填或默认维度）。
- taxonomy 元数据接口下线，层级展示规则转入业务领域配置或页面配置。

### 3.2 数据与约束层

当前已存在：

- meta_category_def.business_domain
- 唯一约束 (business_domain, code_key)

拟确认：

- 以 business_domain 作为所有分类查询主过滤维度。
- 移除文档与服务实现中对 taxonomy 的强绑定假设。

### 3.3 字典层

当前字典体系：

- META_CATEGORY_BUSINESS_DOMAIN
- META_CATEGORY_STATUS
- META_TAXONOMY

拟变更：

- 保留前两者。
- 下线或冻结 META_TAXONOMY（标记 deprecated）。
- 场景字典 category-admin 去除 META_TAXONOMY。

### 3.4 前端层

拟变更点：

- 所有分类查询改按 businessDomain 发起。
- 移除 taxonomy 相关表单项、默认值与文案。
- 树加载、搜索、详情、路径回显均以 businessDomain 维度组织。

---

## 4. API 目标形态（草案）

### 4.1 查询子节点

现状：GET /api/meta/categories/nodes?taxonomy=UNSPSC...

目标：GET /api/meta/categories/nodes?businessDomain=MATERIAL...

### 4.2 节点路径

现状：GET /api/meta/categories/nodes/{id}/path?taxonomy=UNSPSC

目标：GET /api/meta/categories/nodes/{id}/path?businessDomain=MATERIAL

### 4.3 搜索

现状：GET /api/meta/categories/search?taxonomy=UNSPSC&keyword=...

目标：GET /api/meta/categories/search?businessDomain=MATERIAL&keyword=...

### 4.4 批量子节点

现状请求体：

{
  "taxonomy": "UNSPSC",
  "parentIds": [...],
  "status": "ALL"
}

目标请求体：

{
  "businessDomain": "MATERIAL",
  "parentIds": [...],
  "status": "ALL"
}

### 4.5 taxonomy 元数据接口

现状：GET /api/meta/taxonomies/{code}

目标：

- 方案A：下线。
- 方案B：保留兼容壳，返回 410/deprecated 提示，并指向新配置来源。

---

## 5. 兼容策略（建议）

建议采用“短窗口兼容 + 明确下线”策略：

- 第1阶段（兼容窗口）
  - taxonomy 参数保留但忽略（仅用于向后兼容请求结构）。
  - 服务端输出 warning 日志，提示改用 businessDomain。
- 第2阶段（正式切换）
  - taxonomy 参数从接口定义移除。
  - taxonomy 控制器下线。
  - 文档与联调示例全面替换。

注：你若希望一次性强切（无兼容窗口），可直接执行第2阶段。

---

## 6. 风险评估

- 风险1：历史前端或第三方调用仍携带 taxonomy。
  - 缓解：发布前统一扫描调用方；提供短窗口兼容。

- 风险2：当前部分服务逻辑仍内置 UNSPSC 常量。
  - 缓解：重构前建立影响清单，逐处替换为 businessDomain 语义。

- 风险3：文档与实现不一致导致联调混乱。
  - 缓解：本次改造按“代码+文档+示例”同批发布。

---

## 7. 影响清单（待实施）

后端重点：

- MetaCategoryGenericQueryService（taxonomy 校验与默认逻辑）
- MetaCategoryGenericQueryController（请求参数契约）
- MetaTaxonomyController（下线/兼容）
- 相关 DTO（children-batch 请求体字段）
- Dictionary Service（场景移除 META_TAXONOMY）

前端重点：

- 分类管理页查询参数与请求构造
- 分类服务层类型定义（taxonomy -> businessDomain）
- 初始化字典加载逻辑与场景字典清单

文档重点：

- category-api.md
- dictionary-api.md
- 相关设计草案与联调示例

---

## 8. 验收标准（Definition of Done）

- 所有分类查询接口不再依赖 taxonomy。
- 分类联调全链路仅使用 businessDomain。
- taxonomy 接口按决策完成下线或兼容壳处理。
- 字典场景不再要求 META_TAXONOMY。
- 文档、示例、错误语义与实际实现一致。

---

## 9. 本轮结论

- 本文为初始变更草案，已明确重构方向：
  - 分类归属以 businessDomain 为唯一主语义。
  - taxonomy 概念退出业务主链路。
- 本轮不进行代码改动。
- 下一轮进入实施阶段时，按“影响清单 + 验收标准”逐项落地。
