package com.plm.infrastructure.code;

import com.plm.common.version.util.CodeRuleSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;

/**
 * 基于元数据规则的编码生成器（占位实现）。
 * 规则来源表：plm_meta.meta_code_rule / plm_meta.meta_code_rule_version / plm_meta.meta_code_sequence
 * 简化逻辑：按 rule_code 找到 pattern，包含 {DATE} & {SEQ} 两种变量。
 * 序列使用 meta_code_sequence 里的 current_value 自增并回写。
 */
@Component
public class CodeRuleGenerator {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 现已合并为单数据源，直接注入主数据源（mainDataSource 或容器中唯一的 DataSource）。
     */
    public CodeRuleGenerator(@Qualifier("mainDataSource") DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * 兼容旧调用：无上下文。
     */
    @Transactional
    public String generate(String ruleCode) {
        return generate(ruleCode, Collections.emptyMap());
    }

    /**
    * 新增：支持上下文占位符 {CATEGORY_CODE} / {ATTRIBUTE_CODE} / {PARENT_CODE} 等。
    * 序列宽度由共享规则常量统一维护，避免调用方和生成器出现漂移。
     */
    @Transactional
    public String generate(String ruleCode, Map<String, String> context) {
        Map<String, String> ctx = context == null ? Collections.emptyMap() : context;
        RuleMeta meta = jdbcTemplate.query(
                "SELECT pattern, inherit_prefix, parent_rule_id FROM plm_meta.meta_code_rule WHERE code = ?",
                rs -> rs.next() ? new RuleMeta(rs.getString("pattern"), rs.getBoolean("inherit_prefix"), (java.util.UUID) rs.getObject("parent_rule_id")) : null,
                ruleCode
        );
        if (meta == null) {
            throw new IllegalArgumentException("编码规则不存在: " + ruleCode);
        }
        String pattern = meta.pattern;
        String dateStr = LocalDate.now().toString().replaceAll("-", "");
        String result = pattern.contains("{DATE}") ? pattern.replace("{DATE}", dateStr) : pattern;
        if (pattern.contains("{SEQ}")) {
            // 仅在需要序列时访问序列表，减少不必要锁竞争
            Map<String, Object> seqRow = jdbcTemplate.queryForMap(
                    "SELECT current_value FROM plm_meta.meta_code_sequence WHERE rule_code = ? FOR UPDATE",
                    ruleCode
            );
            long next = ((Number) seqRow.get("current_value")).longValue() + 1;
            jdbcTemplate.update("UPDATE plm_meta.meta_code_sequence SET current_value = ? WHERE rule_code = ?", next, ruleCode);
            int seqWidth = sequenceWidth(ruleCode);
            String seqStr = String.format("%0" + seqWidth + "d", next);
            result = result.replace("{SEQ}", seqStr);
        }

        // 上下文占位符替换
        for (Map.Entry<String,String> e : ctx.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            result = result.replace("{" + e.getKey() + "}", e.getValue());
        }

        // inherit_prefix 兜底：如果要求继承但 pattern 没包含父级相关占位符，则尝试使用 PARENT_CODE
        if (meta.inheritPrefix && meta.parentRuleId != null && !containsAnyParentPlaceholder(meta.pattern)) {
            String parentCode = firstNonNull(ctx.get("PARENT_CODE"), ctx.get("CATEGORY_CODE"), ctx.get("ATTRIBUTE_CODE"));
            if (parentCode != null && !result.startsWith(parentCode)) {
                result = parentCode + (result.startsWith("-") ? "" : "-") + result; // 避免重复加 -
            }
        }
        return result;
    }

    private int sequenceWidth(String ruleCode) {
        return CodeRuleSupport.sequenceWidth(ruleCode);
    }

    private boolean containsAnyParentPlaceholder(String pattern) {
        return pattern.contains("{PARENT_CODE}") || pattern.contains("{CATEGORY_CODE}") || pattern.contains("{ATTRIBUTE_CODE}");
    }

    private String firstNonNull(String... arr) {
        for (String s : arr) if (s != null) return s; return null;
    }

    private static class RuleMeta {
        final String pattern; final boolean inheritPrefix; final java.util.UUID parentRuleId;
        RuleMeta(String p, boolean i, java.util.UUID id) { this.pattern = p; this.inheritPrefix = i; this.parentRuleId = id; }
    }
}
