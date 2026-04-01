package com.plm.attribute.version.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.common.api.dto.attribute.MetaAttributeDefDetailDto;
import com.plm.common.api.dto.attribute.MetaAttributeDefListItemDto;
import com.plm.common.api.dto.attribute.MetaAttributeVersionSummaryDto;
import com.plm.common.version.domain.MetaAttributeDef;
import com.plm.common.version.domain.MetaAttributeVersion;
import com.plm.common.version.domain.MetaLovDef;
import com.plm.common.version.domain.MetaLovVersion;
import com.plm.infrastructure.version.repository.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class MetaAttributeQueryService {

    private final MetaAttributeVersionRepository attributeVersionRepository;
    private final MetaLovDefRepository lovDefRepository;
    private final MetaLovVersionRepository lovVersionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PersistenceContext
    private EntityManager em;

    public MetaAttributeQueryService(MetaAttributeVersionRepository attributeVersionRepository,
            MetaLovDefRepository lovDefRepository,
            MetaLovVersionRepository lovVersionRepository) {
        this.attributeVersionRepository = attributeVersionRepository;
        this.lovDefRepository = lovDefRepository;
        this.lovVersionRepository = lovVersionRepository;
    }

    public Page<MetaAttributeDefListItemDto> list(
            String categoryCode,
            String keyword,
            String dataType,
            Boolean required,
            Boolean unique,
            Boolean searchable,
            boolean includeDeleted,
            Pageable pageable) {
        return attributeVersionRepository.searchLatestListItems(
                categoryCode,
                keyword,
                dataType,
                required,
                unique,
                searchable,
                includeDeleted,
                pageable);
    }

    public MetaAttributeDefDetailDto detail(String attrKey) {
        return detail(attrKey, false);
    }

    public MetaAttributeDefDetailDto detail(String attrKey, boolean includeValues) {
        // attrKey is globally unique; fetch by unique key requires custom query;
        // fallback: scan page (simplified)
        MetaAttributeDef def = em
            .createQuery("select d from MetaAttributeDef d where d.key = :k and lower(d.status) <> 'deleted'", MetaAttributeDef.class)
                .setParameter("k", attrKey)
                .getResultStream().findFirst().orElse(null);
        if (def == null)
            return null;
        MetaAttributeVersion latest = attributeVersionRepository.findLatestByDef(def).orElse(null);
        MetaAttributeDefDetailDto dto = new MetaAttributeDefDetailDto();
        dto.setKey(def.getKey());
        dto.setCategoryCode(def.getCategoryDef().getCodeKey());
        dto.setStatus(def.getStatus());
        dto.setCreatedBy(def.getCreatedBy());
        dto.setCreatedAt(def.getCreatedAt());
        dto.setHasLov(def.getLovFlag());
        MetaAttributeDefDetailDto.LatestVersion lv = new MetaAttributeDefDetailDto.LatestVersion();
        if (latest != null) {
            ParsedAttributeJson parsed = parseAttributeJson(latest.getStructureJson());
            lv.setVersionNo(latest.getVersionNo());
            lv.setDisplayName(parsed.displayName);
            lv.setDescription(parsed.description);
            lv.setAttributeField(parsed.attributeField);
            lv.setDataType(parsed.dataType);
            lv.setUnit(parsed.unit);
            lv.setDefaultValue(parsed.defaultValue);
            lv.setRequired(parsed.required);
            lv.setUnique(parsed.unique);
            lv.setHidden(parsed.hidden);
            lv.setReadOnly(parsed.readOnly);
            lv.setSearchable(parsed.searchable);
            lv.setLovKey(parsed.lovKey);
            lv.setMinValue(parsed.minValue);
            lv.setMaxValue(parsed.maxValue);
            lv.setStep(parsed.step);
            lv.setPrecision(parsed.precision);
            lv.setTrueLabel(parsed.trueLabel);
            lv.setFalseLabel(parsed.falseLabel);
            lv.setCreatedBy(latest.getCreatedBy());
            lv.setCreatedAt(latest.getCreatedAt());
            dto.setLovKey(parsed.lovKey);

            // 版本语义下：最新版本创建人/时间即为“修改人/修改时间”
            dto.setModifiedBy(latest.getCreatedBy());
            dto.setModifiedAt(latest.getCreatedAt());

            if (includeValues && parsed.lovKey != null) {
                MetaLovDef lovDef = lovDefRepository.findByAttributeDefAndKey(def, parsed.lovKey).orElse(null);
                if (lovDef != null) {
                    MetaLovVersion lovLatest = lovVersionRepository.findLatestByDef(lovDef).orElse(null);
                    if (lovLatest != null && lovLatest.getValueJson() != null) {
                        dto.setLovValues(parseLovValues(lovLatest.getValueJson()));
                    }
                }
            }
        }
        dto.setLatestVersion(lv);
        // Fill versions summary list
        List<MetaAttributeVersion> versions = em
                .createQuery("select v from MetaAttributeVersion v where v.attributeDef = :d order by v.versionNo asc",
                        MetaAttributeVersion.class)
                .setParameter("d", def).getResultList();
        List<MetaAttributeVersionSummaryDto> versionDtos = versions.stream()
                .map(v -> new MetaAttributeVersionSummaryDto(
                        v.getVersionNo(), v.getHash(), v.getIsLatest(), v.getCreatedAt()))
                .collect(Collectors.toList());
        dto.setVersions(versionDtos);
        return dto;
    }

    public List<MetaAttributeVersionSummaryDto> versions(String attrKey) {
        MetaAttributeDef def = em
            .createQuery("select d from MetaAttributeDef d where d.key = :k and lower(d.status) <> 'deleted'", MetaAttributeDef.class)
                .setParameter("k", attrKey).getResultStream().findFirst().orElse(null);
        if (def == null)
            return Collections.emptyList();
        List<MetaAttributeVersion> versions = em
                .createQuery("select v from MetaAttributeVersion v where v.attributeDef = :d order by v.versionNo asc",
                        MetaAttributeVersion.class)
                .setParameter("d", def).getResultList();
        return versions.stream().map(v -> new MetaAttributeVersionSummaryDto(v.getVersionNo(), v.getHash(),
                v.getIsLatest(), v.getCreatedAt())).toList();
    }

    private ParsedAttributeJson parseAttributeJson(String json) {
        ParsedAttributeJson p = new ParsedAttributeJson();
        if (json == null)
            return p;
        try {
            JsonNode node = objectMapper.readTree(json);
            p.displayName = text(node, "displayName");
            p.description = text(node, "description");
            p.attributeField = text(node, "attributeField");
            p.unit = text(node, "unit");
            p.lovKey = text(node, "lovKey");
            p.dataType = text(node, "dataType");
            p.defaultValue = text(node, "defaultValue");

            p.minValue = decimal(node, "minValue");
            p.maxValue = decimal(node, "maxValue");
            p.step = decimal(node, "step");
            p.precision = integer(node, "precision");
            p.trueLabel = text(node, "trueLabel");
            p.falseLabel = text(node, "falseLabel");

            p.required = bool(node, "required");
            p.unique = bool(node, "unique");
            p.hidden = bool(node, "hidden");
            p.readOnly = bool(node, "readOnly");
            p.searchable = bool(node, "searchable");
        } catch (IOException ignored) {
        }
        return p;
    }

    private String text(JsonNode node, String field) {
        return node != null && node.has(field) ? node.get(field).asText() : null;
    }

    private Boolean bool(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull())
            return null;
        return node.get(field).asBoolean();
    }

    private Integer integer(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull())
            return null;
        return node.get(field).asInt();
    }

    private BigDecimal decimal(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull())
            return null;
        try {
            return node.get(field).decimalValue();
        } catch (Exception ex) {
            return null;
        }
    }

    private static class ParsedAttributeJson {
        String displayName;
        String description;
        String attributeField;
        String unit;
        String lovKey;
        String dataType;
        String defaultValue;
        BigDecimal minValue;
        BigDecimal maxValue;
        BigDecimal step;
        Integer precision;
        String trueLabel;
        String falseLabel;
        Boolean required;
        Boolean unique;
        Boolean hidden;
        Boolean readOnly;
        Boolean searchable;
    }

    private List<MetaAttributeDefDetailDto.LovValueItem> parseLovValues(String json) {
        List<MetaAttributeDefDetailDto.LovValueItem> list = new ArrayList<>();
        if (json == null)
            return list;
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode valuesNode = node.get("values");
            if (valuesNode != null && valuesNode.isArray()) {
                for (JsonNode v : valuesNode) {
                    MetaAttributeDefDetailDto.LovValueItem item = new MetaAttributeDefDetailDto.LovValueItem();
                    if (v.has("code"))
                        item.setCode(v.get("code").asText());
                    // 兼容两种字段命名: value/name, sort/order, disabled/active
                    if (v.has("value")) {
                        item.setValue(v.get("value").asText());
                    } else if (v.has("name")) {
                        item.setValue(v.get("name").asText());
                    }
                    if (v.has("label")) {
                        item.setLabel(v.get("label").asText());
                    }
                    if (v.has("sort")) {
                        item.setSort(v.get("sort").asInt());
                    } else if (v.has("order")) {
                        item.setSort(v.get("order").asInt());
                    }
                    if (v.has("disabled")) {
                        item.setDisabled(v.get("disabled").asBoolean());
                    } else if (v.has("active")) {
                        // active=true => disabled=false
                        item.setDisabled(!v.get("active").asBoolean());
                    }
                    list.add(item);
                }
            }
        } catch (IOException ignored) {
        }
        return list;
    }
}
