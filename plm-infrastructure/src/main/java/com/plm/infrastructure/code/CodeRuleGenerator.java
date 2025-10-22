package com.plm.infrastructure.code;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
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
     * 使用事务保证 SELECT ... FOR UPDATE 与后续 UPDATE 原子性。
     */
    @Transactional
    public synchronized String generate(String ruleCode) {
        // 1. 读取规则模式（示例字段: pattern）
        String pattern = jdbcTemplate.query(
                "SELECT pattern FROM plm_meta.meta_code_rule WHERE code = ?",
                rs -> rs.next() ? rs.getString("pattern") : null,
                ruleCode
        );
        if (pattern == null) {
            throw new IllegalArgumentException("编码规则不存在: " + ruleCode);
        }
        // 2. 获取并更新序列值
        Map<String, Object> seqRow = jdbcTemplate.queryForMap(
                "SELECT current_value FROM plm_meta.meta_code_sequence WHERE rule_code = ? FOR UPDATE",
                ruleCode
        );
        long current = ((Number) seqRow.get("current_value")).longValue();
        long next = current + 1;
        jdbcTemplate.update("UPDATE plm_meta.meta_code_sequence SET current_value = ? WHERE rule_code = ?", next, ruleCode);

        // 3. 替换 pattern 变量
        String dateStr = LocalDate.now().toString().replaceAll("-", "");
        String code = pattern
                .replace("{DATE}", dateStr)
                .replace("{SEQ}", String.format("%05d", next));
        return code;
    }
}
