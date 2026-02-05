package com.plm.attribute.version.service;

import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.common.version.domain.MetaCategoryVersion;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import com.plm.infrastructure.version.repository.MetaCategoryVersionRepository;
import com.plm.common.api.dto.MetaCategoryDefDto;
import com.plm.common.api.dto.MetaCategoryImportSummaryDto;
import com.plm.common.api.dto.UnspscImportItem;
import com.plm.common.api.mapper.MetaCategoryMapper;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.EncryptedDocumentException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.OffsetDateTime;
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
     * 导入简化格式（UNSPSC）：key | parentKey | code | title
     * - code -> code_key & external_code
     * - title -> version.displayName & full_path_name 组成
     */
    @Transactional
    public MetaCategoryImportSummaryDto importUnspsc(List<UnspscImportItem> items, String createdBy) {
        if (items == null || items.isEmpty())
            throw new IllegalArgumentException("导入列表为空");

        Map<String, MetaCategoryDef> codeMap = new HashMap<>();
        Map<String, MetaCategoryDef> keyMap = new HashMap<>();
        // 预加载已存在的 code_key
        Set<String> codes = new HashSet<>();
        for (UnspscImportItem it : items)
            if (it.getCode() != null)
                codes.add(it.getCode());
        if (!codes.isEmpty()) {
            defRepository.findByCodeKeyIn(codes).forEach(def -> {
                codeMap.put(def.getCodeKey(), def);
                keyMap.put(def.getCodeKey(), def); // 如果 key 也用 code 重复引用
            });
        }

        Map<String, UnspscImportItem> itemByKey = new HashMap<>();
        for (UnspscImportItem it : items) {
            if (it.getKey() != null)
                itemByKey.put(it.getKey(), it);
        }

        int createdDefCount = 0;
        int createdVersionCount = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        List<MetaCategoryDefDto> createdDefs = new ArrayList<>();

        Set<String> processedKeys = new HashSet<>();
        boolean progress;
        int safety = items.size() + 5;
        do {
            progress = false;
            for (UnspscImportItem it : items) {
                String itemKey = trim(it.getKey());
                if (itemKey == null) {
                    errors.add("缺少 key");
                    continue;
                }
                if (processedKeys.contains(itemKey))
                    continue;
                String code = trim(it.getCode());
                String title = trim(it.getTitle());
                if (code == null || title == null) {
                    errors.add("缺少 code/title, key=" + itemKey);
                    processedKeys.add(itemKey);
                    continue;
                }

                // 父节点
                MetaCategoryDef parent = null;
                if (it.getParentKey() != null && !it.getParentKey().isBlank()) {
                    parent = keyMap.get(it.getParentKey());
                    if (parent == null)
                        continue; // 父未就绪，留待下一轮
                }

                // 已存在则跳过插入
                MetaCategoryDef def = codeMap.get(code);
                if (def != null) {
                    skipped++;
                    processedKeys.add(itemKey);
                    keyMap.put(itemKey, def);
                    continue;
                }

                UUID newId = null;
                try {
                    newId = jdbcTemplate.queryForObject(
                            "INSERT INTO plm_meta.meta_category_def(id, code_key, status, created_at, created_by, parent_def_id, external_code) "
                                    +
                                    "VALUES (gen_random_uuid(), ?, 'active', now(), ?, ?, ?) ON CONFLICT (code_key) DO NOTHING RETURNING id",
                            (rs, rowNum) -> rs.getObject(1, java.util.UUID.class),
                            code,
                            createdBy,
                            parent == null ? null : parent.getId(),
                            code);
                } catch (EmptyResultDataAccessException ignore) {
                    // 冲突：并发或已存在
                }

                if (newId == null) {
                    def = defRepository.findByCodeKey(code).orElse(null);
                    if (def == null) {
                        errors.add("并发: 未能获取已存在定义 code=" + code);
                        processedKeys.add(itemKey);
                        continue;
                    }
                    skipped++;
                    codeMap.put(code, def);
                    keyMap.put(itemKey, def);
                    processedKeys.add(itemKey);
                    continue;
                }

                def = defRepository.findById(newId).orElse(null);
                if (def == null) {
                    errors.add("插入后加载失败 code=" + code);
                    processedKeys.add(itemKey);
                    continue;
                }
                // 确保 parent 链路可用于闭包插入（避免懒加载额外查询）
                if (parent != null)
                    def.setParent(parent);

                // 如果父节点存在，确保其 is_leaf = false
                if (parent != null && Boolean.TRUE.equals(parent.getIsLeaf())) {
                    parent.setIsLeaf(false);
                    defRepository.save(parent);
                }

                // path/depth/fullPathName
                String parentPath = parent == null ? null : parent.getPath();
                if (parent != null && parentPath == null)
                    parentPath = "/" + parent.getCodeKey();
                String path = (parent == null ? "/" + code : parentPath + "/" + code);
                def.setPath(path);
                def.setDepth((short) (path.split("/").length - 1));
                def.setIsLeaf(true);

                String parentFullName = parent == null ? null : parent.getFullPathName();
                if (parent != null && parentFullName == null)
                    parentFullName = parent.getCodeKey();
                def.setFullPathName(parent == null ? title : parentFullName + "/" + title);
                defRepository.save(def);

                // 创建版本
                MetaCategoryVersion ver = new MetaCategoryVersion();
                ver.setCategoryDef(def);
                ver.setDisplayName(title);
                ver.setIsLatest(true);
                ver.setCreatedBy(createdBy);
                versionRepository.save(ver);

                // 闭包表插入（祖先-后代关系）
                insertClosureRows(def);

                codeMap.put(code, def);
                keyMap.put(itemKey, def);
                processedKeys.add(itemKey);
                createdDefCount++;
                createdVersionCount++;
                createdDefs.add(MetaCategoryMapper.toDefDto(def));
                progress = true;
            }
            safety--;
        } while (progress && safety > 0 && processedKeys.size() < items.size());

        // 未处理完的记录报错
        for (UnspscImportItem it : items) {
            if (!processedKeys.contains(it.getKey())) {
                errors.add("未导入: key=" + it.getKey() + " 可能缺少父节点或重复");
            }
        }

        MetaCategoryImportSummaryDto summary = new MetaCategoryImportSummaryDto();
        summary.setTotalRows(items.size());
        summary.setCreatedDefCount(createdDefCount);
        summary.setCreatedVersionCount(createdVersionCount);
        summary.setSkippedExistingCount(skipped);
        summary.setErrorCount(errors.size());
        summary.setErrors(errors);
        summary.setCreatedDefs(createdDefs);
        return summary;
    }

    /**
     * 从 CSV (UTF-8) 导入 UNSPSC，列顺序：key,parentKey,code,title，首行可为表头自动跳过。
     */
    @Transactional
    public MetaCategoryImportSummaryDto importUnspscCsv(MultipartFile file, String createdBy) {
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("上传文件为空");
        List<UnspscImportItem> items = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty())
                    continue;
                List<String> parts = parseCsvLine(trimmed);
                // 兼容表头：检测首行是否包含非数字/键名
                if (first && isHeader(parts)) {
                    first = false;
                    continue;
                }
                first = false;
                UnspscImportItem item = new UnspscImportItem();
                item.setKey(getCsv(parts, 0));
                item.setParentKey(getCsv(parts, 1));
                item.setCode(getCsv(parts, 2));
                item.setTitle(getCsv(parts, 3));
                if (item.getCode() == null && item.getTitle() == null)
                    continue; // skip invalid row
                items.add(item);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("读取 CSV 失败", e);
        }
        return importUnspsc(items, createdBy);
    }

    /**
     * 导入 Excel 多级分类到 plm_meta.* 表
     * 表头约定 8 列: 一级分类编号 | 一级分类名称 | 二级分类编号 | 二级分类名称 | 三级分类编号 | 三级分类名称 | 四级分类编号 |
     * 四级分类名称
     */
    @Transactional
    public MetaCategoryImportSummaryDto importExcel(MultipartFile file, String createdBy) throws IOException {
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("上传文件为空");
        Workbook workbook;
        try {
            workbook = WorkbookFactory.create(file.getInputStream());
        } catch (EncryptedDocumentException e) {
            throw new IllegalArgumentException("无法读取加密 Excel", e);
        }
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
            if (row == null)
                continue;
            for (int level = 0; level < 4; level++) {
                int codeIdx = level * 2;
                String code = getCellString(row.getCell(codeIdx));
                if (code != null)
                    excelAllCodes.add(code);
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
            if (row == null)
                continue;
            boolean hasValue = false;
            for (int c = 0; c < 8; c++) {
                if (getCellString(row.getCell(c)) != null) {
                    hasValue = true;
                    break;
                }
            }
            if (!hasValue)
                continue;

            for (int level = 0; level < 4; level++) {
                int codeIdx = level * 2;
                int nameIdx = codeIdx + 1;
                String code = getCellString(row.getCell(codeIdx));
                String name = getCellString(row.getCell(nameIdx));
                if (code == null && name == null)
                    continue; // 该层未填写
                if (code == null) {
                    errors.add("第" + (r + 1) + "行: 层级" + (level + 1) + "缺少编号");
                    errorCount++;
                    continue;
                }
                if (name == null) {
                    errors.add("第" + (r + 1) + "行: 层级" + (level + 1) + "编号=" + code + " 缺少名称");
                    errorCount++;
                    continue;
                }

                // 并发安全: 不做 exists 先查直接尝试插入, 依赖唯一约束 (code_key) ON CONFLICT DO NOTHING
                if (sessionNewCodes.contains(code)) {
                    skipped++;
                    continue;
                }
                if (existingDefMap.containsKey(code)) {
                    skipped++;
                    continue;
                }

                // 父节点处理：如果父节点不在缓存，尝试从库查询（避免多次查询仍可并发安全）
                MetaCategoryDef parentDef = null;
                String parentCode = parentCode(code);
                if (parentCode != null) {
                    parentDef = existingDefMap.get(parentCode);
                    if (parentDef == null) {
                        parentDef = defRepository.findByCodeKey(parentCode).orElse(null);
                        if (parentDef != null)
                            existingDefMap.put(parentCode, parentDef);
                    }
                }

                UUID newId = null;
                try {
                    // 只插入最小字段，后续再补 path/depth/fullPathName
                    newId = jdbcTemplate.queryForObject(
                            "INSERT INTO plm_meta.meta_category_def(id, code_key, status, created_at, created_by, parent_def_id) "
                                    +
                                    "VALUES (gen_random_uuid(), ?, 'active', now(), ?, ?) ON CONFLICT (code_key) DO NOTHING RETURNING id",
                            (rs, rowNum) -> rs.getObject(1, java.util.UUID.class),
                            code, createdBy, parentDef == null ? null : parentDef.getId());
                } catch (EmptyResultDataAccessException ignore) {
                    // 冲突 -> 已存在其他并发导入插入
                }

                MetaCategoryDef def;
                if (newId == null) {
                    // 未插入（并发导致存在），加载现有记录并跳过版本创建（视为已存在）
                    def = defRepository.findByCodeKey(code).orElse(null);
                    if (def == null) {
                        errors.add("并发: 未能获取已存在定义 code=" + code);
                        errorCount++;
                        continue;
                    }
                    skipped++; // 视为跳过
                    continue; // 不重复创建版本 & 闭包
                } else {
                    // 刚成功插入
                    def = defRepository.findById(newId).orElse(null);
                    if (def == null) {
                        errors.add("插入后加载失败 code=" + code);
                        errorCount++;
                        continue;
                    }
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
        jdbcTemplate.update(
                "INSERT INTO plm_meta.category_hierarchy(ancestor_def_id, descendant_def_id, distance) VALUES (?,?,0) ON CONFLICT DO NOTHING",
                def.getId(), def.getId());
        int distance = 1;
        MetaCategoryDef cursor = def.getParent();
        while (cursor != null) {
            jdbcTemplate.update(
                    "INSERT INTO plm_meta.category_hierarchy(ancestor_def_id, descendant_def_id, distance) VALUES (?,?,?) ON CONFLICT DO NOTHING",
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
        if (def.getParent() == null)
            return "/" + def.getCodeKey();
        String parentPath = def.getParent().getPath();
        if (parentPath == null)
            parentPath = "/" + def.getParent().getCodeKey();
        return parentPath + "/" + def.getCodeKey();
    }

    private String buildFullPathName(MetaCategoryDef def, String currentDisplayName) {
        if (def.getParent() == null)
            return currentDisplayName;
        // 找父的最新版本显示名
        String parentName = versionRepository.findLatestByDef(def.getParent())
                .map(MetaCategoryVersion::getDisplayName)
                .orElse(def.getParent().getCodeKey());
        return parentName + "/" + currentDisplayName;
    }

    private String getCellString(Cell cell) {
        if (cell == null)
            return null;
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

    private String trim(String s) {
        if (s == null)
            return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String getCsv(List<String> parts, int idx) {
        if (parts == null || idx >= parts.size())
            return null;
        String v = parts.get(idx);
        if (v == null)
            return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private boolean isHeader(List<String> parts) {
        if (parts == null || parts.size() < 3)
            return false;
        String joined = String.join(" ", parts).toLowerCase();
        return joined.contains("key") || joined.contains("键") || joined.contains("code") || joined.contains("编码");
    }

    /**
     * 轻量 CSV 解析：支持双引号包裹字段（字段内可含逗号），支持 "" 转义。
     */
    private List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        if (line == null)
            return out;
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++; // skip escaped quote
                } else {
                    inQuotes = !inQuotes;
                }
                continue;
            }
            if (ch == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }
}
