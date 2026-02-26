package com.plm.attribute.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.plm.common.api.dto.MetaAttributeDefDetailDto;
import com.plm.common.api.dto.MetaAttributeUpsertRequestDto;
import com.plm.common.version.domain.MetaAttributeDef;
import com.plm.common.version.domain.MetaAttributeVersion;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.common.version.domain.MetaCategoryVersion;
import com.plm.common.version.domain.MetaLovDef;
import com.plm.common.version.domain.MetaLovVersion;
import com.plm.common.version.util.AttributeLovImportUtils;
import com.plm.infrastructure.version.repository.MetaAttributeDefRepository;
import com.plm.infrastructure.version.repository.MetaAttributeVersionRepository;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import com.plm.infrastructure.version.repository.MetaCategoryVersionRepository;
import com.plm.infrastructure.version.repository.MetaLovDefRepository;
import com.plm.infrastructure.version.repository.MetaLovVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class MetaAttributeManageService {

    private static final String STATUS_DELETED = "deleted";

    private final MetaCategoryDefRepository categoryDefRepository;
    private final MetaCategoryVersionRepository categoryVersionRepository;
    private final MetaAttributeDefRepository attributeDefRepository;
    private final MetaAttributeVersionRepository attributeVersionRepository;
    private final MetaLovDefRepository lovDefRepository;
    private final MetaLovVersionRepository lovVersionRepository;
    private final MetaAttributeQueryService queryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetaAttributeManageService(MetaCategoryDefRepository categoryDefRepository,
            MetaCategoryVersionRepository categoryVersionRepository,
            MetaAttributeDefRepository attributeDefRepository,
            MetaAttributeVersionRepository attributeVersionRepository,
            MetaLovDefRepository lovDefRepository,
            MetaLovVersionRepository lovVersionRepository,
            MetaAttributeQueryService queryService) {
        this.categoryDefRepository = categoryDefRepository;
        this.categoryVersionRepository = categoryVersionRepository;
        this.attributeDefRepository = attributeDefRepository;
        this.attributeVersionRepository = attributeVersionRepository;
        this.lovDefRepository = lovDefRepository;
        this.lovVersionRepository = lovVersionRepository;
        this.queryService = queryService;
    }

    @Transactional
    public MetaAttributeDefDetailDto create(String categoryCodeKey, MetaAttributeUpsertRequestDto req,
            String operator) {
        if (req == null)
            throw new IllegalArgumentException("request body is required");
        if (isBlank(categoryCodeKey))
            throw new IllegalArgumentException("categoryCode is required");
        if (isBlank(req.getKey()))
            throw new IllegalArgumentException("key is required");
        if (isBlank(req.getDisplayName()))
            throw new IllegalArgumentException("displayName is required");
        if (isBlank(req.getDataType()))
            throw new IllegalArgumentException("dataType is required");

        MetaCategoryDef categoryDef = categoryDefRepository.findByCodeKey(categoryCodeKey)
                .orElseThrow(() -> new IllegalArgumentException("category not found: " + categoryCodeKey));
        MetaCategoryVersion categoryVersion = categoryVersionRepository.findLatestByDef(categoryDef)
                .orElseThrow(() -> new IllegalArgumentException("category has no latest version: " + categoryCodeKey));

        String key = req.getKey().trim();
        String lovKey = resolveLovKeyForCreate(categoryCodeKey, req, key);
        boolean hasLov = !isBlank(lovKey) || isEnumLike(req);

        // 1) create def (unique per category)
        MetaAttributeDef def = attributeDefRepository.findActiveByCategoryDefAndKey(categoryDef, key).orElse(null);
        if (def != null) {
            throw new IllegalArgumentException(
                    "attribute already exists: category=" + categoryCodeKey + ", key=" + key);
        }

        UUID id = UUID.randomUUID();
        // autoBindKey 暂保持与 key 一致（与导入逻辑一致：当 key 是系统生成编码时，两者相同）
        int inserted = attributeDefRepository.insertIgnore(id, categoryDef.getId(), key, hasLov, key, operator);
        if (inserted <= 0) {
            // 并发情况下可能被其它请求插入
            def = attributeDefRepository.findActiveByCategoryDefAndKey(categoryDef, key).orElseThrow();
        } else {
            def = attributeDefRepository.findById(id).orElseThrow();
        }

        // 2) create first version
        MetaAttributeVersion v = new MetaAttributeVersion();
        v.setAttributeDef(def);
        v.setCategoryVersion(categoryVersion);
        v.setStructureJson(buildStructureJson(req, lovKey));
        v.setHash(AttributeLovImportUtils.jsonHash(v.getStructureJson()));
        v.setVersionNo(1);
        v.setIsLatest(true);
        v.setCreatedBy(operator);
        attributeVersionRepository.save(v);

        upsertLovValuesIfNeeded(def, v, req, lovKey, operator);

        return queryService.detail(def.getKey(), true);
    }

    @Transactional
    public MetaAttributeDefDetailDto update(String categoryCodeKey, String attrKey, MetaAttributeUpsertRequestDto req,
            String operator) {
        if (req == null)
            throw new IllegalArgumentException("request body is required");
        if (isBlank(categoryCodeKey))
            throw new IllegalArgumentException("categoryCode is required");
        if (isBlank(attrKey))
            throw new IllegalArgumentException("attrKey is required");

        MetaCategoryDef categoryDef = categoryDefRepository.findByCodeKey(categoryCodeKey)
                .orElseThrow(() -> new IllegalArgumentException("category not found: " + categoryCodeKey));
        MetaCategoryVersion categoryVersion = categoryVersionRepository.findLatestByDef(categoryDef)
                .orElseThrow(() -> new IllegalArgumentException("category has no latest version: " + categoryCodeKey));

        String key = attrKey.trim();
        MetaAttributeDef def = attributeDefRepository.findActiveByCategoryDefAndKey(categoryDef, key)
                .orElseThrow(() -> new IllegalArgumentException(
                        "attribute not found: category=" + categoryCodeKey + ", key=" + key));

        if (isDeleted(def)) {
            throw new IllegalArgumentException("attribute is deleted: category=" + categoryCodeKey + ", key=" + key);
        }

        MetaAttributeVersion latest = attributeVersionRepository.findLatestByDef(def).orElse(null);

        // 如果 body 里传了 key，要求一致（避免误修改）
        if (!isBlank(req.getKey()) && !Objects.equals(req.getKey().trim(), key)) {
            throw new IllegalArgumentException("key mismatch: pathKey=" + key + ", bodyKey=" + req.getKey());
        }
        if (isBlank(req.getDisplayName()))
            throw new IllegalArgumentException("displayName is required");
        if (isBlank(req.getDataType()))
            throw new IllegalArgumentException("dataType is required");

        String lovKey = resolveLovKeyForUpdate(categoryCodeKey, req, key, latest);
        boolean hasLov = !isBlank(lovKey) || isEnumLike(req);
        if (def.getLovFlag() == null || !Objects.equals(def.getLovFlag(), hasLov)) {
            def.setLovFlag(hasLov);
            attributeDefRepository.save(def);
        }

        String newJson = buildStructureJson(req, lovKey);
        String newHash = AttributeLovImportUtils.jsonHash(newJson);

        if (latest != null && Objects.equals(latest.getHash(), newHash)) {
            // 结构无变化时，仍需处理可能变化的 LOV 值（如仅编辑枚举项）
            upsertLovValuesIfNeeded(def, latest, req, lovKey, operator);
            // 无变化：直接返回
            return queryService.detail(def.getKey(), true);
        }

        MetaAttributeVersion v = new MetaAttributeVersion();
        v.setAttributeDef(def);
        v.setCategoryVersion(categoryVersion);
        v.setStructureJson(newJson);
        v.setHash(newHash);
        v.setCreatedBy(operator);

        if (latest != null) {
            latest.setIsLatest(false);
            v.setVersionNo(latest.getVersionNo() + 1);
        } else {
            v.setVersionNo(1);
        }
        v.setIsLatest(true);
        attributeVersionRepository.save(v);

        upsertLovValuesIfNeeded(def, v, req, lovKey, operator);

        return queryService.detail(def.getKey(), true);
    }

    /**
     * 软删属性：仅更新 def.status=deleted，不删除历史版本。
     * - 幂等：重复调用不会报错
     */
    @Transactional
    public void delete(String categoryCodeKey, String attrKey, String operator) {
        if (isBlank(categoryCodeKey))
            throw new IllegalArgumentException("categoryCode is required");
        if (isBlank(attrKey))
            throw new IllegalArgumentException("attrKey is required");

        MetaCategoryDef categoryDef = categoryDefRepository.findByCodeKey(categoryCodeKey)
                .orElseThrow(() -> new IllegalArgumentException("category not found: " + categoryCodeKey));

        String key = attrKey.trim();
        MetaAttributeDef def = attributeDefRepository.findActiveByCategoryDefAndKey(categoryDef, key)
                .orElseThrow(() -> new IllegalArgumentException(
                        "attribute not found: category=" + categoryCodeKey + ", key=" + key));

        if (isDeleted(def))
            return;

        // 先级联标记子表，再标记 def，确保语义一致
        attributeVersionRepository.softDeleteByDef(def);
        var lovDefs = lovDefRepository.findByAttributeDef(def);
        if (!lovDefs.isEmpty()) {
            lovVersionRepository.softDeleteByLovDefs(lovDefs);
        }
        lovDefRepository.softDeleteByAttributeDef(def);

        def.setStatus(STATUS_DELETED);
        attributeDefRepository.save(def);
    }

    private boolean isDeleted(MetaAttributeDef def) {
        if (def == null || def.getStatus() == null)
            return false;
        return STATUS_DELETED.equalsIgnoreCase(def.getStatus().trim());
    }

    private String buildStructureJson(MetaAttributeUpsertRequestDto req, String lovKey) {
        ObjectNode node = objectMapper.createObjectNode();
        // 必填字段由上层校验保证非空
        node.put("displayName", req.getDisplayName().trim());
        node.put("dataType", req.getDataType().trim());

        String description = trimToNull(req.getDescription());
        if (description != null)
            node.put("description", description);
        String unit = trimToNull(req.getUnit());
        if (unit != null)
            node.put("unit", unit);
        String defaultValue = trimToNull(req.getDefaultValue());
        if (defaultValue != null)
            node.put("defaultValue", defaultValue);

        if (req.getRequired() != null)
            node.put("required", req.getRequired());
        if (req.getUnique() != null)
            node.put("unique", req.getUnique());
        if (req.getHidden() != null)
            node.put("hidden", req.getHidden());
        if (req.getReadOnly() != null)
            node.put("readOnly", req.getReadOnly());
        if (req.getSearchable() != null)
            node.put("searchable", req.getSearchable());

        if (req.getMinValue() != null)
            node.put("minValue", req.getMinValue());
        if (req.getMaxValue() != null)
            node.put("maxValue", req.getMaxValue());
        if (req.getStep() != null)
            node.put("step", req.getStep());
        if (req.getPrecision() != null)
            node.put("precision", req.getPrecision());

        String trueLabel = trimToNull(req.getTrueLabel());
        if (trueLabel != null)
            node.put("trueLabel", trueLabel);
        String falseLabel = trimToNull(req.getFalseLabel());
        if (falseLabel != null)
            node.put("falseLabel", falseLabel);

        if (!isBlank(lovKey))
            node.put("lovKey", lovKey.trim());

        return node.toString();
    }

    private String resolveLovKeyForCreate(String categoryCodeKey, MetaAttributeUpsertRequestDto req, String attributeKey) {
        if (req == null)
            return null;
        if (!isBlank(req.getLovKey()))
            return req.getLovKey().trim();
        if (!isEnumLike(req))
            return null;
        // enum/multi-enum 且未显式传 lovKey：使用“分类编码+属性编码(key)”生成稳定 key
        return AttributeLovImportUtils.generateLovKey(categoryCodeKey, attributeKey);
    }

    private String resolveLovKeyForUpdate(String categoryCodeKey, MetaAttributeUpsertRequestDto req,
            String attributeKey,
            MetaAttributeVersion latest) {
        if (req == null)
            return null;
        if (!isBlank(req.getLovKey()))
            return req.getLovKey().trim();

        // 若历史已有 lovKey 且本次未显式修改，优先沿用，避免“改名导致 lovKey 变化”
        if (latest != null && !isBlank(latest.getLovKey()))
            return latest.getLovKey().trim();

        if (!isEnumLike(req))
            return null;
        return AttributeLovImportUtils.generateLovKey(categoryCodeKey, attributeKey);
    }

    private boolean isEnum(MetaAttributeUpsertRequestDto req) {
        return req != null && req.getDataType() != null && "enum".equalsIgnoreCase(req.getDataType().trim());
    }

    private boolean isEnumLike(MetaAttributeUpsertRequestDto req) {
        if (req == null || req.getDataType() == null) {
            return false;
        }
        String dataType = req.getDataType().trim();
        return "enum".equalsIgnoreCase(dataType) || "multi-enum".equalsIgnoreCase(dataType);
    }

    private void upsertLovValuesIfNeeded(MetaAttributeDef def,
                                         MetaAttributeVersion attrVersion,
                                         MetaAttributeUpsertRequestDto req,
                                         String lovKey,
                                         String operator) {
        if (def == null || attrVersion == null || req == null)
            return;
        if (!isEnumLike(req))
            return;
        if (isBlank(lovKey))
            return;
        if (req.getLovValues() == null)
            return;

        MetaLovDef lovDef = lovDefRepository.findByAttributeDefAndKey(def, lovKey).orElse(null);
        if (lovDef == null) {
            UUID lovId = UUID.randomUUID();
            int inserted = lovDefRepository.insertIgnore(lovId, def.getId(), lovKey, def.getKey(), null, operator);
            if (inserted > 0) {
                lovDef = lovDefRepository.findById(lovId).orElseThrow();
            } else {
                lovDef = lovDefRepository.findByAttributeDefAndKey(def, lovKey).orElse(null);
            }
        }
        if (lovDef == null)
            return;

        String valueJson = buildLovValueJson(req.getLovValues());
        String newHash = AttributeLovImportUtils.jsonHash(valueJson);
        MetaLovVersion latest = lovVersionRepository.findLatestByDef(lovDef).orElse(null);
        if (latest != null && Objects.equals(latest.getHash(), newHash)) {
            return;
        }

        MetaLovVersion lv = new MetaLovVersion();
        lv.setLovDef(lovDef);
        lv.setAttributeVersion(attrVersion);
        lv.setValueJson(valueJson);
        lv.setHash(newHash);
        lv.setCreatedBy(operator);
        lv.setIsLatest(true);
        if (latest != null) {
            latest.setIsLatest(false);
            lv.setVersionNo(latest.getVersionNo() + 1);
        } else {
            lv.setVersionNo(1);
        }
        lovVersionRepository.save(lv);
    }

    private String buildLovValueJson(List<MetaAttributeUpsertRequestDto.LovValueUpsertItem> items) {
        ObjectNode root = objectMapper.createObjectNode();
        var arr = objectMapper.createArrayNode();
        List<MetaAttributeUpsertRequestDto.LovValueUpsertItem> safeItems = items == null ? new ArrayList<>() : items;
        int order = 1;
        for (MetaAttributeUpsertRequestDto.LovValueUpsertItem item : safeItems) {
            if (item == null)
                continue;
            String code = trimToNull(item.getCode());
            String name = trimToNull(item.getName());
            if (code == null && name == null)
                continue;
            ObjectNode one = objectMapper.createObjectNode();
            one.put("code", code != null ? code : name);
            one.put("name", name != null ? name : code);
            String label = trimToNull(item.getLabel());
            if (label != null)
                one.put("label", label);
            one.put("order", order++);
            one.put("active", true);
            arr.add(one);
        }
        root.set("values", arr);
        return root.toString();
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String trimToNull(String s) {
        if (s == null)
            return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
