-- V10: Redesign ATTRIBUTE / LOV code patterns to global stable sequence-based keys.
-- ATTRIBUTE pattern: ATTR_{SEQ} (6-digit zero padded)
-- LOV pattern: {ATTRIBUTE_CODE}_LOV (no sequence)
-- Reset ATTRIBUTE sequence current_value to 0 (optional; comment out if you want to continue numbering).

UPDATE plm_meta.meta_code_rule SET pattern = 'ATTR_{SEQ}' WHERE code = 'ATTRIBUTE';
UPDATE plm_meta.meta_code_rule SET pattern = '{ATTRIBUTE_CODE}_LOV' WHERE code = 'LOV';

-- Ensure sequence row exists for ATTRIBUTE
INSERT INTO plm_meta.meta_code_sequence(rule_code, current_value)
SELECT 'ATTRIBUTE', 0 WHERE NOT EXISTS (SELECT 1 FROM plm_meta.meta_code_sequence WHERE rule_code = 'ATTRIBUTE');

-- Reset to 0 to start at ATTR_000001 (remove this line if you want to KEEP existing numbering)
UPDATE plm_meta.meta_code_sequence SET current_value = 0 WHERE rule_code = 'ATTRIBUTE';
