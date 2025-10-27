package com.plm.attribute.version.service;

import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.common.version.domain.MetaCategoryVersion;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import com.plm.infrastructure.version.repository.MetaCategoryVersionRepository;
import com.plm.common.api.dto.MetaCategoryDefDto;
import com.plm.common.api.dto.MetaCategoryImportSummaryDto;
import com.plm.common.api.mapper.MetaCategoryMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.EncryptedDocumentException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@Service
public class MetaCategoryImportService {

    private final MetaCategoryDefRepository defRepository;
    private final MetaCategoryVersionRepository versionRepository;

    public MetaCategoryImportService(MetaCategoryDefRepository defRepository, MetaCategoryVersionRepository versionRepository) {
        this.defRepository = defRepository;
        this.versionRepository = versionRepository;
    }

    /**
     * 导入 Excel 多级分类到 plm_meta.* 表
     * 表头约定 8 列: 一级分类编号 | 一级分类名称 | 二级分类编号 | 二级分类名称 | 三级分类编号 | 三级分类名称 | 四级分类编号 | 四级分类名称
     */
    @Transactional
    public MetaCategoryImportSummaryDto importExcel(MultipartFile file, String createdBy) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("上传文件为空");
        Workbook workbook;
        try { workbook = WorkbookFactory.create(file.getInputStream()); }
        catch (EncryptedDocumentException e) { throw new IllegalArgumentException("无法读取加密 Excel", e); }
        Sheet sheet = workbook.getSheetAt(0);
        int lastRow = sheet.getLastRowNum();

        MetaCategoryImportSummaryDto summary = new MetaCategoryImportSummaryDto();
        List<String> errors = new ArrayList<>();
        List<MetaCategoryDefDto> createdDefs = new ArrayList<>();
        int createdDefCount = 0;
        int createdVersionCount = 0;
        int skipped = 0;
        int errorCount = 0;

        // 记录本次新增，避免同一批次重复
        Set<String> sessionNewCodes = new HashSet<>();

        for (int r = 1; r <= lastRow; r++) { // 从第二行开始
            Row row = sheet.getRow(r);
            if (row == null) continue;
            boolean hasValue = false;
            for (int c = 0; c < 8; c++) { if (getCellString(row.getCell(c)) != null) { hasValue = true; break; } }
            if (!hasValue) continue;

            for (int level = 0; level < 4; level++) {
                int codeIdx = level * 2;
                int nameIdx = codeIdx + 1;
                String code = getCellString(row.getCell(codeIdx));
                String name = getCellString(row.getCell(nameIdx));
                if (code == null && name == null) continue; // 该层未填写
                if (code == null) { errors.add("第" + (r+1) + "行: 层级" + (level+1) + "缺少编号"); errorCount++; continue; }
                if (name == null) { errors.add("第" + (r+1) + "行: 层级" + (level+1) + "编号=" + code + " 缺少名称"); errorCount++; continue; }

                // 已存在
                if (sessionNewCodes.contains(code) || defRepository.existsByCodeKey(code)) { skipped++; continue; }

                // 创建定义
                MetaCategoryDef def = new MetaCategoryDef();
                def.setCodeKey(code);
                def.setCreatedBy(createdBy);
                // 计算父级 code （按最后一个点截断）
                String parentCode = parentCode(code);
                if (parentCode != null) {
                    defRepository.findByCodeKey(parentCode).ifPresent(parent -> {
                        def.setParent(parent);
                        parent.setIsLeaf(false); // 有子节点
                    });
                }
                // 先保存以获取 ID
                defRepository.save(def);

                // 计算 path / depth
                String path = buildPath(def);
                def.setPath(path);
                def.setDepth((short) (path.equals("/") ? 0 : path.split("/").length - 1));

                // 创建版本 & fullPathName
                MetaCategoryVersion ver = new MetaCategoryVersion();
                ver.setCategoryDef(def);
                ver.setDisplayName(name);
                ver.setIsLatest(true);
                ver.setCreatedBy(createdBy);
                versionRepository.save(ver);

                def.setFullPathName(buildFullPathName(def, ver.getDisplayName()));

                // 更新定义（path/fullPathName/isLeaf 等）
                defRepository.save(def);

                sessionNewCodes.add(code);
                createdDefCount++;
                createdVersionCount++;
                createdDefs.add(MetaCategoryMapper.toDefDto(def));
            }
        }

        summary.setTotalRows(lastRow);
        summary.setCreatedDefCount(createdDefCount);
        summary.setCreatedVersionCount(createdVersionCount);
        summary.setSkippedExistingCount(skipped);
        summary.setErrorCount(errorCount);
        summary.setErrors(errors);
        summary.setCreatedDefs(createdDefs);
        return summary;
    }

    private String parentCode(String code) {
        int idx = code.lastIndexOf('.');
        return idx > 0 ? code.substring(0, idx) : null;
    }

    private String buildPath(MetaCategoryDef def) {
        if (def.getParent() == null) return "/" + def.getCodeKey();
        String parentPath = def.getParent().getPath();
        if (parentPath == null) parentPath = "/" + def.getParent().getCodeKey();
        return parentPath + "/" + def.getCodeKey();
    }

    private String buildFullPathName(MetaCategoryDef def, String currentDisplayName) {
        if (def.getParent() == null) return currentDisplayName;
        // 找父的最新版本显示名
        String parentName = versionRepository.findLatestByDef(def.getParent())
                .map(MetaCategoryVersion::getDisplayName)
                .orElse(def.getParent().getCodeKey());
        return parentName + "/" + currentDisplayName;
    }

    private String getCellString(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> trim(cell.getStringCellValue());
            case NUMERIC -> {
                String raw = String.valueOf(cell.getNumericCellValue());
                raw = raw.replaceAll("\\.0$", "");
                yield trim(raw);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }
    private String trim(String s) { if (s == null) return null; String t = s.trim(); return t.isEmpty()? null : t; }
}
