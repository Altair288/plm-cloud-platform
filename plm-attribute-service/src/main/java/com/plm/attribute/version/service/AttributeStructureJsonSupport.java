package com.plm.attribute.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.plm.common.version.util.AttributeLovImportUtils;

import java.math.BigDecimal;
import java.util.Locale;

public final class AttributeStructureJsonSupport {

    private AttributeStructureJsonSupport() {
    }

    public record AttributeStructureSpec(
            String displayName,
            String dataType,
            String description,
            String attributeField,
            String unit,
            String defaultValue,
            Boolean required,
            Boolean unique,
            Boolean hidden,
            Boolean readOnly,
            Boolean searchable,
            BigDecimal minValue,
            BigDecimal maxValue,
            BigDecimal step,
            Integer precision,
            String trueLabel,
            String falseLabel,
            String lovKey) {
    }

    public static String toJson(ObjectMapper objectMapper, AttributeStructureSpec spec) {
        ObjectNode node = objectMapper.createObjectNode();
        String displayName = requireTrimmed(spec.displayName(), "displayName");
        String dataType = requireTrimmed(spec.dataType(), "dataType");
        String normalizedDataType = normalizeDataType(dataType);

        node.put("displayName", displayName);
        node.put("dataType", dataType);

        putIfNotBlank(node, "description", spec.description());
        putIfNotBlank(node, "attributeField", spec.attributeField());
        putIfPresent(node, "required", spec.required());
        putIfPresent(node, "unique", spec.unique());
        putIfPresent(node, "hidden", spec.hidden());
        putIfPresent(node, "readOnly", spec.readOnly());
        putIfPresent(node, "searchable", spec.searchable());

        if (isNumberType(normalizedDataType)) {
            putIfNotBlank(node, "unit", spec.unit());
            putIfNotBlank(node, "defaultValue", spec.defaultValue());
            putIfPresent(node, "minValue", spec.minValue());
            putIfPresent(node, "maxValue", spec.maxValue());
            putIfPresent(node, "step", spec.step());
            putIfPresent(node, "precision", spec.precision());
        } else if (isBooleanType(normalizedDataType)) {
            String booleanDefault = normalizeBooleanDefaultValue(spec.defaultValue());
            if (booleanDefault != null) {
                node.put("defaultValue", booleanDefault);
            }
            putIfNotBlank(node, "trueLabel", spec.trueLabel());
            putIfNotBlank(node, "falseLabel", spec.falseLabel());
        } else {
            putIfNotBlank(node, "defaultValue", spec.defaultValue());
        }

        if (isEnumLike(normalizedDataType)) {
            putIfNotBlank(node, "lovKey", spec.lovKey());
        }

        return node.toString();
    }

    public static String toHash(ObjectMapper objectMapper, AttributeStructureSpec spec) {
        return AttributeLovImportUtils.jsonHash(toJson(objectMapper, spec));
    }

    public static boolean isEnumLike(String dataType) {
        String normalized = normalizeDataType(dataType);
        return "enum".equals(normalized) || "multi-enum".equals(normalized) || "multi_enum".equals(normalized);
    }

    public static boolean isNumberType(String dataType) {
        return "number".equals(normalizeDataType(dataType));
    }

    public static boolean isBooleanType(String dataType) {
        return "bool".equals(normalizeDataType(dataType));
    }

    public static String normalizeDataType(String dataType) {
        String normalized = trimToNull(dataType);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static String requireTrimmed(String value, String fieldName) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }

    private static String normalizeBooleanDefaultValue(String defaultValue) {
        String normalized = trimToNull(defaultValue);
        if (normalized == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(normalized)) {
            return "true";
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return "false";
        }
        return null;
    }

    private static void putIfNotBlank(ObjectNode node, String key, String value) {
        String normalized = trimToNull(value);
        if (normalized != null) {
            node.put(key, normalized);
        }
    }

    private static void putIfPresent(ObjectNode node, String key, Boolean value) {
        if (value != null) {
            node.put(key, value);
        }
    }

    private static void putIfPresent(ObjectNode node, String key, Integer value) {
        if (value != null) {
            node.put(key, value);
        }
    }

    private static void putIfPresent(ObjectNode node, String key, BigDecimal value) {
        if (value != null) {
            node.put(key, value);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}