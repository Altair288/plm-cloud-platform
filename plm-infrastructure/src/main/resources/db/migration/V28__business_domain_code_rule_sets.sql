SET search_path TO plm_meta, public;

ALTER TABLE plm_meta.meta_code_rule
    ADD COLUMN IF NOT EXISTS business_domain VARCHAR(64);

UPDATE plm_meta.meta_code_rule
SET business_domain = 'MATERIAL'
WHERE business_domain IS NULL OR btrim(business_domain) = '';

ALTER TABLE plm_meta.meta_code_rule
    ALTER COLUMN business_domain SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_meta_code_rule_business_domain
    ON plm_meta.meta_code_rule(business_domain, code);

CREATE TABLE IF NOT EXISTS plm_meta.meta_code_rule_set (
    id UUID PRIMARY KEY,
    business_domain VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    active BOOLEAN NOT NULL DEFAULT FALSE,
    remark TEXT,
    category_rule_code VARCHAR(64) NOT NULL,
    attribute_rule_code VARCHAR(64) NOT NULL,
    lov_rule_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(64),
    updated_at TIMESTAMPTZ,
    updated_by VARCHAR(64)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_meta_code_rule_set_business_domain
    ON plm_meta.meta_code_rule_set(business_domain);

INSERT INTO plm_meta.meta_code_rule_set(
    id,
    business_domain,
    name,
    status,
    active,
    remark,
    category_rule_code,
    attribute_rule_code,
    lov_rule_code,
    created_at,
    created_by,
    updated_at,
    updated_by
)
SELECT gen_random_uuid(),
       'MATERIAL',
       'Material 编码规则集',
       'ACTIVE',
       TRUE,
       '默认初始化规则集',
       'CATEGORY',
       'ATTRIBUTE',
       'LOV',
       now(),
       'flyway-v28',
       now(),
       'flyway-v28'
WHERE EXISTS (SELECT 1 FROM plm_meta.meta_code_rule WHERE code = 'CATEGORY' AND business_domain = 'MATERIAL')
  AND EXISTS (SELECT 1 FROM plm_meta.meta_code_rule WHERE code = 'ATTRIBUTE' AND business_domain = 'MATERIAL')
  AND EXISTS (SELECT 1 FROM plm_meta.meta_code_rule WHERE code = 'LOV' AND business_domain = 'MATERIAL')
  AND NOT EXISTS (SELECT 1 FROM plm_meta.meta_code_rule_set WHERE business_domain = 'MATERIAL');