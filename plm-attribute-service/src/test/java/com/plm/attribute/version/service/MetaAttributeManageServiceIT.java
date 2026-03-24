package com.plm.attribute.version.service;

import com.plm.common.api.dto.attribute.MetaAttributeDefDetailDto;
import com.plm.common.api.dto.attribute.MetaAttributeUpsertRequestDto;
import com.plm.common.api.dto.category.CreateCategoryRequestDto;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.main.lazy-initialization=true"
)
@ActiveProfiles("dev")
@Transactional
class MetaAttributeManageServiceIT {

    @Autowired
    private MetaCategoryCrudService categoryCrudService;

    @Autowired
    private MetaAttributeManageService attributeManageService;

    @Test
    void createAttribute_shouldAutoGenerateAttributeKeyAndLovKey() {
        String categoryCode = "MAT-ATTR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        createCategory(categoryCode, "Attribute Auto Category");

        MetaAttributeUpsertRequestDto request = new MetaAttributeUpsertRequestDto();
        request.setDisplayName("Color");
        request.setAttributeField("color");
        request.setDataType("enum");
        request.setLovValues(List.of(lovValue("RED", "Red"), lovValue("BLUE", "Blue")));

        MetaAttributeDefDetailDto detail = attributeManageService.create(categoryCode, request, "it-user");

        Assertions.assertNotNull(detail);
        Assertions.assertNotNull(detail.getKey());
        Assertions.assertTrue(detail.getKey().startsWith("ATTR_"), "actual key=" + detail.getKey());
        Assertions.assertNotNull(detail.getLovKey());
        Assertions.assertEquals(detail.getKey() + "_LOV", detail.getLovKey(),
            "actual key=" + detail.getKey() + ", actual lovKey=" + detail.getLovKey());
        Assertions.assertTrue(Boolean.TRUE.equals(detail.getHasLov()));
        Assertions.assertEquals(2, detail.getLovValues().size());
    }

    @Test
    void createAttribute_shouldRespectManualAttributeKeyAndLovKey() {
        String categoryCode = "MAT-ATTR-MANUAL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        createCategory(categoryCode, "Attribute Manual Category");

        MetaAttributeUpsertRequestDto request = new MetaAttributeUpsertRequestDto();
        request.setKey("ATTR_MANUAL_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase());
        request.setDisplayName("Status");
        request.setAttributeField("status");
        request.setDataType("enum");
        request.setLovKey(request.getKey() + "_CUSTOM_LOV");
        request.setLovValues(List.of(lovValue("A", "Active"), lovValue("I", "Inactive")));

        MetaAttributeDefDetailDto detail = attributeManageService.create(categoryCode, request, "it-user");

        Assertions.assertEquals(request.getKey(), detail.getKey());
        Assertions.assertEquals(request.getLovKey(), detail.getLovKey());
        Assertions.assertEquals(2, detail.getLovValues().size());
    }

    @Test
    void updateAttribute_shouldRejectExplicitLovKeyWhenLovGenerationModeIsAuto() {
        String categoryCode = "MAT-ATTR-UPDATE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        createCategory(categoryCode, "Attribute Update Category");

        MetaAttributeUpsertRequestDto createRequest = new MetaAttributeUpsertRequestDto();
        createRequest.setDisplayName("Lifecycle");
        createRequest.setAttributeField("lifecycle");
        createRequest.setDataType("enum");
        createRequest.setLovValues(List.of(lovValue("DRAFT", "Draft"), lovValue("ACTIVE", "Active")));

        MetaAttributeDefDetailDto created = attributeManageService.create(categoryCode, createRequest, "it-user");

        MetaAttributeUpsertRequestDto updateRequest = new MetaAttributeUpsertRequestDto();
        updateRequest.setDisplayName("Lifecycle");
        updateRequest.setAttributeField("lifecycle");
        updateRequest.setDataType("enum");
        updateRequest.setLovGenerationMode("AUTO");
        updateRequest.setLovKey(created.getKey() + "_MANUAL_OVERRIDE");
        updateRequest.setLovValues(List.of(lovValue("DRAFT", "Draft"), lovValue("ACTIVE", "Active")));

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> attributeManageService.update(categoryCode, created.getKey(), updateRequest, "it-user"));
        Assertions.assertTrue(exception.getMessage().contains("lovKey must be empty"));
    }

    private void createCategory(String code, String name) {
        CreateCategoryRequestDto request = new CreateCategoryRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setCode(code);
        request.setName(name);
        categoryCrudService.create(request, "it-user");
    }

    private MetaAttributeUpsertRequestDto.LovValueUpsertItem lovValue(String code, String name) {
        MetaAttributeUpsertRequestDto.LovValueUpsertItem item = new MetaAttributeUpsertRequestDto.LovValueUpsertItem();
        item.setCode(code);
        item.setName(name);
        item.setLabel(name);
        return item;
    }
}