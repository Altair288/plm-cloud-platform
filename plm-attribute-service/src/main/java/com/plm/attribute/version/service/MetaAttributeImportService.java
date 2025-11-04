package com.plm.attribute.version.service;

import com.plm.common.api.dto.AttributeImportErrorDto;
import com.plm.common.api.dto.AttributeImportSummaryDto;
import com.plm.common.version.domain.*;
import com.plm.common.version.util.AttributeLovImportUtils;
import com.plm.infrastructure.version.repository.*;
import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

@Service
public class MetaAttributeImportService {
    private static final Logger log = LoggerFactory.getLogger(MetaAttributeImportService.class);

    // 分组结构: 同一 (categoryCode, attrName) 聚合所有枚举值
    private static class AttrGroup {
        String categoryCode;
        String attrName;
        String unit;
        List<String> values = new ArrayList<>();
        List<Integer> rowIndices = new ArrayList<>(); // 原始行号集合(1-based Excel 行)
    }

    private final MetaCategoryDefRepository categoryDefRepository;
    private final MetaCategoryVersionRepository categoryVersionRepository;
    private final MetaAttributeDefRepository attributeDefRepository;
    private final MetaAttributeVersionRepository attributeVersionRepository;
    private final MetaLovDefRepository lovDefRepository;
    private final MetaLovVersionRepository lovVersionRepository;
    // JdbcTemplate 目前未使用，如后续需要批量 SQL 可再注入

    @PersistenceContext
    private EntityManager entityManager;

    public MetaAttributeImportService(MetaCategoryDefRepository categoryDefRepository,
                                      MetaCategoryVersionRepository categoryVersionRepository,
                                      MetaAttributeDefRepository attributeDefRepository,
                                      MetaAttributeVersionRepository attributeVersionRepository,
                                      MetaLovDefRepository lovDefRepository,
                                      MetaLovVersionRepository lovVersionRepository,
                                      DataSource dataSource) {
        this.categoryDefRepository = categoryDefRepository;
        this.categoryVersionRepository = categoryVersionRepository;
        this.attributeDefRepository = attributeDefRepository;
        this.attributeVersionRepository = attributeVersionRepository;
        this.lovDefRepository = lovDefRepository;
        this.lovVersionRepository = lovVersionRepository;
    }

    /**
     * Excel 模板(示例): 分类编号 | 分类名称 | 属性名称 | 属性类型 | 单位 | 枚举值1 | 枚举值2 | ...
     */
    @Transactional
    public AttributeImportSummaryDto importExcel(MultipartFile file, String createdBy) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("上传文件为空");
        Workbook workbook = WorkbookFactory.create(file.getInputStream());
        Sheet sheet = workbook.getSheetAt(0);
        int lastRow = sheet.getLastRowNum();

        AttributeImportSummaryDto summary = new AttributeImportSummaryDto();
        List<AttributeImportErrorDto> errors = new ArrayList<>();

        Map<String, MetaCategoryDef> categoryCache = new HashMap<>();
        int createdAttrDefs = 0;
        int createdAttrVers = 0;
        int createdLovDefs = 0;
        int createdLovVers = 0;
        int skipped = 0;

        // 临时结构: (categoryCode, attributeName) -> 枚举值集合 & meta
    Map<String, AttrGroup> groups = new LinkedHashMap<>();

        for (int r = 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String categoryCode = cell(row,0);
            // 分类名称列暂不使用
            String attrName = cell(row,2);
            String dataType = cell(row,3);
            String unit = cell(row,4);
            if (isBlank(categoryCode) && isBlank(attrName)) continue; // 空行
            if (isBlank(categoryCode)) { errors.add(new AttributeImportErrorDto(r+1, "缺少分类编号")); continue; }
            if (isBlank(attrName)) { errors.add(new AttributeImportErrorDto(r+1, "缺少属性名称")); continue; }
            if (isBlank(dataType) || !"enum".equalsIgnoreCase(dataType)) { errors.add(new AttributeImportErrorDto(r+1, "仅支持属性类型=enum")); continue; }
            String key = categoryCode + "||" + attrName;
            AttrGroup g = groups.computeIfAbsent(key, k -> { AttrGroup ag = new AttrGroup(); ag.categoryCode=categoryCode; ag.attrName=attrName; ag.unit=unit; return ag;});
            g.rowIndices.add(r+1); // 保存 Excel 行号(1-based 展示)
            // 枚举列从第5索引(枚举值1列位置=5)开始
            for (int c = 5; c < row.getLastCellNum(); c++) {
                String v = cell(row,c);
                if (!isBlank(v)) g.values.add(v.trim());
            }
        }

        // 预加载分类
        Set<String> allCatCodes = new HashSet<>();
        groups.values().forEach(g -> allCatCodes.add(g.categoryCode));
        allCatCodes.forEach(code -> categoryDefRepository.findByCodeKey(code).ifPresent(d -> categoryCache.put(code, d)));

        // 预加载分类下已有属性 -> (categoryDefId -> (attrKey->MetaAttributeDef))
        Map<UUID, Map<String, MetaAttributeDef>> attrCache = new HashMap<>();
        // 处理每个属性组
        int batchCount = 0;
        final int FLUSH_THRESHOLD = 200; // 可调
        for (AttrGroup g : groups.values()) {
            if (!categoryCache.containsKey(g.categoryCode)) { errors.add(new AttributeImportErrorDto(-1, "分类不存在:"+g.categoryCode)); continue; }
            if (g.values.isEmpty()) { errors.add(new AttributeImportErrorDto(-1, "属性无枚举值:"+g.attrName)); continue; }
            MetaCategoryDef catDef = categoryCache.get(g.categoryCode);

            // 生成 attribute key (slug)
            String attrKey = AttributeLovImportUtils.slug(g.attrName);
            // 查询是否已有 attribute def (简单遍历按分类全部加载避免额外接口)
            Map<String, MetaAttributeDef> catAttrMap = attrCache.computeIfAbsent(catDef.getId(), id -> new HashMap<>());
            MetaAttributeDef attrDef = catAttrMap.get(attrKey);
            boolean newlyCreatedAttr = false;
            if (attrDef == null) {
                UUID newId = UUID.randomUUID();
                int inserted = attributeDefRepository.insertIgnore(newId, catDef.getId(), attrKey, true, attrKey, createdBy);
                if (inserted > 0) {
                    // 查询持久化实体，保证关系映射使用托管对象
                    attrDef = attributeDefRepository.findById(newId).orElseThrow();
                    newlyCreatedAttr = true;
                    createdAttrDefs++;
                } else {
                    // 已存在则查询
                    attrDef = findAttributeDef(catDef, attrKey);
                }
                catAttrMap.put(attrKey, attrDef);
                log.debug("[ATTR-DEF] categoryCode={} attrKey={} inserted={} id={}", g.categoryCode, attrKey, inserted>0, attrDef!=null?attrDef.getId():null);
            }

            // 最新分类版本
            MetaCategoryVersion catVer = categoryVersionRepository.findLatestByDef(catDef).orElse(null);
            if (catVer == null) { errors.add(new AttributeImportErrorDto(-1, "分类缺少版本:"+g.categoryCode)); continue; }

            // 构造 structure_json (简化)
            String structureJson = buildAttributeJson(g, attrDef);
            String structHash = AttributeLovImportUtils.jsonHash(structureJson);
            MetaAttributeVersion latestAttrVer = newlyCreatedAttr ? null : attributeVersionRepository.findLatestByDef(attrDef).orElse(null);
            boolean needNewAttrVersion = newlyCreatedAttr || latestAttrVer == null || (structHash != null && !structHash.equals(latestAttrVer.getHash()));
            log.debug("[ATTR-VERSION-CHECK] attrKey={} newlyCreatedAttr={} latestExists={} needNew={} latestVersionNo={}", attrKey, newlyCreatedAttr, latestAttrVer!=null, needNewAttrVersion, latestAttrVer!=null?latestAttrVer.getVersionNo():null);
            MetaAttributeVersion attrVer;
            if (needNewAttrVersion) {
                attrVer = new MetaAttributeVersion();
                attrVer.setAttributeDef(attrDef);
                attrVer.setCategoryVersion(catVer);
                attrVer.setStructureJson(structureJson);
                attrVer.setHash(structHash);
                attrVer.setCreatedBy(createdBy);
                if (latestAttrVer != null) {
                    latestAttrVer.setIsLatest(false);
                    attrVer.setVersionNo(latestAttrVer.getVersionNo() + 1);
                } else {
                    // 新属性或首次版本
                    attrVer.setVersionNo(1);
                }
                attributeVersionRepository.save(attrVer);
                if (attrDef != null) {
                    log.debug("[ATTR-VERSION-CREATE] attrDefId={} versionNo={} hash={}", attrDef.getId(), attrVer.getVersionNo(), structHash);
                }
                createdAttrVers++;
            } else {
                attrVer = latestAttrVer;
                skipped++;
                if (attrDef != null) {
                    log.debug("[ATTR-VERSION-SKIP] attrDefId={} versionNo={} hash={} (unchanged)", attrDef.getId(), attrVer!=null?attrVer.getVersionNo():null, structHash);
                }
            }

            // LOV 定义 & 版本
            String lovKey = AttributeLovImportUtils.generateLovKey(g.categoryCode, g.attrName);
            MetaLovDef lovDef = lovDefRepository.findByKey(lovKey).orElse(null);
            boolean newlyCreatedLov = false;
            if (lovDef == null) {
                if (attrDef == null) {
                    errors.add(new AttributeImportErrorDto(g.rowIndices.isEmpty()? -1 : g.rowIndices.get(0), "属性定义缺失，无法创建LOV:"+attrKey));
                    continue; // 安全提前
                }
                UUID lovId = UUID.randomUUID();
                int insLov = lovDefRepository.insertIgnore(lovId, attrDef.getId(), lovKey, attrKey, null, createdBy);
                if (insLov > 0) {
                    lovDef = lovDefRepository.findById(lovId).orElseThrow();
                    newlyCreatedLov = true;
                    createdLovDefs++;
                } else {
                    lovDef = lovDefRepository.findByKey(lovKey).orElse(null);
                }
                log.debug("[LOV-DEF] categoryCode={} attrKey={} lovKey={} inserted={} lovId={}", g.categoryCode, attrKey, lovKey, insLov>0, lovDef!=null?lovDef.getId():null);
            }
            String valueJson = buildLovJson(g.values);
            String valueHash = AttributeLovImportUtils.jsonHash(valueJson);
            MetaLovVersion latestLovVer = newlyCreatedLov ? null : lovVersionRepository.findLatestByDef(lovDef).orElse(null);
            boolean needNewLovVersion = newlyCreatedLov || latestLovVer == null || (valueHash != null && !valueHash.equals(latestLovVer.getHash()));
            log.debug("[LOV-VERSION-CHECK] lovKey={} newlyCreatedLov={} latestExists={} needNew={} latestVersionNo={}", lovKey, newlyCreatedLov, latestLovVer!=null, needNewLovVersion, latestLovVer!=null?latestLovVer.getVersionNo():null);
            if (needNewLovVersion) {
                MetaLovVersion lv = new MetaLovVersion();
                lv.setLovDef(lovDef);
                lv.setAttributeVersion(attrVer);
                lv.setValueJson(valueJson);
                lv.setHash(valueHash);
                lv.setCreatedBy(createdBy);
                if (latestLovVer != null) {
                    latestLovVer.setIsLatest(false);
                    lv.setVersionNo(latestLovVer.getVersionNo() + 1);
                } else {
                    lv.setVersionNo(1);
                }
                lovVersionRepository.save(lv);
                if (lovDef != null) {
                    log.debug("[LOV-VERSION-CREATE] lovDefId={} versionNo={} hash={}", lovDef.getId(), lv.getVersionNo(), valueHash);
                }
                createdLovVers++;
            } else {
                skipped++;
                if (lovDef != null) {
                    log.debug("[LOV-VERSION-SKIP] lovDefId={} versionNo={} hash={} (unchanged)", lovDef.getId(), latestLovVer!=null?latestLovVer.getVersionNo():null, valueHash);
                }
            }

            // 批量 flush/clear 降低持久化上下文内存
            if (++batchCount % FLUSH_THRESHOLD == 0) {
                entityManager.flush();
                entityManager.clear();
            }
        }

        summary.setTotalRows(lastRow);
        summary.setAttributeGroupCount(groups.size());
        summary.setCreatedAttributeDefs(createdAttrDefs);
        summary.setCreatedAttributeVersions(createdAttrVers);
        summary.setCreatedLovDefs(createdLovDefs);
        summary.setCreatedLovVersions(createdLovVers);
        summary.setSkippedUnchanged(skipped);
        summary.setErrors(errors);
        summary.setErrorCount(errors.size());
        return summary;
    }

    private MetaAttributeDef findAttributeDef(MetaCategoryDef catDef, String attrKey) {
        // 精准查询，避免加载全集 (后续可加 repository 方法 findByCategoryDefAndKey)
        List<MetaAttributeDef> defs = attributeDefRepository.findByCategoryDefAndKeyIn(catDef, Collections.singleton(attrKey));
        return defs.isEmpty()? null : defs.get(0);
    }

    private String buildAttributeJson(AttrGroup g, MetaAttributeDef def) {
        String unit = g.unit == null? "" : g.unit;
        return "{" +
                "\"displayName\":\""+escape(g.attrName)+"\"," +
                "\"dataType\":\"enum\"," +
                "\"unit\":\""+escape(unit)+"\"," +
                "\"lovKey\":\""+AttributeLovImportUtils.generateLovKey(g.categoryCode,g.attrName)+"\"" +
                "}";
    }

    private String buildLovJson(List<String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"values\":[");
        for (int i = 0; i < values.size(); i++) {
            String v = values.get(i);
            BigDecimal num = AttributeLovImportUtils.parseNumeric(v);
            if (i>0) sb.append(',');
            sb.append('{')
              .append("\"code\":\"").append(escape(v)).append("\",")
              .append("\"name\":\"").append(escape(v)).append("\",")
              .append("\"order\":").append(i+1).append(',')
              .append("\"active\":true");
            if (num != null) {
                sb.append(',').append("\"numericValue\":").append(num.toPlainString());
            }
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private String cell(Row row, int idx) {
        Cell c = row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (c == null) return null;
        return switch (c.getCellType()) {
            case STRING -> trim(c.getStringCellValue());
            case NUMERIC -> trim(String.valueOf(c.getNumericCellValue()).replaceAll("\\.0$",""));
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> null;
        };
    }
    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private String trim(String s){ return s==null?null:(s.trim().isEmpty()?null:s.trim()); }
    private String escape(String s){ return s==null?"": s.replace("\\","\\\\").replace("\"","\\\""); }
}
