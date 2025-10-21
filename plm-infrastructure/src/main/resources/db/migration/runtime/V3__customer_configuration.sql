-- V3__customer_configuration.sql
-- 新增客户配置快照表：保存客户选择的分类版本快照与实际选择值
-- 位于 plm 库（schema plm）。

SET search_path TO plm;

CREATE TABLE IF NOT EXISTS plm.customer_configuration (
  id                  UUID PRIMARY KEY,
  customer_id         VARCHAR(64) NOT NULL,
  name                VARCHAR(200),
  category_code       VARCHAR(64) NOT NULL,
  category_version_id UUID NOT NULL,  -- 引用 plm_meta.meta_category_version(id)（跨库应用层校验）
  snapshot_json       JSONB NOT NULL, -- 完整定义快照 {category, version, attributes:[...]}
  selection_json      JSONB NOT NULL, -- 客户选择 { attrCode: value }
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by          VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_customer_conf_customer ON plm.customer_configuration(customer_id);
CREATE INDEX IF NOT EXISTS idx_customer_conf_category ON plm.customer_configuration(category_code);
CREATE INDEX IF NOT EXISTS idx_customer_conf_snapshot_gin ON plm.customer_configuration USING gin (snapshot_json jsonb_path_ops);
