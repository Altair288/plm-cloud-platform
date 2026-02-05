package com.plm.attribute.version.service;

import com.plm.common.api.dto.MetaCategoryBrowseNodeDto;
import com.plm.common.api.dto.MetaCategoryClassGroupDto;
import com.plm.common.api.dto.MetaCategorySearchHitDto;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.common.version.domain.MetaCategoryVersion;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import com.plm.infrastructure.version.repository.MetaCategoryVersionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class MetaCategoryBrowseService {

  private final MetaCategoryDefRepository defRepository;
  private final MetaCategoryVersionRepository versionRepository;

  public MetaCategoryBrowseService(MetaCategoryDefRepository defRepository,
      MetaCategoryVersionRepository versionRepository) {
    this.defRepository = defRepository;
    this.versionRepository = versionRepository;
  }

  @Transactional(readOnly = true)
  public List<MetaCategoryBrowseNodeDto> listUnspscSegments() {
    List<MetaCategoryDef> defs = defRepository.findByParentIsNullOrderBySortOrderAscCodeKeyAsc();
    Map<UUID, String> titleByDefId = loadLatestTitles(defs);
    return defs.stream()
        .map(d -> toBrowseNode(d, titleByDefId.get(d.getId())))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<MetaCategoryBrowseNodeDto> listChildrenByCodeKey(String parentCodeKey) {
    MetaCategoryDef parent = findDefByCodeKey(parentCodeKey);
    List<MetaCategoryDef> children = defRepository.findByParentIdOrderBySortOrderAscCodeKeyAsc(parent.getId());
    Map<UUID, String> titleByDefId = loadLatestTitles(children);
    return children.stream()
        .map(d -> toBrowseNode(d, titleByDefId.get(d.getId())))
        .toList();
  }

  /**
   * Family -> (Class + Commodities)
   */
  @Transactional(readOnly = true)
  public List<MetaCategoryClassGroupDto> listClassesWithCommodities(String familyCodeKey) {
    MetaCategoryDef family = findDefByCodeKey(familyCodeKey);

    List<MetaCategoryDef> classes = defRepository.findByParentIdOrderBySortOrderAscCodeKeyAsc(family.getId());
    if (classes.isEmpty())
      return List.of();

    List<UUID> classIds = classes.stream().map(MetaCategoryDef::getId).toList();
    List<MetaCategoryDef> commodities = defRepository.findByParentIdInOrderBySortOrderAscCodeKeyAsc(classIds);

    List<MetaCategoryDef> all = new ArrayList<>(classes.size() + commodities.size());
    all.addAll(classes);
    all.addAll(commodities);
    Map<UUID, String> titleByDefId = loadLatestTitles(all);

    Map<UUID, MetaCategoryClassGroupDto> groupByClassId = new LinkedHashMap<>();
    for (MetaCategoryDef clazz : classes) {
      MetaCategoryClassGroupDto group = new MetaCategoryClassGroupDto();
      group.setClazz(toBrowseNode(clazz, titleByDefId.get(clazz.getId())));
      groupByClassId.put(clazz.getId(), group);
    }

    for (MetaCategoryDef commodity : commodities) {
      MetaCategoryDef parent = commodity.getParent();
      if (parent == null)
        continue;
      MetaCategoryClassGroupDto group = groupByClassId.get(parent.getId());
      if (group == null)
        continue;
      group.getCommodities().add(toBrowseNode(commodity, titleByDefId.get(commodity.getId())));
    }

    return new ArrayList<>(groupByClassId.values());
  }

  @Transactional(readOnly = true)
  public List<MetaCategorySearchHitDto> searchUnspsc(String q, String scopeCodeKey, int limit) {
    String query = (q == null) ? "" : q.trim();
    if (query.isEmpty()) {
      return List.of();
    }

    int safeLimit = Math.max(1, Math.min(limit, 200));

    UUID scopeId = null;
    if (scopeCodeKey != null && !scopeCodeKey.isBlank()) {
      scopeId = findDefByCodeKey(scopeCodeKey.trim()).getId();
    }

    String like = "%" + query + "%";
    String prefix = query + "%";

    List<MetaCategoryDef> defs = defRepository.searchUnspsc(
        prefix,
        like,
        scopeId,
        PageRequest.of(0, safeLimit));
    if (defs.isEmpty())
      return List.of();

    Map<UUID, String> titleByDefId = loadLatestTitles(defs);
    return defs.stream().map(d -> {
      MetaCategorySearchHitDto dto = new MetaCategorySearchHitDto();
      dto.setKey(d.getCodeKey());
      dto.setCode(coalesceCode(d));
      dto.setTitle(coalesceTitle(d, titleByDefId.get(d.getId())));
      dto.setDepth(d.getDepth());
      dto.setFullPathName(d.getFullPathName());
      return dto;
    }).toList();
  }

  private MetaCategoryDef findDefByCodeKey(String codeKey) {
    return defRepository.findByCodeKey(codeKey)
        .orElseThrow(() -> new IllegalArgumentException("category not found: codeKey=" + codeKey));
  }

  private Map<UUID, String> loadLatestTitles(Collection<MetaCategoryDef> defs) {
    if (defs == null || defs.isEmpty())
      return Collections.emptyMap();
    List<MetaCategoryVersion> versions = versionRepository.findByCategoryDefInAndIsLatestTrue(defs);
    Map<UUID, String> map = new HashMap<>(versions.size() * 2);
    for (MetaCategoryVersion v : versions) {
      if (v.getCategoryDef() == null)
        continue;
      map.put(v.getCategoryDef().getId(), v.getDisplayName());
    }
    return map;
  }

  private MetaCategoryBrowseNodeDto toBrowseNode(MetaCategoryDef def, String latestTitle) {
    MetaCategoryBrowseNodeDto dto = new MetaCategoryBrowseNodeDto();
    dto.setKey(def.getCodeKey());
    dto.setCode(coalesceCode(def));
    dto.setTitle(coalesceTitle(def, latestTitle));
    dto.setHasChildren(def.getIsLeaf() == null ? null : !def.getIsLeaf());
    dto.setDepth(def.getDepth());
    dto.setFullPathName(def.getFullPathName());
    return dto;
  }

  private String coalesceTitle(MetaCategoryDef def, String latestTitle) {
    if (latestTitle != null && !latestTitle.isBlank())
      return latestTitle;
    return def.getCodeKey();
  }

  private String coalesceCode(MetaCategoryDef def) {
    String external = def.getExternalCode();
    return (external == null || external.isBlank()) ? def.getCodeKey() : external;
  }
}
