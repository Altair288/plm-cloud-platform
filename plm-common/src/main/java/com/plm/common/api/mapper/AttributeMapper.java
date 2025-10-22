package com.plm.common.api.mapper;

import com.plm.common.api.dto.AttributeDto;
import com.plm.common.domain.Attribute;

public class AttributeMapper {
    public static AttributeDto toDto(Attribute e) {
        if (e == null) return null;
        AttributeDto d = new AttributeDto();
        d.setId(e.getId());
        d.setCategoryId(e.getCategoryId());
        d.setCode(e.getCode());
        d.setName(e.getName());
        d.setType(e.getType());
        d.setUnit(e.getUnit());
        d.setLovCode(e.getLovCode());
        d.setSortOrder(e.getSortOrder());
        d.setDescription(e.getDescription());
        d.setCreatedAt(e.getCreatedAt());
        return d;
    }
}
