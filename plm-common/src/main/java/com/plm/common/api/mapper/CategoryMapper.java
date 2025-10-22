package com.plm.common.api.mapper;

import com.plm.common.api.dto.CategoryDto;
import com.plm.common.domain.Category;

public class CategoryMapper {
    public static CategoryDto toDto(Category e) {
        if (e == null) return null;
        CategoryDto d = new CategoryDto();
        d.setId(e.getId());
        d.setCode(e.getCode());
        d.setName(e.getName());
        d.setDescription(e.getDescription());
        d.setCreatedAt(e.getCreatedAt());
        return d;
    }
}
