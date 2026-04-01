package com.plm.attribute.version.service.workbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plm.attribute.version.service.MetaCodeRuleService;
import com.plm.attribute.version.service.MetaCodeRuleSetService;
import com.plm.common.api.dto.code.CodeRulePreviewRequestDto;
import com.plm.common.api.dto.code.CodeRulePreviewResponseDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunOptionsDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunResponseDto;
import com.plm.common.version.domain.MetaAttributeDef;
import com.plm.common.version.domain.MetaAttributeVersion;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.common.version.domain.MetaCategoryVersion;
import com.plm.common.version.domain.MetaLovDef;
import com.plm.common.version.domain.MetaLovVersion;
import com.plm.infrastructure.version.repository.MetaAttributeDefRepository;
import com.plm.infrastructure.version.repository.MetaAttributeVersionRepository;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import com.plm.infrastructure.version.repository.MetaCategoryVersionRepository;
import com.plm.infrastructure.version.repository.MetaLovDefRepository;
import com.plm.infrastructure.version.repository.MetaLovVersionRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkbookImportDryRunService {

    private static final String SHEET_CATEGORIES = "分类层级";
    private static final String SHEET_ATTRIBUTES = "属性定义";
    private static final String SHEET_ENUMS = "枚举值定义";
    private static final int CATEGORY_DATA_START_ROW_INDEX = 3;
    private static final int ATTRIBUTE_DATA_START_ROW_INDEX = 2;
    private static final int ENUM_DATA_START_ROW_INDEX = 2;
    private static final String MODE_EXCEL_MANUAL = "EXCEL_MANUAL";
    private static final String MODE_SYSTEM_RULE_AUTO = "SYSTEM_RULE_AUTO";
    private static final String POLICY_OVERWRITE = "OVERWRITE_EXISTING";
    private static final String POLICY_KEEP = "KEEP_EXISTING";
    private static final String POLICY_FAIL = "FAIL_ON_DUPLICATE";
    private static final Set<String> SUPPORTED_DATA_TYPES = Set.of("string", "number", "bool", "enum", "multi_enum", "date");

    private final WorkbookImportRuntimeService runtimeService;
    private final MetaCategoryDefRepository categoryDefRepository;
    private final MetaCategoryVersionRepository categoryVersionRepository;
    private final MetaAttributeDefRepository attributeDefRepository;
    private final MetaAttributeVersionRepository attributeVersionRepository;
    private final MetaLovDefRepository lovDefRepository;
    private final MetaLovVersionRepository lovVersionRepository;
    private final MetaCodeRuleService metaCodeRuleService;
    private final MetaCodeRuleSetService metaCodeRuleSetService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataFormatter dataFormatter = new DataFormatter();

    public WorkbookImportDryRunService(WorkbookImportRuntimeService runtimeService,
                                       MetaCategoryDefRepository categoryDefRepository,
                                       MetaCategoryVersionRepository categoryVersionRepository,
                                       MetaAttributeDefRepository attributeDefRepository,
                                       MetaAttributeVersionRepository attributeVersionRepository,
                                       MetaLovDefRepository lovDefRepository,
                                       MetaLovVersionRepository lovVersionRepository,
                                       MetaCodeRuleService metaCodeRuleService,
                                       MetaCodeRuleSetService metaCodeRuleSetService) {
        this.runtimeService = runtimeService;
        this.categoryDefRepository = categoryDefRepository;
        this.categoryVersionRepository = categoryVersionRepository;
        this.attributeDefRepository = attributeDefRepository;
        this.attributeVersionRepository = attributeVersionRepository;
        this.lovDefRepository = lovDefRepository;
        this.lovVersionRepository = lovVersionRepository;
        this.metaCodeRuleService = metaCodeRuleService;
        this.metaCodeRuleSetService = metaCodeRuleSetService;
    }

    public WorkbookImportDryRunResponseDto dryRun(MultipartFile file,
                                                  String operator,
                                                  WorkbookImportDryRunOptionsDto rawOptions) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }

        WorkbookImportDryRunOptionsDto options = normalizeOptions(rawOptions);
        String normalizedOperator = normalizeOperator(operator);
        OffsetDateTime now = OffsetDateTime.now();

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet categorySheet = requireSheet(workbook, SHEET_CATEGORIES);
            Sheet attributeSheet = requireSheet(workbook, SHEET_ATTRIBUTES);
            Sheet enumSheet = requireSheet(workbook, SHEET_ENUMS);

            List<MutableCategoryRow> categoryRows = parseCategoryRows(categorySheet);
            assignPreviewCategoryCodes(categoryRows, options);
            CategoryIndexes categoryIndexes = indexCategories(categoryRows);
            Map<String, MutableCategoryRow> categoryByReference = categoryIndexes.byReference();
            Map<String, MutableCategoryRow> categoryByFinalCode = categoryIndexes.byFinalCode();

            List<MutableAttributeRow> attributeRows = parseAttributeRows(attributeSheet, categoryByReference, categoryByFinalCode);
            assignPreviewAttributeCodes(attributeRows, options, categoryByReference);
            AttributeIndexes attributeIndexes = indexAttributes(attributeRows);
            Map<String, MutableAttributeRow> attributeByReference = attributeIndexes.byReference();
            Map<String, MutableAttributeRow> attributeByFinalCode = attributeIndexes.byFinalCode();

            List<MutableEnumOptionRow> enumRows = parseEnumRows(enumSheet, categoryByReference, categoryByFinalCode, attributeByReference, attributeByFinalCode);
            assignPreviewEnumCodes(enumRows, options, categoryByReference, attributeByReference, attributeByFinalCode);

            resolveCategoryActions(categoryRows, options);
            resolveAttributeActions(attributeRows, options, categoryByReference);
            resolveEnumActions(enumRows, options, categoryByReference, attributeByReference, attributeByFinalCode);

            WorkbookImportDryRunResponseDto response = buildResponse(workbook, options, categoryRows, attributeRows, enumRows, now);
            String importSessionId = UUID.randomUUID().toString();
            response.setImportSessionId(importSessionId);
            response.setCreatedAt(now);

            WorkbookImportSupport.ImportSessionState session = new WorkbookImportSupport.ImportSessionState(
                    importSessionId,
                    normalizedOperator,
                    options,
                    response,
                    toCategoryRecords(categoryRows),
                    toAttributeRecords(attributeRows),
                    toEnumRecords(enumRows),
                    now);
            runtimeService.saveSession(session);
            return response;
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to read workbook", ex);
        }
    }

    private Sheet requireSheet(Workbook workbook, String sheetName) {
        Sheet sheet = workbook.getSheet(sheetName);
        if (sheet == null) {
            throw new IllegalArgumentException("required sheet missing: " + sheetName);
        }
        return sheet;
    }

    private WorkbookImportDryRunOptionsDto normalizeOptions(WorkbookImportDryRunOptionsDto rawOptions) {
        WorkbookImportDryRunOptionsDto options = rawOptions == null ? new WorkbookImportDryRunOptionsDto() : rawOptions;
        if (options.getCodingOptions() == null) {
            options.setCodingOptions(new WorkbookImportDryRunOptionsDto.CodingOptions());
        }
        if (options.getDuplicateOptions() == null) {
            options.setDuplicateOptions(new WorkbookImportDryRunOptionsDto.DuplicateOptions());
        }
        options.getCodingOptions().setCategoryCodeMode(normalizeMode(options.getCodingOptions().getCategoryCodeMode()));
        options.getCodingOptions().setAttributeCodeMode(normalizeMode(options.getCodingOptions().getAttributeCodeMode()));
        options.getCodingOptions().setEnumOptionCodeMode(normalizeMode(options.getCodingOptions().getEnumOptionCodeMode()));
        options.getDuplicateOptions().setCategoryDuplicatePolicy(normalizePolicy(options.getDuplicateOptions().getCategoryDuplicatePolicy()));
        options.getDuplicateOptions().setAttributeDuplicatePolicy(normalizePolicy(options.getDuplicateOptions().getAttributeDuplicatePolicy()));
        options.getDuplicateOptions().setEnumOptionDuplicatePolicy(normalizePolicy(options.getDuplicateOptions().getEnumOptionDuplicatePolicy()));
        return options;
    }

    private String normalizeMode(String value) {
        if (value == null || value.isBlank()) {
            return MODE_EXCEL_MANUAL;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!MODE_EXCEL_MANUAL.equals(normalized) && !MODE_SYSTEM_RULE_AUTO.equals(normalized)) {
            throw new IllegalArgumentException("unsupported code mode: " + value);
        }
        return normalized;
    }

    private String normalizePolicy(String value) {
        if (value == null || value.isBlank()) {
            return POLICY_FAIL;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!POLICY_OVERWRITE.equals(normalized) && !POLICY_KEEP.equals(normalized) && !POLICY_FAIL.equals(normalized)) {
            throw new IllegalArgumentException("unsupported duplicate policy: " + value);
        }
        return normalized;
    }

    private List<MutableCategoryRow> parseCategoryRows(Sheet sheet) {
        List<MutableCategoryRow> rows = new ArrayList<>();
        Set<String> batchCodeKeys = new LinkedHashSet<>();
        Set<String> batchPathKeys = new LinkedHashSet<>();
        Set<String> seenPathKeys = new LinkedHashSet<>();
        for (int index = CATEGORY_DATA_START_ROW_INDEX; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (isBlankRow(row, 4)) {
                continue;
            }

            MutableCategoryRow item = new MutableCategoryRow(sheet.getSheetName(), index + 1);
            item.businessDomain = normalizeBusinessDomain(readCell(row, 0));
            item.excelReferenceCode = trimToNull(readCell(row, 1));
            item.categoryPath = trimToNull(readCell(row, 2));
            item.categoryName = trimToNull(readCell(row, 3));

            if (item.businessDomain == null) {
                item.error("Business_Domain", "CATEGORY_BUSINESS_DOMAIN_REQUIRED", "业务域不能为空", null, null);
            }
            if (item.excelReferenceCode == null) {
                item.error("Category_Code", "CATEGORY_CODE_REQUIRED", "分类编码不能为空", null, null);
            } else if (item.excelReferenceCode.contains("/")) {
                item.error("Category_Code", "CATEGORY_CODE_INVALID", "分类编码不能包含 /", item.excelReferenceCode, "分类编码不能包含路径分隔符");
            }
            if (item.categoryPath == null) {
                item.error("Category_Path", "CATEGORY_PATH_REQUIRED", "分类路径不能为空", null, null);
            } else {
                if (!item.categoryPath.startsWith("/")) {
                    item.error("Category_Path", "CATEGORY_PATH_INVALID", "分类路径必须以 / 开头", item.categoryPath, "路径格式 /A/B/C");
                }
                if (item.categoryPath.endsWith("/")) {
                    item.error("Category_Path", "CATEGORY_PATH_INVALID", "分类路径不能以 / 结尾", item.categoryPath, "路径格式 /A/B/C");
                }
                if (item.categoryPath.contains("//") || item.categoryPath.contains(".")) {
                    item.error("Category_Path", "CATEGORY_PATH_INVALID", "分类路径格式非法", item.categoryPath, "路径不能包含 // 或 .");
                }
                String pathLeaf = pathLeaf(item.categoryPath);
                if (pathLeaf != null && item.excelReferenceCode != null && !Objects.equals(pathLeaf, item.excelReferenceCode)) {
                    item.error("Category_Path", "CATEGORY_PATH_CODE_MISMATCH", "分类路径最后一段必须等于分类编码", item.categoryPath, "路径最后一段必须与分类编码一致");
                }
                item.parentPath = parentPath(item.categoryPath);
                if (item.parentPath != null && item.businessDomain != null && !seenPathKeys.contains(composeCategoryPathKey(item.businessDomain, item.parentPath))) {
                    String parentCode = pathLeaf(item.parentPath);
                    boolean parentExists = parentCode != null && categoryDefRepository.findByBusinessDomainAndCodeKey(item.businessDomain, parentCode).isPresent();
                    if (!parentExists) {
                        item.error("Category_Path", "CATEGORY_PARENT_NOT_FOUND", "父级路径未在当前批次上方出现", item.parentPath, "父节点必须先于子节点出现");
                    }
                }
            }
            if (item.categoryName == null) {
                item.error("Category_Name", "CATEGORY_NAME_REQUIRED", "分类名称不能为空", null, null);
            }

            if (item.businessDomain != null && item.excelReferenceCode != null) {
                String batchCodeKey = composeCategoryCodeKey(item.businessDomain, item.excelReferenceCode);
                if (!batchCodeKeys.add(batchCodeKey)) {
                    item.error("Category_Code", "CATEGORY_CODE_DUPLICATE_IN_BATCH", "批次内分类编码重复", item.excelReferenceCode, "businessDomain + categoryCode 必须唯一");
                }
            }
            if (item.businessDomain != null && item.categoryPath != null) {
                String batchPathKey = composeCategoryPathKey(item.businessDomain, item.categoryPath);
                if (!batchPathKeys.add(batchPathKey)) {
                    item.error("Category_Path", "CATEGORY_PATH_DUPLICATE_IN_BATCH", "批次内分类路径重复", item.categoryPath, "businessDomain + categoryPath 必须唯一");
                }
                seenPathKeys.add(batchPathKey);
            }

            rows.add(item);
        }
        return rows;
    }

    private void assignPreviewCategoryCodes(List<MutableCategoryRow> rows, WorkbookImportDryRunOptionsDto options) {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, List<MutableCategoryRow>> childrenByParent = new LinkedHashMap<>();
        Map<String, MutableCategoryRow> byOwnPath = new LinkedHashMap<>();
        for (MutableCategoryRow row : rows) {
            if (row.businessDomain != null && row.categoryPath != null) {
                byOwnPath.put(composeCategoryPathKey(row.businessDomain, row.categoryPath), row);
            }
            String parentKey = composeCategoryPathKey(row.businessDomain, row.parentPath);
            childrenByParent.computeIfAbsent(parentKey, ignored -> new ArrayList<>()).add(row);
        }

        Set<String> visited = new HashSet<>();
        List<MutableCategoryRow> roots = rows.stream()
                .filter(row -> row.parentPath == null || !byOwnPath.containsKey(composeCategoryPathKey(row.businessDomain, row.parentPath)))
                .sorted(Comparator.comparingInt(row -> row.rowNumber))
                .toList();
        for (MutableCategoryRow root : roots) {
            assignCategorySubtree(root, null, null, options, childrenByParent, visited);
        }
    }

    private void assignCategorySubtree(MutableCategoryRow row,
                                       String parentFinalCode,
                                       String parentFinalPath,
                                       WorkbookImportDryRunOptionsDto options,
                                       Map<String, List<MutableCategoryRow>> childrenByParent,
                                       Set<String> visited) {
        if (row.excelReferenceCode == null || row.businessDomain == null) {
            row.resolvedFinalCode = row.excelReferenceCode;
            row.resolvedFinalPath = row.categoryPath;
        } else if (MODE_EXCEL_MANUAL.equals(options.getCodingOptions().getCategoryCodeMode())) {
            row.resolvedFinalCode = row.excelReferenceCode;
            row.resolvedFinalPath = appendPath(parentFinalPath, row.resolvedFinalCode);
        } else {
            CodeRulePreviewRequestDto request = new CodeRulePreviewRequestDto();
            request.setCount(1);
            LinkedHashMap<String, String> context = new LinkedHashMap<>();
            context.put("BUSINESS_DOMAIN", row.businessDomain);
            if (parentFinalCode != null) {
                context.put("PARENT_CODE", parentFinalCode);
            }
            request.setContext(context);
            String ruleCode = metaCodeRuleSetService.resolveCategoryRuleCode(row.businessDomain);
            CodeRulePreviewResponseDto preview = metaCodeRuleService.preview(ruleCode, request);
            row.resolvedFinalCode = firstExample(preview, row.excelReferenceCode);
            row.resolvedFinalPath = appendPath(parentFinalPath, row.resolvedFinalCode);
        }

        String selfKey = composeCategoryPathKey(row.businessDomain, row.categoryPath);
        if (!visited.add(selfKey)) {
            return;
        }

        List<MutableCategoryRow> children = childrenByParent.getOrDefault(composeCategoryPathKey(row.businessDomain, row.categoryPath), List.of());
        if (children.isEmpty()) {
            return;
        }

        if (MODE_SYSTEM_RULE_AUTO.equals(options.getCodingOptions().getCategoryCodeMode())) {
            List<MutableCategoryRow> autoChildren = children.stream().filter(child -> child.excelReferenceCode != null).sorted(Comparator.comparingInt(child -> child.rowNumber)).toList();
            if (!autoChildren.isEmpty()) {
                CodeRulePreviewRequestDto request = new CodeRulePreviewRequestDto();
                request.setCount(autoChildren.size());
                LinkedHashMap<String, String> context = new LinkedHashMap<>();
                context.put("BUSINESS_DOMAIN", row.businessDomain);
                context.put("PARENT_CODE", row.resolvedFinalCode);
                request.setContext(context);
                String ruleCode = metaCodeRuleSetService.resolveCategoryRuleCode(row.businessDomain);
                CodeRulePreviewResponseDto preview = metaCodeRuleService.preview(ruleCode, request);
                List<String> examples = preview.getExamples() == null ? List.of() : preview.getExamples();
                for (int index = 0; index < autoChildren.size(); index++) {
                    MutableCategoryRow child = autoChildren.get(index);
                    child.resolvedFinalCode = index < examples.size() ? examples.get(index) : child.excelReferenceCode;
                    child.resolvedFinalPath = appendPath(row.resolvedFinalPath, child.resolvedFinalCode);
                }
            }
        }

        for (MutableCategoryRow child : children.stream().sorted(Comparator.comparingInt(candidate -> candidate.rowNumber)).toList()) {
            if (child.resolvedFinalCode == null) {
                child.resolvedFinalCode = child.excelReferenceCode;
                child.resolvedFinalPath = appendPath(row.resolvedFinalPath, child.resolvedFinalCode);
            }
            assignCategorySubtree(child, row.resolvedFinalCode, row.resolvedFinalPath, options, childrenByParent, visited);
        }
    }

    private CategoryIndexes indexCategories(List<MutableCategoryRow> rows) {
        Map<String, MutableCategoryRow> byReference = new LinkedHashMap<>();
        Map<String, MutableCategoryRow> byFinalCode = new LinkedHashMap<>();
        for (MutableCategoryRow row : rows) {
            if (row.businessDomain != null && row.excelReferenceCode != null) {
                byReference.put(composeCategoryCodeKey(row.businessDomain, row.excelReferenceCode), row);
            }
            if (row.businessDomain != null && row.resolvedFinalCode != null) {
                byFinalCode.put(composeCategoryCodeKey(row.businessDomain, row.resolvedFinalCode), row);
            }
        }
        return new CategoryIndexes(byReference, byFinalCode);
    }

    private List<MutableAttributeRow> parseAttributeRows(Sheet sheet,
                                                         Map<String, MutableCategoryRow> categoryByReference,
                                                         Map<String, MutableCategoryRow> categoryByFinalCode) {
        List<MutableAttributeRow> rows = new ArrayList<>();
        Set<String> batchKeySet = new LinkedHashSet<>();
        Set<String> batchFieldSet = new LinkedHashSet<>();
        for (int index = ATTRIBUTE_DATA_START_ROW_INDEX; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (isBlankRow(row, 20)) {
                continue;
            }
            MutableAttributeRow item = new MutableAttributeRow(sheet.getSheetName(), index + 1);
            item.categoryReferenceCode = trimToNull(readCell(row, 0));
            item.categoryName = trimToNull(readCell(row, 1));
            item.attributeReferenceCode = trimToNull(readCell(row, 2));
            item.attributeName = trimToNull(readCell(row, 3));
            item.attributeField = trimToNull(readCell(row, 4));
            item.description = trimToNull(readCell(row, 5));
            String rawDataType = trimToNull(readCell(row, 6));
            item.dataType = normalizeDataType(rawDataType, item, "Data_Type");
            item.unit = trimToNull(readCell(row, 7));
            item.defaultValue = trimToNull(readCell(row, 8));
            item.required = parseYn(row, 9, item, "Required");
            item.unique = parseYn(row, 10, item, "Unique");
            item.searchable = parseYn(row, 11, item, "Searchable");
            item.hidden = parseYn(row, 12, item, "Hidden");
            item.readOnly = parseYn(row, 13, item, "Read_Only");
            item.minValue = parseDecimal(row, 14, item, "Min_Value");
            item.maxValue = parseDecimal(row, 15, item, "Max_Value");
            item.step = parseDecimal(row, 16, item, "Step");
            item.precision = parseInteger(row, 17, item, "Precision");
            item.trueLabel = trimToNull(readCell(row, 18));
            item.falseLabel = trimToNull(readCell(row, 19));

            if (item.categoryReferenceCode == null) {
                item.error("Category_Code", "ATTRIBUTE_CATEGORY_REQUIRED", "所属分类编码不能为空", null, null);
            }
            if (item.attributeReferenceCode == null) {
                item.error("Attribute_Key", "ATTRIBUTE_KEY_REQUIRED", "属性编码不能为空", null, null);
            }
            if (item.attributeName == null) {
                item.error("Attribute_Name", "ATTRIBUTE_NAME_REQUIRED", "属性名称不能为空", null, null);
            }
            if (item.attributeField == null) {
                item.error("Attribute_Field", "ATTRIBUTE_FIELD_REQUIRED", "属性字段名不能为空", null, null);
            }
            if (rawDataType == null) {
                item.error("Data_Type", "ATTRIBUTE_DATA_TYPE_REQUIRED", "数据类型不能为空", null, null);
            }

            MutableCategoryRow category = resolveCategoryRow(item.categoryReferenceCode, categoryByReference, categoryByFinalCode);
            MetaCategoryDef existingCategory = resolveExistingCategory(item.categoryReferenceCode);
            if (category == null && existingCategory == null) {
                item.error("Category_Code", "ATTRIBUTE_CATEGORY_NOT_FOUND", "属性所属分类不存在", item.categoryReferenceCode, "分类必须在工作簿中或数据库中存在");
            } else {
                item.businessDomain = category != null ? category.businessDomain : existingCategory.getBusinessDomain();
                item.resolvedCategoryCode = category != null ? category.resolvedFinalCode : existingCategory.getCodeKey();
                String resolvedCategoryName = category != null ? category.categoryName : latestCategoryName(existingCategory);
                if (item.categoryName != null && resolvedCategoryName != null && !Objects.equals(item.categoryName, resolvedCategoryName)) {
                    item.warn("Category_Name", "ATTRIBUTE_CATEGORY_NAME_MISMATCH", "分类名称与解析结果不一致", item.categoryName, resolvedCategoryName);
                }
            }

            if (item.resolvedCategoryCode != null && item.attributeReferenceCode != null) {
                String batchKey = item.resolvedCategoryCode + "::" + item.attributeReferenceCode;
                if (!batchKeySet.add(batchKey)) {
                    item.error("Attribute_Key", "ATTRIBUTE_KEY_DUPLICATE_IN_BATCH", "批次内属性编码重复", item.attributeReferenceCode, "categoryCode + attributeKey 必须唯一");
                }
            }
            if (item.resolvedCategoryCode != null && item.attributeField != null) {
                String batchField = item.resolvedCategoryCode + "::" + item.attributeField.toLowerCase(Locale.ROOT);
                if (!batchFieldSet.add(batchField)) {
                    item.warn("Attribute_Field", "ATTRIBUTE_FIELD_DUPLICATE_IN_BATCH", "批次内属性字段名重复", item.attributeField, "建议 categoryCode + attributeField 唯一");
                }
            }

            validateTypeDrivenColumns(item);
            rows.add(item);
        }
        return rows;
    }

    private void assignPreviewAttributeCodes(List<MutableAttributeRow> rows,
                                             WorkbookImportDryRunOptionsDto options,
                                             Map<String, MutableCategoryRow> categoryByReference) {
        if (MODE_EXCEL_MANUAL.equals(options.getCodingOptions().getAttributeCodeMode())) {
            rows.forEach(row -> row.resolvedFinalCode = row.attributeReferenceCode);
            return;
        }

        Map<String, List<MutableAttributeRow>> grouped = new LinkedHashMap<>();
        for (MutableAttributeRow row : rows) {
            if (row.businessDomain == null || row.resolvedCategoryCode == null) {
                continue;
            }
            grouped.computeIfAbsent(row.businessDomain + "::" + row.resolvedCategoryCode, ignored -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<String, List<MutableAttributeRow>> entry : grouped.entrySet()) {
            List<MutableAttributeRow> group = entry.getValue().stream().sorted(Comparator.comparingInt(item -> item.rowNumber)).toList();
            MutableAttributeRow first = group.get(0);
            CodeRulePreviewRequestDto request = new CodeRulePreviewRequestDto();
            request.setCount(group.size());
            LinkedHashMap<String, String> context = new LinkedHashMap<>();
            context.put("BUSINESS_DOMAIN", first.businessDomain);
            context.put("CATEGORY_CODE", first.resolvedCategoryCode);
            request.setContext(context);
            String ruleCode = metaCodeRuleSetService.resolveAttributeRuleCode(first.businessDomain);
            CodeRulePreviewResponseDto preview = metaCodeRuleService.preview(ruleCode, request);
            List<String> examples = preview.getExamples() == null ? List.of() : preview.getExamples();
            for (int index = 0; index < group.size(); index++) {
                group.get(index).resolvedFinalCode = index < examples.size() ? examples.get(index) : group.get(index).attributeReferenceCode;
            }
        }
    }

    private AttributeIndexes indexAttributes(List<MutableAttributeRow> rows) {
        Map<String, MutableAttributeRow> byReference = new LinkedHashMap<>();
        Map<String, MutableAttributeRow> byFinalCode = new LinkedHashMap<>();
        for (MutableAttributeRow row : rows) {
            if (row.businessDomain != null && row.categoryReferenceCode != null && row.attributeReferenceCode != null) {
                byReference.put(composeAttributeKey(row.businessDomain, row.categoryReferenceCode, row.attributeReferenceCode), row);
            }
            if (row.businessDomain != null && row.resolvedCategoryCode != null && row.resolvedFinalCode != null) {
                byFinalCode.put(composeAttributeKey(row.businessDomain, row.resolvedCategoryCode, row.resolvedFinalCode), row);
            }
        }
        return new AttributeIndexes(byReference, byFinalCode);
    }

    private List<MutableEnumOptionRow> parseEnumRows(Sheet sheet,
                                                     Map<String, MutableCategoryRow> categoryByReference,
                                                     Map<String, MutableCategoryRow> categoryByFinalCode,
                                                     Map<String, MutableAttributeRow> attributeByReference,
                                                     Map<String, MutableAttributeRow> attributeByFinalCode) {
        List<MutableEnumOptionRow> rows = new ArrayList<>();
        Set<String> batchOptionKeys = new LinkedHashSet<>();
        Set<String> batchNameKeys = new LinkedHashSet<>();
        for (int index = ENUM_DATA_START_ROW_INDEX; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (isBlankRow(row, 5)) {
                continue;
            }
            MutableEnumOptionRow item = new MutableEnumOptionRow(sheet.getSheetName(), index + 1);
            item.categoryReferenceCode = trimToNull(readCell(row, 0));
            item.attributeReferenceCode = trimToNull(readCell(row, 1));
            item.optionReferenceCode = trimToNull(readCell(row, 2));
            item.optionName = trimToNull(readCell(row, 3));
            item.displayLabel = trimToNull(readCell(row, 4));

            if (item.categoryReferenceCode == null) {
                item.error("Category_Code", "ENUM_CATEGORY_REQUIRED", "所属分类编码不能为空", null, null);
            }
            if (item.attributeReferenceCode == null) {
                item.error("Attribute_Key", "ENUM_ATTRIBUTE_REQUIRED", "所属属性编码不能为空", null, null);
            }
            if (item.optionReferenceCode == null) {
                item.error("Option_Code", "ENUM_OPTION_CODE_REQUIRED", "枚举值编码不能为空", null, null);
            }
            if (item.optionName == null) {
                item.error("Option_Name", "ENUM_OPTION_NAME_REQUIRED", "枚举值名称不能为空", null, null);
            }

            MutableCategoryRow category = resolveCategoryRow(item.categoryReferenceCode, categoryByReference, categoryByFinalCode);
            MetaCategoryDef existingCategory = resolveExistingCategory(item.categoryReferenceCode);
            if (category == null && existingCategory == null) {
                item.error("Category_Code", "ENUM_CATEGORY_NOT_FOUND", "枚举值所属分类不存在", item.categoryReferenceCode, "分类必须在工作簿中或数据库中存在");
            } else {
                item.businessDomain = category != null ? category.businessDomain : existingCategory.getBusinessDomain();
                item.resolvedCategoryCode = category != null ? category.resolvedFinalCode : existingCategory.getCodeKey();
            }

            if (item.businessDomain != null) {
                MutableAttributeRow batchAttribute = resolveAttributeRow(item, attributeByReference, attributeByFinalCode);
                MetaAttributeDef existingAttribute = resolveExistingAttribute(item.businessDomain, item.resolvedCategoryCode, item.attributeReferenceCode);
                if (batchAttribute == null && existingAttribute == null) {
                    item.error("Attribute_Key", "ENUM_ATTRIBUTE_NOT_FOUND", "枚举值所属属性不存在", item.attributeReferenceCode, "属性必须在工作簿中或数据库中存在");
                } else {
                    item.resolvedAttributeCode = batchAttribute != null ? batchAttribute.resolvedFinalCode : existingAttribute.getKey();
                    String dataType = batchAttribute != null ? batchAttribute.dataType : latestAttributeDataType(existingAttribute);
                    if (!isEnumLike(dataType)) {
                        item.error("Attribute_Key", "ENUM_ATTRIBUTE_TYPE_INVALID", "枚举值只能绑定到 enum 或 multi_enum 属性", item.attributeReferenceCode, "属性类型必须是 enum 或 multi_enum");
                    }
                }
            }

            if (item.resolvedCategoryCode != null && item.resolvedAttributeCode != null && item.optionReferenceCode != null) {
                String batchKey = item.resolvedCategoryCode + "::" + item.resolvedAttributeCode + "::" + item.optionReferenceCode;
                if (!batchOptionKeys.add(batchKey)) {
                    item.error("Option_Code", "ENUM_OPTION_CODE_DUPLICATE_IN_BATCH", "批次内枚举值编码重复", item.optionReferenceCode, "categoryCode + attributeKey + optionCode 必须唯一");
                }
            }
            if (item.resolvedCategoryCode != null && item.resolvedAttributeCode != null && item.optionName != null) {
                String batchName = item.resolvedCategoryCode + "::" + item.resolvedAttributeCode + "::" + item.optionName.toLowerCase(Locale.ROOT);
                if (!batchNameKeys.add(batchName)) {
                    item.warn("Option_Name", "ENUM_OPTION_NAME_DUPLICATE_IN_BATCH", "同一属性下枚举值名称重复", item.optionName, "建议同一属性下 optionName 唯一");
                }
            }

            rows.add(item);
        }
        return rows;
    }

    private void assignPreviewEnumCodes(List<MutableEnumOptionRow> rows,
                                        WorkbookImportDryRunOptionsDto options,
                                        Map<String, MutableCategoryRow> categoryByReference,
                                        Map<String, MutableAttributeRow> attributeByReference,
                                        Map<String, MutableAttributeRow> attributeByFinalCode) {
        if (MODE_EXCEL_MANUAL.equals(options.getCodingOptions().getEnumOptionCodeMode())) {
            rows.forEach(row -> row.resolvedFinalCode = row.optionReferenceCode);
            return;
        }

        Map<String, List<MutableEnumOptionRow>> grouped = new LinkedHashMap<>();
        for (MutableEnumOptionRow row : rows) {
            if (row.businessDomain == null || row.resolvedCategoryCode == null || row.resolvedAttributeCode == null) {
                continue;
            }
            grouped.computeIfAbsent(row.businessDomain + "::" + row.resolvedCategoryCode + "::" + row.resolvedAttributeCode,
                    ignored -> new ArrayList<>()).add(row);
        }
        for (List<MutableEnumOptionRow> group : grouped.values()) {
            MutableEnumOptionRow first = group.get(0);
            CodeRulePreviewRequestDto request = new CodeRulePreviewRequestDto();
            request.setCount(group.size());
            LinkedHashMap<String, String> context = new LinkedHashMap<>();
            context.put("BUSINESS_DOMAIN", first.businessDomain);
            context.put("CATEGORY_CODE", first.resolvedCategoryCode);
            context.put("ATTRIBUTE_CODE", first.resolvedAttributeCode);
            request.setContext(context);
            String ruleCode = metaCodeRuleSetService.resolveLovRuleCode(first.businessDomain);
            CodeRulePreviewResponseDto preview = metaCodeRuleService.preview(ruleCode, request);
            List<String> examples = preview.getExamples() == null ? List.of() : preview.getExamples();
            List<MutableEnumOptionRow> orderedGroup = group.stream().sorted(Comparator.comparingInt(item -> item.rowNumber)).toList();
            for (int index = 0; index < orderedGroup.size(); index++) {
                orderedGroup.get(index).resolvedFinalCode = index < examples.size() ? examples.get(index) : orderedGroup.get(index).optionReferenceCode;
            }
        }
    }

    private void resolveCategoryActions(List<MutableCategoryRow> rows, WorkbookImportDryRunOptionsDto options) {
        String policy = options.getDuplicateOptions().getCategoryDuplicatePolicy();
        for (MutableCategoryRow row : rows) {
            if (row.businessDomain == null || row.resolvedFinalCode == null) {
                row.resolvedAction = "CREATE";
                continue;
            }
            boolean exists = categoryDefRepository.findByBusinessDomainAndCodeKey(row.businessDomain, row.resolvedFinalCode).isPresent();
            if (!exists) {
                row.resolvedAction = "CREATE";
                continue;
            }
            applyResolvedAction(policy, row, "Category_Code", "CATEGORY_DUPLICATE");
        }
    }

    private void resolveAttributeActions(List<MutableAttributeRow> rows,
                                         WorkbookImportDryRunOptionsDto options,
                                         Map<String, MutableCategoryRow> categoryByReference) {
        String policy = options.getDuplicateOptions().getAttributeDuplicatePolicy();
        for (MutableAttributeRow row : rows) {
            if (row.resolvedCategoryCode == null || row.resolvedFinalCode == null) {
                row.resolvedAction = "CREATE";
                continue;
            }
            MetaCategoryDef categoryDef = resolveExistingCategory(row.resolvedCategoryCode);
            if (categoryDef == null) {
                MutableCategoryRow batchCategory = resolveCategoryRow(row.categoryReferenceCode, categoryByReference, Map.of());
                if (batchCategory == null) {
                    row.resolvedAction = "CREATE";
                    continue;
                }
            } else if (attributeDefRepository.findActiveByCategoryDefAndKey(categoryDef, row.resolvedFinalCode).isPresent()) {
                applyResolvedAction(policy, row, "Attribute_Key", "ATTRIBUTE_DUPLICATE");
                continue;
            }
            row.resolvedAction = "CREATE";
        }
    }

    private void resolveEnumActions(List<MutableEnumOptionRow> rows,
                                    WorkbookImportDryRunOptionsDto options,
                                    Map<String, MutableCategoryRow> categoryByReference,
                                    Map<String, MutableAttributeRow> attributeByReference,
                                    Map<String, MutableAttributeRow> attributeByFinalCode) {
        String policy = options.getDuplicateOptions().getEnumOptionDuplicatePolicy();
        for (MutableEnumOptionRow row : rows) {
            if (row.resolvedCategoryCode == null || row.resolvedAttributeCode == null || row.resolvedFinalCode == null) {
                row.resolvedAction = "CREATE";
                continue;
            }
            MetaAttributeDef existingAttribute = resolveExistingAttribute(row.businessDomain, row.resolvedCategoryCode, row.resolvedAttributeCode);
            if (existingAttribute == null) {
                row.resolvedAction = "CREATE";
                continue;
            }
            Map<String, ExistingEnumValue> existingValues = loadExistingEnumValues(existingAttribute);
            ExistingEnumValue existing = existingValues.get(row.resolvedFinalCode);
            if (existing != null) {
                applyResolvedAction(policy, row, "Option_Code", "ENUM_OPTION_DUPLICATE");
            } else {
                row.resolvedAction = "CREATE";
            }
        }
    }

    private WorkbookImportDryRunResponseDto buildResponse(Workbook workbook,
                                                          WorkbookImportDryRunOptionsDto options,
                                                          List<MutableCategoryRow> categories,
                                                          List<MutableAttributeRow> attributes,
                                                          List<MutableEnumOptionRow> enumOptions,
                                                          OffsetDateTime createdAt) {
        WorkbookImportDryRunResponseDto response = new WorkbookImportDryRunResponseDto();
        response.setTemplate(buildTemplate(workbook));
        response.setResolvedImportOptions(options);
        response.setPreview(buildPreview(categories, attributes, enumOptions));
        response.setIssues(collectAllIssues(categories, attributes, enumOptions));
        response.setSummary(buildSummary(categories, attributes, enumOptions));
        response.setChangeSummary(buildChangeSummary(categories, attributes, enumOptions));
        response.setCreatedAt(createdAt);
        return response;
    }

    private WorkbookImportDryRunResponseDto.TemplateDto buildTemplate(Workbook workbook) {
        WorkbookImportDryRunResponseDto.TemplateDto dto = new WorkbookImportDryRunResponseDto.TemplateDto();
        List<String> sheetNames = new ArrayList<>();
        for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
            sheetNames.add(workbook.getSheetName(index));
        }
        dto.setRecognized(sheetNames.containsAll(List.of(SHEET_CATEGORIES, SHEET_ATTRIBUTES, SHEET_ENUMS)));
        dto.setTemplateVersion("v1");
        dto.setSheetNames(sheetNames);
        return dto;
    }

    private WorkbookImportDryRunResponseDto.PreviewDto buildPreview(List<MutableCategoryRow> categories,
                                                                    List<MutableAttributeRow> attributes,
                                                                    List<MutableEnumOptionRow> enumOptions) {
        WorkbookImportDryRunResponseDto.PreviewDto preview = new WorkbookImportDryRunResponseDto.PreviewDto();
        preview.setCategories(categories.stream().map(this::toCategoryPreview).toList());
        preview.setAttributes(attributes.stream().map(this::toAttributePreview).toList());
        preview.setEnumOptions(enumOptions.stream().map(this::toEnumPreview).toList());
        return preview;
    }

    private WorkbookImportDryRunResponseDto.CategoryPreviewItemDto toCategoryPreview(MutableCategoryRow row) {
        WorkbookImportDryRunResponseDto.CategoryPreviewItemDto dto = new WorkbookImportDryRunResponseDto.CategoryPreviewItemDto();
        dto.setSheetName(row.sheetName);
        dto.setRowNumber(row.rowNumber);
        dto.setBusinessDomain(row.businessDomain);
        dto.setExcelReferenceCode(row.excelReferenceCode);
        dto.setCategoryCode(row.excelReferenceCode);
        dto.setCategoryPath(row.categoryPath);
        dto.setResolvedFinalCode(row.resolvedFinalCode);
        dto.setResolvedFinalPath(row.resolvedFinalPath);
        dto.setCodeMode(row.codeMode());
        dto.setCategoryName(row.categoryName);
        dto.setParentPath(row.parentPath);
        dto.setParentCode(pathLeaf(row.parentPath));
        dto.setResolvedAction(row.resolvedAction);
        dto.setIssues(new ArrayList<>(row.issues));
        return dto;
    }

    private WorkbookImportDryRunResponseDto.AttributePreviewItemDto toAttributePreview(MutableAttributeRow row) {
        WorkbookImportDryRunResponseDto.AttributePreviewItemDto dto = new WorkbookImportDryRunResponseDto.AttributePreviewItemDto();
        dto.setSheetName(row.sheetName);
        dto.setRowNumber(row.rowNumber);
        dto.setBusinessDomain(row.businessDomain);
        dto.setCategoryCode(row.resolvedCategoryCode);
        dto.setExcelReferenceCode(row.attributeReferenceCode);
        dto.setAttributeKey(row.attributeReferenceCode);
        dto.setResolvedFinalCode(row.resolvedFinalCode);
        dto.setCodeMode(row.codeMode());
        dto.setAttributeName(row.attributeName);
        dto.setAttributeField(row.attributeField);
        dto.setDataType(row.dataType);
        dto.setResolvedAction(row.resolvedAction);
        dto.setIssues(new ArrayList<>(row.issues));
        return dto;
    }

    private WorkbookImportDryRunResponseDto.EnumOptionPreviewItemDto toEnumPreview(MutableEnumOptionRow row) {
        WorkbookImportDryRunResponseDto.EnumOptionPreviewItemDto dto = new WorkbookImportDryRunResponseDto.EnumOptionPreviewItemDto();
        dto.setSheetName(row.sheetName);
        dto.setRowNumber(row.rowNumber);
        dto.setCategoryCode(row.resolvedCategoryCode);
        dto.setAttributeKey(row.resolvedAttributeCode);
        dto.setExcelReferenceCode(row.optionReferenceCode);
        dto.setOptionCode(row.optionReferenceCode);
        dto.setResolvedFinalCode(row.resolvedFinalCode);
        dto.setCodeMode(row.codeMode());
        dto.setOptionName(row.optionName);
        dto.setDisplayLabel(row.displayLabel);
        dto.setResolvedAction(row.resolvedAction);
        dto.setIssues(new ArrayList<>(row.issues));
        return dto;
    }

    private WorkbookImportDryRunResponseDto.SummaryDto buildSummary(List<MutableCategoryRow> categories,
                                                                    List<MutableAttributeRow> attributes,
                                                                    List<MutableEnumOptionRow> enumOptions) {
        WorkbookImportDryRunResponseDto.SummaryDto dto = new WorkbookImportDryRunResponseDto.SummaryDto();
        List<WorkbookImportDryRunResponseDto.IssueDto> issues = collectAllIssues(categories, attributes, enumOptions);
        dto.setCategoryRowCount(categories.size());
        dto.setAttributeRowCount(attributes.size());
        dto.setEnumRowCount(enumOptions.size());
        dto.setErrorCount((int) issues.stream().filter(issue -> "ERROR".equalsIgnoreCase(issue.getLevel())).count());
        dto.setWarningCount((int) issues.stream().filter(issue -> "WARNING".equalsIgnoreCase(issue.getLevel())).count());
        dto.setCanImport(dto.getErrorCount() == 0);
        return dto;
    }

    private WorkbookImportDryRunResponseDto.ChangeSummaryDto buildChangeSummary(List<MutableCategoryRow> categories,
                                                                                List<MutableAttributeRow> attributes,
                                                                                List<MutableEnumOptionRow> enumOptions) {
        WorkbookImportDryRunResponseDto.ChangeSummaryDto dto = new WorkbookImportDryRunResponseDto.ChangeSummaryDto();
        dto.setCategories(buildCounters(categories.stream().map(item -> item.resolvedAction).toList()));
        dto.setAttributes(buildCounters(attributes.stream().map(item -> item.resolvedAction).toList()));
        dto.setEnumOptions(buildCounters(enumOptions.stream().map(item -> item.resolvedAction).toList()));
        return dto;
    }

    private WorkbookImportDryRunResponseDto.ChangeCounterDto buildCounters(Collection<String> actions) {
        WorkbookImportDryRunResponseDto.ChangeCounterDto dto = new WorkbookImportDryRunResponseDto.ChangeCounterDto();
        dto.setCreate(countAction(actions, "CREATE"));
        dto.setUpdate(countAction(actions, "UPDATE"));
        dto.setSkip(countAction(actions, "KEEP_EXISTING"));
        dto.setConflict(countAction(actions, "CONFLICT"));
        return dto;
    }

    private int countAction(Collection<String> actions, String expected) {
        return (int) actions.stream().filter(expected::equalsIgnoreCase).count();
    }

    private List<WorkbookImportDryRunResponseDto.IssueDto> collectAllIssues(List<MutableCategoryRow> categories,
                                                                            List<MutableAttributeRow> attributes,
                                                                            List<MutableEnumOptionRow> enumOptions) {
        List<WorkbookImportDryRunResponseDto.IssueDto> issues = new ArrayList<>();
        categories.forEach(item -> issues.addAll(item.issues));
        attributes.forEach(item -> issues.addAll(item.issues));
        enumOptions.forEach(item -> issues.addAll(item.issues));
        return issues;
    }

    private List<WorkbookImportSupport.ParsedCategoryRow> toCategoryRecords(List<MutableCategoryRow> rows) {
        return rows.stream().map(row -> new WorkbookImportSupport.ParsedCategoryRow(
                row.sheetName,
                row.rowNumber,
                row.businessDomain,
                row.excelReferenceCode,
                row.categoryPath,
                row.categoryName,
                row.parentPath,
                new ArrayList<>(row.issues))).toList();
    }

    private List<WorkbookImportSupport.ParsedAttributeRow> toAttributeRecords(List<MutableAttributeRow> rows) {
        return rows.stream().map(row -> new WorkbookImportSupport.ParsedAttributeRow(
                row.sheetName,
                row.rowNumber,
                row.categoryReferenceCode,
                row.categoryName,
                row.attributeReferenceCode,
                row.attributeName,
                row.attributeField,
                row.description,
                row.dataType,
                row.unit,
                row.defaultValue,
                row.required,
                row.unique,
                row.searchable,
                row.hidden,
                row.readOnly,
                row.minValue,
                row.maxValue,
                row.step,
                row.precision,
                row.trueLabel,
                row.falseLabel,
                new ArrayList<>(row.issues))).toList();
    }

    private List<WorkbookImportSupport.ParsedEnumOptionRow> toEnumRecords(List<MutableEnumOptionRow> rows) {
        return rows.stream().map(row -> new WorkbookImportSupport.ParsedEnumOptionRow(
                row.sheetName,
                row.rowNumber,
                row.categoryReferenceCode,
                row.attributeReferenceCode,
                row.optionReferenceCode,
                row.optionName,
                row.displayLabel,
                new ArrayList<>(row.issues))).toList();
    }

    private MutableCategoryRow resolveCategoryRow(String categoryCode,
                                                  Map<String, MutableCategoryRow> categoryByReference,
                                                  Map<String, MutableCategoryRow> categoryByFinalCode) {
        if (categoryCode == null) {
            return null;
        }
        for (MutableCategoryRow row : categoryByReference.values()) {
            if (Objects.equals(categoryCode, row.excelReferenceCode)) {
                return row;
            }
        }
        for (MutableCategoryRow row : categoryByFinalCode.values()) {
            if (Objects.equals(categoryCode, row.resolvedFinalCode)) {
                return row;
            }
        }
        return null;
    }

    private MutableAttributeRow resolveAttributeRow(MutableEnumOptionRow enumRow,
                                                    Map<String, MutableAttributeRow> attributeByReference,
                                                    Map<String, MutableAttributeRow> attributeByFinalCode) {
        if (enumRow.businessDomain == null || enumRow.categoryReferenceCode == null || enumRow.attributeReferenceCode == null) {
            return null;
        }
        MutableAttributeRow byReference = attributeByReference.get(composeAttributeKey(enumRow.businessDomain, enumRow.categoryReferenceCode, enumRow.attributeReferenceCode));
        if (byReference != null) {
            return byReference;
        }
        return attributeByFinalCode.get(composeAttributeKey(enumRow.businessDomain, enumRow.resolvedCategoryCode, enumRow.attributeReferenceCode));
    }

    private MetaCategoryDef resolveExistingCategory(String categoryCode) {
        if (categoryCode == null) {
            return null;
        }
        return categoryDefRepository.findByCodeKey(categoryCode).orElse(null);
    }

    private MetaAttributeDef resolveExistingAttribute(String businessDomain, String categoryCode, String attributeKey) {
        if (categoryCode == null || attributeKey == null) {
            return null;
        }
        MetaCategoryDef category = categoryDefRepository.findByCodeKey(categoryCode).orElse(null);
        if (category == null) {
            return null;
        }
        return attributeDefRepository.findActiveByCategoryDefAndKey(category, attributeKey).orElse(null);
    }

    private Map<String, ExistingEnumValue> loadExistingEnumValues(MetaAttributeDef attributeDef) {
        if (attributeDef == null) {
            return Collections.emptyMap();
        }
        MetaLovDef lovDef = lovDefRepository.findByAttributeDef(attributeDef).stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getStatus() == null || !"deleted".equalsIgnoreCase(item.getStatus()))
                .findFirst()
                .orElse(null);
        if (lovDef == null) {
            return Collections.emptyMap();
        }
        MetaLovVersion version = lovVersionRepository.findLatestByDef(lovDef).orElse(null);
        if (version == null || version.getValueJson() == null || version.getValueJson().isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, ExistingEnumValue> result = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(version.getValueJson());
            JsonNode values = root.path("values");
            if (!values.isArray()) {
                return Collections.emptyMap();
            }
            for (JsonNode item : values) {
                if (item == null || item.isNull()) {
                    continue;
                }
                String code = trimToNull(item.path("code").asText(null));
                String name = trimToNull(item.path("name").asText(null));
                if (name == null) {
                    name = trimToNull(item.path("value").asText(null));
                }
                if (code != null) {
                    result.put(code, new ExistingEnumValue(code, name));
                }
            }
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
        return result;
    }

    private String latestCategoryName(MetaCategoryDef def) {
        if (def == null) {
            return null;
        }
        return categoryVersionRepository.findLatestByDef(def).map(MetaCategoryVersion::getDisplayName).orElse(def.getCodeKey());
    }

    private String latestAttributeDataType(MetaAttributeDef def) {
        if (def == null) {
            return null;
        }
        return attributeVersionRepository.findLatestByDef(def).map(MetaAttributeVersion::getDataType).orElse(null);
    }

    private void validateTypeDrivenColumns(MutableAttributeRow row) {
        if (row.dataType == null) {
            return;
        }
        boolean numberType = "number".equals(row.dataType);
        if (!numberType && (row.minValue != null || row.maxValue != null || row.step != null || row.precision != null)) {
            row.warn("Data_Type", "ATTRIBUTE_NUMBER_COLUMNS_MISMATCH", "当前数据类型不建议填写数值约束列", row.dataType, "仅 number 类型建议填写最小值、最大值、步长和精度");
        }
        boolean boolType = "bool".equals(row.dataType);
        if (!boolType && (row.trueLabel != null || row.falseLabel != null)) {
            row.warn("Data_Type", "ATTRIBUTE_BOOL_COLUMNS_MISMATCH", "当前数据类型不建议填写布尔标签列", row.dataType, "仅 bool 类型建议填写 trueLabel 和 falseLabel");
        }
    }

    private Boolean parseYn(Row row, int cellIndex, MutableIssueHolder holder, String columnName) {
        String value = trimToNull(readCell(row, cellIndex));
        if (value == null) {
            return null;
        }
        if ("Y".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("N".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        holder.error(columnName, "YN_VALUE_INVALID", "布尔标志列仅接受 Y/N", value, "只接受 Y 或 N");
        return null;
    }

    private BigDecimal parseDecimal(Row row, int cellIndex, MutableIssueHolder holder, String columnName) {
        String value = trimToNull(readCell(row, cellIndex));
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            holder.error(columnName, "DECIMAL_VALUE_INVALID", "数值列格式非法", value, "请输入合法数字");
            return null;
        }
    }

    private Integer parseInteger(Row row, int cellIndex, MutableIssueHolder holder, String columnName) {
        String value = trimToNull(readCell(row, cellIndex));
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            holder.error(columnName, "INTEGER_VALUE_INVALID", "整数列格式非法", value, "请输入合法整数");
            return null;
        }
    }

    private String normalizeDataType(String value, MutableIssueHolder holder, String columnName) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_DATA_TYPES.contains(normalized)) {
            if (holder != null) {
                holder.error(
                        columnName,
                        "ATTRIBUTE_DATA_TYPE_INVALID",
                        "不支持的数据类型",
                        value,
                        "支持值: " + String.join(", ", SUPPORTED_DATA_TYPES));
            }
            return null;
        }
        return normalized;
    }

    private boolean isEnumLike(String dataType) {
        if (dataType == null) {
            return false;
        }
        return "enum".equalsIgnoreCase(dataType) || "multi_enum".equalsIgnoreCase(dataType) || "multi-enum".equalsIgnoreCase(dataType);
    }

    private void applyResolvedAction(String policy, MutableIssueHolder row, String columnName, String code) {
        switch (policy) {
            case POLICY_OVERWRITE -> row.setResolvedAction("UPDATE");
            case POLICY_KEEP -> row.setResolvedAction("KEEP_EXISTING");
            case POLICY_FAIL -> {
                row.setResolvedAction("CONFLICT");
                row.error(columnName, code, "检测到重复数据，且当前策略为 FAIL_ON_DUPLICATE", null, "请改为覆盖或保留已有数据");
            }
            default -> row.setResolvedAction("CREATE");
        }
    }

    private boolean isBlankRow(Row row, int inputColumnCount) {
        if (row == null) {
            return true;
        }
        for (int index = 0; index < inputColumnCount; index++) {
            if (trimToNull(readCell(row, index)) != null) {
                return false;
            }
        }
        return true;
    }

    private String readCell(Row row, int index) {
        if (row == null) {
            return null;
        }
        Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) {
            return null;
        }
        String value = dataFormatter.formatCellValue(cell);
        return value == null ? null : value.trim();
    }

    private String normalizeBusinessDomain(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String firstExample(CodeRulePreviewResponseDto preview, String fallback) {
        if (preview == null || preview.getExamples() == null || preview.getExamples().isEmpty()) {
            return fallback;
        }
        return preview.getExamples().get(0);
    }

    private String appendPath(String parentPath, String code) {
        if (code == null) {
            return parentPath;
        }
        if (parentPath == null || parentPath.isBlank()) {
            return "/" + code;
        }
        return parentPath + "/" + code;
    }

    private String pathLeaf(String path) {
        String normalized = trimToNull(path);
        if (normalized == null) {
            return null;
        }
        String[] segments = normalized.split("/");
        for (int index = segments.length - 1; index >= 0; index--) {
            if (!segments[index].isBlank()) {
                return segments[index];
            }
        }
        return null;
    }

    private String parentPath(String path) {
        String normalized = trimToNull(path);
        if (normalized == null) {
            return null;
        }
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash <= 0) {
            return null;
        }
        return normalized.substring(0, lastSlash);
    }

    private String composeCategoryCodeKey(String businessDomain, String code) {
        return (businessDomain == null ? "" : businessDomain) + "::" + (code == null ? "" : code);
    }

    private String composeCategoryPathKey(String businessDomain, String path) {
        return (businessDomain == null ? "" : businessDomain) + "::" + (path == null ? "<ROOT>" : path);
    }

    private String composeAttributeKey(String businessDomain, String categoryCode, String attributeKey) {
        return (businessDomain == null ? "" : businessDomain) + "::" + (categoryCode == null ? "" : categoryCode) + "::" + (attributeKey == null ? "" : attributeKey);
    }

    private String normalizeOperator(String operator) {
        String normalized = trimToNull(operator);
        return normalized == null ? "system" : normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record ExistingEnumValue(String code, String name) {
    }

    private interface MutableIssueHolder {
        void error(String columnName, String errorCode, String message, String rawValue, String expectedRule);

        void warn(String columnName, String errorCode, String message, String rawValue, String expectedRule);

        void setResolvedAction(String action);
    }

    private record CategoryIndexes(
            Map<String, MutableCategoryRow> byReference,
            Map<String, MutableCategoryRow> byFinalCode
    ) {
    }

    private record AttributeIndexes(
            Map<String, MutableAttributeRow> byReference,
            Map<String, MutableAttributeRow> byFinalCode
    ) {
    }

    private abstract static class BaseMutableRow implements MutableIssueHolder {
        protected final String sheetName;
        protected final int rowNumber;
        protected final List<WorkbookImportDryRunResponseDto.IssueDto> issues = new ArrayList<>();
        protected String resolvedAction = "CREATE";

        protected BaseMutableRow(String sheetName, int rowNumber) {
            this.sheetName = sheetName;
            this.rowNumber = rowNumber;
        }

        @Override
        public void error(String columnName, String errorCode, String message, String rawValue, String expectedRule) {
            issues.add(issue("ERROR", sheetName, rowNumber, columnName, errorCode, message, rawValue, expectedRule));
        }

        @Override
        public void warn(String columnName, String errorCode, String message, String rawValue, String expectedRule) {
            issues.add(issue("WARNING", sheetName, rowNumber, columnName, errorCode, message, rawValue, expectedRule));
        }

        @Override
        public void setResolvedAction(String action) {
            this.resolvedAction = action;
        }

        protected WorkbookImportDryRunResponseDto.IssueDto issue(String level,
                                                                 String sheetName,
                                                                 Integer rowNumber,
                                                                 String columnName,
                                                                 String errorCode,
                                                                 String message,
                                                                 String rawValue,
                                                                 String expectedRule) {
            WorkbookImportDryRunResponseDto.IssueDto dto = new WorkbookImportDryRunResponseDto.IssueDto();
            dto.setLevel(level);
            dto.setSheetName(sheetName);
            dto.setRowNumber(rowNumber);
            dto.setColumnName(columnName);
            dto.setErrorCode(errorCode);
            dto.setMessage(message);
            dto.setRawValue(rawValue);
            dto.setExpectedRule(expectedRule);
            return dto;
        }
    }

    private static final class MutableCategoryRow extends BaseMutableRow {
        private String businessDomain;
        private String excelReferenceCode;
        private String categoryPath;
        private String categoryName;
        private String parentPath;
        private String resolvedFinalCode;
        private String resolvedFinalPath;

        private MutableCategoryRow(String sheetName, int rowNumber) {
            super(sheetName, rowNumber);
        }

        private String codeMode() {
            return resolvedFinalCode != null && Objects.equals(resolvedFinalCode, excelReferenceCode)
                    ? MODE_EXCEL_MANUAL
                    : MODE_SYSTEM_RULE_AUTO;
        }
    }

    private static final class MutableAttributeRow extends BaseMutableRow {
        private String businessDomain;
        private String categoryReferenceCode;
        private String categoryName;
        private String resolvedCategoryCode;
        private String attributeReferenceCode;
        private String resolvedFinalCode;
        private String attributeName;
        private String attributeField;
        private String description;
        private String dataType;
        private String unit;
        private String defaultValue;
        private Boolean required;
        private Boolean unique;
        private Boolean searchable;
        private Boolean hidden;
        private Boolean readOnly;
        private BigDecimal minValue;
        private BigDecimal maxValue;
        private BigDecimal step;
        private Integer precision;
        private String trueLabel;
        private String falseLabel;

        private MutableAttributeRow(String sheetName, int rowNumber) {
            super(sheetName, rowNumber);
        }

        private String codeMode() {
            return resolvedFinalCode != null && Objects.equals(resolvedFinalCode, attributeReferenceCode)
                    ? MODE_EXCEL_MANUAL
                    : MODE_SYSTEM_RULE_AUTO;
        }
    }

    private static final class MutableEnumOptionRow extends BaseMutableRow {
        private String businessDomain;
        private String categoryReferenceCode;
        private String resolvedCategoryCode;
        private String attributeReferenceCode;
        private String resolvedAttributeCode;
        private String optionReferenceCode;
        private String resolvedFinalCode;
        private String optionName;
        private String displayLabel;

        private MutableEnumOptionRow(String sheetName, int rowNumber) {
            super(sheetName, rowNumber);
        }

        private String codeMode() {
            return resolvedFinalCode != null && Objects.equals(resolvedFinalCode, optionReferenceCode)
                    ? MODE_EXCEL_MANUAL
                    : MODE_SYSTEM_RULE_AUTO;
        }
    }
}