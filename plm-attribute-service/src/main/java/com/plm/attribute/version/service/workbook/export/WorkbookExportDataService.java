package com.plm.attribute.version.service.workbook.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.attribute.version.service.MetaCategoryGenericQueryService;
import com.plm.common.api.dto.category.subtree.MetaCategorySubtreeFlatNodeDto;
import com.plm.common.api.dto.category.subtree.MetaCategorySubtreeRequestDto;
import com.plm.common.api.dto.category.subtree.MetaCategorySubtreeResponseDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportStartRequestDto;
import com.plm.common.version.domain.MetaAttributeDef;
import com.plm.common.version.domain.MetaAttributeVersion;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.common.version.domain.MetaCategoryVersion;
import com.plm.common.version.domain.MetaLovDef;
import com.plm.common.version.domain.MetaLovVersion;
import com.plm.infrastructure.version.repository.CategoryHierarchyRepository;
import com.plm.infrastructure.version.repository.MetaAttributeDefRepository;
import com.plm.infrastructure.version.repository.MetaAttributeVersionRepository;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import com.plm.infrastructure.version.repository.MetaCategoryVersionRepository;
import com.plm.infrastructure.version.repository.MetaLovDefRepository;
import com.plm.infrastructure.version.repository.MetaLovVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class WorkbookExportDataService {

    private final MetaCategoryGenericQueryService categoryGenericQueryService;
    private final CategoryHierarchyRepository categoryHierarchyRepository;
    private final MetaCategoryDefRepository categoryDefRepository;
    private final MetaCategoryVersionRepository categoryVersionRepository;
    private final MetaAttributeDefRepository attributeDefRepository;
    private final MetaAttributeVersionRepository attributeVersionRepository;
    private final MetaLovDefRepository lovDefRepository;
    private final MetaLovVersionRepository lovVersionRepository;
    private final ObjectMapper objectMapper;

    public WorkbookExportDataService(MetaCategoryGenericQueryService categoryGenericQueryService,
                                     CategoryHierarchyRepository categoryHierarchyRepository,
                                     MetaCategoryDefRepository categoryDefRepository,
                                     MetaCategoryVersionRepository categoryVersionRepository,
                                     MetaAttributeDefRepository attributeDefRepository,
                                     MetaAttributeVersionRepository attributeVersionRepository,
                                     MetaLovDefRepository lovDefRepository,
                                     MetaLovVersionRepository lovVersionRepository,
                                     ObjectMapper objectMapper) {
        this.categoryGenericQueryService = Objects.requireNonNull(categoryGenericQueryService, "categoryGenericQueryService");
                        this.categoryHierarchyRepository = Objects.requireNonNull(categoryHierarchyRepository, "categoryHierarchyRepository");
        this.categoryDefRepository = Objects.requireNonNull(categoryDefRepository, "categoryDefRepository");
        this.categoryVersionRepository = Objects.requireNonNull(categoryVersionRepository, "categoryVersionRepository");
        this.attributeDefRepository = Objects.requireNonNull(attributeDefRepository, "attributeDefRepository");
        this.attributeVersionRepository = Objects.requireNonNull(attributeVersionRepository, "attributeVersionRepository");
        this.lovDefRepository = Objects.requireNonNull(lovDefRepository, "lovDefRepository");
        this.lovVersionRepository = Objects.requireNonNull(lovVersionRepository, "lovVersionRepository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public List<UUID> resolveScopeCategoryIds(WorkbookExportStartRequestDto request) {
        LinkedHashSet<UUID> resolved = new LinkedHashSet<>();
        List<UUID> requestedIds = request.getScope().getCategoryIds();
        boolean includeChildren = Boolean.TRUE.equals(request.getScope().getIncludeChildren());
        for (UUID categoryId : requestedIds) {
            if (categoryId == null) {
                continue;
            }
            if (!includeChildren) {
                resolved.add(categoryId);
                continue;
            }
            resolved.addAll(categoryHierarchyRepository.findDescendantIdsIncludingSelf(categoryId));
        }
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("no categories resolved for export scope");
        }
        return new ArrayList<>(resolved);
    }

    public ExportEstimate estimateRows(String businessDomain,
                                       Collection<UUID> categoryIds) {
        long categoryRows = categoryIds.isEmpty() ? 0L : categoryDefRepository.countActiveByBusinessDomainAndIdIn(businessDomain, categoryIds);
        long attributeRows = categoryIds.isEmpty() ? 0L : attributeDefRepository.countActiveByBusinessDomainAndCategoryDefIdIn(businessDomain, categoryIds);
        long enumOptionRows = categoryIds.isEmpty() ? 0L : lovVersionRepository.countActiveLatestOptionRowsByBusinessDomainAndCategoryDefIds(businessDomain, categoryIds);
        return new ExportEstimate(toIntExact(categoryRows, "categoryRows"), toIntExact(attributeRows, "attributeRows"), toIntExact(enumOptionRows, "enumOptionRows"));
    }

    public WorkbookExportSupport.ExportDataBundle loadData(String businessDomain,
                                                           Collection<UUID> categoryIds) {
        List<MetaCategoryDef> categoryDefs = loadCategoryDefs(businessDomain, categoryIds);
        List<java.util.Map<String, Object>> categoryRows = buildCategoryRows(categoryDefs);
        AttributeLoadResult attributes = buildAttributeRows(categoryDefs, categoryRows);
        return new WorkbookExportSupport.ExportDataBundle(categoryRows, attributes.attributeRows(), attributes.enumRows());
    }

    private List<MetaCategoryDef> loadCategoryDefs(String businessDomain,
                                                   Collection<UUID> categoryIds) {
        List<MetaCategoryDef> defs = categoryDefRepository.findAllById(categoryIds).stream()
                .filter(def -> equalsIgnoreCase(def.getBusinessDomain(), businessDomain))
                .filter(this::isActive)
                .sorted(Comparator.comparing((MetaCategoryDef def) -> defaultString(def.getPath()))
                        .thenComparing(MetaCategoryDef::getCodeKey, Comparator.nullsLast(String::compareTo)))
                .toList();
        if (defs.isEmpty()) {
            throw new IllegalArgumentException("no active categories found for workbook export scope");
        }
        return defs;
    }

    private List<java.util.Map<String, Object>> buildCategoryRows(List<MetaCategoryDef> defs) {
        Map<UUID, MetaCategoryVersion> latestVersionById = latestCategoryVersionByDefId(defs);
        List<java.util.Map<String, Object>> rows = new ArrayList<>();
        for (MetaCategoryDef def : defs) {
            MetaCategoryVersion latest = latestVersionById.get(def.getId());
            MetaCategoryDef parent = def.getParent();
            MetaCategoryDef root = resolveRoot(def);
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("categoryId", def.getId());
            row.put("businessDomain", def.getBusinessDomain());
            row.put("categoryCode", def.getCodeKey());
            row.put("categoryName", latest == null ? def.getCodeKey() : latest.getDisplayName());
            row.put("status", upper(def.getStatus()));
            row.put("parentId", parent == null ? null : parent.getId());
            row.put("parentCode", parent == null ? null : parent.getCodeKey());
            row.put("parentName", parent == null ? null : latestCategoryName(parent));
            row.put("rootId", root == null ? null : root.getId());
            row.put("rootCode", root == null ? null : root.getCodeKey());
            row.put("rootName", root == null ? null : latestCategoryName(root));
            row.put("path", def.getPath());
            row.put("fullPathName", def.getFullPathName());
            row.put("level", resolveLevel(def));
            row.put("depth", def.getDepth() == null ? null : def.getDepth().intValue());
            row.put("sortOrder", def.getSortOrder());
            row.put("isLeaf", def.getIsLeaf());
            row.put("hasChildren", def.getIsLeaf() == null ? null : !def.getIsLeaf());
            row.put("externalCode", def.getExternalCode());
            row.put("codeKeyManualOverride", def.getCodeKeyManualOverride());
            row.put("codeKeyFrozen", def.getCodeKeyFrozen());
            row.put("generatedRuleCode", def.getGeneratedRuleCode());
            row.put("generatedRuleVersionNo", def.getGeneratedRuleVersionNo());
            row.put("copiedFromCategoryId", def.getCopiedFromCategoryId());
            row.put("latestVersionNo", latest == null ? null : latest.getVersionNo());
            row.put("latestVersionDate", latest == null ? null : latest.getCreatedAt());
            row.put("latestVersionUpdatedBy", latest == null ? null : latest.getCreatedBy());
            row.put("latestVersionDescription", latest == null ? null : readTextFromJson(latest.getStructureJson(), "description"));
            row.put("createdAt", def.getCreatedAt());
            row.put("createdBy", def.getCreatedBy());
            row.put("modifiedAt", latest == null ? null : latest.getCreatedAt());
            row.put("modifiedBy", latest == null ? null : latest.getCreatedBy());
            rows.add(row);
        }
        return rows;
    }

    private AttributeLoadResult buildAttributeRows(List<MetaCategoryDef> categoryDefs,
                                                   List<java.util.Map<String, Object>> categoryRows) {
        Map<UUID, java.util.Map<String, Object>> categoryRowById = new HashMap<>();
        for (java.util.Map<String, Object> row : categoryRows) {
            Object categoryId = row.get("categoryId");
            if (categoryId instanceof UUID uuid) {
                categoryRowById.put(uuid, row);
            }
        }

        List<MetaAttributeDef> attributeDefs = attributeDefRepository.findByCategoryDefIdIn(categoryDefs.stream().map(MetaCategoryDef::getId).toList()).stream()
                .filter(this::isActive)
                .sorted(Comparator.comparing((MetaAttributeDef def) -> defaultString(def.getCategoryDef() == null ? null : def.getCategoryDef().getCodeKey()))
                        .thenComparing(MetaAttributeDef::getKey, Comparator.nullsLast(String::compareTo)))
                .toList();
        Map<UUID, MetaAttributeVersion> latestAttributeVersionByDefId = latestAttributeVersionByDefId(attributeDefs);
        List<MetaLovDef> lovDefs = lovDefRepository.findByAttributeDefIn(attributeDefs).stream().filter(this::isActive).toList();
        Map<UUID, List<MetaLovDef>> lovDefsByAttributeId = new HashMap<>();
        for (MetaLovDef lovDef : lovDefs) {
            if (lovDef.getAttributeDef() == null || lovDef.getAttributeDef().getId() == null) {
                continue;
            }
            lovDefsByAttributeId.computeIfAbsent(lovDef.getAttributeDef().getId(), ignored -> new ArrayList<>()).add(lovDef);
        }
        Map<UUID, MetaLovVersion> latestLovVersionByDefId = latestLovVersionByDefId(lovDefs);

        List<java.util.Map<String, Object>> attributeRows = new ArrayList<>();
        List<java.util.Map<String, Object>> enumRows = new ArrayList<>();
        for (MetaAttributeDef def : attributeDefs) {
            MetaAttributeVersion latest = latestAttributeVersionByDefId.get(def.getId());
            ParsedAttributeStructure structure = parseAttributeStructure(latest == null ? null : latest.getStructureJson());
            java.util.Map<String, Object> categoryRow = categoryRowById.get(def.getCategoryDef().getId());

            LinkedHashMap<String, Object> attributeRow = new LinkedHashMap<>();
            attributeRow.put("attributeId", def.getId());
            attributeRow.put("businessDomain", def.getBusinessDomain());
            attributeRow.put("categoryId", def.getCategoryDef().getId());
            attributeRow.put("categoryCode", categoryRow == null ? def.getCategoryDef().getCodeKey() : categoryRow.get("categoryCode"));
            attributeRow.put("categoryName", categoryRow == null ? null : categoryRow.get("categoryName"));
            attributeRow.put("attributeKey", def.getKey());
            attributeRow.put("status", upper(def.getStatus()));
            attributeRow.put("hasLov", def.getLovFlag());
            attributeRow.put("autoBindKey", def.getAutoBindKey());
            attributeRow.put("keyManualOverride", def.getKeyManualOverride());
            attributeRow.put("keyFrozen", def.getKeyFrozen());
            attributeRow.put("generatedRuleCode", def.getGeneratedRuleCode());
            attributeRow.put("generatedRuleVersionNo", def.getGeneratedRuleVersionNo());
            attributeRow.put("latestVersionId", latest == null ? null : latest.getId());
            attributeRow.put("latestVersionNo", latest == null ? null : latest.getVersionNo());
            attributeRow.put("categoryVersionId", latest == null || latest.getCategoryVersion() == null ? null : latest.getCategoryVersion().getId());
            attributeRow.put("resolvedCodePrefix", latest == null ? null : latest.getResolvedCodePrefix());
            attributeRow.put("structureHash", latest == null ? null : latest.getHash());
            attributeRow.put("displayName", structure.displayName);
            attributeRow.put("description", structure.description);
            attributeRow.put("attributeField", structure.attributeField);
            attributeRow.put("dataType", structure.dataType);
            attributeRow.put("unit", structure.unit);
            attributeRow.put("defaultValue", structure.defaultValue);
            attributeRow.put("required", structure.required);
            attributeRow.put("unique", structure.unique);
            attributeRow.put("hidden", structure.hidden);
            attributeRow.put("readOnly", structure.readOnly);
            attributeRow.put("searchable", structure.searchable);
            attributeRow.put("lovKey", structure.lovKey);
            attributeRow.put("minValue", structure.minValue);
            attributeRow.put("maxValue", structure.maxValue);
            attributeRow.put("step", structure.step);
            attributeRow.put("precision", structure.precision);
            attributeRow.put("trueLabel", structure.trueLabel);
            attributeRow.put("falseLabel", structure.falseLabel);
            attributeRow.put("createdAt", def.getCreatedAt());
            attributeRow.put("createdBy", def.getCreatedBy());
            attributeRow.put("modifiedAt", latest == null ? null : latest.getCreatedAt());
            attributeRow.put("modifiedBy", latest == null ? null : latest.getCreatedBy());
            attributeRows.add(attributeRow);

            List<MetaLovDef> attributeLovDefs = lovDefsByAttributeId.getOrDefault(def.getId(), List.of());
            for (MetaLovDef lovDef : attributeLovDefs) {
                MetaLovVersion lovLatest = latestLovVersionByDefId.get(lovDef.getId());
                if (lovLatest == null) {
                    continue;
                }
                List<ParsedLovValue> values = parseLovValues(lovLatest.getValueJson());
                values.sort(Comparator.comparing(ParsedLovValue::order, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(ParsedLovValue::code, Comparator.nullsLast(String::compareTo)));
                for (ParsedLovValue value : values) {
                    LinkedHashMap<String, Object> enumRow = new LinkedHashMap<>();
                    enumRow.put("businessDomain", lovDef.getBusinessDomain());
                    enumRow.put("categoryId", def.getCategoryDef().getId());
                    enumRow.put("categoryCode", categoryRow == null ? def.getCategoryDef().getCodeKey() : categoryRow.get("categoryCode"));
                    enumRow.put("categoryName", categoryRow == null ? null : categoryRow.get("categoryName"));
                    enumRow.put("attributeId", def.getId());
                    enumRow.put("attributeKey", def.getKey());
                    enumRow.put("attributeDisplayName", structure.displayName);
                    enumRow.put("attributeField", structure.attributeField);
                    enumRow.put("attributeDataType", structure.dataType);
                    enumRow.put("lovDefId", lovDef.getId());
                    enumRow.put("lovKey", lovDef.getKey());
                    enumRow.put("lovStatus", upper(lovDef.getStatus()));
                    enumRow.put("lovDescription", lovDef.getDescription());
                    enumRow.put("sourceAttributeKey", lovDef.getSourceAttributeKey());
                    enumRow.put("lovKeyManualOverride", lovDef.getKeyManualOverride());
                    enumRow.put("lovKeyFrozen", lovDef.getKeyFrozen());
                    enumRow.put("lovGeneratedRuleCode", lovDef.getGeneratedRuleCode());
                    enumRow.put("lovGeneratedRuleVersionNo", lovDef.getGeneratedRuleVersionNo());
                    enumRow.put("lovCreatedAt", lovDef.getCreatedAt());
                    enumRow.put("lovCreatedBy", lovDef.getCreatedBy());
                    enumRow.put("lovVersionId", lovLatest.getId());
                    enumRow.put("lovVersionNo", lovLatest.getVersionNo());
                    enumRow.put("lovResolvedCodePrefix", lovLatest.getResolvedCodePrefix());
                    enumRow.put("lovHash", lovLatest.getHash());
                    enumRow.put("lovVersionCreatedAt", lovLatest.getCreatedAt());
                    enumRow.put("lovVersionCreatedBy", lovLatest.getCreatedBy());
                    enumRow.put("optionCode", value.code());
                    enumRow.put("optionName", value.name());
                    enumRow.put("optionLabel", value.label());
                    enumRow.put("optionOrder", value.order());
                    enumRow.put("optionDisabled", value.disabled());
                    enumRows.add(enumRow);
                }
            }
        }

        return new AttributeLoadResult(attributeRows, enumRows);
    }

    private Map<UUID, MetaCategoryVersion> latestCategoryVersionByDefId(List<MetaCategoryDef> defs) {
        Map<UUID, MetaCategoryVersion> map = new HashMap<>();
        for (MetaCategoryVersion version : categoryVersionRepository.findByCategoryDefInAndIsLatestTrue(defs)) {
            if (version.getCategoryDef() != null && version.getCategoryDef().getId() != null) {
                map.put(version.getCategoryDef().getId(), version);
            }
        }
        return map;
    }

    private Map<UUID, MetaAttributeVersion> latestAttributeVersionByDefId(List<MetaAttributeDef> defs) {
        Map<UUID, MetaAttributeVersion> map = new HashMap<>();
        for (MetaAttributeVersion version : attributeVersionRepository.findByAttributeDefInAndIsLatestTrue(defs)) {
            if (version.getAttributeDef() != null && version.getAttributeDef().getId() != null) {
                map.put(version.getAttributeDef().getId(), version);
            }
        }
        return map;
    }

    private Map<UUID, MetaLovVersion> latestLovVersionByDefId(List<MetaLovDef> defs) {
        Map<UUID, MetaLovVersion> map = new HashMap<>();
        if (defs.isEmpty()) {
            return map;
        }
        for (MetaLovVersion version : lovVersionRepository.findByLovDefInAndIsLatestTrue(defs)) {
            if (version.getLovDef() != null && version.getLovDef().getId() != null) {
                map.put(version.getLovDef().getId(), version);
            }
        }
        return map;
    }

    private MetaCategoryDef resolveRoot(MetaCategoryDef def) {
        MetaCategoryDef current = def;
        while (current != null && current.getParent() != null) {
            current = current.getParent();
        }
        return current;
    }

    private String latestCategoryName(MetaCategoryDef def) {
        return categoryVersionRepository.findLatestByDef(def)
                .map(MetaCategoryVersion::getDisplayName)
                .orElse(def.getCodeKey());
    }

    private Integer resolveLevel(MetaCategoryDef def) {
        if (def.getDepth() != null) {
            return def.getDepth().intValue() + 1;
        }
        if (def.getPath() == null || def.getPath().isBlank()) {
            return null;
        }
        return (int) java.util.Arrays.stream(def.getPath().split("/")).filter(item -> !item.isBlank()).count();
    }

    private ParsedAttributeStructure parseAttributeStructure(String json) {
        ParsedAttributeStructure parsed = new ParsedAttributeStructure();
        if (json == null || json.isBlank()) {
            return parsed;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            parsed.displayName = text(node, "displayName");
            parsed.description = text(node, "description");
            parsed.attributeField = text(node, "attributeField");
            parsed.dataType = text(node, "dataType");
            parsed.unit = text(node, "unit");
            parsed.defaultValue = text(node, "defaultValue");
            parsed.required = bool(node, "required");
            parsed.unique = bool(node, "unique");
            parsed.hidden = bool(node, "hidden");
            parsed.readOnly = bool(node, "readOnly");
            parsed.searchable = bool(node, "searchable");
            parsed.lovKey = text(node, "lovKey");
            parsed.minValue = decimal(node, "minValue");
            parsed.maxValue = decimal(node, "maxValue");
            parsed.step = decimal(node, "step");
            parsed.precision = integer(node, "precision");
            parsed.trueLabel = text(node, "trueLabel");
            parsed.falseLabel = text(node, "falseLabel");
        } catch (IOException ignored) {
        }
        return parsed;
    }

    private List<ParsedLovValue> parseLovValues(String json) {
        List<ParsedLovValue> values = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return values;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode array = root.path("values");
            if (!array.isArray()) {
                return values;
            }
            for (JsonNode item : array) {
                String code = text(item, "code");
                String name = text(item, "name");
                if (name == null) {
                    name = text(item, "value");
                }
                String label = text(item, "label");
                Integer order = integer(item, "sort");
                if (order == null) {
                    order = integer(item, "order");
                }
                Boolean disabled = bool(item, "disabled");
                if (disabled == null && item.has("active") && !item.get("active").isNull()) {
                    disabled = !item.get("active").asBoolean();
                }
                values.add(new ParsedLovValue(code, name, label, order, disabled));
            }
        } catch (IOException ignored) {
        }
        return values;
    }

    private String readTextFromJson(String json, String field) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return text(objectMapper.readTree(json), field);
        } catch (IOException ignored) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        return node != null && node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private Boolean bool(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asBoolean();
    }

    private Integer integer(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asInt();
    }

    private BigDecimal decimal(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        try {
            return node.get(field).decimalValue();
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isActive(MetaCategoryDef def) {
        return def != null && !equalsIgnoreCase(def.getStatus(), "deleted");
    }

    private boolean isActive(MetaAttributeDef def) {
        return def != null && !equalsIgnoreCase(def.getStatus(), "deleted");
    }

    private boolean isActive(MetaLovDef def) {
        return def != null && !equalsIgnoreCase(def.getStatus(), "deleted");
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private int toIntExact(long value,
                           String fieldName) {
        if (value > Integer.MAX_VALUE) {
            throw new IllegalStateException("workbook export estimate overflow: field=" + fieldName + ", value=" + value);
        }
        return (int) value;
    }

    public record ExportEstimate(int categoryRows, int attributeRows, int enumOptionRows) {
    }

    private record ParsedLovValue(String code, String name, String label, Integer order, Boolean disabled) {
    }

    private static final class ParsedAttributeStructure {
        private String displayName;
        private String description;
        private String attributeField;
        private String dataType;
        private String unit;
        private String defaultValue;
        private Boolean required;
        private Boolean unique;
        private Boolean hidden;
        private Boolean readOnly;
        private Boolean searchable;
        private String lovKey;
        private BigDecimal minValue;
        private BigDecimal maxValue;
        private BigDecimal step;
        private Integer precision;
        private String trueLabel;
        private String falseLabel;
    }

    private record AttributeLoadResult(
            List<java.util.Map<String, Object>> attributeRows,
            List<java.util.Map<String, Object>> enumRows) {
    }
}