package com.plm.attribute.version.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.common.version.domain.MetaCodeRule;
import com.plm.common.version.domain.MetaCodeRuleSet;
import com.plm.common.version.domain.MetaCodeRuleVersion;
import com.plm.common.version.util.CodeRuleSupport;
import com.plm.infrastructure.version.repository.MetaCodeRuleRepository;
import com.plm.infrastructure.version.repository.MetaCodeRuleSetRepository;
import com.plm.infrastructure.version.repository.MetaCodeRuleVersionRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MetaCodeRuleBootstrapService {

    private static final String BUSINESS_DOMAIN = "MATERIAL";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String OPERATOR = "system-bootstrap";

    private final MetaCodeRuleRepository codeRuleRepository;
    private final MetaCodeRuleVersionRepository codeRuleVersionRepository;
    private final MetaCodeRuleSetRepository codeRuleSetRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetaCodeRuleBootstrapService(MetaCodeRuleRepository codeRuleRepository,
                                        MetaCodeRuleVersionRepository codeRuleVersionRepository,
                                        MetaCodeRuleSetRepository codeRuleSetRepository) {
        this.codeRuleRepository = codeRuleRepository;
        this.codeRuleVersionRepository = codeRuleVersionRepository;
        this.codeRuleSetRepository = codeRuleSetRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureBuiltInRulesAndRuleSet() {
        MetaCodeRule categoryRule = ensureRule(new BuiltInRuleSpec(
                "CATEGORY",
                "category",
                "Material 默认分类编码规则",
                "{BUSINESS_DOMAIN}-{SEQ}"
        ));
        MetaCodeRule attributeRule = ensureRule(new BuiltInRuleSpec(
                "ATTRIBUTE",
                "attribute",
                "Material 默认属性编码规则",
                "ATTR-{CATEGORY_CODE}-{SEQ}"
        ));
        MetaCodeRule lovRule = ensureRule(new BuiltInRuleSpec(
                "LOV",
                "lov",
                "Material 默认枚举值编码规则",
                "ENUM-{ATTRIBUTE_CODE}-{SEQ}"
        ));

        ensureRuleSet(categoryRule, attributeRule, lovRule);
    }

    private MetaCodeRule ensureRule(BuiltInRuleSpec spec) {
        MetaCodeRule rule = codeRuleRepository.findByCode(spec.ruleCode()).orElse(null);
        if (rule == null) {
            rule = new MetaCodeRule();
            rule.setCode(spec.ruleCode());
            rule.setBusinessDomain(BUSINESS_DOMAIN);
            rule.setName(spec.name());
            rule.setTargetType(spec.targetType());
            rule.setPattern(spec.pattern());
            rule.setScopeType("GLOBAL");
            rule.setAllowManualOverride(Boolean.TRUE);
            rule.setRegexPattern("^[A-Z][A-Z0-9_./-]{0,127}$");
            rule.setMaxLength(128);
            rule.setStatus(STATUS_ACTIVE);
            rule.setActive(Boolean.TRUE);
            rule.setCreatedBy(OPERATOR);
            rule.setUpdatedAt(OffsetDateTime.now());
            rule.setUpdatedBy(OPERATOR);
            codeRuleRepository.save(rule);
        }

        ensureLatestVersion(rule, spec);
        return rule;
    }

    private void ensureLatestVersion(MetaCodeRule rule, BuiltInRuleSpec spec) {
        MetaCodeRuleVersion latestVersion = codeRuleVersionRepository.findByCodeRuleAndIsLatestTrue(rule).orElse(null);
        if (latestVersion != null) {
            return;
        }

        List<MetaCodeRuleVersion> versions = codeRuleVersionRepository.findByCodeRuleOrderByVersionNoDesc(rule);
        for (MetaCodeRuleVersion version : versions) {
            if (Boolean.TRUE.equals(version.getIsLatest())) {
                version.setIsLatest(Boolean.FALSE);
                codeRuleVersionRepository.save(version);
            }
        }

        MetaCodeRuleVersion version = new MetaCodeRuleVersion();
        version.setCodeRule(rule);
        version.setVersionNo(versions.isEmpty() ? 1 : versions.get(0).getVersionNo() + 1);
        version.setRuleJson(writeJson(buildStructuredDefaultRuleJson(spec)));
        version.setHash(CodeRuleSupport.md5Hex(version.getRuleJson()));
        version.setIsLatest(Boolean.TRUE);
        version.setCreatedBy(OPERATOR);
        codeRuleVersionRepository.save(version);
    }

    private void ensureRuleSet(MetaCodeRule categoryRule, MetaCodeRule attributeRule, MetaCodeRule lovRule) {
        if (codeRuleSetRepository.existsByBusinessDomain(BUSINESS_DOMAIN)) {
            return;
        }

        MetaCodeRuleSet ruleSet = new MetaCodeRuleSet();
        ruleSet.setBusinessDomain(BUSINESS_DOMAIN);
        ruleSet.setName("Material 默认编码规则集");
        ruleSet.setStatus(STATUS_ACTIVE);
        ruleSet.setActive(Boolean.TRUE);
        ruleSet.setRemark("系统自动补齐默认编码规则集");
        ruleSet.setCategoryRuleCode(categoryRule.getCode());
        ruleSet.setAttributeRuleCode(attributeRule.getCode());
        ruleSet.setLovRuleCode(lovRule.getCode());
        ruleSet.setCreatedBy(OPERATOR);
        ruleSet.setUpdatedAt(OffsetDateTime.now());
        ruleSet.setUpdatedBy(OPERATOR);
        codeRuleSetRepository.save(ruleSet);
    }

    private Map<String, Object> buildStructuredDefaultRuleJson(BuiltInRuleSpec spec) {
        Map<String, Object> subRule = new LinkedHashMap<>();
        String subRuleKey;
        switch (spec.targetType()) {
            case "category" -> {
                subRuleKey = "category";
                subRule.put("separator", "-");
                subRule.put("segments", List.of(
                        variableSegment("BUSINESS_DOMAIN"),
                        sequenceSegment(CodeRuleSupport.CATEGORY_SEQUENCE_WIDTH, "NEVER", "GLOBAL")
                ));
                subRule.put("allowedVariableKeys", List.of("BUSINESS_DOMAIN", "PARENT_CODE"));
            }
            case "attribute" -> {
                subRuleKey = "attribute";
                subRule.put("separator", "-");
                subRule.put("segments", List.of(
                        stringSegment("ATTR"),
                        variableSegment("CATEGORY_CODE"),
                        sequenceSegment(CodeRuleSupport.ATTRIBUTE_SEQUENCE_WIDTH, "PER_PARENT", "CATEGORY_CODE")
                ));
                subRule.put("allowedVariableKeys", List.of("BUSINESS_DOMAIN", "CATEGORY_CODE"));
            }
            case "lov" -> {
                subRuleKey = "enum";
                subRule.put("separator", "-");
                subRule.put("segments", List.of(
                        stringSegment("ENUM"),
                        variableSegment("ATTRIBUTE_CODE"),
                        sequenceSegment(CodeRuleSupport.LOV_SEQUENCE_WIDTH, "PER_PARENT", "ATTRIBUTE_CODE")
                ));
                subRule.put("allowedVariableKeys", List.of("ATTRIBUTE_CODE", "CATEGORY_CODE", "BUSINESS_DOMAIN"));
            }
            default -> throw new IllegalArgumentException("unsupported built-in targetType: " + spec.targetType());
        }

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("maxLength", 128);
        validation.put("regex", "^[A-Z][A-Z0-9_./-]{0,127}$");
        validation.put("allowManualOverride", true);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("pattern", spec.pattern());
        root.put("hierarchyMode", "NONE");
        root.put("subRules", Map.of(subRuleKey, subRule));
        root.put("validation", validation);
        return root;
    }

    private Map<String, Object> stringSegment(String value) {
        return Map.of("type", "STRING", "value", value);
    }

    private Map<String, Object> variableSegment(String variableKey) {
        return Map.of("type", "VARIABLE", "variableKey", variableKey);
    }

    private Map<String, Object> sequenceSegment(int length, String resetRule, String scopeKey) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("type", "SEQUENCE");
        segment.put("length", length);
        segment.put("startValue", 1);
        segment.put("step", 1);
        segment.put("resetRule", resetRule);
        segment.put("scopeKey", scopeKey);
        return segment;
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize built-in rule json", ex);
        }
    }

    private record BuiltInRuleSpec(String ruleCode, String targetType, String name, String pattern) {
    }
}