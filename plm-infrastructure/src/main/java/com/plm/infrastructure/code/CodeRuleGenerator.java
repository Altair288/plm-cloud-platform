package com.plm.infrastructure.code;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.common.version.util.CodeRuleSupport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CodeRuleGenerator {

    private static final String GLOBAL_SCOPE_KEY = "GLOBAL";
    private static final String GLOBAL_SCOPE_VALUE = "GLOBAL";
    private static final String DEFAULT_SUB_RULE_KEY = "ROOT";
    private static final String DEFAULT_PERIOD_KEY = "NONE";
    private static final String RESET_NEVER = "NEVER";
    private static final String RESET_DAILY = "DAILY";
    private static final String RESET_MONTHLY = "MONTHLY";
    private static final String RESET_YEARLY = "YEARLY";
    private static final String RESET_PER_PARENT = "PER_PARENT";
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([A-Z0-9_]+)}");
    private static final DateTimeFormatter PERIOD_DAILY_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter PERIOD_MONTHLY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter PERIOD_YEARLY_FORMATTER = DateTimeFormatter.ofPattern("yyyy");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CodeRuleGenerator(@Qualifier("mainDataSource") DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Transactional
    public String generate(String ruleCode) {
        return generate(ruleCode, Collections.emptyMap());
    }

    @Transactional
    public String generate(String ruleCode, Map<String, String> context) {
        Map<String, String> safeContext = copyContext(context);
        RuleMeta meta = loadRuleMeta(ruleCode);
        StructuredRule structuredRule = resolveStructuredRule(meta, safeContext);
        if (structuredRule != null) {
            return renderStructured(meta, structuredRule, safeContext, 1, true).code();
        }
        return renderLegacy(meta, safeContext, 1, true).code();
    }

    @Transactional(readOnly = true)
    public PreviewResult preview(String ruleCode, Map<String, String> context, int count) {
        Map<String, String> safeContext = copyContext(context);
        RuleMeta meta = loadRuleMeta(ruleCode);
        StructuredRule structuredRule = resolveStructuredRule(meta, safeContext);
        int previewCount = Math.max(1, Math.min(count, 20));
        List<String> examples = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String resolvedSequenceScope = null;
        String resolvedPeriodKey = null;

        try {
            if (structuredRule != null) {
                boolean hasSequenceSegment = structuredRuleHasSequence(structuredRule);
                for (int index = 1; index <= previewCount; index++) {
                    RenderOutcome outcome = renderStructured(meta, structuredRule, safeContext, index, false);
                    examples.add(outcome.code());
                    if (resolvedSequenceScope == null) {
                        resolvedSequenceScope = outcome.resolvedSequenceScope();
                    }
                    if (resolvedPeriodKey == null) {
                        resolvedPeriodKey = outcome.resolvedPeriodKey();
                    }
                }
                if (!hasSequenceSegment && previewCount > 1) {
                    warnings.add("RULE_HAS_NO_SEQUENCE_PLACEHOLDER");
                }
            } else {
                boolean hasSequence = meta.pattern().contains("{SEQ}");
                for (int index = 1; index <= previewCount; index++) {
                    RenderOutcome outcome = renderLegacy(meta, safeContext, index, false);
                    examples.add(outcome.code());
                    if (resolvedSequenceScope == null) {
                        resolvedSequenceScope = outcome.resolvedSequenceScope();
                    }
                    if (resolvedPeriodKey == null) {
                        resolvedPeriodKey = outcome.resolvedPeriodKey();
                    }
                }
                if (!hasSequence && previewCount > 1) {
                    warnings.add("RULE_HAS_NO_SEQUENCE_PLACEHOLDER");
                }
            }
        } catch (IllegalArgumentException ex) {
            warnings.addAll(extractPreviewWarnings(ex, meta.pattern(), safeContext));
            examples = Collections.emptyList();
        }

        return new PreviewResult(
                meta.pattern(),
                examples,
                warnings,
                safeContext,
                resolvedSequenceScope,
                resolvedPeriodKey
        );
    }

    private List<String> extractPreviewWarnings(IllegalArgumentException ex,
                                                String pattern,
                                                Map<String, String> context) {
        String message = ex.getMessage();
        if (message != null && message.startsWith("missing context variable for code rule: ")) {
            String variableKey = message.substring("missing context variable for code rule: ".length()).trim();
            return List.of("MISSING_CONTEXT_VARIABLE:" + variableKey);
        }
        if (message != null && message.startsWith("rule context is incomplete for pattern: ")) {
            List<String> warnings = new ArrayList<>();
            for (String placeholder : unresolvedPlaceholders(pattern, context)) {
                warnings.add("MISSING_CONTEXT_VARIABLE:" + placeholder);
            }
            if (!warnings.isEmpty()) {
                return warnings;
            }
        }
        return List.of("PREVIEW_RENDER_FAILED:" + (message == null ? "UNKNOWN" : message));
    }

    private List<String> unresolvedPlaceholders(String pattern, Map<String, String> context) {
        String rendered = pattern;
        for (Map.Entry<String, String> entry : context.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            rendered = rendered.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(rendered);
        List<String> placeholders = new ArrayList<>();
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            if (!"SEQ".equals(placeholder) && !"DATE".equals(placeholder)) {
                placeholders.add(placeholder);
            }
        }
        return placeholders;
    }

    private RuleMeta loadRuleMeta(String ruleCode) {
        String normalizedRuleCode = normalizeRuleCode(ruleCode);
        RuleMeta meta = jdbcTemplate.query(
                """
                SELECT r.code, r.pattern, r.inherit_prefix, r.parent_rule_id, v.rule_json
                FROM plm_meta.meta_code_rule r
                LEFT JOIN plm_meta.meta_code_rule_version v
                  ON v.code_rule_id = r.id
                 AND v.is_latest = TRUE
                WHERE r.code = ?
                """,
                rs -> rs.next()
                        ? new RuleMeta(
                                rs.getString("code"),
                                rs.getString("pattern"),
                                rs.getBoolean("inherit_prefix"),
                                rs.getObject("parent_rule_id", UUID.class),
                                rs.getString("rule_json"))
                        : null,
                normalizedRuleCode
        );
        if (meta == null) {
            throw new IllegalArgumentException("编码规则不存在: " + normalizedRuleCode);
        }
        return meta;
    }

    private StructuredRule resolveStructuredRule(RuleMeta meta, Map<String, String> context) {
        Map<String, Object> ruleJson = readRuleJson(meta.ruleJson());
        if (ruleJson.isEmpty()) {
            return null;
        }
        Map<String, Object> subRules = readObjectMap(ruleJson.get("subRules"));
        if (subRules.isEmpty()) {
            return null;
        }

        String explicitSubRuleKey = trimToNull(context.get("SUB_RULE_KEY"));
        if (explicitSubRuleKey != null) {
            Map<String, Object> explicitRule = readObjectMap(subRules.get(explicitSubRuleKey));
            if (!explicitRule.isEmpty()) {
                return new StructuredRule(ruleJson, explicitSubRuleKey, explicitRule);
            }
        }

        for (String candidate : defaultSubRuleCandidates(meta.code())) {
            Map<String, Object> selected = readObjectMap(subRules.get(candidate));
            if (!selected.isEmpty()) {
                return new StructuredRule(ruleJson, candidate, selected);
            }
        }
        if (subRules.size() == 1) {
            Map.Entry<String, Object> firstEntry = subRules.entrySet().iterator().next();
            Map<String, Object> selected = readObjectMap(firstEntry.getValue());
            if (!selected.isEmpty()) {
                return new StructuredRule(ruleJson, firstEntry.getKey(), selected);
            }
        }
        return null;
    }

    private List<String> defaultSubRuleCandidates(String ruleCode) {
        String normalizedRuleCode = normalizeRuleCode(ruleCode);
        if ("CATEGORY".equals(normalizedRuleCode)) {
            return List.of("category");
        }
        if ("ATTRIBUTE".equals(normalizedRuleCode)) {
            return List.of("attribute");
        }
        if ("LOV".equals(normalizedRuleCode)) {
            return List.of("enum", "lov");
        }
        return List.of(DEFAULT_SUB_RULE_KEY.toLowerCase(Locale.ROOT));
    }

    private RenderOutcome renderStructured(RuleMeta meta,
                                           StructuredRule structuredRule,
                                           Map<String, String> context,
                                           int sequenceOffset,
                                           boolean allocateSequence) {
        Map<String, Object> subRule = structuredRule.definition();
        String separator = trimToNull(readString(subRule, "separator"));
        String effectiveSeparator = separator == null ? "-" : separator;
        String hierarchyMode = normalizeHierarchyMode(readString(structuredRule.rootJson(), "hierarchyMode"));
        List<Map<String, Object>> childSegments = readObjectList(subRule.get("childSegments"));
        boolean childMode = "category".equalsIgnoreCase(structuredRule.subRuleKey())
                && "APPEND_CHILD_SUFFIX".equals(hierarchyMode)
                && !childSegments.isEmpty()
                && trimToNull(context.get("PARENT_CODE")) != null;

        SequenceMetaHolder sequenceMetaHolder = new SequenceMetaHolder();
        List<String> parts = new ArrayList<>();
        if (childMode) {
            parts.add(requireContext(context, "PARENT_CODE"));
            String suffix = renderSegments(
                    meta.code(),
                    structuredRule.subRuleKey(),
                    childSegments,
                    effectiveSeparator,
                    context,
                    sequenceOffset,
                    allocateSequence,
                    sequenceMetaHolder
            );
            if (trimToNull(suffix) != null) {
                parts.add(suffix);
            }
        } else {
            String rendered = renderSegments(
                    meta.code(),
                    structuredRule.subRuleKey(),
                    readObjectList(subRule.get("segments")),
                    effectiveSeparator,
                    context,
                    sequenceOffset,
                    allocateSequence,
                    sequenceMetaHolder
            );
            if (trimToNull(rendered) != null) {
                parts.add(rendered);
            }
        }

        String result = String.join(effectiveSeparator, parts);
        if (meta.inheritPrefix() && meta.parentRuleId() != null && !containsAnyParentPlaceholder(result)) {
            String parentCode = firstNonNull(context.get("PARENT_CODE"), context.get("CATEGORY_CODE"), context.get("ATTRIBUTE_CODE"));
            if (parentCode != null && !result.startsWith(parentCode)) {
                result = parentCode + (result.startsWith(effectiveSeparator) ? "" : effectiveSeparator) + result;
            }
        }

        return new RenderOutcome(
                result,
                sequenceMetaHolder.scopeDescriptor,
                sequenceMetaHolder.periodKey
        );
    }

    private String renderSegments(String ruleCode,
                                  String subRuleKey,
                                  List<Map<String, Object>> segments,
                                  String separator,
                                  Map<String, String> context,
                                  int sequenceOffset,
                                  boolean allocateSequence,
                                  SequenceMetaHolder sequenceMetaHolder) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("structured rule segments must not be empty");
        }
        List<String> renderedParts = new ArrayList<>();
        for (int index = 0; index < segments.size(); index++) {
            Map<String, Object> segment = segments.get(index);
            String value = renderSegment(
                    ruleCode,
                    subRuleKey,
                    index,
                    segment,
                    context,
                    sequenceOffset,
                    allocateSequence,
                    sequenceMetaHolder
            );
            if (trimToNull(value) != null) {
                renderedParts.add(value);
            }
        }
        return String.join(separator, renderedParts);
    }

    private String renderSegment(String ruleCode,
                                 String subRuleKey,
                                 int segmentIndex,
                                 Map<String, Object> segment,
                                 Map<String, String> context,
                                 int sequenceOffset,
                                 boolean allocateSequence,
                                 SequenceMetaHolder sequenceMetaHolder) {
        String type = normalizeSegmentType(readString(segment, "type"));
        return switch (type) {
            case "STRING" -> renderStringSegment(segment);
            case "VARIABLE" -> renderVariableSegment(segment, context);
            case "DATE" -> renderDateSegment(segment);
            case "SEQUENCE" -> renderSequenceSegment(
                    ruleCode,
                    subRuleKey,
                    segmentIndex,
                    segment,
                    context,
                    sequenceOffset,
                    allocateSequence,
                    sequenceMetaHolder
            );
            default -> throw new IllegalArgumentException("unsupported segment type: " + type);
        };
    }

    private String renderStringSegment(Map<String, Object> segment) {
        return requireText(readString(segment, "value"), "segment.value is required for STRING");
    }

    private String renderVariableSegment(Map<String, Object> segment, Map<String, String> context) {
        String variableKey = requireText(readString(segment, "variableKey"), "segment.variableKey is required for VARIABLE");
        return requireContext(context, variableKey);
    }

    private String renderDateSegment(Map<String, Object> segment) {
        String format = trimToNull(readString(segment, "format"));
        String effectiveFormat = format == null ? "yyyyMMdd" : format;
        try {
            return LocalDate.now().format(DateTimeFormatter.ofPattern(effectiveFormat));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid date format in code rule segment: format=" + effectiveFormat, ex);
        }
    }

    private String renderSequenceSegment(String ruleCode,
                                         String subRuleKey,
                                         int segmentIndex,
                                         Map<String, Object> segment,
                                         Map<String, String> context,
                                         int sequenceOffset,
                                         boolean allocateSequence,
                                         SequenceMetaHolder sequenceMetaHolder) {
        int length = readInt(segment, "length", sequenceWidth(ruleCode));
        long startValue = readLong(segment, "startValue", 1L);
        long step = readLong(segment, "step", 1L);
        String resetRule = normalizeResetRule(readString(segment, "resetRule"));
        String scopeKey = normalizeScopeKey(readString(segment, "scopeKey"), resetRule);
        String scopeValue = resolveScopeValue(scopeKey, context);
        String periodKey = resolvePeriodKey(resetRule, LocalDate.now());
        String effectiveSubRuleKey = buildSequenceSubRuleKey(subRuleKey, segmentIndex);

        if (sequenceMetaHolder.scopeDescriptor == null) {
            sequenceMetaHolder.scopeDescriptor = effectiveSubRuleKey + ":" + scopeKey + "=" + scopeValue;
            sequenceMetaHolder.periodKey = periodKey;
        }

        long sequenceValue = allocateSequence
                ? nextSequenceValue(ruleCode, effectiveSubRuleKey, scopeKey, scopeValue, resetRule, periodKey, startValue, step)
                : previewSequenceValue(ruleCode, effectiveSubRuleKey, scopeKey, scopeValue, periodKey, startValue, step, sequenceOffset);
        return String.format("%0" + length + "d", sequenceValue);
    }

    private RenderOutcome renderLegacy(RuleMeta meta,
                                       Map<String, String> context,
                                       int sequenceOffset,
                                       boolean allocateSequence) {
        String pattern = meta.pattern();
        String result = pattern.contains("{DATE}")
                ? pattern.replace("{DATE}", LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE))
                : pattern;
        String resolvedSequenceScope = null;
        String resolvedPeriodKey = null;
        if (result.contains("{SEQ}")) {
            String subRuleKey = DEFAULT_SUB_RULE_KEY;
            String scopeKey = GLOBAL_SCOPE_KEY;
            String scopeValue = GLOBAL_SCOPE_VALUE;
            String periodKey = DEFAULT_PERIOD_KEY;
            long sequenceValue = allocateSequence
                    ? nextSequenceValue(meta.code(), subRuleKey, scopeKey, scopeValue, RESET_NEVER, periodKey, 1L, 1L)
                    : previewSequenceValue(meta.code(), subRuleKey, scopeKey, scopeValue, periodKey, 1L, 1L, sequenceOffset);
            result = result.replace("{SEQ}", String.format("%0" + sequenceWidth(meta.code()) + "d", sequenceValue));
            resolvedSequenceScope = subRuleKey + ":" + scopeKey + "=" + scopeValue;
            resolvedPeriodKey = periodKey;
        }
        for (Map.Entry<String, String> entry : context.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        if (result.contains("{")) {
            throw new IllegalArgumentException("rule context is incomplete for pattern: " + pattern);
        }
        if (meta.inheritPrefix() && meta.parentRuleId() != null && !containsAnyParentPlaceholder(pattern)) {
            String parentCode = firstNonNull(context.get("PARENT_CODE"), context.get("CATEGORY_CODE"), context.get("ATTRIBUTE_CODE"));
            if (parentCode != null && !result.startsWith(parentCode)) {
                result = parentCode + (result.startsWith("-") ? "" : "-") + result;
            }
        }
        return new RenderOutcome(result, resolvedSequenceScope, resolvedPeriodKey);
    }

    private long nextSequenceValue(String ruleCode,
                                   String subRuleKey,
                                   String scopeKey,
                                   String scopeValue,
                                   String resetRule,
                                   String periodKey,
                                   long startValue,
                                   long step) {
        ensureSequenceRow(ruleCode, subRuleKey, scopeKey, scopeValue, resetRule, periodKey, startValue, step);
        List<Long> rows = jdbcTemplate.query(
                """
                SELECT current_value
                FROM plm_meta.meta_code_sequence
                WHERE rule_code = ?
                  AND sub_rule_key = ?
                  AND scope_key = ?
                  AND scope_value = ?
                  AND period_key = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> rs.getLong(1),
                ruleCode,
                subRuleKey,
                scopeKey,
                scopeValue,
                periodKey
        );
        long currentValue = rows.isEmpty() ? startValue - step : rows.get(0);
        long nextValue = currentValue + step;
        jdbcTemplate.update(
                """
                UPDATE plm_meta.meta_code_sequence
                SET current_value = ?,
                    reset_rule = ?
                WHERE rule_code = ?
                  AND sub_rule_key = ?
                  AND scope_key = ?
                  AND scope_value = ?
                  AND period_key = ?
                """,
                nextValue,
                resetRule,
                ruleCode,
                subRuleKey,
                scopeKey,
                scopeValue,
                periodKey
        );
        return nextValue;
    }

    private long previewSequenceValue(String ruleCode,
                                      String subRuleKey,
                                      String scopeKey,
                                      String scopeValue,
                                      String periodKey,
                                      long startValue,
                                      long step,
                                      int previewOffset) {
        long currentValue = readCurrentSequenceValue(ruleCode, subRuleKey, scopeKey, scopeValue, periodKey, startValue, step);
        return currentValue + (step * previewOffset);
    }

    private long readCurrentSequenceValue(String ruleCode,
                                          String subRuleKey,
                                          String scopeKey,
                                          String scopeValue,
                                          String periodKey,
                                          long startValue,
                                          long step) {
        List<Long> rows = jdbcTemplate.query(
                """
                SELECT current_value
                FROM plm_meta.meta_code_sequence
                WHERE rule_code = ?
                  AND sub_rule_key = ?
                  AND scope_key = ?
                  AND scope_value = ?
                  AND period_key = ?
                """,
                (rs, rowNum) -> rs.getLong(1),
                ruleCode,
                subRuleKey,
                scopeKey,
                scopeValue,
                periodKey
        );
        return rows.isEmpty() ? startValue - step : rows.get(0);
    }

    private void ensureSequenceRow(String ruleCode,
                                   String subRuleKey,
                                   String scopeKey,
                                   String scopeValue,
                                   String resetRule,
                                   String periodKey,
                                   long startValue,
                                   long step) {
        jdbcTemplate.update(
                """
                INSERT INTO plm_meta.meta_code_sequence(
                    rule_code,
                    sub_rule_key,
                    scope_key,
                    scope_value,
                    reset_rule,
                    period_key,
                    current_value
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                ruleCode,
                subRuleKey,
                scopeKey,
                scopeValue,
                resetRule,
                periodKey,
                startValue - step
        );
    }

    private String buildSequenceSubRuleKey(String subRuleKey, int segmentIndex) {
        String normalizedSubRuleKey = trimToNull(subRuleKey);
        String effectiveSubRuleKey = normalizedSubRuleKey == null ? DEFAULT_SUB_RULE_KEY : normalizedSubRuleKey.toUpperCase(Locale.ROOT);
        return effectiveSubRuleKey + "#" + segmentIndex;
    }

    private String normalizeHierarchyMode(String hierarchyMode) {
        String normalized = trimToNull(hierarchyMode);
        return normalized == null ? "NONE" : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeResetRule(String resetRule) {
        String normalized = trimToNull(resetRule);
        if (normalized == null) {
            return RESET_NEVER;
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeScopeKey(String scopeKey, String resetRule) {
        String normalizedScopeKey = trimToNull(scopeKey);
        if (normalizedScopeKey != null) {
            return normalizedScopeKey.toUpperCase(Locale.ROOT);
        }
        if (RESET_PER_PARENT.equals(resetRule)) {
            return "PARENT_CODE";
        }
        return GLOBAL_SCOPE_KEY;
    }

    private String resolveScopeValue(String scopeKey, Map<String, String> context) {
        if (GLOBAL_SCOPE_KEY.equals(scopeKey)) {
            return GLOBAL_SCOPE_VALUE;
        }
        return requireContext(context, scopeKey);
    }

    private String resolvePeriodKey(String resetRule, LocalDate date) {
        return switch (resetRule) {
            case RESET_DAILY -> date.format(PERIOD_DAILY_FORMATTER);
            case RESET_MONTHLY -> date.format(PERIOD_MONTHLY_FORMATTER);
            case RESET_YEARLY -> date.format(PERIOD_YEARLY_FORMATTER);
            case RESET_NEVER, RESET_PER_PARENT -> DEFAULT_PERIOD_KEY;
            default -> throw new IllegalArgumentException("unsupported resetRule: " + resetRule);
        };
    }

    private boolean structuredRuleHasSequence(StructuredRule structuredRule) {
        return segmentsContainSequence(readObjectList(structuredRule.definition().get("segments")))
                || segmentsContainSequence(readObjectList(structuredRule.definition().get("childSegments")));
    }

    private boolean segmentsContainSequence(List<Map<String, Object>> segments) {
        for (Map<String, Object> segment : segments) {
            if ("SEQUENCE".equals(normalizeSegmentType(readString(segment, "type")))) {
                return true;
            }
        }
        return false;
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

    private String normalizeSegmentType(String segmentType) {
        String normalized = trimToNull(segmentType);
        if (normalized == null) {
            throw new IllegalArgumentException("segment.type is required");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String requireContext(Map<String, String> context, String key) {
        String normalizedKey = requireText(key, "context key is required");
        String value = trimToNull(context.get(normalizedKey));
        if (value == null) {
            throw new IllegalArgumentException("missing context variable for code rule: " + normalizedKey);
        }
        return value;
    }

    private String requireText(String value, String message) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String readString(Map<String, Object> json, String key) {
        Object value = json.get(key);
        return value == null ? null : String.valueOf(value);
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

    private int sequenceWidth(String ruleCode) {
        return CodeRuleSupport.sequenceWidth(ruleCode);
    }

    private Map<String, String> copyContext(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : context.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copied.put(entry.getKey(), entry.getValue());
            }
        }
        return copied;
    }

    private String normalizeRuleCode(String ruleCode) {
        String normalized = trimToNull(ruleCode);
        if (normalized == null) {
            throw new IllegalArgumentException("ruleCode is required");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean containsAnyParentPlaceholder(String pattern) {
        return pattern.contains("{PARENT_CODE}")
                || pattern.contains("{CATEGORY_CODE}")
                || pattern.contains("{ATTRIBUTE_CODE}");
    }

    private String firstNonNull(String... arr) {
        for (String s : arr) {
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    private record RuleMeta(String code, String pattern, boolean inheritPrefix, UUID parentRuleId, String ruleJson) {
    }

    private record StructuredRule(Map<String, Object> rootJson, String subRuleKey, Map<String, Object> definition) {
    }

    private record RenderOutcome(String code, String resolvedSequenceScope, String resolvedPeriodKey) {
    }

    private static final class SequenceMetaHolder {
        private String scopeDescriptor;
        private String periodKey;
    }

    public record PreviewResult(String pattern,
                                List<String> examples,
                                List<String> warnings,
                                Map<String, String> resolvedContext,
                                String resolvedSequenceScope,
                                String resolvedPeriodKey) {
    }
}
