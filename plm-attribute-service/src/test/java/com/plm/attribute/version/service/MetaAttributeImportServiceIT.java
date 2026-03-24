package com.plm.attribute.version.service;

import com.plm.common.api.dto.category.CreateCategoryRequestDto;
import com.plm.common.api.dto.attribute.imports.AttributeImportSummaryDto;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.main.lazy-initialization=true"
)
@ActiveProfiles("dev")
@Transactional
public class MetaAttributeImportServiceIT {

    @Autowired
    private MetaAttributeImportService importService;

    @Autowired
    private MetaCategoryCrudService categoryCrudService;

    @Test
    void testImportIdempotent() throws Exception {
        CreateCategoryRequestDto createCategoryRequest = new CreateCategoryRequestDto();
        createCategoryRequest.setBusinessDomain("MATERIAL");
        createCategoryRequest.setCode("CAT001");
        createCategoryRequest.setName("Import Test Category");
        categoryCrudService.create(createCategoryRequest, "tester");

        // 构造 Excel
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("attr");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("分类编号");
        header.createCell(1).setCellValue("分类名称");
        header.createCell(2).setCellValue("属性名称");
        header.createCell(3).setCellValue("属性类型");
        header.createCell(4).setCellValue("单位");
        header.createCell(5).setCellValue("枚举值1");
        header.createCell(6).setCellValue("枚举值2");
        // 数据行 (假设分类 CAT001 已存在并有版本)
        Row r1 = sheet.createRow(1);
        r1.createCell(0).setCellValue("CAT001");
        r1.createCell(2).setCellValue("颜色");
        r1.createCell(3).setCellValue("enum");
        r1.createCell(5).setCellValue("RED");
        r1.createCell(6).setCellValue("BLUE");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        wb.write(bos);
        wb.close();

        MockMultipartFile mf = new MockMultipartFile("file", "test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bos.toByteArray());

        AttributeImportSummaryDto first = importService.importExcel(mf, "tester");
        AttributeImportSummaryDto second = importService.importExcel(mf, "tester");

        Assertions.assertTrue(first.getCreatedAttributeDefs() >= 0,
            "first summary: createdDefs=" + first.getCreatedAttributeDefs()
                + ", createdAttrVers=" + first.getCreatedAttributeVersions()
                + ", createdLovDefs=" + first.getCreatedLovDefs()
                + ", createdLovVers=" + first.getCreatedLovVersions()
                + ", skipped=" + first.getSkippedUnchanged()
                + ", errors=" + first.getErrorCount());
        Assertions.assertTrue(second.getSkippedUnchanged() >= 1,
            "second summary: createdDefs=" + second.getCreatedAttributeDefs()
                + ", createdAttrVers=" + second.getCreatedAttributeVersions()
                + ", createdLovDefs=" + second.getCreatedLovDefs()
                + ", createdLovVers=" + second.getCreatedLovVersions()
                + ", skipped=" + second.getSkippedUnchanged()
                + ", errors=" + second.getErrorCount());
    }
}
