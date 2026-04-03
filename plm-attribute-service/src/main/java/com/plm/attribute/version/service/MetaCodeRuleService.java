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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetaCodeRuleService(MetaCodeRuleRepository codeRuleRepository,
                               MetaCodeRuleVersionRepository codeRuleVersionRepository,
                               MetaCodeGenerationAuditRepository codeGenerationAuditRepository,
                               CodeRuleGenerator codeRuleGenerator) {
        this.codeRuleRepository = codeRuleRepository;
        this.codeRuleVersionRepository = codeRuleVersionRepository;
        this.codeGenerationAuditRepository = codeGenerationAuditRepository;
        this.codeRuleGenerator = codeRuleGenerator;
    }

    @Transactional(readOnly = true)
    public List<CodeRuleDetailDto> list(String businessDomain, String targetType, String status) {
        String normalizedBusinessDomain = trimToNull(businessDomain);
        String normalizedTargetType = trimToNull(targetType);
        String normalizedStatus = trimToNull(status);
        List<CodeRuleDetailDto> result = new ArrayList<>();
        List<MetaCodeRule> rules = normalizedBusinessDomain == null
                ? codeRuleRepository.findAllByOrderByCodeAsc()
                : codeRuleRepository.findAllByBusinessDomainOrderByCodeAsc(normalizeBusinessDomain(normalizedBusinessDomain));
        for (MetaCodeRule rule : rules) {
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

    @Transactional(readOnly = true)
    public Map<String, CodeRuleDetailDto> detailByRuleCodes(List<String> ruleCodes) {
        if (ruleCodes == null || ruleCodes.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> normalizedRuleCodes = ruleCodes.stream()
                .map(this::normalizeRuleCode)
                .distinct()
                .toList();
        List<MetaCodeRule> rules = codeRuleRepository.findAllByCodeIn(normalizedRuleCodes);
        Map<String, MetaCodeRule> rulesByCode = new LinkedHashMap<>();
        for (MetaCodeRule rule : rules) {
            rulesByCode.put(rule.getCode(), rule);
        }
        for (String normalizedRuleCode : normalizedRuleCodes) {
            if (!rulesByCode.containsKey(normalizedRuleCode)) {
                throw new IllegalArgumentException("code rule not found: ruleCode=" + normalizedRuleCode);
            }
        }

        Map<String, MetaCodeRuleVersion> latestVersionsByCode = loadLatestVersionsByCode(rules);
        Map<String, CodeRuleDetailDto> result = new LinkedHashMap<>();
        for (String normalizedRuleCode : normalizedRuleCodes) {
            MetaCodeRule rule = rulesByCode.get(normalizedRuleCode);
            MetaCodeRuleVersion latestVersion = latestVersionsByCode.get(normalizedRuleCode);
            if (latestVersion == null) {
                throw new IllegalArgumentException("code rule latest version not found: ruleCode=" + normalizedRuleCode);
            }
            result.put(normalizedRuleCode, toDetailDto(rule, latestVersion));
        }
        return result;
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
        rule.setBusinessDomain(normalizeBusinessDomain(request.getBusinessDomain()));
        rule.setCode(ruleCode);
        rule.setName(requireText(request.getName(), "name"));
        rule.setTargetType(normalizeTargetType(request.getTargetType()));
        ensureBusinessDomainTargetTypeUnique(rule.getBusinessDomain(), rule.getTargetType(), null);
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
        String requestBusinessDomain = normalizeBusinessDomain(request.getBusinessDomain());
        if (!requestBusinessDomain.equals(rule.getBusinessDomain())) {
            throw new IllegalArgumentException("businessDomain in body must match existing rule");
        }
        rule.setTargetType(normalizeTargetType(request.getTargetType()));
        ensureBusinessDomainTargetTypeUnique(rule.getBusinessDomain(), rule.getTargetType(), rule.getCode());
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
        validateRuleForPublish(rule, latestRuleJson);
        String latestPattern = readString(latestRuleJson, "pattern");
        if (latestPattern != null) {
            rule.setPattern(latestPattern);
        }
        rule.setStatus(STATUS_ACTIVE);
        rule.setActive(Boolean.TRUE);
        rule.setUpdatedAt(OffsetDateTime.now());
        rule.setUpdatedBy(normalizeOperator(operator));
        codeRuleRepository.saveAndFlush(rule);
        MetaCodeRule persistedRule = loadRule(ruleCode);
        return toDetailDto(persistedRule, latest);
    }

    @Transactional(readOnly = true)
    public CodeRulePreviewResponseDto preview(String ruleCode, CodeRulePreviewRequestDto request) {
        MetaCodeRule rule = loadRule(ruleCode);
        MetaCodeRuleVersion latest = loadLatestVersion(rule);
        Map<String, String> context = request == null || request.getContext() == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(request.getContext());
        String manualCode = request == null ? null : trimToNull(request.getManualCode());
        int count = request == null || request.getCount() == null ? 3 : Math.max(1, request.getCount());

        String pattern = resolvePattern(rule, latest);
        List<String> examples;
        List<String> warnings;
        Map<String, String> resolvedContext = context;
        String resolvedSequenceScope = null;
        String resolvedPeriodKey = null;
        if (manualCode != null) {
            validateCodeAgainstRule(rule, manualCode, true);
            examples = List.of(manualCode);
            warnings = Collections.emptyList();
        } else {
            CodeRuleGenerator.PreviewResult previewResult = codeRuleGenerator.preview(rule.getCode(), context, count);
            pattern = previewResult.pattern();
            examples = previewResult.examples();
            warnings = previewResult.warnings();
            resolvedContext = previewResult.resolvedContext();
            resolvedSequenceScope = previewResult.resolvedSequenceScope();
            resolvedPeriodKey = previewResult.resolvedPeriodKey();
        }

        CodeRulePreviewResponseDto response = new CodeRulePreviewResponseDto();
        response.setRuleCode(rule.getCode());
        response.setRuleVersion(latest.getVersionNo());
        response.setPattern(pattern);
        response.setExamples(examples);
        response.setWarnings(warnings);
        response.setResolvedContext(resolvedContext);
        response.setResolvedSequenceScope(resolvedSequenceScope);
        response.setResolvedPeriodKey(resolvedPeriodKey);
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

    @Transactional
    public ReservedCodeBatchResult reserveCodes(String ruleCode,
                                                String targetType,
                                                Map<String, String> context,
                                                int count,
                                                String operator,
                                                boolean freezeAfterGenerate) {
        MetaCodeRule rule = loadRule(ruleCode);
        if (!STATUS_ACTIVE.equalsIgnoreCase(rule.getStatus()) || !Boolean.TRUE.equals(rule.getActive())) {
            throw new IllegalArgumentException("code rule is not active: ruleCode=" + ruleCode);
        }
        MetaCodeRuleVersion latest = loadLatestVersion(rule);
        Map<String, String> safeContext = context == null ? Collections.emptyMap() : new LinkedHashMap<>(context);
        int reservationCount = Math.max(1, count);

        CodeRuleGenerator.ReservationResult reservation = codeRuleGenerator.reserve(rule.getCode(), safeContext, reservationCount);
        List<String> codes = reservation.codes();
        for (String code : codes) {
            validateCodeAgainstRule(rule, code, false);
        }

        List<MetaCodeGenerationAudit> audits = new ArrayList<>(codes.size());
        String normalizedTargetType = normalizeTargetAuditType(targetType);
        String normalizedOperator = normalizeOperator(operator);
        String contextJson = writeJson(safeContext);
        for (String code : codes) {
            MetaCodeGenerationAudit audit = new MetaCodeGenerationAudit();
            audit.setRuleCode(rule.getCode());
            audit.setRuleVersionNo(latest.getVersionNo());
            audit.setGeneratedCode(code);
            audit.setTargetType(normalizedTargetType);
            audit.setTargetId(null);
            audit.setContextJson(contextJson);
            audit.setManualOverrideFlag(Boolean.FALSE);
            audit.setFrozenFlag(freezeAfterGenerate);
            audit.setCreatedBy(normalizedOperator);
            audits.add(audit);
        }
        codeGenerationAuditRepository.saveAll(audits);

        return new ReservedCodeBatchResult(
                codes,
                rule.getCode(),
                latest.getVersionNo(),
                freezeAfterGenerate,
                reservation.resolvedSequenceScope(),
                reservation.resolvedPeriodKey()
        );
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
        Map<String, Object> latestRuleJson = latest == null ? Collections.emptyMap() : readRuleJson(latest.getRuleJson());
        CodeRuleDetailDto dto = new CodeRuleDetailDto();
        dto.setBusinessDomain(rule.getBusinessDomain());
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
        dto.setSupportsHierarchy(supportsHierarchy(latestRuleJson));
        dto.setSupportsScopedSequence(supportsScopedSequence(latestRuleJson));
        dto.setSupportedVariableKeys(extractSupportedVariableKeys(rule, latestRuleJson));
        dto.setLatestRuleJson(latestRuleJson);
        return dto;
    }

    private boolean supportsHierarchy(Map<String, Object> ruleJson) {
        String hierarchyMode = normalizeHierarchyMode(readString(ruleJson, "hierarchyMode"));
        if (!"NONE".equals(hierarchyMode)) {
            return true;
        }
        Map<String, Object> subRules = readObjectMap(ruleJson.get("subRules"));
        for (Object subRuleValue : subRules.values()) {
            if (!readObjectList(readObjectMap(subRuleValue).get("childSegments")).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean supportsScopedSequence(Map<String, Object> ruleJson) {
        Map<String, Object> subRules = readObjectMap(ruleJson.get("subRules"));
        for (Object subRuleValue : subRules.values()) {
            Map<String, Object> subRule = readObjectMap(subRuleValue);
            if (segmentsRequireScopedSequence(readObjectList(subRule.get("segments")))) {
                return true;
            }
            if (segmentsRequireScopedSequence(readObjectList(subRule.get("childSegments")))) {
                return true;
            }
        }

        Map<String, Object> sequence = readObjectMap(ruleJson.get("sequence"));
        String scopeKey = normalizeScopeKey(readString(sequence, "scopeKey"), normalizeResetRule(readString(sequence, "resetRule")));
        String resetRule = normalizeResetRule(readString(sequence, "resetRule"));
        return !sequence.isEmpty() && (!"GLOBAL".equals(scopeKey) || !"NEVER".equals(resetRule));
    }

    private boolean segmentsRequireScopedSequence(List<Map<String, Object>> segments) {
        for (Map<String, Object> segment : segments) {
            if (!"SEQUENCE".equals(normalizeSegmentType(readString(segment, "type")))) {
                continue;
            }
            String resetRule = normalizeResetRule(readString(segment, "resetRule"));
            String scopeKey = normalizeScopeKey(readString(segment, "scopeKey"), resetRule);
            if (!"GLOBAL".equals(scopeKey) || !"NEVER".equals(resetRule)) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractSupportedVariableKeys(MetaCodeRule rule, Map<String, Object> ruleJson) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Map<String, Object> subRules = readObjectMap(ruleJson.get("subRules"));
        for (Object subRuleValue : subRules.values()) {
            Map<String, Object> subRule = readObjectMap(subRuleValue);
            result.addAll(readStringList(subRule.get("allowedVariableKeys")));
        }
        if (!result.isEmpty()) {
            return new ArrayList<>(result);
        }

        String pattern = resolvePattern(rule, null);
        if (pattern.contains("{BUSINESS_DOMAIN}")) {
            result.add("BUSINESS_DOMAIN");
        }
        if (pattern.contains("{PARENT_CODE}")) {
            result.add("PARENT_CODE");
        }
        if (pattern.contains("{CATEGORY_CODE}")) {
            result.add("CATEGORY_CODE");
        }
        if (pattern.contains("{ATTRIBUTE_CODE}")) {
            result.add("ATTRIBUTE_CODE");
        }
        if (pattern.contains("{LOV_CODE}")) {
            result.add("LOV_CODE");
        }
        return new ArrayList<>(result);
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

    private Map<String, MetaCodeRuleVersion> loadLatestVersionsByCode(List<MetaCodeRule> rules) {
        List<MetaCodeRuleVersion> latestVersions = codeRuleVersionRepository.findByCodeRuleInAndIsLatestTrue(rules);
        Map<String, MetaCodeRuleVersion> result = new LinkedHashMap<>();
        for (MetaCodeRuleVersion latestVersion : latestVersions) {
            result.put(latestVersion.getCodeRule().getCode(), latestVersion);
        }
        return result;
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
        Map<String, Object> structuredDefault = buildStructuredDefaultRuleJson(rule);
        if (!structuredDefault.isEmpty()) {
            return writeJson(structuredDefault);
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
        sequence.put("resetRule", "NEVER");
        sequence.put("scopeKey", "GLOBAL");
        root.put("sequence", sequence);

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("maxLength", rule.getMaxLength());
        validation.put("regex", rule.getRegexPattern());
        validation.put("allowManualOverride", rule.getAllowManualOverride());
        root.put("validation", validation);

        return writeJson(root);
    }

    private Map<String, Object> buildStructuredDefaultRuleJson(MetaCodeRule rule) {
        if (!shouldUseStructuredDefault(rule)) {
            return Collections.emptyMap();
        }
        String normalizedTargetType = normalizeTargetType(rule.getTargetType());
        return switch (normalizedTargetType) {
            case "category" -> buildCategoryStructuredDefault(rule);
            case "attribute" -> buildAttributeStructuredDefault(rule);
            case "lov" -> buildLovStructuredDefault(rule);
            default -> Collections.emptyMap();
        };
    }

    private boolean shouldUseStructuredDefault(MetaCodeRule rule) {
        String normalizedRuleCode = normalizeRuleCode(rule.getCode());
        String normalizedTargetType = normalizeTargetType(rule.getTargetType());
        return switch (normalizedTargetType) {
            case "category" -> normalizedRuleCode.equals("CATEGORY") || normalizedRuleCode.startsWith("CATEGORY_");
            case "attribute" -> normalizedRuleCode.equals("ATTRIBUTE") || normalizedRuleCode.startsWith("ATTRIBUTE_");
            case "lov" -> normalizedRuleCode.equals("LOV") || normalizedRuleCode.startsWith("LOV_");
            default -> false;
        };
    }

    private Map<String, Object> buildCategoryStructuredDefault(MetaCodeRule rule) {
        Map<String, Object> subRule = new LinkedHashMap<>();
        subRule.put("separator", "-");
        subRule.put("segments", List.of(
                variableSegment("BUSINESS_DOMAIN"),
                sequenceSegment(sequenceWidth(rule.getCode()), "NEVER", "GLOBAL")
        ));
        subRule.put("allowedVariableKeys", List.of("BUSINESS_DOMAIN", "PARENT_CODE"));
        return structuredRuleRoot(rule, "NONE", "category", subRule);
    }

    private Map<String, Object> buildAttributeStructuredDefault(MetaCodeRule rule) {
        Map<String, Object> subRule = new LinkedHashMap<>();
        subRule.put("separator", "-");
        subRule.put("segments", List.of(
            stringSegment("ATTR"),
            variableSegment("CATEGORY_CODE"),
            sequenceSegment(sequenceWidth(rule.getCode()), "PER_PARENT", "CATEGORY_CODE")
        ));
        subRule.put("allowedVariableKeys", List.of("BUSINESS_DOMAIN", "CATEGORY_CODE"));
        return structuredRuleRoot(rule, "NONE", "attribute", subRule);
    }

    private Map<String, Object> buildLovStructuredDefault(MetaCodeRule rule) {
        Map<String, Object> subRule = new LinkedHashMap<>();
        subRule.put("separator", "-");
        subRule.put("segments", List.of(
            stringSegment("ENUM"),
            variableSegment("ATTRIBUTE_CODE"),
            sequenceSegment(sequenceWidth(rule.getCode()), "PER_PARENT", "ATTRIBUTE_CODE")
        ));
        subRule.put("allowedVariableKeys", List.of("ATTRIBUTE_CODE", "CATEGORY_CODE", "BUSINESS_DOMAIN"));
        return structuredRuleRoot(rule, "NONE", "enum", subRule);
    }

    private Map<String, Object> structuredRuleRoot(MetaCodeRule rule,
                                                   String hierarchyMode,
                                                   String subRuleKey,
                                                   Map<String, Object> subRule) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("pattern", rule.getPattern());
        root.put("hierarchyMode", hierarchyMode);
        root.put("subRules", Map.of(subRuleKey, subRule));

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("maxLength", rule.getMaxLength());
        validation.put("regex", rule.getRegexPattern());
        validation.put("allowManualOverride", rule.getAllowManualOverride());
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

    private void validateRuleForPublish(MetaCodeRule rule, Map<String, Object> ruleJson) {
        Map<String, Object> subRules = readObjectMap(ruleJson.get("subRules"));
        if (subRules.isEmpty()) {
            return;
        }

        String hierarchyMode = normalizeHierarchyMode(readString(ruleJson, "hierarchyMode"));
        for (Map.Entry<String, Object> entry : subRules.entrySet()) {
            String subRuleKey = entry.getKey();
            Map<String, Object> subRule = readObjectMap(entry.getValue());
            if (subRule.isEmpty()) {
                throw new IllegalArgumentException("subRule must not be empty: subRuleKey=" + subRuleKey);
            }
            validateSubRule(rule, subRuleKey, subRule, hierarchyMode);
        }
    }

    private void validateSubRule(MetaCodeRule rule,
                                 String subRuleKey,
                                 Map<String, Object> subRule,
                                 String hierarchyMode) {
        List<Map<String, Object>> segments = readObjectList(subRule.get("segments"));
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("segments must not be empty: ruleCode=" + rule.getCode() + ", subRuleKey=" + subRuleKey);
        }

        Set<String> allowedVariableKeys = new LinkedHashSet<>(readStringList(subRule.get("allowedVariableKeys")));
        validateSegments(rule, subRuleKey, segments, allowedVariableKeys, false);

        if ("category".equalsIgnoreCase(subRuleKey) && "APPEND_CHILD_SUFFIX".equals(hierarchyMode)) {
            List<Map<String, Object>> childSegments = readObjectList(subRule.get("childSegments"));
            if (childSegments.isEmpty()) {
                throw new IllegalArgumentException("childSegments must not be empty when hierarchyMode=APPEND_CHILD_SUFFIX");
            }
            validateSegments(rule, subRuleKey, childSegments, allowedVariableKeys, true);
        }
    }

    private void validateSegments(MetaCodeRule rule,
                                  String subRuleKey,
                                  List<Map<String, Object>> segments,
                                  Set<String> allowedVariableKeys,
                                  boolean childSegments) {
        boolean containsDateSegment = segments.stream().anyMatch(this::isDateSegment);
        boolean hasRenderableContent = false;
        for (Map<String, Object> segment : segments) {
            String type = normalizeSegmentType(readString(segment, "type"));
            switch (type) {
                case "STRING" -> {
                    requireText(readString(segment, "value"), "segment.value is required");
                    hasRenderableContent = true;
                }
                case "VARIABLE" -> {
                    String variableKey = requireText(readString(segment, "variableKey"), "segment.variableKey is required");
                    if (!allowedVariableKeys.isEmpty() && !allowedVariableKeys.contains(variableKey)) {
                        throw new IllegalArgumentException("variable is not allowed in subRule: ruleCode=" + rule.getCode()
                                + ", subRuleKey=" + subRuleKey + ", variableKey=" + variableKey);
                    }
                    hasRenderableContent = true;
                }
                case "DATE" -> {
                    validateDateSegment(segment);
                    hasRenderableContent = true;
                }
                case "SEQUENCE" -> {
                    validateSequenceSegment(rule, subRuleKey, segment, containsDateSegment, childSegments);
                    hasRenderableContent = true;
                }
                default -> throw new IllegalArgumentException("unsupported segment type: " + type);
            }
        }
        if (!hasRenderableContent) {
            throw new IllegalArgumentException("segments must produce renderable content: ruleCode=" + rule.getCode() + ", subRuleKey=" + subRuleKey);
        }
    }

    private void validateSequenceSegment(MetaCodeRule rule,
                                         String subRuleKey,
                                         Map<String, Object> segment,
                                         boolean containsDateSegment,
                                         boolean childSegments) {
        int length = readInt(segment, "length", sequenceWidth(rule.getCode()));
        long startValue = readLong(segment, "startValue", 1L);
        long step = readLong(segment, "step", 1L);
        if (length < 1 || length > 32) {
            throw new IllegalArgumentException("sequence length must be between 1 and 32: ruleCode=" + rule.getCode());
        }
        if (startValue < 0) {
            throw new IllegalArgumentException("sequence startValue must not be negative: ruleCode=" + rule.getCode());
        }
        if (step < 1) {
            throw new IllegalArgumentException("sequence step must be greater than 0: ruleCode=" + rule.getCode());
        }

        String resetRule = normalizeResetRule(readString(segment, "resetRule"));
        String scopeKey = normalizeScopeKey(readString(segment, "scopeKey"), resetRule);
        if ("PER_PARENT".equals(resetRule) && "GLOBAL".equals(scopeKey)) {
            throw new IllegalArgumentException("PER_PARENT reset requires non-global scopeKey: ruleCode=" + rule.getCode()
                    + ", subRuleKey=" + subRuleKey);
        }
        if (!"PER_PARENT".equals(resetRule) && "PARENT_CODE".equals(scopeKey)) {
            throw new IllegalArgumentException("only PER_PARENT reset may depend on PARENT_CODE scope: ruleCode=" + rule.getCode()
                    + ", subRuleKey=" + subRuleKey);
        }
        if (("DAILY".equals(resetRule) || "MONTHLY".equals(resetRule) || "YEARLY".equals(resetRule)) && !containsDateSegment) {
            throw new IllegalArgumentException("time-based reset requires a DATE segment: ruleCode=" + rule.getCode()
                    + ", subRuleKey=" + subRuleKey);
        }
        if (childSegments && !"PER_PARENT".equals(resetRule) && "category".equalsIgnoreCase(subRuleKey)) {
            throw new IllegalArgumentException("category childSegments must declare PER_PARENT resetRule");
        }
    }

    private void validateDateSegment(Map<String, Object> segment) {
        String format = trimToNull(readString(segment, "format"));
        if (format == null) {
            return;
        }
        DateTimeFormatter.ofPattern(format);
    }

    private boolean isDateSegment(Map<String, Object> segment) {
        return "DATE".equals(normalizeSegmentType(readString(segment, "type")));
    }

    private Map<String, Object> readObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private List<Map<String, Object>> readObjectList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : rawList) {
            Map<String, Object> map = readObjectMap(item);
            if (!map.isEmpty()) {
                result.add(map);
            }
        }
        return result;
    }

    private List<String> readStringList(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (Object item : rawList) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private String normalizeHierarchyMode(String hierarchyMode) {
        String normalized = trimToNull(hierarchyMode);
        return normalized == null ? "NONE" : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeResetRule(String resetRule) {
        String normalized = trimToNull(resetRule);
        return normalized == null ? "NEVER" : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeScopeKey(String scopeKey, String resetRule) {
        String normalized = trimToNull(scopeKey);
        if (normalized != null) {
            return normalized.toUpperCase(Locale.ROOT);
        }
        return "PER_PARENT".equals(resetRule) ? "PARENT_CODE" : "GLOBAL";
    }

    private String normalizeSegmentType(String segmentType) {
        String normalized = trimToNull(segmentType);
        if (normalized == null) {
            throw new IllegalArgumentException("segment.type is required");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private int readInt(Map<String, Object> json, String key, int defaultValue) {
        Object value = json.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private long readLong(Map<String, Object> json, String key, long defaultValue) {
        Object value = json.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
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

    private String normalizeBusinessDomain(String businessDomain) {
        String normalized = trimToNull(businessDomain);
        if (normalized == null) {
            throw new IllegalArgumentException("businessDomain is required");
        }
        return normalized.toUpperCase(Locale.ROOT);
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

    private void ensureBusinessDomainTargetTypeUnique(String businessDomain, String targetType, String currentRuleCode) {
        boolean exists = currentRuleCode == null
                ? codeRuleRepository.existsByBusinessDomainAndTargetType(businessDomain, targetType)
                : codeRuleRepository.existsByBusinessDomainAndTargetTypeAndCodeNot(businessDomain, targetType, currentRuleCode);
        if (exists) {
            throw new IllegalArgumentException("code rule targetType already exists for businessDomain: businessDomain="
                    + businessDomain + ", targetType=" + targetType);
        }
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

        public record ReservedCodeBatchResult(
            List<String> codes,
            String ruleCode,
            Integer ruleVersion,
            boolean frozen,
            String resolvedSequenceScope,
            String resolvedPeriodKey
        ) {
        }
}