package com.plm.attribute.version.controller;

import com.plm.common.api.dto.category.imports.UnspscImportItem;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
public class MetaCategoryImportControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MetaCategoryDefRepository categoryDefRepository;

    @Test
    void importEndpoints_shouldSupportExcelCsvAndJson() throws Exception {
        String suffix = uniqueSuffix();
        String excelCode = "CAT-IMP-XLS-" + suffix;
        String csvRootCode = "CAT-IMP-CSV-R-" + suffix;
        String csvChildCode = "CAT-IMP-CSV-C-" + suffix;
        String jsonCode = "CAT-IMP-JSON-" + suffix;

        mockMvc.perform(multipart("/api/meta/categories/import")
                        .file(createExcelFile(excelCode, "Excel Category " + suffix)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdDefCount").value(1))
                .andExpect(jsonPath("$.createdVersionCount").value(1))
                .andExpect(jsonPath("$.errorCount").value(0));

        MetaCategoryDef excelDef = categoryDefRepository.findByBusinessDomainAndCodeKey("MATERIAL", excelCode).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("system", excelDef.getCreatedBy());

        mockMvc.perform(multipart("/api/meta/categories/import-unspsc-csv")
                        .file(createUnspscCsv(csvRootCode, csvChildCode, suffix))
                        .param("createdBy", "csv-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdDefCount").value(2))
                .andExpect(jsonPath("$.createdVersionCount").value(2))
                .andExpect(jsonPath("$.errorCount").value(0));

        List<UnspscImportItem> items = List.of(unspscItem("json-root-" + suffix, null, jsonCode, "Json Category " + suffix));
        mockMvc.perform(post("/api/meta/categories/import-unspsc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(items)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdDefCount").value(1))
                .andExpect(jsonPath("$.createdVersionCount").value(1))
                .andExpect(jsonPath("$.errorCount").value(0));

        MetaCategoryDef jsonDef = categoryDefRepository.findByBusinessDomainAndCodeKey("MATERIAL", jsonCode).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("system", jsonDef.getCreatedBy());
    }

    @Test
    void hierarchyEndpoints_shouldRebuildClosureAndReturnDescendants() throws Exception {
        String suffix = uniqueSuffix();
        String rootCode = "CAT-HIER-R-" + suffix;
        String childCode = "CAT-HIER-C-" + suffix;

        List<UnspscImportItem> items = List.of(
                unspscItem("root-" + suffix, null, rootCode, "Hierarchy Root " + suffix),
                unspscItem("child-" + suffix, "root-" + suffix, childCode, "Hierarchy Child " + suffix)
        );

        mockMvc.perform(post("/api/meta/categories/import-unspsc")
                        .param("createdBy", "hier-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(items)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdDefCount").value(2));

        MetaCategoryDef root = categoryDefRepository.findByBusinessDomainAndCodeKey("MATERIAL", rootCode).orElseThrow();

        mockMvc.perform(post("/api/meta/categories/rebuild-closure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definitions").isNumber())
                .andExpect(jsonPath("$.closureRows").isNumber())
                .andExpect(jsonPath("$.strategy").value("ancestor_list_dp"));

        mockMvc.perform(get("/api/meta/categories/{id}/descendants", root.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codeKey").value(childCode));
    }

    private UnspscImportItem unspscItem(String key, String parentKey, String code, String title) {
        UnspscImportItem item = new UnspscImportItem();
        item.setKey(key);
        item.setParentKey(parentKey);
        item.setCode(code);
        item.setTitle(title);
        return item;
    }

    private MockMultipartFile createExcelFile(String code, String name) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("categories");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("一级分类编号");
            header.createCell(1).setCellValue("一级分类名称");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(code);
            row.createCell(1).setCellValue(name);

            workbook.write(output);
            return new MockMultipartFile(
                    "file",
                    "category-import.xlsx",
                    MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    output.toByteArray());
        }
    }

    private MockMultipartFile createUnspscCsv(String rootCode, String childCode, String suffix) {
        String csv = String.join("\n",
                "key,parentKey,code,title",
                "root,," + rootCode + ",CSV Root " + suffix,
                "child,root," + childCode + ",CSV Child " + suffix);
        return new MockMultipartFile(
                "file",
                "category-import.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }
}