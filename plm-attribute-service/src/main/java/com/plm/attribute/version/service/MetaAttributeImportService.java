package com.plm.attribute.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.plm.common.api.dto.attribute.imports.AttributeImportErrorDto;
import com.plm.common.api.dto.attribute.imports.AttributeImportSummaryDto;
import com.plm.common.version.domain.*;
import com.plm.common.version.util.AttributeLovImportUtils; // still used for json hash & numeric parse
import com.plm.infrastructure.version.repository.*;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

@Service
public class MetaAttributeImportService {
    // 分组结构: 同一 (categoryCode, attrName) 聚合所有枚举值
    private static class AttrGroup {
        String categoryCode;
        String attrName;
        String unit;
        List<String> values = new ArrayList<>();
        List<Integer> rowIndices = new ArrayList<>(); // 原始行号集合(1-based Excel 行)
    }

    private final MetaCategoryDefRepository categoryDefRepository;
    private final MetaCategoryVersionRepository categoryVersionRepository;
    private final MetaAttributeDefRepository attributeDefRepository;
    private final MetaAttributeVersionRepository attributeVersionRepository;
    private final MetaLovDefRepository lovDefRepository;
    private final MetaLovVersionRepository lovVersionRepository;
    private final MetaCodeRuleService metaCodeRuleService;
    private final MetaCodeRuleSetService metaCodeRuleSetService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    // JdbcTemplate 目前未使用，如后续需要批量 SQL 可再注入

    @PersistenceContext
    private EntityManager entityManager;

    public MetaAttributeImportService(MetaCategoryDefRepository categoryDefRepository,
            MetaCategoryVersionRepository categoryVersionRepository,
            MetaAttributeDefRepository attributeDefRepository,
            MetaAttributeVersionRepository attributeVersionRepository,
            MetaLovDefRepository lovDefRepository,
            MetaLovVersionRepository lovVersionRepository,
            MetaCodeRuleService metaCodeRuleService,
            MetaCodeRuleSetService metaCodeRuleSetService) {
        this.categoryDefRepository = categoryDefRepository;
        this.categoryVersionRepository = categoryVersionRepository;
        this.attributeDefRepository = attributeDefRepository;
        this.attributeVersionRepository = attributeVersionRepository;
        this.lovDefRepository = lovDefRepository;
        this.lovVersionRepository = lovVersionRepository;
        this.metaCodeRuleService = metaCodeRuleService;
        this.metaCodeRuleSetService = metaCodeRuleSetService;
    }

    /**
     * Excel 模板(示例): 分类编号 | 分类名称 | 属性名称 | 属性类型 | 单位 | 枚举值1 | 枚举值2 | ...
     */
    @Transactional
    public AttributeImportSummaryDto importExcel(MultipartFile file, String createdBy) throws IOException {
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("上传文件为空");
        Workbook workbook = WorkbookFactory.create(file.getInputStream());
        Sheet sheet = workbook.getSheetAt(0);
        int lastRow = sheet.getLastRowNum();

        AttributeImportSummaryDto summary = new AttributeImportSummaryDto();
        List<AttributeImportErrorDto> errors = new ArrayList<>();

        Map<String, MetaCategoryDef> categoryCache = new HashMap<>();
        int createdAttrDefs = 0;
        int createdAttrVers = 0;
        int createdLovDefs = 0;
        int createdLovVers = 0;
        int skipped = 0;

        // 临时结构: (categoryCode, attributeName) -> 枚举值集合 & meta
        Map<String, AttrGroup> groups = new LinkedHashMap<>();

        for (int r = 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null)
                continue;
            String categoryCode = cell(row, 0);
            // 分类名称列暂不使用
            String attrName = cell(row, 2);
            String dataType = cell(row, 3);
            String unit = cell(row, 4);
            if (isBlank(categoryCode) && isBlank(attrName))
                continue; // 空行
            if (isBlank(categoryCode)) {
                errors.add(new AttributeImportErrorDto(r + 1, "缺少分类编号"));
                continue;
            }
            if (isBlank(attrName)) {
                errors.add(new AttributeImportErrorDto(r + 1, "缺少属性名称"));
                continue;
            }
            if (isBlank(dataType) || !"enum".equalsIgnoreCase(dataType)) {
                errors.add(new AttributeImportErrorDto(r + 1, "仅支持属性类型=enum"));
                continue;
            }
            String key = categoryCode + "||" + attrName;
            AttrGroup g = groups.computeIfAbsent(key, k -> {
                AttrGroup ag = new AttrGroup();
                ag.categoryCode = categoryCode;
                ag.attrName = attrName;
                ag.unit = unit;
                return ag;
            });
            g.rowIndices.add(r + 1); // 保存 Excel 行号(1-based 展示)
            // 枚举列从第5索引(枚举值1列位置=5)开始
            for (int c = 5; c < row.getLastCellNum(); c++) {
                String v = cell(row, c);
                if (!isBlank(v))
                    g.values.add(v.trim());
            }
        }

        // 预加载分类
        Set<String> allCatCodes = new HashSet<>();
        groups.values().forEach(g -> allCatCodes.add(g.categoryCode));
        allCatCodes
                .forEach(code -> categoryDefRepository.findByCodeKey(code).ifPresent(d -> categoryCache.put(code, d)));

        // 预加载分类下已有属性 -> (categoryDefId -> (semanticDisplayName->MetaAttributeDef))
        Map<UUID, Map<String, MetaAttributeDef>> attrCache = new HashMap<>();
        // 处理每个属性组
        int batchCount = 0;
        final int FLUSH_THRESHOLD = 200; // 可调
        for (AttrGroup g : groups.values()) {
            if (!categoryCache.containsKey(g.categoryCode)) {
                errors.add(new AttributeImportErrorDto(-1, "分类不存在:" + g.categoryCode));
                continue;
            }
            if (g.values.isEmpty()) {
                errors.add(new AttributeImportErrorDto(-1, "属性无枚举值:" + g.attrName));
                continue;
            }
            MetaCategoryDef catDef = categoryCache.get(g.categoryCode);

            String semanticKey = normalizeSemanticKey(g.attrName);
            Map<String, MetaAttributeDef> catAttrMap = attrCache.computeIfAbsent(catDef.getId(), id -> new HashMap<>());
            MetaAttributeDef attrDef = catAttrMap.get(semanticKey);
            if (attrDef == null) {
                attrDef = findAttributeDefByDisplayName(catDef, g.attrName);
                if (attrDef != null) {
                    catAttrMap.put(semanticKey, attrDef);
                }
            }

            MetaCodeRuleService.GeneratedCodeResult attrCode = null;
            String attrKey = attrDef != null ? attrDef.getKey() : null;
            boolean newlyCreatedAttr = false;
            if (attrDef == null) {
                // 默认属性编码已切换为按分类派生：ATTR-{CATEGORY_CODE}-{SEQ}
                String attributeRuleCode = metaCodeRuleSetService.resolveAttributeRuleCode(catDef.getBusinessDomain());
                attrCode = metaCodeRuleService.generateCode(
                    attributeRuleCode,
                        "ATTRIBUTE",
                        null,
                        buildCodeContext(catDef, null),
                        null,
                        createdBy,
                        false
                );
                attrKey = attrCode.code();
                UUID newId = UUID.randomUUID();
                int inserted = attributeDefRepository.insertIgnore(newId, catDef.getId(), attrKey, true, attrKey,
                        createdBy);
                if (inserted > 0) {
                    // 查询持久化实体，保证关系映射使用托管对象
                    attrDef = attributeDefRepository.findById(Objects.requireNonNull(newId, "attributeId")).orElseThrow();
                    newlyCreatedAttr = true;
                    createdAttrDefs++;
                } else {
                    // 已存在则查询
                    attrDef = findAttributeDef(catDef, attrKey);
                }
                catAttrMap.put(semanticKey, attrDef);
                applyAttributeCodeGovernance(attrDef, attrCode);
            }

            // 最新分类版本
            MetaCategoryVersion catVer = categoryVersionRepository.findLatestByDef(catDef).orElse(null);
            if (catVer == null) {
                errors.add(new AttributeImportErrorDto(-1, "分类缺少版本:" + g.categoryCode));
                continue;
            }

            String lovKey = resolveLovKeyForImport(attrDef, attrKey);

            // 构造 structure_json (简化)
            String structureJson = buildAttributeJson(g, attrDef, lovKey);
            String structHash = AttributeLovImportUtils.jsonHash(structureJson);
            MetaAttributeVersion latestAttrVer = newlyCreatedAttr ? null
                    : attributeVersionRepository.findLatestByDef(attrDef).orElse(null);
            boolean needNewAttrVersion = newlyCreatedAttr || latestAttrVer == null
                    || (structHash != null && !structHash.equals(latestAttrVer.getHash()));
            MetaAttributeVersion attrVer;
            if (needNewAttrVersion) {
                attrVer = new MetaAttributeVersion();
                attrVer.setAttributeDef(attrDef);
                attrVer.setCategoryVersion(catVer);
                attrVer.setStructureJson(structureJson);
                attrVer.setHash(structHash);
                attrVer.setCreatedBy(createdBy);
                if (latestAttrVer != null) {
                    latestAttrVer.setIsLatest(false);
                    attrVer.setVersionNo(latestAttrVer.getVersionNo() + 1);
                } else {
                    // 新属性或首次版本
                    attrVer.setVersionNo(1);
                }
                attributeVersionRepository.save(attrVer);
                createdAttrVers++;
            } else {
                attrVer = latestAttrVer;
                skipped++;
            }

            // LOV 定义 & 版本（使用与 JSON 相同的 lovKey）
            MetaLovDef lovDef = lovDefRepository.findByKey(lovKey).orElse(null);
            boolean newlyCreatedLov = false;
            if (lovDef == null) {
                if (attrDef == null) {
                    errors.add(new AttributeImportErrorDto(g.rowIndices.isEmpty() ? -1 : g.rowIndices.get(0),
                            "属性定义缺失，无法创建LOV:" + attrKey));
                    continue; // 安全提前
                }
                UUID lovId = UUID.randomUUID();
                int insLov = lovDefRepository.insertIgnore(lovId, attrDef.getId(), lovKey, attrKey, null, createdBy);
                if (insLov > 0) {
                    lovDef = lovDefRepository.findById(Objects.requireNonNull(lovId, "lovId")).orElseThrow();
                    newlyCreatedLov = true;
                    createdLovDefs++;
                } else {
                    lovDef = lovDefRepository.findByKey(lovKey).orElse(null);
                }
            }
            MetaLovVersion latestLovVer = newlyCreatedLov ? null
                    : lovVersionRepository.findLatestByDef(lovDef).orElse(null);
                Map<String, String> existingValueCodes = extractExistingLovCodes(latestLovVer);
                String valueJson = buildLovJson(catDef, attrKey, g.values, existingValueCodes, createdBy);
                String valueHash = AttributeLovImportUtils.jsonHash(valueJson);
            boolean needNewLovVersion = newlyCreatedLov || latestLovVer == null
                    || (valueHash != null && !valueHash.equals(latestLovVer.getHash()));
            if (needNewLovVersion) {
                MetaLovVersion lv = new MetaLovVersion();
                lv.setLovDef(lovDef);
                lv.setAttributeVersion(attrVer);
                lv.setValueJson(valueJson);
                lv.setHash(valueHash);
                lv.setCreatedBy(createdBy);
                if (latestLovVer != null) {
                    latestLovVer.setIsLatest(false);
                    lv.setVersionNo(latestLovVer.getVersionNo() + 1);
                } else {
                    lv.setVersionNo(1);
                }
                lovVersionRepository.save(lv);
                createdLovVers++;
            } else {
                skipped++;
            }

            // 批量 flush/clear 降低持久化上下文内存
            if (++batchCount % FLUSH_THRESHOLD == 0) {
                entityManager.flush();
                entityManager.clear();
            }
        }

        summary.setTotalRows(lastRow);
        summary.setAttributeGroupCount(groups.size());
        summary.setCreatedAttributeDefs(createdAttrDefs);
        summary.setCreatedAttributeVersions(createdAttrVers);
        summary.setCreatedLovDefs(createdLovDefs);
        summary.setCreatedLovVersions(createdLovVers);
        summary.setSkippedUnchanged(skipped);
        summary.setErrors(errors);
        summary.setErrorCount(errors.size());
        return summary;
    }

    private MetaAttributeDef findAttributeDef(MetaCategoryDef catDef, String attrKey) {
        return attributeDefRepository.findActiveByCategoryDefAndKey(catDef, attrKey).orElse(null);
    }

    private MetaAttributeDef findAttributeDefByDisplayName(MetaCategoryDef catDef, String displayName) {
        String normalizedDisplayName = trim(displayName);
        if (catDef == null || normalizedDisplayName == null) {
            return null;
        }
        List<?> ids = entityManager.createNativeQuery("""
                SELECT d.id
                FROM plm_meta.meta_attribute_def d
                JOIN plm_meta.meta_attribute_version v
                  ON v.attribute_def_id = d.id
                 AND v.is_latest = TRUE
                WHERE d.category_def_id = :categoryDefId
                  AND COALESCE(LOWER(d.status), '') <> 'deleted'
                  AND LOWER(v.structure_json ->> 'displayName') = :displayName
                ORDER BY d.created_at DESC
                LIMIT 1
                """)
                .setParameter("categoryDefId", catDef.getId())
                .setParameter("displayName", normalizedDisplayName.toLowerCase(Locale.ROOT))
                .getResultList();
        if (ids.isEmpty()) {
            return null;
        }
        Object first = ids.get(0);
        UUID id = first instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(first));
        return attributeDefRepository.findById(Objects.requireNonNull(id, "attributeId")).orElse(null);
    }

    private String buildAttributeJson(AttrGroup g, MetaAttributeDef def, String lovKey) {
        String unit = g.unit == null ? "" : g.unit;
        return "{" +
                "\"displayName\":\"" + escape(g.attrName) + "\"," +
                "\"dataType\":\"enum\"," +
                "\"unit\":\"" + escape(unit) + "\"," +
                "\"lovKey\":\"" + escape(lovKey) + "\"" +
                "}";
    }

    private String buildLovJson(MetaCategoryDef categoryDef,
                                String attributeCode,
                                List<String> values,
                                Map<String, String> existingValueCodes,
                                String operator) {
        String lovRuleCode = metaCodeRuleSetService.resolveLovRuleCode(categoryDef.getBusinessDomain());
        ObjectNode root = objectMapper.createObjectNode();
        var arr = objectMapper.createArrayNode();
        for (int i = 0; i < values.size(); i++) {
            String value = trim(values.get(i));
            if (value == null) {
                continue;
            }
            String manualOrExistingCode = existingValueCodes.get(value);
            MetaCodeRuleService.GeneratedCodeResult generatedValueCode = metaCodeRuleService.generateCode(
                    lovRuleCode,
                    "LOV_VALUE",
                    null,
                    buildCodeContext(categoryDef, attributeCode),
                    manualOrExistingCode,
                    operator,
                    false
            );

            ObjectNode one = objectMapper.createObjectNode();
            one.put("code", generatedValueCode.code());
            one.put("name", value);
            one.put("order", i + 1);
            one.put("active", true);
            BigDecimal num = AttributeLovImportUtils.parseNumeric(value);
            if (num != null) {
                one.put("numericValue", num);
            }
            arr.add(one);
        }
        root.set("values", arr);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialize lov values", ex);
        }
    }

    private String cell(Row row, int idx) {
        Cell c = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (c == null)
            return null;
        return switch (c.getCellType()) {
            case STRING -> trim(c.getStringCellValue());
            case NUMERIC -> trim(String.valueOf(c.getNumericCellValue()).replaceAll("\\.0$", ""));
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> null;
        };
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private Map<String, String> buildCodeContext(MetaCategoryDef categoryDef, String attributeCode) {
        LinkedHashMap<String, String> context = new LinkedHashMap<>();
        context.put("BUSINESS_DOMAIN", categoryDef.getBusinessDomain());
        context.put("CATEGORY_CODE", categoryDef.getCodeKey());
        if (!isBlank(attributeCode)) {
            context.put("ATTRIBUTE_CODE", attributeCode);
        }
        return context;
    }

    private String resolveLovKeyForImport(MetaAttributeDef attributeDef,
                                          String attributeCode) {
        MetaLovDef existingLovDef = findExistingActiveLovDef(attributeDef);
        if (existingLovDef != null && !isBlank(existingLovDef.getKey())) {
            return existingLovDef.getKey();
        }
        return requireValue(attributeCode, "attributeCode") + "_LOV";
    }

    private MetaLovDef findExistingActiveLovDef(MetaAttributeDef attributeDef) {
        if (attributeDef == null) {
            return null;
        }
        return lovDefRepository.findByAttributeDef(attributeDef).stream()
                .filter(Objects::nonNull)
                .filter(def -> def.getStatus() == null || !"deleted".equalsIgnoreCase(def.getStatus().trim()))
                .findFirst()
                .orElse(null);
    }

    private void applyAttributeCodeGovernance(MetaAttributeDef def, MetaCodeRuleService.GeneratedCodeResult generated) {
        if (def == null || generated == null) {
            return;
        }
        def.setKeyManualOverride(generated.manualOverride());
        def.setKeyFrozen(generated.frozen());
        def.setGeneratedRuleCode(generated.ruleCode());
        def.setGeneratedRuleVersionNo(generated.ruleVersion());
        attributeDefRepository.save(def);
    }

    private Map<String, String> extractExistingLovCodes(MetaLovVersion latestLovVer) {
        LinkedHashMap<String, String> codes = new LinkedHashMap<>();
        if (latestLovVer == null || isBlank(latestLovVer.getValueJson())) {
            return codes;
        }
        try {
            var values = objectMapper.readTree(latestLovVer.getValueJson()).path("values");
            if (!values.isArray()) {
                return codes;
            }
            values.forEach(node -> {
                String name = trim(node.path("name").asText(null));
                String code = trim(node.path("code").asText(null));
                if (name != null && code != null && !codes.containsKey(name)) {
                    codes.put(name, code);
                }
            });
            return codes;
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private String requireValue(String value, String fieldName) {
        String trimmed = trim(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return trimmed;
    }

    private String trim(String s) {
        return s == null ? null : (s.trim().isEmpty() ? null : s.trim());
    }

    private String normalizeSemanticKey(String value) {
        String normalized = trim(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
