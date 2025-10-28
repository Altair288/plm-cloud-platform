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
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

@Service
public class MetaCategoryImportService {

    private final MetaCategoryDefRepository defRepository;
    private final MetaCategoryVersionRepository versionRepository;
    private final JdbcTemplate jdbcTemplate;

    public MetaCategoryImportService(MetaCategoryDefRepository defRepository,
                                     MetaCategoryVersionRepository versionRepository,
                                     DataSource dataSource) {
        this.defRepository = defRepository;
        this.versionRepository = versionRepository;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
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

        // 1. 预采集本次 Excel 中出现的所有 code，降低并发下 exists 竞争 & N+1 查询
        Set<String> excelAllCodes = new HashSet<>();
        for (int r = 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int level = 0; level < 4; level++) {
                int codeIdx = level * 2;
                String code = getCellString(row.getCell(codeIdx));
                if (code != null) excelAllCodes.add(code);
            }
        }
        // 2. 一次性查询已存在的 definitions
        Map<String, MetaCategoryDef> existingDefMap = new HashMap<>();
        if (!excelAllCodes.isEmpty()) {
            defRepository.findByCodeKeyIn(excelAllCodes).forEach(def -> existingDefMap.put(def.getCodeKey(), def));
        }

        // 记录本次成功插入的新 code，避免同一批次重复处理
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

                // 并发安全: 不做 exists 先查直接尝试插入, 依赖唯一约束 (code_key) ON CONFLICT DO NOTHING
                if (sessionNewCodes.contains(code)) { skipped++; continue; }
                if (existingDefMap.containsKey(code)) { skipped++; continue; }

                // 父节点处理：如果父节点不在缓存，尝试从库查询（避免多次查询仍可并发安全）
                MetaCategoryDef parentDef = null;
                String parentCode = parentCode(code);
                if (parentCode != null) {
                    parentDef = existingDefMap.get(parentCode);
                    if (parentDef == null) {
                        parentDef = defRepository.findByCodeKey(parentCode).orElse(null);
                        if (parentDef != null) existingDefMap.put(parentCode, parentDef);
                    }
                }

                UUID newId = null;
                try {
                    // 只插入最小字段，后续再补 path/depth/fullPathName
                    newId = jdbcTemplate.queryForObject(
                            "INSERT INTO plm_meta.meta_category_def(id, code_key, status, created_at, created_by, parent_def_id) " +
                                    "VALUES (gen_random_uuid(), ?, 'active', now(), ?, ?) ON CONFLICT (code_key) DO NOTHING RETURNING id",
                            (rs, rowNum) -> rs.getObject(1, java.util.UUID.class),
                            code, createdBy, parentDef == null ? null : parentDef.getId()
                    );
                } catch (EmptyResultDataAccessException ignore) {
                    // 冲突 -> 已存在其他并发导入插入
                }

                MetaCategoryDef def;
                if (newId == null) {
                    // 未插入（并发导致存在），加载现有记录并跳过版本创建（视为已存在）
                    def = defRepository.findByCodeKey(code).orElse(null);
                    if (def == null) { errors.add("并发: 未能获取已存在定义 code=" + code); errorCount++; continue; }
                    skipped++; // 视为跳过
                    continue; // 不重复创建版本 & 闭包
                } else {
                    // 刚成功插入
                    def = defRepository.findById(newId).orElse(null);
                    if (def == null) { errors.add("插入后加载失败 code=" + code); errorCount++; continue; }
                    existingDefMap.put(code, def);
                }

                // 如果父节点存在，确保其 is_leaf = false
                if (parentDef != null && Boolean.TRUE.equals(parentDef.getIsLeaf())) {
                    parentDef.setIsLeaf(false);
                    defRepository.save(parentDef);
                    existingDefMap.put(parentCode, parentDef);
                }

                // 计算 path / depth
                String path = buildPath(def);
                def.setPath(path);
                def.setDepth((short) (path.equals("/") ? 0 : path.split("/").length - 1));

                // 创建版本 （并发重复插入: def_id+version_no 唯一, 使用 save 发生冲突风险较低且本批只创建初始版本）
                MetaCategoryVersion ver = new MetaCategoryVersion();
                ver.setCategoryDef(def);
                ver.setDisplayName(name);
                ver.setIsLatest(true);
                ver.setCreatedBy(createdBy);
                versionRepository.save(ver);

                def.setFullPathName(buildFullPathName(def, ver.getDisplayName()));
                defRepository.save(def);

                // 闭包表插入（祖先-后代关系）
                insertClosureRows(def);

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

    /**
     * 为新定义节点插入闭包表行：
     * (self,self,0) 以及 (ancestor,self,distance)
     * 使用 ON CONFLICT DO NOTHING 保证幂等。
     */
    private void insertClosureRows(MetaCategoryDef def) {
        // self
        jdbcTemplate.update("INSERT INTO plm_meta.category_hierarchy(ancestor_def_id, descendant_def_id, distance) VALUES (?,?,0) ON CONFLICT DO NOTHING",
                def.getId(), def.getId());
        int distance = 1;
        MetaCategoryDef cursor = def.getParent();
        while (cursor != null) {
            jdbcTemplate.update("INSERT INTO plm_meta.category_hierarchy(ancestor_def_id, descendant_def_id, distance) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                    cursor.getId(), def.getId(), distance);
            cursor = cursor.getParent();
            distance++;
        }
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
