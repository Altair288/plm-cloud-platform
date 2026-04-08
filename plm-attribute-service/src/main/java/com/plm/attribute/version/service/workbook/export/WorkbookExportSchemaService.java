package com.plm.attribute.version.service.workbook.export;

import com.plm.common.api.dto.exports.workbook.WorkbookExportColumnRequestDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportSchemaResponseDto;
import com.plm.common.api.dto.exports.workbook.WorkbookExportStartRequestDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class WorkbookExportSchemaService {

    private static final String SCHEMA_VERSION = "2026-04-08";

    private final LinkedHashMap<String, ModuleDefinition> definitions = buildDefinitions();

    public WorkbookExportSchemaResponseDto schema() {
        WorkbookExportSchemaResponseDto dto = new WorkbookExportSchemaResponseDto();
        dto.setSchemaVersion(SCHEMA_VERSION);
        List<WorkbookExportSchemaResponseDto.ModuleSchemaDto> modules = new ArrayList<>();
        for (ModuleDefinition definition : definitions.values()) {
            modules.add(definition.toDto());
        }
        dto.setModules(modules);
        return dto;
    }

    public WorkbookExportStartRequestDto normalizeRequest(WorkbookExportStartRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (request.getBusinessDomain() == null || request.getBusinessDomain().isBlank()) {
            throw new IllegalArgumentException("businessDomain is required");
        }
        if (request.getScope() == null || request.getScope().getCategoryIds() == null || request.getScope().getCategoryIds().isEmpty()) {
            throw new IllegalArgumentException("scope.categoryIds is required");
        }
        if (request.getOutput() == null) {
            throw new IllegalArgumentException("output is required");
        }

        String format = normalize(request.getOutput().getFormat());
        if (format == null) {
            format = "XLSX";
        }
        if (!"XLSX".equals(format)) {
            throw new IllegalArgumentException("unsupported workbook export format: " + request.getOutput().getFormat());
        }

        if (request.getModules() == null || request.getModules().isEmpty()) {
            throw new IllegalArgumentException("modules is required");
        }

        WorkbookExportStartRequestDto normalized = new WorkbookExportStartRequestDto();
        normalized.setBusinessDomain(request.getBusinessDomain().trim());
        normalized.setOperator(trimToNull(request.getOperator()));
        normalized.setClientRequestId(trimToNull(request.getClientRequestId()));

        WorkbookExportStartRequestDto.ScopeDto scope = new WorkbookExportStartRequestDto.ScopeDto();
        scope.setCategoryIds(new ArrayList<>(new LinkedHashSet<>(request.getScope().getCategoryIds())));
        scope.setIncludeChildren(Boolean.TRUE.equals(request.getScope().getIncludeChildren()));
        normalized.setScope(scope);

        WorkbookExportStartRequestDto.OutputDto output = new WorkbookExportStartRequestDto.OutputDto();
        output.setFormat(format);
        output.setPathSeparator(trimToNull(request.getOutput().getPathSeparator()) == null ? " > " : request.getOutput().getPathSeparator());
        output.setFileName(trimToNull(request.getOutput().getFileName()));
        normalized.setOutput(output);

        List<WorkbookExportStartRequestDto.ModuleRequestDto> normalizedModules = new ArrayList<>();
        for (WorkbookExportStartRequestDto.ModuleRequestDto module : request.getModules()) {
            if (module == null || !Boolean.TRUE.equals(module.getEnabled())) {
                continue;
            }
            String moduleKey = normalizeModuleKey(module.getModuleKey());
            ModuleDefinition definition = requireDefinition(moduleKey);
            List<WorkbookExportColumnRequestDto> normalizedColumns = normalizeColumns(moduleKey, module.getColumns());
            if (normalizedColumns.isEmpty()) {
                throw new IllegalArgumentException("enabled module has no valid columns: moduleKey=" + moduleKey);
            }

            WorkbookExportStartRequestDto.ModuleRequestDto normalizedModule = new WorkbookExportStartRequestDto.ModuleRequestDto();
            normalizedModule.setModuleKey(moduleKey);
            normalizedModule.setEnabled(true);
            normalizedModule.setSheetName(trimToNull(module.getSheetName()) == null ? definition.defaultSheetName() : module.getSheetName().trim());
            normalizedModule.setColumns(normalizedColumns);
            normalizedModules.add(normalizedModule);
        }

        if (normalizedModules.isEmpty()) {
            throw new IllegalArgumentException("at least one enabled module is required");
        }
        normalized.setModules(normalizedModules);
        return normalized;
    }

    public List<WorkbookExportColumnRequestDto> normalizeColumns(String moduleKey,
                                                                 List<WorkbookExportColumnRequestDto> columns) {
        ModuleDefinition definition = requireDefinition(moduleKey);
        if (columns == null || columns.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<WorkbookExportColumnRequestDto> result = new ArrayList<>();
        for (WorkbookExportColumnRequestDto column : columns) {
            if (column == null) {
                continue;
            }
            String fieldKey = trimToNull(column.getFieldKey());
            if (fieldKey == null || !definition.fieldsByKey().containsKey(fieldKey) || !seen.add(fieldKey)) {
                continue;
            }
            WorkbookExportColumnRequestDto copy = new WorkbookExportColumnRequestDto();
            copy.setFieldKey(fieldKey);
            copy.setHeaderText(trimToNull(column.getHeaderText()) == null ? definition.fieldsByKey().get(fieldKey).defaultHeader() : column.getHeaderText().trim());
            copy.setClientColumnId(trimToNull(column.getClientColumnId()));
            result.add(copy);
        }
        return result;
    }

    public String defaultSheetName(String moduleKey) {
        return requireDefinition(moduleKey).defaultSheetName();
    }

    public String defaultHeader(String moduleKey, String fieldKey) {
        return requireField(moduleKey, fieldKey).defaultHeader();
    }

    public List<String> fieldKeys(String moduleKey) {
        return new ArrayList<>(requireDefinition(moduleKey).fieldsByKey().keySet());
    }

    public String normalizeModuleKey(String moduleKey) {
        String normalized = normalize(moduleKey);
        if (normalized == null) {
            throw new IllegalArgumentException("moduleKey is required");
        }
        return normalized;
    }

    private ModuleDefinition requireDefinition(String moduleKey) {
        String normalizedModuleKey = normalizeModuleKey(moduleKey);
        ModuleDefinition definition = definitions.get(normalizedModuleKey);
        if (definition == null) {
            throw new IllegalArgumentException("unsupported workbook export moduleKey: " + moduleKey);
        }
        return definition;
    }

    private FieldDefinition requireField(String moduleKey, String fieldKey) {
        ModuleDefinition definition = requireDefinition(moduleKey);
        FieldDefinition field = definition.fieldsByKey().get(fieldKey);
        if (field == null) {
            throw new IllegalArgumentException("unsupported workbook export fieldKey: moduleKey=" + moduleKey + ", fieldKey=" + fieldKey);
        }
        return field;
    }

    private String normalize(String value) {
        return trimToNull(value) == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private LinkedHashMap<String, ModuleDefinition> buildDefinitions() {
        LinkedHashMap<String, ModuleDefinition> map = new LinkedHashMap<>();
        map.put(WorkbookExportSupport.MODULE_CATEGORY, new ModuleDefinition(
                WorkbookExportSupport.MODULE_CATEGORY,
                "分类",
                fields(
                        field("categoryId", "分类ID", "分类定义主键", "UUID", false),
                        field("businessDomain", "业务域", "分类业务域", "STRING", true),
                        field("categoryCode", "分类编码", "分类业务编码", "STRING", true),
                        field("categoryName", "分类名称", "最新版本名称", "STRING", true),
                        field("status", "状态", "分类状态", "STRING", true),
                        field("parentId", "父级ID", "父分类主键", "UUID", false),
                        field("parentCode", "父级编码", "父分类业务编码", "STRING", true),
                        field("parentName", "父级名称", "父分类名称", "STRING", false),
                        field("rootId", "根节点ID", "根分类主键", "UUID", false),
                        field("rootCode", "根节点编码", "根分类业务编码", "STRING", false),
                        field("rootName", "根节点名称", "根分类名称", "STRING", false),
                        field("path", "结构路径", "分类结构路径", "STRING", false),
                        field("fullPathName", "名称全路径", "分类名称全路径", "STRING", false),
                        field("level", "层级", "分类层级", "INTEGER", false),
                        field("depth", "深度", "分类深度", "INTEGER", false),
                        field("sortOrder", "排序", "排序号", "INTEGER", false),
                        field("isLeaf", "叶子节点", "是否叶子节点", "BOOLEAN", false),
                        field("hasChildren", "含子节点", "是否有直接子节点", "BOOLEAN", false),
                        field("externalCode", "外部编码", "外部编码", "STRING", false),
                        field("codeKeyManualOverride", "人工覆盖编码", "编码是否人工覆盖", "BOOLEAN", false),
                        field("codeKeyFrozen", "编码冻结", "编码是否冻结", "BOOLEAN", false),
                        field("generatedRuleCode", "编码规则", "生成编码规则编码", "STRING", false),
                        field("generatedRuleVersionNo", "编码规则版本", "生成编码规则版本号", "INTEGER", false),
                        field("copiedFromCategoryId", "复制来源分类ID", "复制来源分类主键", "UUID", false),
                        field("latestVersionNo", "最新版本号", "最新版本号", "INTEGER", false),
                        field("latestVersionDate", "最新版本时间", "最新版本时间", "DATETIME", false),
                        field("latestVersionUpdatedBy", "最新版本更新人", "最新版本更新人", "STRING", false),
                        field("latestVersionDescription", "最新版本描述", "最新版本描述", "STRING", false),
                        field("createdAt", "创建时间", "创建时间", "DATETIME", false),
                        field("createdBy", "创建人", "创建人", "STRING", false),
                        field("modifiedAt", "修改时间", "最新版本修改时间", "DATETIME", false),
                        field("modifiedBy", "修改人", "最新版本修改人", "STRING", false)
                )));
        map.put(WorkbookExportSupport.MODULE_ATTRIBUTE, new ModuleDefinition(
                WorkbookExportSupport.MODULE_ATTRIBUTE,
                "属性",
                fields(
                        field("attributeId", "属性ID", "属性定义主键", "UUID", false),
                        field("businessDomain", "业务域", "属性业务域", "STRING", true),
                        field("categoryId", "所属分类ID", "所属分类主键", "UUID", false),
                        field("categoryCode", "所属分类编码", "所属分类业务编码", "STRING", true),
                        field("categoryName", "所属分类名称", "所属分类名称", "STRING", true),
                        field("attributeKey", "属性编码", "属性业务编码", "STRING", true),
                        field("status", "状态", "属性状态", "STRING", true),
                        field("hasLov", "含枚举", "是否绑定LOV", "BOOLEAN", false),
                        field("autoBindKey", "自动绑定键", "属性自动绑定键", "STRING", false),
                        field("keyManualOverride", "人工覆盖编码", "属性编码是否人工覆盖", "BOOLEAN", false),
                        field("keyFrozen", "编码冻结", "属性编码是否冻结", "BOOLEAN", false),
                        field("generatedRuleCode", "编码规则", "属性编码规则编码", "STRING", false),
                        field("generatedRuleVersionNo", "编码规则版本", "属性编码规则版本号", "INTEGER", false),
                        field("latestVersionId", "最新版本ID", "属性最新版本主键", "UUID", false),
                        field("latestVersionNo", "最新版本号", "属性最新版本号", "INTEGER", false),
                        field("categoryVersionId", "分类版本ID", "属性绑定的分类版本主键", "UUID", false),
                        field("resolvedCodePrefix", "解析编码前缀", "版本解析编码前缀", "STRING", false),
                        field("structureHash", "结构哈希", "属性结构哈希", "STRING", false),
                        field("displayName", "属性名称", "属性展示名称", "STRING", true),
                        field("description", "描述", "属性描述", "STRING", false),
                        field("attributeField", "属性字段", "属性字段名", "STRING", true),
                        field("dataType", "数据类型", "属性数据类型", "STRING", true),
                        field("unit", "单位", "属性单位", "STRING", false),
                        field("defaultValue", "默认值", "属性默认值", "STRING", false),
                        field("required", "必填", "是否必填", "BOOLEAN", true),
                        field("unique", "唯一", "是否唯一", "BOOLEAN", true),
                        field("hidden", "隐藏", "是否隐藏", "BOOLEAN", true),
                        field("readOnly", "只读", "是否只读", "BOOLEAN", true),
                        field("searchable", "可搜索", "是否可搜索", "BOOLEAN", true),
                        field("lovKey", "LOV编码", "绑定的LOV编码", "STRING", false),
                        field("minValue", "最小值", "数值型最小值", "DECIMAL", false),
                        field("maxValue", "最大值", "数值型最大值", "DECIMAL", false),
                        field("step", "步长", "数值型步长", "DECIMAL", false),
                        field("precision", "精度", "数值型精度", "INTEGER", false),
                        field("trueLabel", "真值标签", "布尔真值标签", "STRING", false),
                        field("falseLabel", "假值标签", "布尔假值标签", "STRING", false),
                        field("createdAt", "创建时间", "创建时间", "DATETIME", false),
                        field("createdBy", "创建人", "创建人", "STRING", false),
                        field("modifiedAt", "修改时间", "最新版本修改时间", "DATETIME", false),
                        field("modifiedBy", "修改人", "最新版本修改人", "STRING", false)
                )));
        map.put(WorkbookExportSupport.MODULE_ENUM_OPTION, new ModuleDefinition(
                WorkbookExportSupport.MODULE_ENUM_OPTION,
                "枚举值",
                fields(
                        field("businessDomain", "业务域", "LOV业务域", "STRING", true),
                        field("categoryId", "所属分类ID", "所属分类主键", "UUID", false),
                        field("categoryCode", "所属分类编码", "所属分类业务编码", "STRING", true),
                        field("categoryName", "所属分类名称", "所属分类名称", "STRING", true),
                        field("attributeId", "所属属性ID", "所属属性主键", "UUID", false),
                        field("attributeKey", "所属属性编码", "所属属性业务编码", "STRING", true),
                        field("attributeDisplayName", "所属属性名称", "所属属性名称", "STRING", true),
                        field("attributeField", "所属属性字段", "所属属性字段名", "STRING", false),
                        field("attributeDataType", "属性数据类型", "所属属性数据类型", "STRING", true),
                        field("lovDefId", "LOV定义ID", "LOV定义主键", "UUID", false),
                        field("lovKey", "LOV编码", "LOV业务编码", "STRING", true),
                        field("lovStatus", "LOV状态", "LOV定义状态", "STRING", false),
                        field("lovDescription", "LOV描述", "LOV描述", "STRING", false),
                        field("sourceAttributeKey", "来源属性编码", "LOV来源属性编码", "STRING", false),
                        field("lovKeyManualOverride", "LOV人工覆盖编码", "LOV编码是否人工覆盖", "BOOLEAN", false),
                        field("lovKeyFrozen", "LOV编码冻结", "LOV编码是否冻结", "BOOLEAN", false),
                        field("lovGeneratedRuleCode", "LOV编码规则", "LOV生成编码规则编码", "STRING", false),
                        field("lovGeneratedRuleVersionNo", "LOV编码规则版本", "LOV生成编码规则版本号", "INTEGER", false),
                        field("lovCreatedAt", "LOV创建时间", "LOV定义创建时间", "DATETIME", false),
                        field("lovCreatedBy", "LOV创建人", "LOV定义创建人", "STRING", false),
                        field("lovVersionId", "LOV版本ID", "LOV最新版本主键", "UUID", false),
                        field("lovVersionNo", "LOV版本号", "LOV最新版本号", "INTEGER", false),
                        field("lovResolvedCodePrefix", "LOV解析编码前缀", "LOV解析编码前缀", "STRING", false),
                        field("lovHash", "LOV哈希", "LOV值集哈希", "STRING", false),
                        field("lovVersionCreatedAt", "LOV版本创建时间", "LOV最新版本创建时间", "DATETIME", false),
                        field("lovVersionCreatedBy", "LOV版本创建人", "LOV最新版本创建人", "STRING", false),
                        field("optionCode", "枚举编码", "枚举项编码", "STRING", true),
                        field("optionName", "枚举值", "枚举项值", "STRING", true),
                        field("optionLabel", "显示标签", "枚举项显示标签", "STRING", true),
                        field("optionOrder", "排序", "枚举项排序", "INTEGER", true),
                        field("optionDisabled", "禁用", "枚举项是否禁用", "BOOLEAN", false)
                )));
        return map;
    }

    @SafeVarargs
    private final LinkedHashMap<String, FieldDefinition> fields(FieldDefinition... fields) {
        LinkedHashMap<String, FieldDefinition> map = new LinkedHashMap<>();
        for (FieldDefinition field : fields) {
            map.put(field.fieldKey(), field);
        }
        return map;
    }

    private FieldDefinition field(String fieldKey,
                                  String defaultHeader,
                                  String description,
                                  String valueType,
                                  boolean defaultSelected) {
        return new FieldDefinition(fieldKey, defaultHeader, description, valueType, defaultSelected, true);
    }

    private record ModuleDefinition(
            String moduleKey,
            String defaultSheetName,
            LinkedHashMap<String, FieldDefinition> fieldsByKey) {

        WorkbookExportSchemaResponseDto.ModuleSchemaDto toDto() {
            WorkbookExportSchemaResponseDto.ModuleSchemaDto dto = new WorkbookExportSchemaResponseDto.ModuleSchemaDto();
            dto.setModuleKey(moduleKey);
            dto.setDefaultSheetName(defaultSheetName);
            List<WorkbookExportSchemaResponseDto.FieldSchemaDto> fields = new ArrayList<>();
            for (FieldDefinition field : fieldsByKey.values()) {
                fields.add(field.toDto());
            }
            dto.setFields(fields);
            return dto;
        }
    }

    private record FieldDefinition(
            String fieldKey,
            String defaultHeader,
            String description,
            String valueType,
            Boolean defaultSelected,
            Boolean allowCustomHeader) {

        WorkbookExportSchemaResponseDto.FieldSchemaDto toDto() {
            WorkbookExportSchemaResponseDto.FieldSchemaDto dto = new WorkbookExportSchemaResponseDto.FieldSchemaDto();
            dto.setFieldKey(fieldKey);
            dto.setDefaultHeader(defaultHeader);
            dto.setDescription(description);
            dto.setValueType(valueType);
            dto.setDefaultSelected(defaultSelected);
            dto.setAllowCustomHeader(allowCustomHeader);
            return dto;
        }
    }
}