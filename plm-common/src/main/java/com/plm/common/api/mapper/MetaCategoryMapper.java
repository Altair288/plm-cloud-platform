package com.plm.common.api.mapper;

import com.plm.common.api.dto.MetaCategoryDefDto;
import com.plm.common.api.dto.MetaCategoryVersionDto;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.common.version.domain.MetaCategoryVersion;

public class MetaCategoryMapper {
    public static MetaCategoryDefDto toDefDto(MetaCategoryDef def) {
        if (def == null) return null;
        MetaCategoryDefDto d = new MetaCategoryDefDto();
        d.setId(def.getId());
        d.setCodeKey(def.getCodeKey());
        d.setStatus(def.getStatus());
        d.setPath(def.getPath());
        d.setDepth(def.getDepth());
        d.setSortOrder(def.getSortOrder());
        d.setFullPathName(def.getFullPathName());
        d.setIsLeaf(def.getIsLeaf());
        return d;
    }

    public static MetaCategoryVersionDto toVersionDto(MetaCategoryVersion v) {
        if (v == null) return null;
        MetaCategoryVersionDto d = new MetaCategoryVersionDto();
        d.setId(v.getId());
        d.setCategoryDefId(v.getCategoryDef().getId());
        d.setVersionNo(v.getVersionNo());
        d.setDisplayName(v.getDisplayName());
        d.setIsLatest(v.getIsLatest());
        return d;
    }
}
