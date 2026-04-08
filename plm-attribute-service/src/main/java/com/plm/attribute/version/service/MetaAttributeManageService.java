package com.plm.attribute.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.plm.common.api.dto.attribute.CreateAttributeCodePreviewRequestDto;
import com.plm.common.api.dto.attribute.CreateAttributeCodePreviewResponseDto;
import com.plm.common.api.dto.attribute.MetaAttributeDefDetailDto;
import com.plm.common.api.dto.attribute.MetaAttributeUpsertRequestDto;
import com.plm.common.api.dto.code.CodeRuleDetailDto;
import com.plm.common.api.dto.code.CodeRulePreviewRequestDto;
import com.plm.common.api.dto.code.CodeRulePreviewResponseDto;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final MetaCodeRuleService metaCodeRuleService;
    private final MetaCodeRuleSetService metaCodeRuleSetService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetaAttributeManageService(MetaCategoryDefRepository categoryDefRepository,
            MetaCategoryVersionRepository categoryVersionRepository,
            MetaAttributeDefRepository attributeDefRepository,
            MetaAttributeVersionRepository attributeVersionRepository,
            MetaLovDefRepository lovDefRepository,
            MetaLovVersionRepository lovVersionRepository,
            MetaAttributeQueryService queryService,
            MetaCodeRuleService metaCodeRuleService,
            MetaCodeRuleSetService metaCodeRuleSetService) {
        this.categoryDefRepository = categoryDefRepository;
        this.categoryVersionRepository = categoryVersionRepository;
        this.attributeDefRepository = attributeDefRepository;
        this.attributeVersionRepository = attributeVersionRepository;
        this.lovDefRepository = lovDefRepository;
        this.lovVersionRepository = lovVersionRepository;
        this.queryService = queryService;
        this.metaCodeRuleService = metaCodeRuleService;
        this.metaCodeRuleSetService = metaCodeRuleSetService;
    }

    @Transactional(readOnly = true)
    public CreateAttributeCodePreviewResponseDto previewCreateCode(String categoryCodeKey,
                                                                   CreateAttributeCodePreviewRequestDto req) {
        MetaCategoryDef categoryDef = resolveCategory(null, categoryCodeKey);
        return previewCreateCode(categoryDef.getBusinessDomain(), categoryCodeKey, req);
    }

    @Transactional(readOnly = true)
    public CreateAttributeCodePreviewResponseDto previewCreateCode(String businessDomain,
                                                                   String categoryCodeKey,
                                                                   CreateAttributeCodePreviewRequestDto req) {
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (isBlank(categoryCodeKey)) {
            throw new IllegalArgumentException("categoryCode is required");
        }

        MetaCategoryDef categoryDef = resolveCategory(businessDomain, categoryCodeKey);
        Map<String, String> attributeContext = buildCodeContext(categoryDef, null);
        String manualKey = trimToNull(req.getManualKey());
        String attributeRuleCode = metaCodeRuleSetService.resolveAttributeRuleCode(categoryDef.getBusinessDomain());

        CodeRulePreviewRequestDto previewRequest = new CodeRulePreviewRequestDto();
        previewRequest.setContext(attributeContext);
        previewRequest.setManualCode(manualKey);
        previewRequest.setCount(normalizePreviewCount(req.getCount()));

        CodeRulePreviewResponseDto preview = metaCodeRuleService.preview(attributeRuleCode, previewRequest);
        CodeRuleDetailDto attributeRuleDetail = metaCodeRuleService.detail(attributeRuleCode);
        String suggestedAttributeKey = preview.getExamples() == null || preview.getExamples().isEmpty()
                ? manualKey
                : preview.getExamples().get(0);

        LovValuePreviewResult lovPreview = previewLovValueCodes(categoryDef, suggestedAttributeKey, req);

        CreateAttributeCodePreviewResponseDto response = new CreateAttributeCodePreviewResponseDto();
        response.setBusinessDomain(categoryDef.getBusinessDomain());
        response.setCategoryCode(categoryDef.getCodeKey());
        response.setAttributeRuleCode(attributeRuleCode);
        response.setGenerationMode(manualKey == null ? "AUTO" : "MANUAL");
        response.setAllowManualOverride(Boolean.TRUE.equals(attributeRuleDetail.getAllowManualOverride()));
        response.setSuggestedCode(suggestedAttributeKey);
        response.setExamples(preview.getExamples());
        response.setWarnings(preview.getWarnings());
        response.setResolvedContext(preview.getResolvedContext());
        response.setResolvedSequenceScope(preview.getResolvedSequenceScope());
        response.setResolvedPeriodKey(preview.getResolvedPeriodKey());
        response.setPreviewStale(Boolean.FALSE);
        response.setLovRuleCode(lovPreview.ruleCode());
        response.setAllowLovValueManualOverride(lovPreview.allowManualOverride());
        response.setLovWarnings(lovPreview.warnings());
        response.setLovResolvedContext(lovPreview.resolvedContext());
        response.setLovResolvedSequenceScope(lovPreview.resolvedSequenceScope());
        response.setLovResolvedPeriodKey(lovPreview.resolvedPeriodKey());
        response.setLovValuePreviews(lovPreview.items());
        return response;
    }

    @Transactional
    public MetaAttributeDefDetailDto create(String categoryCodeKey, MetaAttributeUpsertRequestDto req,
            String operator) {
        MetaCategoryDef categoryDef = resolveCategory(null, categoryCodeKey);
        return create(categoryDef.getBusinessDomain(), categoryCodeKey, req, operator);
    }

    @Transactional
    public MetaAttributeDefDetailDto create(String businessDomain, String categoryCodeKey, MetaAttributeUpsertRequestDto req,
            String operator) {
        if (req == null)
            throw new IllegalArgumentException("request body is required");
        if (isBlank(categoryCodeKey))
            throw new IllegalArgumentException("categoryCode is required");
        if (isBlank(req.getDisplayName()))
            throw new IllegalArgumentException("displayName is required");
        if (isBlank(req.getDataType()))
            throw new IllegalArgumentException("dataType is required");

        MetaCategoryDef categoryDef = resolveCategory(businessDomain, categoryCodeKey);
        MetaCategoryVersion categoryVersion = categoryVersionRepository.findLatestByDef(categoryDef)
                .orElseThrow(() -> new IllegalArgumentException("category has no latest version: " + categoryCodeKey));

        MetaCodeRuleService.GeneratedCodeResult attributeCode = resolveAttributeKeyForCreate(categoryDef, req, operator);
        String key = attributeCode.code();
        String lovKey = resolveLovBindingKeyForCreate(req, key);
        boolean hasLov = !isBlank(lovKey) || isEnumLike(req);

        // 1) create def (unique per business domain)
        MetaAttributeDef def = attributeDefRepository.findActiveByBusinessDomainAndKey(categoryDef.getBusinessDomain(), key).orElse(null);
        if (def != null) {
            throw new IllegalArgumentException(
                    "attribute already exists: businessDomain=" + categoryDef.getBusinessDomain() + ", key=" + key);
        }

        UUID id = UUID.randomUUID();
        // autoBindKey 暂保持与 key 一致（与导入逻辑一致：当 key 是系统生成编码时，两者相同）
        int inserted = attributeDefRepository.insertIgnore(id, categoryDef.getId(), categoryDef.getBusinessDomain(), key, hasLov, key, operator);
        if (inserted <= 0) {
            // 并发情况下可能被其它请求插入
            def = attributeDefRepository.findActiveByBusinessDomainAndKey(categoryDef.getBusinessDomain(), key).orElseThrow();
        } else {
            def = attributeDefRepository.findById(Objects.requireNonNull(id, "attributeId")).orElseThrow();
        }
        applyAttributeCodeGovernance(def, attributeCode);

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

        upsertLovValuesIfNeeded(categoryDef, def, v, req, lovKey, operator);

        return queryService.detail(categoryDef.getBusinessDomain(), def.getKey(), true);
    }

    @Transactional
    public MetaAttributeDefDetailDto update(String categoryCodeKey, String attrKey, MetaAttributeUpsertRequestDto req,
            String operator) {
        MetaCategoryDef categoryDef = resolveCategory(null, categoryCodeKey);
        return update(categoryDef.getBusinessDomain(), categoryCodeKey, attrKey, req, operator);
    }

    @Transactional
    public MetaAttributeDefDetailDto update(String businessDomain, String categoryCodeKey, String attrKey, MetaAttributeUpsertRequestDto req,
            String operator) {
        if (req == null)
            throw new IllegalArgumentException("request body is required");
        if (isBlank(categoryCodeKey))
            throw new IllegalArgumentException("categoryCode is required");
        if (isBlank(attrKey))
            throw new IllegalArgumentException("attrKey is required");

        MetaCategoryDef categoryDef = resolveCategory(businessDomain, categoryCodeKey);
        MetaCategoryVersion categoryVersion = categoryVersionRepository.findLatestByDef(categoryDef)
                .orElseThrow(() -> new IllegalArgumentException("category has no latest version: " + categoryCodeKey));

        String key = attrKey.trim();
        MetaAttributeDef def = attributeDefRepository.findActiveByBusinessDomainAndKey(categoryDef.getBusinessDomain(), key)
            .orElseThrow(() -> new IllegalArgumentException(
                "attribute not found: businessDomain=" + categoryDef.getBusinessDomain() + ", key=" + key));
        if (def.getCategoryDef() == null || !Objects.equals(def.getCategoryDef().getId(), categoryDef.getId())) {
            throw new IllegalArgumentException("attribute does not belong to category: category=" + categoryCodeKey + ", key=" + key);
        }

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

        String lovKey = resolveLovBindingKeyForUpdate(req, key, latest);
        boolean hasLov = !isBlank(lovKey) || isEnumLike(req);
        if (def.getLovFlag() == null || !Objects.equals(def.getLovFlag(), hasLov)) {
            def.setLovFlag(hasLov);
            attributeDefRepository.save(def);
        }

        String newJson = buildStructureJson(req, lovKey);
        String newHash = AttributeLovImportUtils.jsonHash(newJson);

        if (latest != null && Objects.equals(latest.getHash(), newHash)) {
            // 结构无变化时，仍需处理可能变化的 LOV 值（如仅编辑枚举项）
            upsertLovValuesIfNeeded(categoryDef, def, latest, req, lovKey, operator);
            // 无变化：直接返回
            return queryService.detail(categoryDef.getBusinessDomain(), def.getKey(), true);
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

        upsertLovValuesIfNeeded(categoryDef, def, v, req, lovKey, operator);

        return queryService.detail(categoryDef.getBusinessDomain(), def.getKey(), true);
    }

    /**
     * 软删属性：仅更新 def.status=deleted，不删除历史版本。
     * - 幂等：重复调用不会报错
     */
    @Transactional
    public void delete(String categoryCodeKey, String attrKey, String operator) {
        MetaCategoryDef categoryDef = resolveCategory(null, categoryCodeKey);
        delete(categoryDef.getBusinessDomain(), categoryCodeKey, attrKey, operator);
    }

    @Transactional
    public void delete(String businessDomain, String categoryCodeKey, String attrKey, String operator) {
        if (isBlank(categoryCodeKey))
            throw new IllegalArgumentException("categoryCode is required");
        if (isBlank(attrKey))
            throw new IllegalArgumentException("attrKey is required");

        MetaCategoryDef categoryDef = resolveCategory(businessDomain, categoryCodeKey);

        String key = attrKey.trim();
        MetaAttributeDef def = attributeDefRepository.findActiveByBusinessDomainAndKey(categoryDef.getBusinessDomain(), key)
            .orElseThrow(() -> new IllegalArgumentException(
                "attribute not found: businessDomain=" + categoryDef.getBusinessDomain() + ", key=" + key));
        if (def.getCategoryDef() == null || !Objects.equals(def.getCategoryDef().getId(), categoryDef.getId())) {
            throw new IllegalArgumentException("attribute does not belong to category: category=" + categoryCodeKey + ", key=" + key);
        }

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

    private MetaCategoryDef resolveCategory(String businessDomain, String categoryCodeKey) {
        if (isBlank(categoryCodeKey)) {
            throw new IllegalArgumentException("categoryCode is required");
        }
        String normalizedCategoryCode = categoryCodeKey.trim();
        if (!isBlank(businessDomain)) {
            return categoryDefRepository.findByBusinessDomainAndCodeKey(businessDomain.trim(), normalizedCategoryCode)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "category not found: businessDomain=" + businessDomain + ", categoryCode=" + normalizedCategoryCode));
        }
        List<MetaCategoryDef> matches = categoryDefRepository.findAllByCodeKey(normalizedCategoryCode).stream()
                .filter(Objects::nonNull)
                .filter(def -> def.getStatus() == null || !STATUS_DELETED.equalsIgnoreCase(def.getStatus().trim()))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("category not found: " + normalizedCategoryCode);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("categoryCode is ambiguous across business domains, businessDomain is required: " + normalizedCategoryCode);
        }
        return matches.get(0);
    }

    private boolean isDeleted(MetaAttributeDef def) {
        if (def == null || def.getStatus() == null)
            return false;
        return STATUS_DELETED.equalsIgnoreCase(def.getStatus().trim());
    }

    private boolean isDeleted(MetaLovDef def) {
        if (def == null || def.getStatus() == null) {
            return false;
        }
        return STATUS_DELETED.equalsIgnoreCase(def.getStatus().trim());
    }

    private String buildStructureJson(MetaAttributeUpsertRequestDto req, String lovKey) {
        return AttributeStructureJsonSupport.toJson(objectMapper,
                new AttributeStructureJsonSupport.AttributeStructureSpec(
                        req.getDisplayName(),
                        req.getDataType(),
                        req.getDescription(),
                        req.getAttributeField(),
                        req.getUnit(),
                        req.getDefaultValue(),
                        req.getRequired(),
                        req.getUnique(),
                        req.getHidden(),
                        req.getReadOnly(),
                        req.getSearchable(),
                        req.getMinValue(),
                        req.getMaxValue(),
                        req.getStep(),
                        req.getPrecision(),
                        req.getTrueLabel(),
                        req.getFalseLabel(),
                        lovKey));
    }

    private String resolveLovBindingKeyForCreate(MetaAttributeUpsertRequestDto req,
                                                 String attributeKey) {
        if (req == null)
            return null;
        if (!isEnumLike(req))
            return null;

        return resolveLovBindingKey(req.getLovKey(), req.getLovGenerationMode(), attributeKey, null);
    }

    private String resolveLovBindingKeyForUpdate(MetaAttributeUpsertRequestDto req,
                                                 String attributeKey,
                                                 MetaAttributeVersion latest) {
        if (req == null)
            return null;

        if (!isEnumLike(req))
            return null;

        return resolveLovBindingKey(req.getLovKey(), req.getLovGenerationMode(), attributeKey,
                latest == null ? null : latest.getLovKey());
    }

    private boolean isEnumLike(MetaAttributeUpsertRequestDto req) {
        if (req == null || req.getDataType() == null) {
            return false;
        }
        String dataType = req.getDataType().trim();
        return "enum".equalsIgnoreCase(dataType)
                || "multi-enum".equalsIgnoreCase(dataType)
                || "multi_enum".equalsIgnoreCase(dataType);
    }

    private boolean isEnumLike(String dataType) {
        return AttributeStructureJsonSupport.isEnumLike(dataType);
    }

    private int normalizePreviewCount(Integer count) {
        if (count == null) {
            return 1;
        }
        return Math.max(1, Math.min(count, 20));
    }

    private void upsertLovValuesIfNeeded(MetaCategoryDef categoryDef,
                                         MetaAttributeDef def,
                                         MetaAttributeVersion attrVersion,
                                         MetaAttributeUpsertRequestDto req,
                                         String lovKey,
                                         String operator) {
        if (categoryDef == null || def == null || attrVersion == null || req == null)
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
            int inserted = lovDefRepository.insertIgnore(lovId, def.getId(), categoryDef.getBusinessDomain(), lovKey, def.getKey(), null, operator);
            if (inserted > 0) {
                lovDef = lovDefRepository.findById(Objects.requireNonNull(lovId, "lovId")).orElseThrow();
            } else {
                lovDef = lovDefRepository.findByAttributeDefAndKey(def, lovKey).orElse(null);
            }
        }
        if (lovDef == null)
            return;

        MetaLovVersion latest = lovVersionRepository.findLatestByDef(lovDef).orElse(null);
        Map<String, String> existingValueCodes = extractExistingLovCodes(latest);
        String valueJson = buildLovValueJson(categoryDef, def.getKey(), req.getLovValues(), existingValueCodes, operator);
        validateLovValueCodesUnique(categoryDef.getBusinessDomain(), lovDef, valueJson);
        String newHash = AttributeLovImportUtils.jsonHash(valueJson);
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

    private String buildLovValueJson(MetaCategoryDef categoryDef,
                                     String attributeKey,
                                     List<MetaAttributeUpsertRequestDto.LovValueUpsertItem> items,
                                     Map<String, String> existingValueCodes,
                                     String operator) {
        String lovRuleCode = metaCodeRuleSetService.resolveLovRuleCode(categoryDef.getBusinessDomain());
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
                String resolvedManualCode = code != null ? code : existingValueCodes.get(name);
            MetaCodeRuleService.GeneratedCodeResult generatedValueCode = metaCodeRuleService.generateCode(
                    lovRuleCode,
                    "LOV_VALUE",
                    null,
                    buildCodeContext(categoryDef, attributeKey),
                    resolvedManualCode,
                    operator,
                    false
            );
            one.put("code", generatedValueCode.code());
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

    private LovValuePreviewResult previewLovValueCodes(MetaCategoryDef categoryDef,
                                                       String attributeKey,
                                                       CreateAttributeCodePreviewRequestDto req) {
        if (!isEnumLike(req == null ? null : req.getDataType())) {
            return LovValuePreviewResult.empty();
        }

        String lovRuleCode = metaCodeRuleSetService.resolveLovRuleCode(categoryDef.getBusinessDomain());
        CodeRuleDetailDto lovRuleDetail = metaCodeRuleService.detail(lovRuleCode);
        if (isBlank(attributeKey)) {
            return LovValuePreviewResult.empty(lovRuleCode, Boolean.TRUE.equals(lovRuleDetail.getAllowManualOverride()));
        }

        Map<String, String> lovContext = buildCodeContext(categoryDef, attributeKey);
        List<CreateAttributeCodePreviewRequestDto.LovValuePreviewItem> requestItems =
            req == null || req.getLovValues() == null ? Collections.emptyList() : req.getLovValues();
        List<CreateAttributeCodePreviewResponseDto.LovValueCodePreviewItem> previewItems = new ArrayList<>();
        List<Integer> autoIndexes = new ArrayList<>();

        for (int index = 0; index < requestItems.size(); index++) {
            CreateAttributeCodePreviewRequestDto.LovValuePreviewItem requestItem = requestItems.get(index);
            CreateAttributeCodePreviewResponseDto.LovValueCodePreviewItem previewItem =
                    new CreateAttributeCodePreviewResponseDto.LovValueCodePreviewItem();
            previewItem.setIndex(index);
            if (requestItem != null) {
                previewItem.setManualCode(trimToNull(requestItem.getCode()));
                previewItem.setName(trimToNull(requestItem.getName()));
                previewItem.setLabel(trimToNull(requestItem.getLabel()));
            }
            previewItems.add(previewItem);

            if (previewItem.getManualCode() != null) {
                CodeRulePreviewRequestDto manualPreviewRequest = new CodeRulePreviewRequestDto();
                manualPreviewRequest.setContext(lovContext);
                manualPreviewRequest.setManualCode(previewItem.getManualCode());
                CodeRulePreviewResponseDto manualPreview = metaCodeRuleService.preview(lovRuleCode, manualPreviewRequest);
                previewItem.setSuggestedCode(manualPreview.getExamples() == null || manualPreview.getExamples().isEmpty()
                        ? previewItem.getManualCode()
                        : manualPreview.getExamples().get(0));
                continue;
            }

            if (previewItem.getName() != null || previewItem.getLabel() != null) {
                autoIndexes.add(index);
            }
        }

        List<String> warnings = Collections.emptyList();
        Map<String, String> resolvedContext = lovContext;
        String resolvedSequenceScope = null;
        String resolvedPeriodKey = null;
        if (!autoIndexes.isEmpty()) {
            CodeRulePreviewRequestDto autoPreviewRequest = new CodeRulePreviewRequestDto();
            autoPreviewRequest.setContext(lovContext);
            autoPreviewRequest.setCount(autoIndexes.size());
            CodeRulePreviewResponseDto autoPreview = metaCodeRuleService.preview(lovRuleCode, autoPreviewRequest);
            warnings = autoPreview.getWarnings();
            resolvedContext = autoPreview.getResolvedContext();
            resolvedSequenceScope = autoPreview.getResolvedSequenceScope();
            resolvedPeriodKey = autoPreview.getResolvedPeriodKey();

            List<String> examples = autoPreview.getExamples() == null ? Collections.emptyList() : autoPreview.getExamples();
            for (int i = 0; i < autoIndexes.size(); i++) {
                CreateAttributeCodePreviewResponseDto.LovValueCodePreviewItem previewItem = previewItems.get(autoIndexes.get(i));
                previewItem.setSuggestedCode(i < examples.size() ? examples.get(i) : null);
            }
        }

        return new LovValuePreviewResult(
                lovRuleCode,
                Boolean.TRUE.equals(lovRuleDetail.getAllowManualOverride()),
                warnings,
                resolvedContext,
                resolvedSequenceScope,
                resolvedPeriodKey,
                previewItems
        );
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private MetaCodeRuleService.GeneratedCodeResult resolveAttributeKeyForCreate(MetaCategoryDef categoryDef,
                                                                                 MetaAttributeUpsertRequestDto req,
                                                                                 String operator) {
        String explicitMode = trimToNull(req.getGenerationMode());
        String generationMode = explicitMode == null
                ? (isBlank(req.getKey()) ? "AUTO" : "MANUAL")
                : explicitMode.trim().toUpperCase(Locale.ROOT);
        Map<String, String> context = buildCodeContext(categoryDef, null);
        boolean freezeKey = Boolean.TRUE.equals(req.getFreezeKey());

        return switch (generationMode) {
            case "AUTO" -> {
                if (!isBlank(req.getKey())) {
                    throw new IllegalArgumentException(
                            "Attribute key must not be provided when generationMode is AUTO, but received: " + req.getKey());
                }
                String ruleCode = metaCodeRuleSetService.resolveAttributeRuleCode(categoryDef.getBusinessDomain());
                yield metaCodeRuleService.generateCode(ruleCode, "ATTRIBUTE", null, context, null, operator, freezeKey);
            }
            case "AUTO_RESERVED" -> {
                String ruleCode = metaCodeRuleSetService.resolveAttributeRuleCode(categoryDef.getBusinessDomain());
                Integer ruleVersion = metaCodeRuleService.detail(ruleCode).getLatestVersionNo();
                yield new MetaCodeRuleService.GeneratedCodeResult(
                        requireValue(req.getKey(), "key"),
                        ruleCode,
                        ruleVersion,
                        false,
                        freezeKey
                );
            }
            case "MANUAL" -> {
                String ruleCode = metaCodeRuleSetService.resolveAttributeRuleCode(categoryDef.getBusinessDomain());
                yield metaCodeRuleService.generateCode(
                        ruleCode,
                        "ATTRIBUTE",
                        null,
                        context,
                        requireValue(req.getKey(), "key"),
                        operator,
                        freezeKey
                );
            }
            default -> throw new IllegalArgumentException("unsupported generationMode: " + generationMode);
        };
    }

    private String resolveLovBindingKey(String requestLovKey,
                                        String requestGenerationMode,
                                        String attributeKey,
                                        String existingLovKey) {
        String explicitMode = trimToNull(requestGenerationMode);
        String generationMode = explicitMode == null
                ? (isBlank(requestLovKey) ? "AUTO" : "MANUAL")
                : explicitMode.trim().toUpperCase(Locale.ROOT);
        return switch (generationMode) {
            case "AUTO" -> {
                if (!isBlank(requestLovKey)) {
                    throw new IllegalArgumentException(
                            "lovKey must be empty when lovGenerationMode=AUTO, but received lovKey: '" + requestLovKey + "'");
                }
                if (!isBlank(existingLovKey)) {
                    yield existingLovKey;
                }
                yield buildInternalLovBindingKey(attributeKey);
            }
            case "MANUAL" -> requireValue(requestLovKey, "lovKey");
            default -> throw new IllegalArgumentException("unsupported lovGenerationMode: " + generationMode);
        };
    }

    private Map<String, String> buildCodeContext(MetaCategoryDef categoryDef, String attributeKey) {
        java.util.LinkedHashMap<String, String> context = new java.util.LinkedHashMap<>();
        context.put("BUSINESS_DOMAIN", categoryDef.getBusinessDomain());
        context.put("CATEGORY_CODE", categoryDef.getCodeKey());
        if (!isBlank(attributeKey)) {
            context.put("ATTRIBUTE_CODE", attributeKey);
        }
        return context;
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

    private String buildInternalLovBindingKey(String attributeKey) {
        return requireValue(attributeKey, "attributeKey") + "_LOV";
    }

    private Map<String, String> extractExistingLovCodes(MetaLovVersion latest) {
        LinkedHashMap<String, String> codes = new LinkedHashMap<>();
        if (latest == null || isBlank(latest.getValueJson())) {
            return codes;
        }
        try {
            var values = objectMapper.readTree(latest.getValueJson()).path("values");
            if (!values.isArray()) {
                return codes;
            }
            values.forEach(node -> {
                String name = trimToNull(node.path("name").asText(null));
                String code = trimToNull(node.path("code").asText(null));
                if (name != null && code != null && !codes.containsKey(name)) {
                    codes.put(name, code);
                }
            });
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
        return codes;
    }

    private void validateLovValueCodesUnique(String businessDomain, MetaLovDef currentLovDef, String valueJson) {
        if (isBlank(businessDomain) || isBlank(valueJson)) {
            return;
        }
        Map<String, String> incomingCodes = extractLovCodeToName(valueJson);
        if (incomingCodes.isEmpty()) {
            return;
        }
        for (MetaLovDef lovDef : lovDefRepository.findByBusinessDomain(businessDomain)) {
            if (lovDef == null || isDeleted(lovDef)) {
                continue;
            }
            if (currentLovDef != null && Objects.equals(lovDef.getId(), currentLovDef.getId())) {
                continue;
            }
            MetaLovVersion latest = lovVersionRepository.findLatestByDef(lovDef).orElse(null);
            if (latest == null || isBlank(latest.getValueJson())) {
                continue;
            }
            Map<String, String> existingCodes = extractLovCodeToName(latest.getValueJson());
            for (String code : incomingCodes.keySet()) {
                if (existingCodes.containsKey(code)) {
                    throw new IllegalArgumentException("enum option code already exists in business domain: " + code);
                }
            }
        }
    }

    private Map<String, String> extractLovCodeToName(String valueJson) {
        LinkedHashMap<String, String> codes = new LinkedHashMap<>();
        if (isBlank(valueJson)) {
            return codes;
        }
        try {
            var values = objectMapper.readTree(valueJson).path("values");
            if (!values.isArray()) {
                return codes;
            }
            values.forEach(node -> {
                String code = trimToNull(node.path("code").asText(null));
                String name = trimToNull(node.path("name").asText(null));
                if (code != null && !codes.containsKey(code)) {
                    codes.put(code, name);
                }
            });
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
        return codes;
    }

    private String requireValue(String value, String fieldName) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }

    private String trimToNull(String s) {
        if (s == null)
            return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private record LovValuePreviewResult(
            String ruleCode,
            Boolean allowManualOverride,
            List<String> warnings,
            Map<String, String> resolvedContext,
            String resolvedSequenceScope,
            String resolvedPeriodKey,
            List<CreateAttributeCodePreviewResponseDto.LovValueCodePreviewItem> items) {

        private static LovValuePreviewResult empty() {
            return new LovValuePreviewResult(null, null, Collections.emptyList(), null, null, null,
                    Collections.emptyList());
        }

        private static LovValuePreviewResult empty(String ruleCode, Boolean allowManualOverride) {
            return new LovValuePreviewResult(ruleCode, allowManualOverride, Collections.emptyList(), null, null, null,
                    Collections.emptyList());
        }
    }
}
