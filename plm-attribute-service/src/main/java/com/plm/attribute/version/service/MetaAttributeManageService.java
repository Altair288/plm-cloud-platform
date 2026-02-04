package com.plm.attribute.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.plm.common.api.dto.MetaAttributeDefDetailDto;
import com.plm.common.api.dto.MetaAttributeUpsertRequestDto;
import com.plm.common.version.domain.MetaAttributeDef;
import com.plm.common.version.domain.MetaAttributeVersion;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.common.version.domain.MetaCategoryVersion;
import com.plm.common.version.util.AttributeLovImportUtils;
import com.plm.infrastructure.version.repository.MetaAttributeDefRepository;
import com.plm.infrastructure.version.repository.MetaAttributeVersionRepository;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import com.plm.infrastructure.version.repository.MetaCategoryVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class MetaAttributeManageService {

    private final MetaCategoryDefRepository categoryDefRepository;
    private final MetaCategoryVersionRepository categoryVersionRepository;
    private final MetaAttributeDefRepository attributeDefRepository;
    private final MetaAttributeVersionRepository attributeVersionRepository;
    private final MetaAttributeQueryService queryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetaAttributeManageService(MetaCategoryDefRepository categoryDefRepository,
                                     MetaCategoryVersionRepository categoryVersionRepository,
                                     MetaAttributeDefRepository attributeDefRepository,
                                     MetaAttributeVersionRepository attributeVersionRepository,
                                     MetaAttributeQueryService queryService) {
        this.categoryDefRepository = categoryDefRepository;
        this.categoryVersionRepository = categoryVersionRepository;
        this.attributeDefRepository = attributeDefRepository;
        this.attributeVersionRepository = attributeVersionRepository;
        this.queryService = queryService;
    }

    @Transactional
    public MetaAttributeDefDetailDto create(String categoryCodeKey, MetaAttributeUpsertRequestDto req, String operator) {
        if (req == null) throw new IllegalArgumentException("request body is required");
        if (isBlank(categoryCodeKey)) throw new IllegalArgumentException("categoryCode is required");
        if (isBlank(req.getKey())) throw new IllegalArgumentException("key is required");
        if (isBlank(req.getDisplayName())) throw new IllegalArgumentException("displayName is required");
        if (isBlank(req.getDataType())) throw new IllegalArgumentException("dataType is required");

        MetaCategoryDef categoryDef = categoryDefRepository.findByCodeKey(categoryCodeKey)
                .orElseThrow(() -> new IllegalArgumentException("category not found: " + categoryCodeKey));
        MetaCategoryVersion categoryVersion = categoryVersionRepository.findLatestByDef(categoryDef)
                .orElseThrow(() -> new IllegalArgumentException("category has no latest version: " + categoryCodeKey));

        String key = req.getKey().trim();
        String lovKey = resolveLovKeyForCreate(categoryCodeKey, req);
        boolean hasLov = !isBlank(lovKey) || isEnum(req);

        // 1) create def (unique per category)
        MetaAttributeDef def = attributeDefRepository.findByCategoryDefAndKey(categoryDef, key).orElse(null);
        if (def != null) {
            throw new IllegalArgumentException("attribute already exists: category=" + categoryCodeKey + ", key=" + key);
        }

        UUID id = UUID.randomUUID();
        // autoBindKey 暂保持与 key 一致（与导入逻辑一致：当 key 是系统生成编码时，两者相同）
        int inserted = attributeDefRepository.insertIgnore(id, categoryDef.getId(), key, hasLov, key, operator);
        if (inserted <= 0) {
            // 并发情况下可能被其它请求插入
            def = attributeDefRepository.findByCategoryDefAndKey(categoryDef, key).orElseThrow();
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

        return queryService.detail(def.getKey(), true);
    }

    @Transactional
    public MetaAttributeDefDetailDto update(String categoryCodeKey, String attrKey, MetaAttributeUpsertRequestDto req, String operator) {
        if (req == null) throw new IllegalArgumentException("request body is required");
        if (isBlank(categoryCodeKey)) throw new IllegalArgumentException("categoryCode is required");
        if (isBlank(attrKey)) throw new IllegalArgumentException("attrKey is required");

        MetaCategoryDef categoryDef = categoryDefRepository.findByCodeKey(categoryCodeKey)
                .orElseThrow(() -> new IllegalArgumentException("category not found: " + categoryCodeKey));
        MetaCategoryVersion categoryVersion = categoryVersionRepository.findLatestByDef(categoryDef)
                .orElseThrow(() -> new IllegalArgumentException("category has no latest version: " + categoryCodeKey));

        String key = attrKey.trim();
        MetaAttributeDef def = attributeDefRepository.findByCategoryDefAndKey(categoryDef, key)
                .orElseThrow(() -> new IllegalArgumentException("attribute not found: category=" + categoryCodeKey + ", key=" + key));

        MetaAttributeVersion latest = attributeVersionRepository.findLatestByDef(def).orElse(null);

        // 如果 body 里传了 key，要求一致（避免误修改）
        if (!isBlank(req.getKey()) && !Objects.equals(req.getKey().trim(), key)) {
            throw new IllegalArgumentException("key mismatch: pathKey=" + key + ", bodyKey=" + req.getKey());
        }
        if (isBlank(req.getDisplayName())) throw new IllegalArgumentException("displayName is required");
        if (isBlank(req.getDataType())) throw new IllegalArgumentException("dataType is required");

        String lovKey = resolveLovKeyForUpdate(categoryCodeKey, req, latest);
        boolean hasLov = !isBlank(lovKey) || isEnum(req);
        if (def.getLovFlag() == null || !Objects.equals(def.getLovFlag(), hasLov)) {
            def.setLovFlag(hasLov);
            attributeDefRepository.save(def);
        }

        String newJson = buildStructureJson(req, lovKey);
        String newHash = AttributeLovImportUtils.jsonHash(newJson);

        if (latest != null && Objects.equals(latest.getHash(), newHash)) {
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

        return queryService.detail(def.getKey(), true);
    }

    private String buildStructureJson(MetaAttributeUpsertRequestDto req, String lovKey) {
        ObjectNode node = objectMapper.createObjectNode();
        // 必填字段由上层校验保证非空
        node.put("displayName", req.getDisplayName().trim());
        node.put("dataType", req.getDataType().trim());

        String description = trimToNull(req.getDescription());
        if (description != null) node.put("description", description);
        String unit = trimToNull(req.getUnit());
        if (unit != null) node.put("unit", unit);
        String defaultValue = trimToNull(req.getDefaultValue());
        if (defaultValue != null) node.put("defaultValue", defaultValue);

        if (req.getRequired() != null) node.put("required", req.getRequired());
        if (req.getUnique() != null) node.put("unique", req.getUnique());
        if (req.getHidden() != null) node.put("hidden", req.getHidden());
        if (req.getReadOnly() != null) node.put("readOnly", req.getReadOnly());
        if (req.getSearchable() != null) node.put("searchable", req.getSearchable());

        if (!isBlank(lovKey)) node.put("lovKey", lovKey.trim());

        return node.toString();
    }

    private String resolveLovKeyForCreate(String categoryCodeKey, MetaAttributeUpsertRequestDto req) {
        if (req == null) return null;
        if (!isBlank(req.getLovKey())) return req.getLovKey().trim();
        if (!isEnum(req)) return null;
        // enum 且未显式传 lovKey：生成一个可重复的 key，便于前端不传也可用
        return AttributeLovImportUtils.generateLovKey(categoryCodeKey, req.getDisplayName());
    }

    private String resolveLovKeyForUpdate(String categoryCodeKey, MetaAttributeUpsertRequestDto req, MetaAttributeVersion latest) {
        if (req == null) return null;
        if (!isBlank(req.getLovKey())) return req.getLovKey().trim();

        // 若历史已有 lovKey 且本次未显式修改，优先沿用，避免“改名导致 lovKey 变化”
        if (latest != null && !isBlank(latest.getLovKey())) return latest.getLovKey().trim();

        if (!isEnum(req)) return null;
        return AttributeLovImportUtils.generateLovKey(categoryCodeKey, req.getDisplayName());
    }

    private boolean isEnum(MetaAttributeUpsertRequestDto req) {
        return req != null && req.getDataType() != null && "enum".equalsIgnoreCase(req.getDataType().trim());
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
