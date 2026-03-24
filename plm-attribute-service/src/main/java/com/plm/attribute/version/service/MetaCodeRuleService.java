package com.plm.attribute.version.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.common.api.dto.code.CodeRuleDetailDto;
import com.plm.common.api.dto.code.CodeRulePreviewRequestDto;
import com.plm.common.api.dto.code.CodeRulePreviewResponseDto;
import com.plm.common.api.dto.code.CodeRuleSaveRequestDto;
import com.plm.common.version.domain.MetaCodeGenerationAudit;
import com.plm.common.version.domain.MetaCodeRule;
import com.plm.common.version.domain.MetaCodeRuleVersion;
import com.plm.common.version.util.CodeRuleSupport;
import com.plm.infrastructure.code.CodeRuleGenerator;
import com.plm.infrastructure.version.repository.MetaCodeGenerationAuditRepository;
import com.plm.infrastructure.version.repository.MetaCodeRuleRepository;
import com.plm.infrastructure.version.repository.MetaCodeRuleVersionRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class MetaCodeRuleService {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";

    private final MetaCodeRuleRepository codeRuleRepository;
    private final MetaCodeRuleVersionRepository codeRuleVersionRepository;
    private final MetaCodeGenerationAuditRepository codeGenerationAuditRepository;
    private final CodeRuleGenerator codeRuleGenerator;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetaCodeRuleService(MetaCodeRuleRepository codeRuleRepository,
                               MetaCodeRuleVersionRepository codeRuleVersionRepository,
                               MetaCodeGenerationAuditRepository codeGenerationAuditRepository,
                               CodeRuleGenerator codeRuleGenerator,
                               @Qualifier("mainDataSource") DataSource dataSource) {
        this.codeRuleRepository = codeRuleRepository;
        this.codeRuleVersionRepository = codeRuleVersionRepository;
        this.codeGenerationAuditRepository = codeGenerationAuditRepository;
        this.codeRuleGenerator = codeRuleGenerator;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Transactional(readOnly = true)
    public List<CodeRuleDetailDto> list(String targetType, String status) {
        String normalizedTargetType = trimToNull(targetType);
        String normalizedStatus = trimToNull(status);
        List<CodeRuleDetailDto> result = new ArrayList<>();
        for (MetaCodeRule rule : codeRuleRepository.findAllByOrderByCodeAsc()) {
            if (normalizedTargetType != null && !normalizedTargetType.equalsIgnoreCase(rule.getTargetType())) {
                continue;
            }
            if (normalizedStatus != null && !normalizedStatus.equalsIgnoreCase(rule.getStatus())) {
                continue;
            }
            result.add(toDetailDto(rule, loadLatestVersion(rule)));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public CodeRuleDetailDto detail(String ruleCode) {
        MetaCodeRule rule = loadRule(ruleCode);
        return toDetailDto(rule, loadLatestVersion(rule));
    }

    @Transactional
    public CodeRuleDetailDto create(CodeRuleSaveRequestDto request, String operator) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String ruleCode = normalizeRuleCode(request.getRuleCode());
        if (codeRuleRepository.existsByCode(ruleCode)) {
            throw new IllegalArgumentException("code rule already exists: ruleCode=" + ruleCode);
        }

        MetaCodeRule rule = new MetaCodeRule();
        rule.setCode(ruleCode);
        rule.setName(requireText(request.getName(), "name"));
        rule.setTargetType(normalizeTargetType(request.getTargetType()));
        rule.setPattern(requireText(request.getPattern(), "pattern"));
        rule.setScopeType(normalizeScopeType(request.getScopeType()));
        rule.setScopeValue(trimToNull(request.getScopeValue()));
        rule.setAllowManualOverride(Boolean.TRUE.equals(request.getAllowManualOverride()));
        rule.setRegexPattern(normalizeRegexPattern(request.getRegexPattern()));
        rule.setMaxLength(normalizeMaxLength(request.getMaxLength()));
        rule.setStatus(STATUS_DRAFT);
        rule.setActive(Boolean.FALSE);
        rule.setCreatedBy(normalizeOperator(operator));
        rule.setUpdatedAt(OffsetDateTime.now());
        rule.setUpdatedBy(normalizeOperator(operator));
        codeRuleRepository.save(rule);

        MetaCodeRuleVersion version = appendVersion(rule, request, normalizeOperator(operator));
        return toDetailDto(rule, version);
    }

    @Transactional
    public CodeRuleDetailDto update(String ruleCode, CodeRuleSaveRequestDto request, String operator) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        MetaCodeRule rule = loadRule(ruleCode);
        ensureDraftEditable(rule);

        String requestRuleCode = trimToNull(request.getRuleCode());
        if (requestRuleCode != null && !normalizeRuleCode(requestRuleCode).equals(rule.getCode())) {
            throw new IllegalArgumentException("ruleCode in path and body must match");
        }

        rule.setName(requireText(request.getName(), "name"));
        rule.setTargetType(normalizeTargetType(request.getTargetType()));
        rule.setPattern(requireText(request.getPattern(), "pattern"));
        rule.setScopeType(normalizeScopeType(request.getScopeType()));
        rule.setScopeValue(trimToNull(request.getScopeValue()));
        rule.setAllowManualOverride(Boolean.TRUE.equals(request.getAllowManualOverride()));
        rule.setRegexPattern(normalizeRegexPattern(request.getRegexPattern()));
        rule.setMaxLength(normalizeMaxLength(request.getMaxLength()));
        rule.setUpdatedAt(OffsetDateTime.now());
        rule.setUpdatedBy(normalizeOperator(operator));
        codeRuleRepository.save(rule);

        MetaCodeRuleVersion version = appendVersion(rule, request, normalizeOperator(operator));
        return toDetailDto(rule, version);
    }

    @Transactional
    public CodeRuleDetailDto publish(String ruleCode, String operator) {
        MetaCodeRule rule = loadRule(ruleCode);
        if (STATUS_ARCHIVED.equalsIgnoreCase(rule.getStatus())) {
            throw new IllegalArgumentException("archived rule cannot be published: ruleCode=" + ruleCode);
        }
        MetaCodeRuleVersion latest = loadLatestVersion(rule);
        Map<String, Object> latestRuleJson = readRuleJson(latest.getRuleJson());
        String latestPattern = readString(latestRuleJson, "pattern");
        if (latestPattern != null) {
            rule.setPattern(latestPattern);
        }
        rule.setStatus(STATUS_ACTIVE);
        rule.setActive(Boolean.TRUE);
        rule.setUpdatedAt(OffsetDateTime.now());
        rule.setUpdatedBy(normalizeOperator(operator));
        codeRuleRepository.save(rule);
        return toDetailDto(rule, latest);
    }

    @Transactional(readOnly = true)
    public CodeRulePreviewResponseDto preview(String ruleCode, CodeRulePreviewRequestDto request) {
        MetaCodeRule rule = loadRule(ruleCode);
        MetaCodeRuleVersion latest = loadLatestVersion(rule);
        String pattern = resolvePattern(rule, latest);
        Map<String, String> context = request == null || request.getContext() == null
                ? Collections.emptyMap()
                : request.getContext();
        String manualCode = request == null ? null : trimToNull(request.getManualCode());
        int count = request == null || request.getCount() == null ? 3 : Math.max(1, Math.min(request.getCount(), 20));

        List<String> examples = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (manualCode != null) {
            validateCodeAgainstRule(rule, manualCode, true);
            examples.add(manualCode);
        } else if (pattern.contains("{SEQ}")) {
            long currentValue = readCurrentSequenceValue(rule.getCode());
            for (int i = 1; i <= count; i++) {
                examples.add(renderPattern(pattern, context, currentValue + i));
            }
        } else {
            examples.add(renderPattern(pattern, context, null));
            if (count > 1) {
                warnings.add("RULE_HAS_NO_SEQUENCE_PLACEHOLDER");
            }
        }

        CodeRulePreviewResponseDto response = new CodeRulePreviewResponseDto();
        response.setRuleCode(rule.getCode());
        response.setRuleVersion(latest.getVersionNo());
        response.setPattern(pattern);
        response.setExamples(examples);
        response.setWarnings(warnings);
        return response;
    }

    @Transactional
    public GeneratedCodeResult generateCode(String ruleCode,
                                            String targetType,
                                            UUID targetId,
                                            Map<String, String> context,
                                            String manualCode,
                                            String operator,
                                            boolean freezeAfterGenerate) {
        MetaCodeRule rule = loadRule(ruleCode);
        if (!STATUS_ACTIVE.equalsIgnoreCase(rule.getStatus()) || !Boolean.TRUE.equals(rule.getActive())) {
            throw new IllegalArgumentException("code rule is not active: ruleCode=" + ruleCode);
        }
        MetaCodeRuleVersion latest = loadLatestVersion(rule);
        Map<String, String> safeContext = context == null ? Collections.emptyMap() : new LinkedHashMap<>(context);
        String normalizedManualCode = trimToNull(manualCode);
        boolean manualOverride = normalizedManualCode != null;

        String code;
        if (manualOverride) {
            validateCodeAgainstRule(rule, normalizedManualCode, true);
            code = normalizedManualCode;
        } else {
            code = codeRuleGenerator.generate(rule.getCode(), safeContext);
            validateCodeAgainstRule(rule, code, false);
        }

        MetaCodeGenerationAudit audit = new MetaCodeGenerationAudit();
        audit.setRuleCode(rule.getCode());
        audit.setRuleVersionNo(latest.getVersionNo());
        audit.setGeneratedCode(code);
        audit.setTargetType(normalizeTargetAuditType(targetType));
        audit.setTargetId(targetId);
        audit.setContextJson(writeJson(safeContext));
        audit.setManualOverrideFlag(manualOverride);
        audit.setFrozenFlag(freezeAfterGenerate);
        audit.setCreatedBy(normalizeOperator(operator));
        codeGenerationAuditRepository.save(audit);

        return new GeneratedCodeResult(code, rule.getCode(), latest.getVersionNo(), manualOverride, freezeAfterGenerate);
    }

    private MetaCodeRuleVersion appendVersion(MetaCodeRule rule, CodeRuleSaveRequestDto request, String operator) {
        codeRuleVersionRepository.findByCodeRuleAndIsLatestTrue(rule).ifPresent(existing -> {
            existing.setIsLatest(Boolean.FALSE);
            codeRuleVersionRepository.save(existing);
        });

        MetaCodeRuleVersion version = new MetaCodeRuleVersion();
        version.setCodeRule(rule);
        version.setVersionNo(codeRuleVersionRepository.findFirstByCodeRuleOrderByVersionNoDesc(rule)
                .map(existing -> existing.getVersionNo() + 1)
                .orElse(1));
        version.setRuleJson(buildRuleJson(request, rule));
        version.setHash(hash(version.getRuleJson()));
        version.setIsLatest(Boolean.TRUE);
        version.setCreatedBy(operator);
        return codeRuleVersionRepository.save(version);
    }

    private CodeRuleDetailDto toDetailDto(MetaCodeRule rule, MetaCodeRuleVersion latest) {
        CodeRuleDetailDto dto = new CodeRuleDetailDto();
        dto.setRuleCode(rule.getCode());
        dto.setName(rule.getName());
        dto.setTargetType(rule.getTargetType());
        dto.setScopeType(rule.getScopeType());
        dto.setScopeValue(rule.getScopeValue());
        dto.setPattern(resolvePattern(rule, latest));
        dto.setStatus(rule.getStatus());
        dto.setActive(rule.getActive());
        dto.setAllowManualOverride(rule.getAllowManualOverride());
        dto.setRegexPattern(rule.getRegexPattern());
        dto.setMaxLength(rule.getMaxLength());
        dto.setLatestVersionNo(latest == null ? null : latest.getVersionNo());
        dto.setLatestRuleJson(latest == null ? Collections.emptyMap() : readRuleJson(latest.getRuleJson()));
        return dto;
    }

    private MetaCodeRule loadRule(String ruleCode) {
        String normalizedRuleCode = normalizeRuleCode(ruleCode);
        return codeRuleRepository.findByCode(normalizedRuleCode)
                .orElseThrow(() -> new IllegalArgumentException("code rule not found: ruleCode=" + normalizedRuleCode));
    }

    private MetaCodeRuleVersion loadLatestVersion(MetaCodeRule rule) {
        return codeRuleVersionRepository.findByCodeRuleAndIsLatestTrue(rule)
                .orElseThrow(() -> new IllegalArgumentException("code rule latest version not found: ruleCode=" + rule.getCode()));
    }

    private void ensureDraftEditable(MetaCodeRule rule) {
        if (!STATUS_DRAFT.equalsIgnoreCase(rule.getStatus())) {
            throw new IllegalArgumentException("only draft rule can be updated: ruleCode=" + rule.getCode());
        }
    }

    private String buildRuleJson(CodeRuleSaveRequestDto request, MetaCodeRule rule) {
        if (request.getRuleJson() != null && !request.getRuleJson().isEmpty()) {
            return writeJson(request.getRuleJson());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("pattern", rule.getPattern());

        List<String> tokens = new ArrayList<>();
        if (rule.getPattern().contains("{BUSINESS_DOMAIN}")) {
            tokens.add("BUSINESS_DOMAIN");
        }
        if (rule.getPattern().contains("{CATEGORY_CODE}")) {
            tokens.add("CATEGORY_CODE");
        }
        if (rule.getPattern().contains("{ATTRIBUTE_CODE}")) {
            tokens.add("ATTRIBUTE_CODE");
        }
        if (rule.getPattern().contains("{LOV_CODE}")) {
            tokens.add("LOV_CODE");
        }
        if (rule.getPattern().contains("{SEQ}")) {
            tokens.add("SEQ");
        }
        root.put("tokens", tokens);

        Map<String, Object> sequence = new LinkedHashMap<>();
        sequence.put("enabled", rule.getPattern().contains("{SEQ}"));
        sequence.put("width", sequenceWidth(rule.getCode()));
        sequence.put("step", 1);
        root.put("sequence", sequence);

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("maxLength", rule.getMaxLength());
        validation.put("regex", rule.getRegexPattern());
        validation.put("allowManualOverride", rule.getAllowManualOverride());
        root.put("validation", validation);

        return writeJson(root);
    }

    private String resolvePattern(MetaCodeRule rule, MetaCodeRuleVersion latest) {
        if (latest != null) {
            String latestPattern = readString(readRuleJson(latest.getRuleJson()), "pattern");
            if (latestPattern != null) {
                return latestPattern;
            }
        }
        return rule.getPattern();
    }

    private Map<String, Object> readRuleJson(String ruleJson) {
        if (trimToNull(ruleJson) == null) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(ruleJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("invalid ruleJson in storage", ex);
        }
    }

    private String renderPattern(String pattern, Map<String, String> context, Long sequenceValue) {
        String result = pattern.replace("{DATE}", LocalDate.now().toString().replace("-", ""));
        if (sequenceValue != null && result.contains("{SEQ}")) {
            String sequence = String.format("%0" + sequenceWidth(normalizeSequenceRuleCode(pattern, context)) + "d", sequenceValue);
            result = result.replace("{SEQ}", sequence);
        }
        for (Map.Entry<String, String> entry : context.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        if (result.contains("{")) {
            throw new IllegalArgumentException("preview context is incomplete for pattern: " + pattern);
        }
        return result;
    }

    private String normalizeSequenceRuleCode(String pattern, Map<String, String> context) {
        if (pattern.contains("ATTR_")) {
            return "ATTRIBUTE";
        }
        if (pattern.contains("LOV") || context.containsKey("ATTRIBUTE_CODE")) {
            return "LOV";
        }
        if (pattern.contains("BUSINESS_DOMAIN")) {
            return "CATEGORY";
        }
        return "CATEGORY";
    }

    private long readCurrentSequenceValue(String ruleCode) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT current_value FROM plm_meta.meta_code_sequence WHERE rule_code = ?",
                (rs, rowNum) -> rs.getLong(1),
                normalizeRuleCode(ruleCode)
        );
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    private void validateCodeAgainstRule(MetaCodeRule rule, String code, boolean manualOverride) {
        if (manualOverride && !Boolean.TRUE.equals(rule.getAllowManualOverride())) {
            throw new IllegalArgumentException("manual code override is not allowed: ruleCode=" + rule.getCode());
        }
        if (trimToNull(code) == null) {
            throw new IllegalArgumentException("generated code cannot be blank");
        }
        if (code.contains("{")) {
            throw new IllegalArgumentException("code contains unresolved placeholders: code=" + code);
        }
        if (rule.getMaxLength() != null && code.length() > rule.getMaxLength()) {
            throw new IllegalArgumentException("code exceeds maxLength: maxLength=" + rule.getMaxLength());
        }
        String regexPattern = trimToNull(rule.getRegexPattern());
        if (regexPattern != null && !code.matches(regexPattern)) {
            throw new IllegalArgumentException("code does not match regex pattern: pattern=" + regexPattern);
        }
    }

    private int sequenceWidth(String ruleCode) {
        return CodeRuleSupport.sequenceWidth(normalizeRuleCode(ruleCode));
    }

    private String normalizeRuleCode(String ruleCode) {
        String normalized = trimToNull(ruleCode);
        if (normalized == null) {
            throw new IllegalArgumentException("ruleCode is required");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeTargetType(String targetType) {
        String normalized = trimToNull(targetType);
        if (normalized == null) {
            throw new IllegalArgumentException("targetType is required");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeTargetAuditType(String targetType) {
        String normalized = trimToNull(targetType);
        return normalized == null ? "UNKNOWN" : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeScopeType(String scopeType) {
        String normalized = trimToNull(scopeType);
        return normalized == null ? "GLOBAL" : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeRegexPattern(String regexPattern) {
        return trimToNull(regexPattern);
    }

    private Integer normalizeMaxLength(Integer maxLength) {
        if (maxLength == null) {
            return 64;
        }
        if (maxLength < 1 || maxLength > 128) {
            throw new IllegalArgumentException("maxLength must be between 1 and 128");
        }
        return maxLength;
    }

    private String requireText(String value, String fieldName) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }

    private String normalizeOperator(String operator) {
        String normalized = trimToNull(operator);
        return normalized == null ? "system" : normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String readString(Map<String, Object> json, String key) {
        Object value = json.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("failed to serialize json", ex);
        }
    }

    private String hash(String input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        return CodeRuleSupport.md5Hex(input);
    }

    public record GeneratedCodeResult(
            String code,
            String ruleCode,
            Integer ruleVersion,
            boolean manualOverride,
            boolean frozen
    ) {
    }
}