# 1）整体架构（推荐微服务 + 事件驱动）

适合大规模 PLM 的高阶架构要点：

* 微服务分界：Auth、User/Profile、Attribute/Meta（Attribute Service）、Classification/Taxonomy、Part/Product Master、BOM 服务、Document/PDM（文件/CAD）、Workflow/ECO（变更）、Search Indexer、Notification、Analytics/Reporting。
* 通信：同步 REST/gRPC（短时交互），异步事件总线用 Kafka/RabbitMQ（用于索引、BOM 变更、ECO 推送、Audit）。
* 数据存储：关系库（Postgres/MySQL）+ 文档/NoSQL（MongoDB 或 Postgres JSONB）+ 搜索引擎（Elasticsearch）+ 对象存储（MinIO/S3）。
* API 网关 + Authentication（Keycloak / Spring Security + OAuth2 / OIDC）。
* 部署：Docker + Kubernetes，CI/CD（GitHub Actions / Jenkins）+ Helm。
* 监控/观测：Prometheus + Grafana，分布式追踪 SkyWalking/Zipkin，集中日志 ELK/EFK。

图（概念）：
API Gateway → 微服务群（Attribute Service / Part Service / BOM / PDM / Workflow / Search）→ 数据层（RDB / ES / ObjectStore）
事件总线（Kafka）连通变更与索引服务

---

# 2）分类（Classification）与属性模型（Attribute）设计策略

这是关键：属性（attribute）数目巨大、类型多、并且每个分类的可用属性不同。常用方案与权衡：

## 方案 A — 传统 EAV（Entity-Attribute-Value）

优点：灵活，任意属性都能存；缺点：查询复杂、性能瓶颈（尤其聚合/筛选）。
简化表结构示例：

* `attribute`（id, code, name, data_type, options_json, unit, required, version, last_update...）
* `classification`（id, code, name, parent_id...）
* `entity_attribute_value`（entity_id, attribute_id, value_string, value_number, value_date, locale, created_at）

EAV 适合写入灵活性高，但**为检索/筛选需要把属性值同步到搜索索引（ES）或做物化视图**。

## 方案 B — JSONB（Postgres）或 Document（MongoDB）

优点：自然表达对象、查询便捷（特定字段可索引），更直观。缺点：动态索引管理较麻烦，PATCH 更新要小心。
示例：产品主表 `product_master` 有 `attributes JSONB` 字段；此外维护 `attribute_metadata` 表来约束与校验。
建议：对高频筛选字段建立 GIN/GIN expression 索引或单列 B-tree 索引（如果知道常查的属性）。

## 方案 C — 混合（推荐）

* 元数据（attribute definitions、classification、templates）放在关系库；
* 主数据（产品/部件）主表用关系字段 + `attributes JSONB` 存放动态属性；
* 对于需要做筛选/聚合的属性或高频属性，做**专门列（denormalized）或在同步流程里把它们写入 Elasticsearch**做搜索/过滤/聚合。
* 使用 Attribute Service 管理属性生命周期、版本、依赖、校验规则与 UI 表单描述（控件类型、枚举、范围、单位等）。

这个混合方案在工程上最实用：写入与维护方便，同时检索性能由 ES 支撑。

---

# 3）属性数量非常大时的工程化做法

如果属性数量级：**数十万到百万级属性定义** 或 不同分类组合成百万种属性维度，应注意：

1. **属性分级与继承**

   * 分类树（taxonomy）：支持继承属性（父分类定义属性，子分类继承/覆盖）。
   * 模板（template）：为常见分类提供属性集合，实例化后可自定义。

2. **属性注册与版本**

   * 每个 attribute 有版本与生效时间，变更（ECO）且要保持历史值兼容性。
   * 属性删除不直接物理删除，只做下线/标记，旧数据保持历史。

3. **属性类型化与分表**

   * 按类型分表（string_values, numeric_values, date_values）可以提升单表性能。
   * 或按分类或按业务域分库分表（sharding）。

4. **索引策略**

   * 不对所有属性做索引。根据**查询热度**与**业务场景**决定：为常用筛选属性建立索引或同步到 ES。
   * ES 索引模板需动态生成，字段映射要按类型（keyword/text/number/date）设置，避免 dynamic mapping 导致索引碎片。

5. **高性能筛选**

   * 推荐用 ES 做 faceted search（聚合、过滤），在产品变更时通过事件流异步更新 ES 索引（near-real-time）。
   * 对于事务性强的查询（事务内需要强一致性），还是走 RDB。

6. **预计算 / 物化视图**

   * 报表/聚合/复杂筛选可以做定时物化视图或实时 materialized view（在 Elastic/OLAP）。

---

# 4）BOM（物料清单）与配置管理

PLM 的核心之一，要求版本、配置、差异比较：

* BOM 存储：BOM header（id, product_id, revision, effective_date, status） + BOM lines（line_id, parent_line_id, part_id, quantity, unit, reference_designator, attributes_json）
* 支持：层级 BOM（树结构）、风格（制造BOM、工程BOM、服务BOM）、替代件（alternate parts）、有效期与生效范围（site/plant）。
* 版本控制：每次变更产生新的 BOM revision（或以变更单引用快照），并能对比 BOM 差异（结构 + 属性）。
* 配置管理：基线（baseline）、变更请求（ECO/ECN）、审批流水线、影响分析（Change Impact Analysis）。

---

# 5）核心 PLM 功能（建议模块与职责）

* **Product Master / Part Management**：主数据、属性、分类、状态机。
* **Classification & Attribute Manager**：管理分类树、属性定义、模板、属性集。
* **BOM & Configuration**：BOM 管理、替代、结构化视图、对比。
* **Document / PDM Service**：CAD/图纸管理、文件版本、Check-in/Check-out、关联到部件。
* **Workflow / ECO**：表单化的变更流程、审批、任务、权限。
* **Search & Discovery**：基于 ES 的面向属性的筛选、聚合、快速检索。
* **Integration / Connector**：与 MES/ERP/CAD 系统的同步（双向），包括批量导入导出（CSV、Excel、PLM 标准接口）。
* **Analytics / Traceability**：生命周期报表、质量/合规追溯（谁在何时变更什么）。

---

# 6）安全、权限、多租户与审计

* **权限模型**：RBAC + 资源级权限（基于 Part / Project / Org）+ 属性级约束（敏感属性可隐藏）。
* **多租户**：平台支持租户隔离（逻辑隔离：租户ID 作为查询前缀；物理隔离：多库）——视安全与合规决定。
* **审计日志**：所有属性变更、文件操作、ECO 流程都必须可审计（谁、何时、旧值->新值）。
* **数据加密**：文件存储与敏感字段加密。

---

# 7）性能与扩展（工程实践）

* **写放行，读优化**：采用异步索引（事件驱动）把写操作从搜索/分析解耦。
* **缓存**：Redis 用于热点数据与会话；对模板/分类等做本地缓存并设置版本失效机制。
* **分库分表策略**：按租户 / 按业务域 / 按时间分区。对大表（历史审计）做冷数据归档。
* **批量操作优化**：批量导入支持 streaming、增量提交、并行处理、限流。
* **水平扩展**：服务无状态化，数据库读写分离，ES 集群横向扩展。
* **监控告警**：指标（QPS、延迟、索引延迟、队列积压）并设置告警。

---

# 8）示例：属性元模型与产品 JSON（样例）

`attribute`（元定义）简化：

```json
{
  "id": "attr-123",
  "code": "length_mm",
  "name": "长度（mm）",
  "data_type": "number",
  "unit": "mm",
  "allowed_values": null,
  "min": 0,
  "max": 10000,
  "ui_control": "number_input",
  "searchable": true,
  "facetable": true,
  "required": false,
  "version": 2
}
```

产品实例（Product Master）：

```json
{
  "id": "part-001",
  "part_number": "ABC-1000",
  "name": "电机 A",
  "classification": ["motors", "brushless"],
  "attributes": {
    "length_mm": 120,
    "weight_g": 850,
    "operating_temperature": " -20~85",
    "material": "Aluminium",
    "supply_voltage": 24
  },
  "bom_refs": ["bom-2025-001"],
  "revision": "A.03",
  "status": "released"
}
```

上面 `attributes` 存在于 JSONB 字段，但同时会把 `length_mm`, `weight_g`, `material` 等标记 `searchable` 的字段同步到 ES，便于 faceted search / range filter。

---

# 9）索引与查询实践（ES）

* 每个 Product document 在 ES 包含：静态字段（part_number, name, classification path） + 动态字段（attributes.*）映射。
* 使用 nested 类型存储复杂 attribute（例如数组/多值属性）。
* 聚合（facets）使用 keyword 类型或 aggregation-friendly 类型；数值范围使用 `double` 或 `integer`。
* 当属性超多时，避免把所有属性都映射到同一个索引（会导致 mapping blow-up）。策略：

  * 只把“必须用于筛选/聚合/排序”的属性同步；
  * 对“稀疏属性”用 `attributes` as `object` + `flattened` type（ES 的 flattened type 对动态键非常有用）；
  * 或分索引：按分类或按租户分索引。

---

# 10）API 设计示例（REST）

* `GET /api/v1/parts/{id}` → 获取 Part（支持字段选择 ?fields=attributes,length_mm）
* `POST /api/v1/parts` → 创建 Part（body 包含 attributes JSON）
* `PUT /api/v1/parts/{id}` → 全量更新（带 revision）
* `PATCH /api/v1/parts/{id}` → 局部更新（attributes 的单属性 patch）
* `GET /api/v1/search/parts?q=&filters=material:Aluminium;length_mm:>100&facets=material,length_mm` → ES 驱动的搜索
* `POST /api/v1/attributes` → 管理属性定义（Attribute Service）
* `POST /api/v1/bom/{id}/compare?rev1=A.01&rev2=A.03` → BOM 对比接口

注意：对 attributes 的修改应由 Attribute Service 发出 schema change 事件，通知 Indexer/Front-end 更新 UI 表单 schema。

---

# 11）CAD / 文档集成（PDM）

* 文件存储：MinIO/S3 + metadata in RDB。
* 文件版本与 Check-in/out：实现锁机制（optimistic 或 pessimistic），并在 Document Service 中记录元信息与访问权限。
* 与 CAD 的集成：挂载预览（转换到 PDF/3D preview），并把文件与 Part/Assembly 建联。
* 变更影响链：当某 CAD 文件更新时，触发 ECO 流程并做影响分析（哪些 BOM / 哪些装配受影响）。

---

# 12）工程里常见的坑 & 建议

* **不要把所有属性实时索引到 ES** —— mapping 数量爆炸会崩掉集群。选择性索引或使用 ES flattened。
* **属性定义治理不足** 会导致同类属性重复（length_mm vs length）——要强制 attribute registry + UI 引导。
* **变更历史若不设计好**，会破坏合规和追溯（永远保存变更快照）。
* **ECO/Workflow 一定要可审计、可回溯**（包括审批链、附件、备注）。
* **测试数据非常重要**：生成大量属性/产品的压力测试，测索引延迟、查询耗时、批量导入时间。
* **合理分层缓存**：模板/分类做本地缓存，产品列表用 CDN/near-cache。

---

# 13）推荐技术栈（快速参考）

* 后端：Java 17+, Spring Boot 3.x, Spring Cloud / Spring Authorization Server
* DB：Postgres（主） + JSONB；MongoDB（可选，用于更灵活文档）；ShardingSphere（分表/分库）
* Search：Elasticsearch（或 OpenSearch）
* MQ：Kafka（事件驱动），RabbitMQ（任务队列）
* 存储：MinIO / S3
* Auth：Keycloak / Spring Security + OAuth2
* Observability：Prometheus + Grafana + SkyWalking
* DevOps：Docker / Kubernetes / Helm / ArgoCD（或 Jenkins/GitHub Actions）

---
