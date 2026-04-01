package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.MetaAttributeQueryService;
import com.plm.attribute.version.service.MetaCategoryCrudService;
import com.plm.common.api.dto.attribute.MetaAttributeDefDetailDto;
import com.plm.common.api.dto.category.CreateCategoryRequestDto;
import com.plm.common.version.util.CodeRuleSupport;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.main.lazy-initialization=true",
                "spring.main.allow-bean-definition-overriding=true"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class MetaAttributeImportControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MetaCategoryCrudService categoryCrudService;

    @Autowired
    private MetaAttributeQueryService queryService;

    @Test
    void importEndpoint_shouldDefaultCreatedByToSystem() throws Exception {
        String categoryCode = "CATHTTP";
        createCategory(categoryCode, "Import HTTP Category");

        mockMvc.perform(multipart("/api/meta/attributes/import")
                        .file(createImportFile(categoryCode, "Color", "RED", "BLUE"))
                        .param("businessDomain", "MATERIAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCount").value(0))
                .andExpect(jsonPath("$.createdAttributeDefs").value(1))
                .andExpect(jsonPath("$.createdLovDefs").value(1));

        String expectedAttrKey = "ATTR-" + categoryCode + "-" + String.format("%0" + CodeRuleSupport.ATTRIBUTE_SEQUENCE_WIDTH + "d", 1);
        MetaAttributeDefDetailDto detail = queryService.detail("MATERIAL", expectedAttrKey, true);
        Assertions.assertEquals("system", detail.getCreatedBy());
        Assertions.assertEquals(2, detail.getLovValues().size());
    }

    @Test
    void importEndpoint_shouldRejectBlankBusinessDomain() throws Exception {
        mockMvc.perform(multipart("/api/meta/attributes/import")
                        .file(createImportFile("CATHTTP", "Color", "RED", "BLUE"))
                        .param("businessDomain", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value(containsString("businessDomain is required")));
    }

    private void createCategory(String code, String name) {
        CreateCategoryRequestDto request = new CreateCategoryRequestDto();
        request.setBusinessDomain("MATERIAL");
        request.setCode(code);
        request.setName(name);
        categoryCrudService.create(request, "it-user");
    }

    private MockMultipartFile createImportFile(String categoryCode, String attributeName, String... values) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("attr");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("分类编号");
            header.createCell(1).setCellValue("分类名称");
            header.createCell(2).setCellValue("属性名称");
            header.createCell(3).setCellValue("属性类型");
            header.createCell(4).setCellValue("单位");
            for (int i = 0; i < values.length; i++) {
                header.createCell(5 + i).setCellValue("枚举值" + (i + 1));
            }

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(categoryCode);
            row.createCell(2).setCellValue(attributeName);
            row.createCell(3).setCellValue("enum");
            for (int i = 0; i < values.length; i++) {
                row.createCell(5 + i).setCellValue(values[i]);
            }

            workbook.write(output);
            return new MockMultipartFile(
                    "file",
                    "attribute-import.xlsx",
                    MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    output.toByteArray());
        }
    }
}