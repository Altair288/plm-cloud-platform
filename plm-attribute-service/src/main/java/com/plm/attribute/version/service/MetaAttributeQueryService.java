package com.plm.attribute.version.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.common.api.dto.MetaAttributeDefDetailDto;
import com.plm.common.api.dto.MetaAttributeDefListItemDto;
import com.plm.common.api.dto.MetaAttributeVersionSummaryDto;
import com.plm.common.version.domain.MetaAttributeDef;
import com.plm.common.version.domain.MetaAttributeVersion;
import com.plm.infrastructure.version.repository.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class MetaAttributeQueryService {

    private final MetaAttributeDefRepository attributeDefRepository;
    private final MetaAttributeVersionRepository attributeVersionRepository;
    private final MetaLovDefRepository lovDefRepository;
    private final MetaLovVersionRepository lovVersionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PersistenceContext
    private EntityManager em;

    public MetaAttributeQueryService(MetaAttributeDefRepository attributeDefRepository,
            MetaAttributeVersionRepository attributeVersionRepository,
            MetaLovDefRepository lovDefRepository,
            MetaLovVersionRepository lovVersionRepository) {
        this.attributeDefRepository = attributeDefRepository;
        this.attributeVersionRepository = attributeVersionRepository;
        this.lovDefRepository = lovDefRepository;
        this.lovVersionRepository = lovVersionRepository;
    }

    public Page<MetaAttributeDefListItemDto> list(String categoryCodePrefix, String keyword, Pageable pageable) {
        // Build JPQL dynamically (simple where conditions)
        String base = "select d from MetaAttributeDef d join d.categoryDef c";
        List<String> cond = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (categoryCodePrefix != null && !categoryCodePrefix.isBlank()) {
            cond.add("c.code like :catPrefix");
            params.put("catPrefix", categoryCodePrefix + "%");
        }
        // keyword matches displayName inside latest version's JSON -> fallback: load
        // all page then filter in memory
        String where = cond.isEmpty() ? "" : (" where " + String.join(" and ", cond));
        String order = " order by d.createdAt desc";
        TypedQuery<MetaAttributeDef> q = em.createQuery(base + where + order, MetaAttributeDef.class);
        params.forEach(q::setParameter);
        int total = q.getResultList().size(); // simple count; can optimize with count query
        q.setFirstResult((int) pageable.getOffset());
        q.setMaxResults(pageable.getPageSize());
        List<MetaAttributeDef> defs = q.getResultList();

        // Load latest versions & lov defs
        List<MetaAttributeDefListItemDto> items = new ArrayList<>();
        for (MetaAttributeDef def : defs) {
            MetaAttributeVersion ver = attributeVersionRepository.findLatestByDef(def).orElse(null);
            String displayName = null;
            String unit = null;
            String lovKey = null;
            if (ver != null) {
                ParsedAttributeJson parsed = parseAttributeJson(ver.getStructureJson());
                displayName = parsed.displayName;
                unit = parsed.unit;
                lovKey = parsed.lovKey;
            }
            if (keyword != null && !keyword.isBlank() && (displayName == null || !displayName.contains(keyword))) {
                continue; // Skip; will reduce page size (simpler initial implementation)
            }
            items.add(new MetaAttributeDefListItemDto(
                    def.getKey(),
                    lovKey,
                    def.getCategoryDef().getCodeKey(),
                    def.getStatus(),
                    ver != null ? ver.getVersionNo() : null,
                    displayName,
                    unit,
                    def.getLovFlag(),
                    def.getCreatedAt()));
        }
        // Adjust total if keyword filtered
        int filteredTotal = (keyword != null && !keyword.isBlank()) ? items.size() : total;
        return new PageImpl<>(items, pageable, filteredTotal);
    }

    public MetaAttributeDefDetailDto detail(String attrKey) {
        // attrKey is globally unique; fetch by unique key requires custom query;
        // fallback: scan page (simplified)
        MetaAttributeDef def = em
                .createQuery("select d from MetaAttributeDef d where d.key = :k", MetaAttributeDef.class)
                .setParameter("k", attrKey)
                .getResultStream().findFirst().orElse(null);
        if (def == null)
            return null;
        MetaAttributeVersion latest = attributeVersionRepository.findLatestByDef(def).orElse(null);
        MetaAttributeDefDetailDto dto = new MetaAttributeDefDetailDto();
        dto.setKey(def.getKey());
        dto.setCategoryCode(def.getCategoryDef().getCodeKey());
        dto.setStatus(def.getStatus());
        dto.setCreatedAt(def.getCreatedAt());
        dto.setHasLov(def.getLovFlag());
        MetaAttributeDefDetailDto.LatestVersion lv = new MetaAttributeDefDetailDto.LatestVersion();
        if (latest != null) {
            ParsedAttributeJson parsed = parseAttributeJson(latest.getStructureJson());
            lv.setVersionNo(latest.getVersionNo());
            lv.setDisplayName(parsed.displayName);
            lv.setDataType(parsed.dataType);
            lv.setUnit(parsed.unit);
            lv.setLovKey(parsed.lovKey);
            dto.setLovKey(parsed.lovKey);
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
                .createQuery("select d from MetaAttributeDef d where d.key = :k", MetaAttributeDef.class)
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
            p.unit = text(node, "unit");
            p.lovKey = text(node, "lovKey");
            p.dataType = text(node, "dataType");
        } catch (IOException ignored) {
        }
        return p;
    }

    private String text(JsonNode node, String field) {
        return node != null && node.has(field) ? node.get(field).asText() : null;
    }

    private static class ParsedAttributeJson {
        String displayName;
        String unit;
        String lovKey;
        String dataType;
    }
}
