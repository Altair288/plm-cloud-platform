# 1 目标概述

* 保证客户在某一时刻基于“定义版本（Definition Version）”做出的选择（配置）**不受系统后续定义变更影响**。
* 定义（分类/属性/LOV/编码规则）支持版本化；每次变更产生新版本记录（不可覆盖旧版本）。
* 客户配置保存时记录所用定义版本并保存一份快照（snapshot），以便准确回放 / 导出 / 表单渲染。
* 支持版本差异对比、回滚到指定版本、以及高性能查询与缓存。

---

# 2 关键概念与术语

* **Definition（定义）**：分类、属性、LOV、编码规则等元数据。
* **Definition Version（定义版本）**：某一类别定义在某时刻的不可变快照，带版本号。
* **Snapshot**：客户保存配置时，对当时定义的完整 JSON 拷贝（用于完全回放）。
* **Selection（选择/配置实例）**：客户填写或选择的值集合（映射属性名→值）。
* **Canonical ID / Code**：分类与属性的编码（如 `CAT_BTN_ATT_0001`）。

---

# 3 数据模型（PostgreSQL + Redis）

## 3.1 PostgreSQL 表设计（主要表）

### 3.1.1 category_definition_version（分类定义版本表）

```sql
CREATE TABLE category_definition_version (
  id BIGSERIAL PRIMARY KEY,
  category_code VARCHAR(64) NOT NULL,   -- e.g. CAT_BTN
  version INT NOT NULL,                 -- 版本号，自增
  attributes JSONB NOT NULL,            -- 属性定义数组（不可变）
  metadata JSONB,                       -- 其它元数据：displayName, description...
  created_by VARCHAR(64),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT now(),
  is_latest BOOLEAN DEFAULT TRUE,
  CONSTRAINT uq_category_version UNIQUE (category_code, version)
);
CREATE INDEX idx_category_code ON category_definition_version(category_code);
CREATE INDEX idx_category_latest ON category_definition_version(category_code, is_latest);
```

`attributes` 示例：

```json
{
  "category": "开关按钮",
  "attributes": [
    {"code":"CAT_BTN_ATT_0001","name":"按钮类型","type":"enum","lovId":"LOV_BTN_TYPE"},
    {"code":"CAT_BTN_ATT_0002","name":"额定电压","type":"number","unit":"V"}
  ]
}
```

---

### 3.1.2 lov_version（枚举/LOV 的版本）

```sql
CREATE TABLE lov_version (
  id BIGSERIAL PRIMARY KEY,
  lov_code VARCHAR(64) NOT NULL,        -- lov 标识
  version INT NOT NULL,
  values JSONB NOT NULL,                -- 值列表：[{code:"...", value:"..."}]
  created_at TIMESTAMP WITH TIME ZONE DEFAULT now(),
  is_latest BOOLEAN DEFAULT TRUE,
  CONSTRAINT uq_lov_version UNIQUE (lov_code, version)
);
CREATE INDEX idx_lov_code ON lov_version(lov_code);
```

---

### 3.1.3 code_rule_version（编码规则版本）

```sql
CREATE TABLE code_rule_version (
  id BIGSERIAL PRIMARY KEY,
  rule_code VARCHAR(64) NOT NULL,
  version INT NOT NULL,
  rule_def JSONB NOT NULL,              -- pattern, length, step, inheritFrom...
  created_at TIMESTAMP WITH TIME ZONE DEFAULT now(),
  is_latest BOOLEAN DEFAULT TRUE
);
CREATE INDEX idx_code_rule_code ON code_rule_version(rule_code);
```

---

### 3.1.4 customer_configuration（客户配置表，保存快照与选择）

```sql
CREATE TABLE customer_configuration (
  id BIGSERIAL PRIMARY KEY,
  customer_id VARCHAR(64) NOT NULL,
  name VARCHAR(200),                    -- 客户自定义配置名
  category_code VARCHAR(64) NOT NULL,
  category_version INT NOT NULL,        -- 客户选择使用的 category definition version
  snapshot JSONB NOT NULL,              -- definition snapshot（冗余保存，便于回放）
  selection JSONB NOT NULL,             -- 客户实际选择值 { attributeCode: value }
  created_by VARCHAR(64),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT now()
);
CREATE INDEX idx_customer_conf_customer ON customer_configuration(customer_id);
CREATE INDEX idx_customer_conf_category ON customer_configuration(category_code);
```

`snapshot` 示例（与 `category_definition_version.attributes` 结构相同）：

```json
{
  "category": "开关按钮",
  "version": 3,
  "attributes": [
    {"code":"CAT_BTN_ATT_0001","name":"按钮类型","type":"enum","lov":[{"code":"...","value":"常开"}]},
    {"code":"CAT_BTN_ATT_0002","name":"额定电压","type":"number","unit":"V"}
  ]
}
```

---

## 3.2 Redis 缓存策略（Key 设计）

使用 Redis 缓存最新版本以加速读取与生成编码。

* `category:latest:<category_code>` → integer latestVersion
* `category:version:<category_code>:<version>` → JSON (definition) **(可选，缓存热点版本)**
* `lov:latest:<lov_code>` → integer latestVersion
* `lov:version:<lov_code>:<version>` → JSON values
* `code_rule:latest:<rule_code>` → JSON rule (currentSeq, pattern ...)

示例：

```
SET category:latest:CAT_BTN 4
HSET code_rule:latest:attribute pattern "${categoryCode}_ATT_" currentSeq 123 length 4
```

Redis 仅缓存“热数据”和 currentSeq 以避免频繁读写 PG。

---

# 4 主要流程设计（伪代码 + 事务说明）

## 4.1 新增 / 修改分类定义（生成新版本）

**语义**：每次需要变更分类属性时，新增一条 `category_definition_version`（version++），并将旧版本 `is_latest = false`。

**步骤（在 DB 事务中执行）**：

1. SELECT max(version) FROM category_definition_version WHERE category_code = X FOR UPDATE;
2. nextVersion = (maxVersion ?? 0) + 1
3. INSERT INTO category_definition_version(category_code, version, attributes, metadata, created_by, is_latest=true)
4. UPDATE category_definition_version SET is_latest = false WHERE category_code = X AND version <> nextVersion
5. 更新 Redis：`SET category:latest:<category_code> nextVersion`、`SET category:version...`（可选）

**伪代码**：

```sql
BEGIN;
SELECT max(version) as v FROM category_definition_version WHERE category_code='CAT_BTN' FOR UPDATE;
INSERT INTO category_definition_version(..., version = v+1, attributes = $attrs, is_latest = true);
UPDATE category_definition_version SET is_latest = false WHERE category_code='CAT_BTN' AND version <> v+1;
COMMIT;
```

**注意**：`FOR UPDATE` 保证并发下的版本递增一致（若高并发可用 advisory lock）。

---

## 4.2 客户保存配置（保存 snapshot）

当客户在 UI 点击“保存配置”时，后端要把“当时使用的 definition 版本号”和**完整快照**一并保存到 `customer_configuration`。

**步骤**：

1. 从 Redis 获取 `category:latest:<category_code>` 或从 PG 获取指定 `category_version` 的 definition（若 UI 在读取时已经拿到 definition，则可直接传回）。
2. 构建 `snapshot`（包含 category_code、category_version、attributes-definition、lov-values for enums）——建议 snapshot 包含所有用于渲染表单的必要字段（属性名/属性code/type/enum list/unit）。
3. INSERT INTO customer_configuration (customer_id, name, category_code, category_version, snapshot, selection, created_by)
4. 返回保存结果。

**伪代码**：

```js
// Assume def = GET /category/:code/version/:v  (or passed from frontend)
snapshot = defWithLovValues(def);
INSERT INTO customer_configuration (...) VALUES (..., snapshot, selection);
```

**事务**：无需跨表复杂事务，单表写入即可。

---

## 4.3 客户查看/导出配置（回放）

读取 `customer_configuration` 的 `snapshot` 与 `selection`：

* 使用 `snapshot.attributes` 渲染表单字段（顺序、类型、枚举项均来自 snapshot）。
* 使用 `selection` 填充字段值。
* 导出时以 snapshot 为依据生成表头（固定、不受后续变更影响）。

---

## 4.4 版本回滚（管理员操作）

**目标**：管理员可以将 `is_latest` 指向某历史版本（谨慎操作）。

**实现**：

* `UPDATE category_definition_version SET is_latest=false WHERE category_code=...;`
* `UPDATE category_definition_version SET is_latest=true WHERE category_code=... AND version=<target_version>;`
* 更新 Redis：`SET category:latest:<category_code> <target_version>`

**注意**：回滚不会修改现有 customer_configuration 的 snapshot（历史不会被改变）。

---

# 5 差异对比（Diff）算法设计

用途：管理员查看 `version A` → `version B` 的变化（新增属性、删除、改名、枚举变更）。

## 5.1 输入

* `old_def = category_definition_version.attributes`（array）
* `new_def = category_definition_version.attributes`（array）

每个属性具有 `code`、`name`、`type`、`unit`、`lovId` 等。

## 5.2 输出格式（示例）

```json
{
  "added": [ { "code":"CAT_BTN_ATT_0003", "name":"额定电流" } ],
  "removed": [ { "code":"CAT_BTN_ATT_0002", "name":"额定电压" } ],
  "modified": [
    {
      "code":"CAT_BTN_ATT_0001",
      "changes": {
        "name": {"from":"是否带灯","to":"是否带指示灯"},
        "lov": {"from": ["不带","带"], "to": ["不带","带","半灯"]}
      }
    }
  ]
}
```

## 5.3 算法（步骤）

1. 将 old/new 的属性数组用 `code` 建 map： `oldMap[code]`, `newMap[code]`。
2. 遍历 `newMap`：

   * 若 code 不在 `oldMap` → `added`
   * 若在 `oldMap` → 比较字段（name/type/unit/lovId）。若不同 → `modified`，记录差异项。
3. 遍历 `oldMap`：

   * 若 code 不在 `newMap` → `removed`
4. 对 `enum` 类型的 `lov`，还需比较 `lov_version` 的 `values`（也按 code 比较）。

**伪代码**：

```js
function diff(oldAttrs, newAttrs) {
  oldMap = mapByCode(oldAttrs);
  newMap = mapByCode(newAttrs);
  added = []; removed = []; modified = [];

  for (code in newMap) {
    if (!oldMap[code]) added.push(newMap[code]);
    else {
      changes = compareProps(oldMap[code], newMap[code]);
      if (Object.keys(changes).length) modified.push({code, changes});
    }
  }
  for (code in oldMap) {
    if (!newMap[code]) removed.push(oldMap[code]);
  }
  return {added, removed, modified};
}
```

**复杂度**：O(n)（n = 属性数）。

---

# 6 并发、一致性与性能注意事项

## 6.1 并发写版本（版本号竞争）

* 在 `category_definition_version` 写版本时需要锁定同一 `category_code` 的并发（可用 `SELECT ... FOR UPDATE` 或 PostgreSQL advisory locks）。
* 另一种方案：把 `current_version` 放在单独表 `category_sequence`，用 `UPDATE ... RETURNING` 或 `nextval()` 来原子自增。

示例：

```sql
CREATE SEQUENCE category_version_seq; -- 全局序列（若按 category 每个有独立序列，可用表+UPDATE）
```

## 6.2 编码 `currentSeq`（编码规则序列）

* `currentSeq` 是编码规则里用于生成流水号的字段，必须保证原子更新（可使用 Redis INCR 或 PG 序列）。
* 推荐用 **Postgres 序列** 或 **Redis atomic INCR**：

  * 若使用 Redis INCR，写回 PG 时要小心持久化（在某些故障场景需 reconcile）。

## 6.3 Snapshot 大小与存储

* Snapshot 是冗余数据，可能变大。建议：

  * 只保存渲染表单所需字段（属性定义 + LOV 值，不必保存所有 metadata）。
  * 对 snapshot 使用压缩（PG 支持 `pg_compress` 或应用层 gzip 存为 base64）。
  * 定期归档旧 snapshot（例如 3 年后转对象存储）。

## 6.4 索引

* 对 `customer_configuration(customer_id)`、`category_definition_version(category_code,is_latest)` 建索引。
* 对 `attributes`（JSONB）若需按特定属性筛选，创建 GIN 索引或表达式索引。

示例索引（Postgres）：

```sql
CREATE INDEX idx_cat_attrs_gin ON category_definition_version USING gin (attributes jsonb_path_ops);
CREATE INDEX idx_conf_snapshot_attrs ON customer_configuration USING gin (snapshot jsonb_path_ops);
```

---

# 7 导出 / 兼容性 / API 设计（示例）

## 7.1 核心 REST API（示例）

* `GET /api/categories/:code/latest` → 返回 latest version metadata
* `GET /api/categories/:code/versions/:v` → 返回指定版本
* `POST /api/categories/:code/versions` → 新增版本（body: attributes）
* `POST /api/customer-configurations` → 保存客户配置（body: customerId, categoryCode, categoryVersion(optional), selection, name）
* `GET /api/customer-configurations/:id` → 查看（返回 snapshot + selection）
* `GET /api/categories/:code/diff?from=2&to=4` → 版本 diff

## 7.2 导出规则

* 导出格式支持 CSV / Excel / JSON：

  * Excel 表头从 snapshot.attributes.name 或 code 生成；
  * 值从 selection 读取；
* 导出接口应提供 `format` 参数 & `includeSnapshot`（是否在导出中包含定义元信息）。

---

# 8 回滚与恢复

## 8.1 回滚策略

* 管理员可以通过 `POST /api/categories/:code/rollback` 指定 `targetVersion`，系统将：

  1. 标记旧的 `is_latest=false`；
  2. 将 `targetVersion` 的 `is_latest=true`；
  3. 更新 Redis latest key。
* 回滚不会修改现有 `customer_configuration`（历史不可变）。

## 8.2 恢复（灾难恢复）

* 定期备份 `category_definition_version`、`customer_configuration`（pg_dump）；或使用 WAL 备份策略。
* 备份 snapshot 时可选择压缩存储。

---

# 9 运维与存储策略建议

* Snapshot 压缩：对大于 2KB 的 snapshot 使用 gzip 压缩后存入 JSONB（或存对象存储并在表中保存 URL）。
* 归档策略：对超过 N 年的老 snapshot 导出到对象存储（S3）并从主 DB 清除，表中保留索引指针与元信息。
* 审计日志：对所有版本变更、编码 seq 变更与客户保存记录审计日志（写入 `operation_log` 表）。
* 监控：监控 Redis seq 错误、PG 表增长、索引膨胀、导出失败率。

---

# 10 测试用例（开发时必写）

1. **版本创建并发测试**

   * 并发 N 个请求修改同分类定义，断言版本连续且无冲突。
2. **客户保存并隔离测试**

   * 创建版本 v1，客户保存（snapshot1）；随后新版本 v2 增加属性，客户保存的 snapshot1 不应包含新属性。
3. **回滚测试**

   * 新建 v1→v2→v3，回滚到 v1，检查 latest 指向 v1，不影响已有 snapshot。
4. **diff 准确性测试**

   * 比较两版本的 diff 输出是否正确（包含新增/删除/修改）。
5. **导出一致性测试**

   * 根据 snapshot 导出 CSV/Excel，值与 selection 应一致。
6. **压缩/归档测试**

   * 压缩后可以解压还原，归档后原数据可通过 URL 访问并重建 snapshot。

---

# 11 示例用例（端到端）

### 11.1 管理员创建分类 v1

* POST `/api/categories/CAT_BTN/versions` body:

```json
{
  "attributes": [
    {"code":"CAT_BTN_ATT_0001","name":"按钮类型","type":"enum","lovCode":"LOV_BTN_TYPE"},
    {"code":"CAT_BTN_ATT_0002","name":"额定电压","type":"number","unit":"V"}
  ]
}
```

* DB: 插入 `category_definition_version` version=1

### 11.2 客户基于 v1 生成配置并保存

* 前端 GET `/api/categories/CAT_BTN/latest` 得到 v1
* 客户选择 values `{ "CAT_BTN_ATT_0001":"常开", "CAT_BTN_ATT_0002":220 }`
* POST `/api/customer-configurations` body:

```json
{
  "customerId":"cust_A",
  "categoryCode":"CAT_BTN",
  "categoryVersion":1,
  "snapshot": { ... v1 definition with lov values ... },
  "selection": { "CAT_BTN_ATT_0001":"常开", "CAT_BTN_ATT_0002":220 }
}
```

### 11.3 管理员新增属性（v2）

* 新增属性 `额定电流` 导致 category v2，旧客户 snapshot 保留 v1 内容。

---

# 12 扩展建议（未来迭代）

* 引入 **temporal tables**（Postgres temporal extension）实现更强的时态查询。
* 对 snapshot 使用内容寻址（如保存到对象存储并用 hash 作为 key，避免重复保存）。
* 为 diff 增加可视化组件（前端显示新增/删除/修改）。
* 提供“迁移工具”：当客户希望把旧 snapshot 基于最新定义迁移（比如映射新属性），实现“迁移建议”功能（仅建议，不自动改写 snapshot）。

---

# 13 小结（要点回顾）

* 推荐实现方式：**Postgres JSONB 存 Definition Version + Snapshot 存入 customer_configuration + Redis 缓存最新版本与 seq**。
* 每次定义变更新增版本（不覆盖旧版本），并更新 Redis latest pointer。
* 客户保存时一定要保存 `snapshot`（definition + lov list）与 `selection`。这保证历史不变且可回放。
* 对并发更新、seq 管理、存储膨胀、归档与恢复提供明确策略。

---

