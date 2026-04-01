package com.plm.attribute.version.service;

import com.plm.common.api.dto.attribute.MetaAttributeDefDetailDto;
import com.plm.common.api.dto.attribute.MetaAttributeDefListItemDto;
import com.plm.common.api.dto.attribute.MetaAttributeUpsertRequestDto;
import com.plm.common.api.dto.category.CreateCategoryRequestDto;
import com.plm.common.api.dto.category.MetaCategoryDetailDto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.lazy-initialization=true",
                "spring.main.allow-bean-definition-overriding=true"
        }
)
@ActiveProfiles("dev")
@Transactional
class MetaAttributeQueryServiceIT {

    @Autowired
    private MetaCategoryCrudService categoryCrudService;

    @Autowired
    private MetaAttributeManageService attributeManageService;

    @Autowired
    private MetaAttributeQueryService queryService;

    @Test
    void list_shouldFilterByExactCategoryCodeInsteadOfPrefix() {
        String rootCode = "P01" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        String childCode = rootCode + "_01";

        MetaCategoryDetailDto root = createCategory(rootCode, "Root Category", null);
        createCategory(childCode, "Child Category", root.getId());

        MetaAttributeDefDetailDto rootAttribute = attributeManageService.create(rootCode,
                attribute("Root Attribute", "rootAttribute"),
                "it-user");
        attributeManageService.create(childCode,
                attribute("Child Attribute", "childAttribute"),
                "it-user");

        Page<MetaAttributeDefListItemDto> page = queryService.list(
                rootCode,
                null,
                null,
                null,
                null,
                null,
                false,
                PageRequest.of(0, 20));

        Assertions.assertEquals(1, page.getTotalElements());
        Assertions.assertEquals(List.of(rootAttribute.getKey()),
                page.getContent().stream().map(MetaAttributeDefListItemDto::getKey).toList());
        Assertions.assertTrue(page.getContent().stream().allMatch(item -> rootCode.equals(item.getCategoryCode())));
    }

    private MetaCategoryDetailDto createCategory(String code, String name, UUID parentId) {
        CreateCategoryRequestDto request = new CreateCategoryRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setCode(code);
        request.setName(name);
        request.setParentId(parentId);
        return categoryCrudService.create(request, "it-user");
    }

    private MetaAttributeUpsertRequestDto attribute(String displayName, String attributeField) {
        MetaAttributeUpsertRequestDto request = new MetaAttributeUpsertRequestDto();
        request.setDisplayName(displayName);
        request.setAttributeField(attributeField);
        request.setDataType("text");
        return request;
    }
}