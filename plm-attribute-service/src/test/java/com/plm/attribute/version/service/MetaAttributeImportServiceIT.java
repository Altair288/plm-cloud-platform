package com.plm.attribute.version.service;

import com.plm.common.api.dto.AttributeImportSummaryDto;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;

@SpringBootTest
@Transactional
public class MetaAttributeImportServiceIT {

    @Autowired
    private MetaAttributeImportService importService;

    @Test
    void testImportIdempotent() throws Exception {
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

        Assertions.assertTrue(first.getCreatedAttributeDefs() >= 0); // 若分类不存在会报错
        Assertions.assertTrue(second.getSkippedUnchanged() >= 1); // 第二次应跳过未变化版本
    }
}
