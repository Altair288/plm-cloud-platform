SET search_path TO plm_meta, public;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_meta_code_rule_set_category_rule_code'
    ) THEN
        ALTER TABLE plm_meta.meta_code_rule_set
            ADD CONSTRAINT fk_meta_code_rule_set_category_rule_code
            FOREIGN KEY (category_rule_code)
            REFERENCES plm_meta.meta_code_rule(code)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_meta_code_rule_set_attribute_rule_code'
    ) THEN
        ALTER TABLE plm_meta.meta_code_rule_set
            ADD CONSTRAINT fk_meta_code_rule_set_attribute_rule_code
            FOREIGN KEY (attribute_rule_code)
            REFERENCES plm_meta.meta_code_rule(code)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_meta_code_rule_set_lov_rule_code'
    ) THEN
        ALTER TABLE plm_meta.meta_code_rule_set
            ADD CONSTRAINT fk_meta_code_rule_set_lov_rule_code
            FOREIGN KEY (lov_rule_code)
            REFERENCES plm_meta.meta_code_rule(code)
            ON UPDATE RESTRICT
            ON DELETE RESTRICT;
    END IF;
END $$;