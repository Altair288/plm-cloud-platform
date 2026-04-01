package com.plm.attribute.version.service.workbook;

import com.plm.attribute.version.service.MetaAttributeManageService;
import com.plm.attribute.version.service.MetaAttributeQueryService;
import com.plm.attribute.version.service.MetaCategoryCrudService;
import com.plm.attribute.version.service.MetaCodeRuleService;
import com.plm.attribute.version.service.MetaCodeRuleSetService;
import com.plm.common.api.dto.attribute.MetaAttributeDefDetailDto;
import com.plm.common.api.dto.attribute.MetaAttributeUpsertRequestDto;
import com.plm.common.api.dto.category.CreateCategoryRequestDto;
import com.plm.common.api.dto.category.UpdateCategoryRequestDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunResponseDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportJobStatusDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportStartRequestDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportStartResponseDto;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.infrastructure.version.repository.MetaAttributeDefRepository;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class WorkbookImportExecutionService {

    private static final String MODE_EXCEL_MANUAL = "EXCEL_MANUAL";
    private static final String MODE_SYSTEM_RULE_AUTO = "SYSTEM_RULE_AUTO";

    private final WorkbookImportRuntimeService runtimeService;
    private final MetaCategoryCrudService categoryCrudService;
    private final MetaAttributeManageService attributeManageService;
    private final MetaAttributeQueryService attributeQueryService;
    private final MetaCategoryDefRepository categoryDefRepository;
    private final MetaAttributeDefRepository attributeDefRepository;
    private final MetaCodeRuleService metaCodeRuleService;
    private final MetaCodeRuleSetService metaCodeRuleSetService;
    private final TransactionTemplate transactionTemplate;

    public WorkbookImportExecutionService(WorkbookImportRuntimeService runtimeService,
                                          MetaCategoryCrudService categoryCrudService,
                                          MetaAttributeManageService attributeManageService,
                                          MetaAttributeQueryService attributeQueryService,
                                          MetaCategoryDefRepository categoryDefRepository,
                                          MetaAttributeDefRepository attributeDefRepository,
                                          MetaCodeRuleService metaCodeRuleService,
                                          MetaCodeRuleSetService metaCodeRuleSetService,
                                          PlatformTransactionManager transactionManager) {
        this.runtimeService = runtimeService;
        this.categoryCrudService = categoryCrudService;
        this.attributeManageService = attributeManageService;
        this.attributeQueryService = attributeQueryService;
        this.categoryDefRepository = categoryDefRepository;
        this.attributeDefRepository = attributeDefRepository;
        this.metaCodeRuleService = metaCodeRuleService;
        this.metaCodeRuleSetService = metaCodeRuleSetService;
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    public WorkbookImportStartResponseDto startImport(WorkbookImportStartRequestDto request) {
        if (request == null || request.getImportSessionId() == null || request.getImportSessionId().isBlank()) {
            throw new IllegalArgumentException("importSessionId is required");
        }
        WorkbookImportSupport.ImportSessionState session = runtimeService.getSession(request.getImportSessionId());
        WorkbookImportDryRunResponseDto response = session.response();
        if (response.getSummary() == null || !Boolean.TRUE.equals(response.getSummary().getCanImport())) {
            throw new IllegalArgumentException("dry-run contains errors; import is not allowed");
        }

        String operator = normalizeOperator(request.getOperator() == null ? session.operator() : request.getOperator());
        boolean atomic = request.getAtomic() == null || Boolean.TRUE.equals(request.getAtomic());
        WorkbookImportSupport.JobState job = runtimeService.createJob(session, operator, atomic);
        executeAsync(job.getJobId());

        WorkbookImportStartResponseDto dto = new WorkbookImportStartResponseDto();
        dto.setJobId(job.getJobId());
        dto.setImportSessionId(job.getImportSessionId());
        dto.setStatus(WorkbookImportSupport.STATUS_QUEUED);
        dto.setAtomic(atomic);
        dto.setCreatedAt(OffsetDateTime.now());
        return dto;
    }

    @Async("workbookImportTaskExecutor")
    public void executeAsync(String jobId) {
        WorkbookImportSupport.JobState job = runtimeService.getJob(jobId);
        WorkbookImportSupport.ImportSessionState session = runtimeService.getSession(job.getImportSessionId());
        try {
            if (job.isAtomic()) {
                transactionTemplate.executeWithoutResult(status -> executePlan(job, session, true));
            } else {
                executePlan(job, session, false);
            }
            runtimeService.completeJob(jobId);
        } catch (Exception ex) {
            runtimeService.appendLog(jobId, log -> {
                log.setLevel("ERROR");
                log.setStage(runtimeService.getJobStatus(jobId).getCurrentStage());
                log.setEventType("TASK_FAILED");
                log.setCode("WORKBOOK_IMPORT_FAILED");
                log.setMessage(ex.getMessage());
            });
            runtimeService.failJob(jobId, ex.getMessage());
        }
    }

    private void executePlan(WorkbookImportSupport.JobState job,
                             WorkbookImportSupport.ImportSessionState session,
                             boolean atomic) {
        ExecutionPlan plan = buildPlan(session);

        runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_PREPARING, WorkbookImportSupport.STAGE_PREPARING);
        reserveCategoryCodes(job, plan, session);
        reserveAttributeCodes(job, plan, session);
        reserveEnumCodes(job, plan, session);
        resolveActions(plan, session);

        runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_IMPORTING_CATEGORIES, WorkbookImportSupport.STAGE_CATEGORIES);
        importCategories(job, plan, atomic);

        runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_IMPORTING_ATTRIBUTES, WorkbookImportSupport.STAGE_ATTRIBUTES);
        importAttributes(job, plan, atomic);

        runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_IMPORTING_ENUM_OPTIONS, WorkbookImportSupport.STAGE_ENUM_OPTIONS);
        importEnumOptions(job, plan, atomic);

        runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_FINALIZING, WorkbookImportSupport.STAGE_FINALIZING);
    }

    private ExecutionPlan buildPlan(WorkbookImportSupport.ImportSessionState session) {
        Map<Integer, WorkbookImportDryRunResponseDto.CategoryPreviewItemDto> previewCategoriesByRow = new HashMap<>();
        Map<Integer, WorkbookImportDryRunResponseDto.AttributePreviewItemDto> previewAttributesByRow = new HashMap<>();
        Map<Integer, WorkbookImportDryRunResponseDto.EnumOptionPreviewItemDto> previewEnumsByRow = new HashMap<>();
        session.response().getPreview().getCategories().forEach(item -> previewCategoriesByRow.put(item.getRowNumber(), item));
        session.response().getPreview().getAttributes().forEach(item -> previewAttributesByRow.put(item.getRowNumber(), item));
        session.response().getPreview().getEnumOptions().forEach(item -> previewEnumsByRow.put(item.getRowNumber(), item));

        List<CategoryWorkItem> categories = session.categories().stream().map(row -> {
            WorkbookImportDryRunResponseDto.CategoryPreviewItemDto preview = previewCategoriesByRow.get(row.rowNumber());
            return new CategoryWorkItem(
                    row.sheetName(),
                    row.rowNumber(),
                    row.businessDomain(),
                    row.excelReferenceCode(),
                    row.categoryPath(),
                    row.categoryName(),
                    row.parentPath(),
                    preview == null ? null : preview.getResolvedFinalCode(),
                    preview == null ? null : preview.getResolvedFinalPath(),
                    preview == null ? "CREATE" : preview.getResolvedAction(),
                    preview == null ? MODE_EXCEL_MANUAL : preview.getCodeMode());
        }).sorted(Comparator.comparingInt(CategoryWorkItem::rowNumber)).toList();

        List<AttributeWorkItem> attributes = session.attributes().stream().map(row -> {
            WorkbookImportDryRunResponseDto.AttributePreviewItemDto preview = previewAttributesByRow.get(row.rowNumber());
            return new AttributeWorkItem(
                    row.sheetName(),
                    row.rowNumber(),
                    row.categoryReferenceCode(),
                    row.attributeReferenceCode(),
                    row.attributeName(),
                    row.attributeField(),
                    row.description(),
                    row.dataType(),
                    row.unit(),
                    row.defaultValue(),
                    row.required(),
                    row.unique(),
                    row.searchable(),
                    row.hidden(),
                    row.readOnly(),
                    row.minValue(),
                    row.maxValue(),
                    row.step(),
                    row.precision(),
                    row.trueLabel(),
                    row.falseLabel(),
                    preview == null ? null : preview.getCategoryCode(),
                    preview == null ? null : preview.getResolvedFinalCode(),
                    preview == null ? "CREATE" : preview.getResolvedAction(),
                    preview == null ? MODE_EXCEL_MANUAL : preview.getCodeMode());
        }).sorted(Comparator.comparingInt(AttributeWorkItem::rowNumber)).toList();

        List<EnumWorkItem> enums = session.enumOptions().stream().map(row -> {
            WorkbookImportDryRunResponseDto.EnumOptionPreviewItemDto preview = previewEnumsByRow.get(row.rowNumber());
            return new EnumWorkItem(
                    row.sheetName(),
                    row.rowNumber(),
                    row.categoryReferenceCode(),
                    row.attributeReferenceCode(),
                    row.optionReferenceCode(),
                    row.optionName(),
                    row.displayLabel(),
                    preview == null ? null : preview.getCategoryCode(),
                    preview == null ? null : preview.getAttributeKey(),
                    preview == null ? null : preview.getResolvedFinalCode(),
                    preview == null ? "CREATE" : preview.getResolvedAction(),
                    preview == null ? MODE_EXCEL_MANUAL : preview.getCodeMode());
        }).sorted(Comparator.comparingInt(EnumWorkItem::rowNumber)).toList();

        return new ExecutionPlan(categories, attributes, enums);
    }

    private void reserveCategoryCodes(WorkbookImportSupport.JobState job,
                                      ExecutionPlan plan,
                                      WorkbookImportSupport.ImportSessionState session) {
        Map<String, CategoryWorkItem> byPath = new LinkedHashMap<>();
        for (CategoryWorkItem item : plan.categories) {
            byPath.put(item.businessDomain + "::" + item.categoryPath, item);
        }
        for (CategoryWorkItem item : plan.categories) {
            if (MODE_EXCEL_MANUAL.equals(item.codeMode)
                    || (item.finalCode != null
                    && MODE_EXCEL_MANUAL.equals(session.options().getCodingOptions().getCategoryCodeMode()))) {
                item.finalCode = item.excelCode;
                item.finalPath = appendPath(resolveParentFinalPath(item, byPath), item.finalCode);
                continue;
            }

        }

        Map<String, List<CategoryWorkItem>> grouped = new LinkedHashMap<>();
        for (CategoryWorkItem item : plan.categories) {
            if (!MODE_SYSTEM_RULE_AUTO.equals(item.codeMode)) {
                continue;
            }
            String parentFinalCode = resolveParentFinalCode(item, byPath);
            String scopeKey = item.businessDomain + "::" + (parentFinalCode == null ? "<ROOT>" : parentFinalCode);
            grouped.computeIfAbsent(scopeKey, ignored -> new ArrayList<>()).add(item);
        }
        for (List<CategoryWorkItem> group : grouped.values()) {
            String firstCode = null;
            String lastCode = null;
            for (CategoryWorkItem item : group) {
                String parentFinalCode = resolveParentFinalCode(item, byPath);
                Map<String, String> context = new LinkedHashMap<>();
                context.put("BUSINESS_DOMAIN", item.businessDomain);
                if (parentFinalCode != null) {
                    context.put("PARENT_CODE", parentFinalCode);
                }
                String ruleCode = metaCodeRuleSetService.resolveCategoryRuleCode(item.businessDomain);
                MetaCodeRuleService.GeneratedCodeResult generated = metaCodeRuleService.generateCode(ruleCode, "CATEGORY", null, context, null, job.getOperator(), false);
                item.finalCode = generated.code();
                item.finalPath = appendPath(resolveParentFinalPath(item, byPath), item.finalCode);
                if (firstCode == null) {
                    firstCode = item.finalCode;
                }
                lastCode = item.finalCode;
            }
            if (!group.isEmpty()) {
                CategoryWorkItem sample = group.get(0);
                String reservedStartCode = firstCode;
                String reservedEndCode = lastCode;
                runtimeService.appendLog(job.getJobId(), log -> {
                    log.setLevel("INFO");
                    log.setStage(WorkbookImportSupport.STAGE_PREPARING);
                    log.setEventType("SEQUENCE_RESERVED");
                    log.setCode("CATEGORY_CODE_SEQUENCE_RESERVED");
                    log.setMessage("分类编码规则已预留号段");
                    log.setDetails(Map.of(
                            "businessDomain", sample.businessDomain,
                            "reservedCount", group.size(),
                            "startCode", reservedStartCode,
                            "endCode", reservedEndCode));
                });
            }
        }
    }

    private void reserveAttributeCodes(WorkbookImportSupport.JobState job,
                                       ExecutionPlan plan,
                                       WorkbookImportSupport.ImportSessionState session) {
        Map<String, CategoryWorkItem> categoriesByReference = new LinkedHashMap<>();
        for (CategoryWorkItem item : plan.categories) {
            categoriesByReference.put(item.excelCode, item);
        }
        Map<String, List<AttributeWorkItem>> grouped = new LinkedHashMap<>();
        for (AttributeWorkItem item : plan.attributes) {
            CategoryWorkItem category = categoriesByReference.get(item.categoryReferenceCode);
            if (category != null) {
                item.finalCategoryCode = category.finalCode;
            }
            if (!MODE_SYSTEM_RULE_AUTO.equals(item.codeMode)) {
                item.finalCode = item.attributeCode;
                continue;
            }
            grouped.computeIfAbsent(item.businessDomain(plan.categories) + "::" + item.finalCategoryCode, ignored -> new ArrayList<>()).add(item);
        }
        for (List<AttributeWorkItem> group : grouped.values()) {
            String firstCode = null;
            String lastCode = null;
            for (AttributeWorkItem item : group) {
                String businessDomain = item.businessDomain(plan.categories);
                Map<String, String> context = new LinkedHashMap<>();
                context.put("BUSINESS_DOMAIN", businessDomain);
                context.put("CATEGORY_CODE", item.finalCategoryCode);
                String ruleCode = metaCodeRuleSetService.resolveAttributeRuleCode(businessDomain);
                MetaCodeRuleService.GeneratedCodeResult generated = metaCodeRuleService.generateCode(ruleCode, "ATTRIBUTE", null, context, null, job.getOperator(), false);
                item.finalCode = generated.code();
                if (firstCode == null) {
                    firstCode = item.finalCode;
                }
                lastCode = item.finalCode;
            }
            if (!group.isEmpty()) {
                AttributeWorkItem sample = group.get(0);
                String reservedStartCode = firstCode;
                String reservedEndCode = lastCode;
                runtimeService.appendLog(job.getJobId(), log -> {
                    log.setLevel("INFO");
                    log.setStage(WorkbookImportSupport.STAGE_PREPARING);
                    log.setEventType("SEQUENCE_RESERVED");
                    log.setCode("ATTRIBUTE_CODE_SEQUENCE_RESERVED");
                    log.setMessage("属性编码规则已预留号段");
                    log.setDetails(Map.of(
                            "categoryCode", sample.finalCategoryCode,
                            "reservedCount", group.size(),
                            "startCode", reservedStartCode,
                            "endCode", reservedEndCode));
                });
            }
        }
    }

    private void reserveEnumCodes(WorkbookImportSupport.JobState job,
                                  ExecutionPlan plan,
                                  WorkbookImportSupport.ImportSessionState session) {
        Map<String, CategoryWorkItem> categoriesByReference = new LinkedHashMap<>();
        for (CategoryWorkItem item : plan.categories) {
            categoriesByReference.put(item.excelCode, item);
        }
        Map<String, AttributeWorkItem> attributesByReference = new LinkedHashMap<>();
        Map<String, AttributeWorkItem> attributesByFinal = new LinkedHashMap<>();
        for (AttributeWorkItem item : plan.attributes) {
            String businessDomain = item.businessDomain(plan.categories);
            attributesByReference.put(businessDomain + "::" + item.categoryReferenceCode + "::" + item.attributeCode, item);
            attributesByFinal.put(businessDomain + "::" + item.finalCategoryCode + "::" + item.finalCode, item);
        }

        Map<String, List<EnumWorkItem>> grouped = new LinkedHashMap<>();
        for (EnumWorkItem item : plan.enums) {
            CategoryWorkItem category = categoriesByReference.get(item.categoryReferenceCode);
            if (category != null) {
                item.finalCategoryCode = category.finalCode;
            }
            String businessDomain = item.businessDomain(plan.categories);
            AttributeWorkItem attribute = attributesByReference.get(businessDomain + "::" + item.categoryReferenceCode + "::" + item.attributeCode);
            if (attribute == null) {
                attribute = attributesByFinal.get(businessDomain + "::" + item.finalCategoryCode + "::" + item.attributeCode);
            }
            if (attribute != null) {
                item.finalAttributeCode = attribute.finalCode;
            }
            if (!MODE_SYSTEM_RULE_AUTO.equals(item.codeMode)) {
                item.finalCode = item.optionCode;
                continue;
            }
            grouped.computeIfAbsent(businessDomain + "::" + item.finalCategoryCode + "::" + item.finalAttributeCode,
                    ignored -> new ArrayList<>()).add(item);
        }
        for (List<EnumWorkItem> group : grouped.values()) {
            String firstCode = null;
            String lastCode = null;
            for (EnumWorkItem item : group) {
                String businessDomain = item.businessDomain(plan.categories);
                Map<String, String> context = new LinkedHashMap<>();
                context.put("BUSINESS_DOMAIN", businessDomain);
                context.put("CATEGORY_CODE", item.finalCategoryCode);
                context.put("ATTRIBUTE_CODE", item.finalAttributeCode);
                String ruleCode = metaCodeRuleSetService.resolveLovRuleCode(businessDomain);
                MetaCodeRuleService.GeneratedCodeResult generated = metaCodeRuleService.generateCode(ruleCode, "LOV_VALUE", null, context, null, job.getOperator(), false);
                item.finalCode = generated.code();
                if (firstCode == null) {
                    firstCode = item.finalCode;
                }
                lastCode = item.finalCode;
            }
            if (!group.isEmpty()) {
                EnumWorkItem sample = group.get(0);
                String reservedStartCode = firstCode;
                String reservedEndCode = lastCode;
                runtimeService.appendLog(job.getJobId(), log -> {
                    log.setLevel("INFO");
                    log.setStage(WorkbookImportSupport.STAGE_PREPARING);
                    log.setEventType("SEQUENCE_RESERVED");
                    log.setCode("ENUM_OPTION_CODE_SEQUENCE_RESERVED");
                    log.setMessage("枚举值编码规则已预留号段");
                    log.setDetails(Map.of(
                            "categoryCode", sample.finalCategoryCode,
                            "attributeCode", sample.finalAttributeCode,
                            "reservedCount", group.size(),
                            "startCode", reservedStartCode,
                            "endCode", reservedEndCode));
                });
            }
        }
    }

    private void resolveActions(ExecutionPlan plan, WorkbookImportSupport.ImportSessionState session) {
        String categoryPolicy = session.options().getDuplicateOptions().getCategoryDuplicatePolicy();
        String attributePolicy = session.options().getDuplicateOptions().getAttributeDuplicatePolicy();
        String enumPolicy = session.options().getDuplicateOptions().getEnumOptionDuplicatePolicy();

        for (CategoryWorkItem item : plan.categories) {
            boolean exists = item.finalCode != null && categoryDefRepository.findByBusinessDomainAndCodeKey(item.businessDomain, item.finalCode).isPresent();
            item.action = resolveAction(exists, categoryPolicy);
        }
        for (AttributeWorkItem item : plan.attributes) {
            String businessDomain = item.businessDomain(plan.categories);
            var existing = businessDomain == null || item.finalCode == null
                    ? java.util.Optional.<com.plm.common.version.domain.MetaAttributeDef>empty()
                    : attributeDefRepository.findActiveByBusinessDomainAndKey(businessDomain, item.finalCode);
            if (existing.isEmpty()) {
                item.action = resolveAction(false, attributePolicy);
                continue;
            }
            MetaCategoryDef ownerCategory = existing.get().getCategoryDef();
            if (ownerCategory != null && Objects.equals(ownerCategory.getCodeKey(), item.finalCategoryCode)) {
                item.action = resolveAction(true, attributePolicy);
                continue;
            }
            item.action = "CONFLICT";
        }
        Map<String, Map<String, String>> existingEnumCodes = new HashMap<>();
        for (EnumWorkItem item : plan.enums) {
            String businessDomain = item.businessDomain(plan.categories);
            String key = businessDomain + "::" + item.finalCategoryCode + "::" + item.finalAttributeCode;
            Map<String, String> values = existingEnumCodes.computeIfAbsent(key, ignored -> loadExistingEnumCodes(businessDomain, item.finalAttributeCode));
            item.action = resolveAction(values.containsKey(item.finalCode), enumPolicy);
        }
    }

    private String resolveAction(boolean exists, String policy) {
        if (!exists) {
            return "CREATE";
        }
        return switch (policy) {
            case "OVERWRITE_EXISTING" -> "UPDATE";
            case "KEEP_EXISTING" -> "KEEP_EXISTING";
            case "FAIL_ON_DUPLICATE" -> "CONFLICT";
            default -> "CREATE";
        };
    }

    private void importCategories(WorkbookImportSupport.JobState job, ExecutionPlan plan, boolean atomic) {
        int total = plan.categories.size();
        int processed = 0;
        for (CategoryWorkItem item : plan.categories) {
            try {
                if ("CONFLICT".equals(item.action)) {
                    throw new IllegalArgumentException("category duplicate conflict: " + item.finalCode);
                }
                if ("KEEP_EXISTING".equals(item.action)) {
                    runtimeService.appendLog(job.getJobId(), log -> {
                        log.setLevel("INFO");
                        log.setStage(WorkbookImportSupport.STAGE_CATEGORIES);
                        log.setSheetName(item.sheetName);
                        log.setRowNumber(item.rowNumber);
                        log.setEntityType("CATEGORY");
                        log.setEntityKey(item.finalCode);
                        log.setAction("KEEP_EXISTING");
                        log.setEventType("ROW_PROCESSED");
                        log.setCode("CATEGORY_SKIPPED");
                        log.setMessage("分类已存在，按策略跳过");
                    });
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_CATEGORIES, "skipped", ++processed, total);
                    continue;
                }

                MetaCategoryDef parent = resolveParentCategory(item, plan.categories);
                if ("UPDATE".equals(item.action)) {
                    MetaCategoryDef existing = categoryDefRepository.findByBusinessDomainAndCodeKey(item.businessDomain, item.finalCode).orElseThrow();
                    UpdateCategoryRequestDto request = new UpdateCategoryRequestDto();
                    request.setCode(item.finalCode);
                    request.setName(item.categoryName);
                    request.setBusinessDomain(item.businessDomain);
                    request.setParentId(parent == null ? null : parent.getId());
                    request.setStatus("active");
                    categoryCrudService.update(existing.getId(), request, job.getOperator(), false);
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_CATEGORIES, "updated", ++processed, total);
                } else {
                    CreateCategoryRequestDto request = new CreateCategoryRequestDto();
                    request.setCode(item.finalCode);
                    request.setGenerationMode(MODE_SYSTEM_RULE_AUTO.equals(item.codeMode) ? "AUTO_RESERVED" : "MANUAL");
                    request.setName(item.categoryName);
                    request.setBusinessDomain(item.businessDomain);
                    request.setParentId(parent == null ? null : parent.getId());
                    request.setStatus("active");
                    categoryCrudService.create(request, job.getOperator());
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_CATEGORIES, "created", ++processed, total);
                }
                runtimeService.appendLog(job.getJobId(), log -> {
                    log.setLevel("INFO");
                    log.setStage(WorkbookImportSupport.STAGE_CATEGORIES);
                    log.setSheetName(item.sheetName);
                    log.setRowNumber(item.rowNumber);
                    log.setEntityType("CATEGORY");
                    log.setEntityKey(item.finalCode);
                    log.setAction(item.action);
                    log.setEventType("ROW_PROCESSED");
                    log.setCode("CATEGORY_IMPORTED");
                    log.setMessage("分类处理成功");
                });
            } catch (Exception ex) {
                handleRowFailure(job, WorkbookImportSupport.STAGE_CATEGORIES, item.sheetName, item.rowNumber, "CATEGORY", item.finalCode, ex, atomic);
                incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_CATEGORIES, "failed", ++processed, total);
                if (atomic) {
                    throw ex;
                }
            }
        }
    }

    private void importAttributes(WorkbookImportSupport.JobState job, ExecutionPlan plan, boolean atomic) {
        int total = plan.attributes.size();
        int processed = 0;
        for (AttributeWorkItem item : plan.attributes) {
            try {
                if ("CONFLICT".equals(item.action)) {
                    throw new IllegalArgumentException("attribute duplicate conflict: " + item.finalCode);
                }
                if ("KEEP_EXISTING".equals(item.action)) {
                    runtimeService.appendLog(job.getJobId(), log -> {
                        log.setLevel("INFO");
                        log.setStage(WorkbookImportSupport.STAGE_ATTRIBUTES);
                        log.setSheetName(item.sheetName);
                        log.setRowNumber(item.rowNumber);
                        log.setEntityType("ATTRIBUTE");
                        log.setEntityKey(item.finalCategoryCode + "/" + item.finalCode);
                        log.setAction("KEEP_EXISTING");
                        log.setEventType("ROW_PROCESSED");
                        log.setCode("ATTRIBUTE_SKIPPED");
                        log.setMessage("属性已存在，按策略跳过");
                    });
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ATTRIBUTES, "skipped", ++processed, total);
                    continue;
                }

                MetaAttributeUpsertRequestDto request = toAttributeRequest(item, false);
                if ("UPDATE".equals(item.action)) {
                    attributeManageService.update(item.businessDomain(plan.categories), item.finalCategoryCode, item.finalCode, request, job.getOperator());
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ATTRIBUTES, "updated", ++processed, total);
                } else {
                    attributeManageService.create(item.businessDomain(plan.categories), item.finalCategoryCode, request, job.getOperator());
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ATTRIBUTES, "created", ++processed, total);
                }
                runtimeService.appendLog(job.getJobId(), log -> {
                    log.setLevel("INFO");
                    log.setStage(WorkbookImportSupport.STAGE_ATTRIBUTES);
                    log.setSheetName(item.sheetName);
                    log.setRowNumber(item.rowNumber);
                    log.setEntityType("ATTRIBUTE");
                    log.setEntityKey(item.finalCategoryCode + "/" + item.finalCode);
                    log.setAction(item.action);
                    log.setEventType("ROW_PROCESSED");
                    log.setCode("ATTRIBUTE_IMPORTED");
                    log.setMessage("属性处理成功");
                });
            } catch (Exception ex) {
                handleRowFailure(job, WorkbookImportSupport.STAGE_ATTRIBUTES, item.sheetName, item.rowNumber, "ATTRIBUTE", item.finalCode, ex, atomic);
                incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ATTRIBUTES, "failed", ++processed, total);
                if (atomic) {
                    throw ex;
                }
            }
        }
    }

    private void importEnumOptions(WorkbookImportSupport.JobState job, ExecutionPlan plan, boolean atomic) {
        int total = plan.enums.size();
        int processed = 0;
        Map<String, List<EnumWorkItem>> grouped = new LinkedHashMap<>();
        for (EnumWorkItem item : plan.enums) {
            grouped.computeIfAbsent(item.finalCategoryCode + "::" + item.finalAttributeCode, ignored -> new ArrayList<>()).add(item);
        }

        for (List<EnumWorkItem> group : grouped.values()) {
            try {
                EnumWorkItem first = group.get(0);
                if (group.stream().anyMatch(item -> "CONFLICT".equals(item.action))) {
                    throw new IllegalArgumentException("enum option duplicate conflict: " + first.finalAttributeCode);
                }
                if (group.stream().allMatch(item -> "KEEP_EXISTING".equals(item.action))) {
                    for (EnumWorkItem item : group) {
                        runtimeService.appendLog(job.getJobId(), log -> {
                            log.setLevel("INFO");
                            log.setStage(WorkbookImportSupport.STAGE_ENUM_OPTIONS);
                            log.setSheetName(item.sheetName);
                            log.setRowNumber(item.rowNumber);
                            log.setEntityType("ENUM_OPTION");
                            log.setEntityKey(item.finalCategoryCode + "/" + item.finalAttributeCode + "/" + item.finalCode);
                            log.setAction("KEEP_EXISTING");
                            log.setEventType("ROW_PROCESSED");
                            log.setCode("ENUM_OPTION_SKIPPED");
                            log.setMessage("枚举值已存在，按策略跳过");
                        });
                        incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ENUM_OPTIONS, "skipped", ++processed, total);
                    }
                    continue;
                }

                MetaAttributeDefDetailDto detail = attributeQueryService.detail(first.businessDomain(plan.categories), first.finalAttributeCode, true);
                if (detail == null || detail.getLatestVersion() == null) {
                    throw new IllegalArgumentException("attribute not found for enum import: " + first.finalAttributeCode);
                }
                MetaAttributeUpsertRequestDto request = toAttributeRequest(detail, first.finalAttributeCode);
                request.setLovValues(mergeEnumValues(detail, group));
                attributeManageService.update(first.businessDomain(plan.categories), first.finalCategoryCode, first.finalAttributeCode, request, job.getOperator());

                for (EnumWorkItem item : group) {
                    String metric = "UPDATE".equals(item.action) ? "updated" : "created";
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ENUM_OPTIONS, metric, ++processed, total);
                    runtimeService.appendLog(job.getJobId(), log -> {
                        log.setLevel("INFO");
                        log.setStage(WorkbookImportSupport.STAGE_ENUM_OPTIONS);
                        log.setSheetName(item.sheetName);
                        log.setRowNumber(item.rowNumber);
                        log.setEntityType("ENUM_OPTION");
                        log.setEntityKey(item.finalCategoryCode + "/" + item.finalAttributeCode + "/" + item.finalCode);
                        log.setAction(item.action);
                        log.setEventType("ROW_PROCESSED");
                        log.setCode("ENUM_OPTION_IMPORTED");
                        log.setMessage("枚举值处理成功");
                    });
                }
            } catch (Exception ex) {
                for (EnumWorkItem item : group) {
                    handleRowFailure(job, WorkbookImportSupport.STAGE_ENUM_OPTIONS, item.sheetName, item.rowNumber, "ENUM_OPTION", item.finalCode, ex, atomic);
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ENUM_OPTIONS, "failed", ++processed, total);
                }
                if (atomic) {
                    throw ex;
                }
            }
        }
    }

    private MetaCategoryDef resolveParentCategory(CategoryWorkItem item, List<CategoryWorkItem> allItems) {
        if (item.parentPath == null) {
            return null;
        }
        CategoryWorkItem parentInBatch = allItems.stream()
                .filter(candidate -> Objects.equals(candidate.categoryPath, item.parentPath))
                .findFirst()
                .orElse(null);
        if (parentInBatch != null && parentInBatch.finalCode != null) {
            return categoryDefRepository.findByBusinessDomainAndCodeKey(parentInBatch.businessDomain, parentInBatch.finalCode).orElse(null);
        }
        String parentCode = pathLeaf(item.parentPath);
        return parentCode == null ? null : categoryDefRepository.findByBusinessDomainAndCodeKey(item.businessDomain, parentCode).orElse(null);
    }

    private MetaAttributeUpsertRequestDto toAttributeRequest(AttributeWorkItem item, boolean includeLovValues) {
        MetaAttributeUpsertRequestDto request = new MetaAttributeUpsertRequestDto();
        request.setKey(item.finalCode);
        request.setGenerationMode(MODE_SYSTEM_RULE_AUTO.equals(item.codeMode) ? "AUTO_RESERVED" : "MANUAL");
        request.setDisplayName(item.attributeName);
        request.setAttributeField(item.attributeField);
        request.setDescription(item.description);
        request.setDataType(normalizeServiceDataType(item.dataType));
        request.setUnit(item.unit);
        request.setDefaultValue(item.defaultValue);
        request.setRequired(item.required);
        request.setUnique(item.unique);
        request.setSearchable(item.searchable);
        request.setHidden(item.hidden);
        request.setReadOnly(item.readOnly);
        request.setMinValue(item.minValue);
        request.setMaxValue(item.maxValue);
        request.setStep(item.step);
        request.setPrecision(item.precision);
        request.setTrueLabel(item.trueLabel);
        request.setFalseLabel(item.falseLabel);
        if (!includeLovValues) {
            request.setLovValues(List.of());
        }
        return request;
    }

    private MetaAttributeUpsertRequestDto toAttributeRequest(MetaAttributeDefDetailDto detail, String attrKey) {
        MetaAttributeUpsertRequestDto request = new MetaAttributeUpsertRequestDto();
        request.setKey(attrKey);
        request.setGenerationMode("MANUAL");
        request.setDisplayName(detail.getLatestVersion().getDisplayName());
        request.setAttributeField(detail.getLatestVersion().getAttributeField());
        request.setDescription(detail.getLatestVersion().getDescription());
        request.setDataType(normalizeServiceDataType(detail.getLatestVersion().getDataType()));
        request.setUnit(detail.getLatestVersion().getUnit());
        request.setDefaultValue(detail.getLatestVersion().getDefaultValue());
        request.setRequired(detail.getLatestVersion().getRequired());
        request.setUnique(detail.getLatestVersion().getUnique());
        request.setSearchable(detail.getLatestVersion().getSearchable());
        request.setHidden(detail.getLatestVersion().getHidden());
        request.setReadOnly(detail.getLatestVersion().getReadOnly());
        request.setMinValue(detail.getLatestVersion().getMinValue());
        request.setMaxValue(detail.getLatestVersion().getMaxValue());
        request.setStep(detail.getLatestVersion().getStep());
        request.setPrecision(detail.getLatestVersion().getPrecision());
        request.setTrueLabel(detail.getLatestVersion().getTrueLabel());
        request.setFalseLabel(detail.getLatestVersion().getFalseLabel());
        request.setLovKey(detail.getLovKey());
        request.setLovGenerationMode(detail.getLovKey() == null ? null : "MANUAL");
        return request;
    }

    private List<MetaAttributeUpsertRequestDto.LovValueUpsertItem> mergeEnumValues(MetaAttributeDefDetailDto detail,
                                                                                    List<EnumWorkItem> group) {
        Map<String, MetaAttributeUpsertRequestDto.LovValueUpsertItem> merged = new LinkedHashMap<>();
        if (detail.getLovValues() != null) {
            for (MetaAttributeDefDetailDto.LovValueItem existing : detail.getLovValues()) {
                MetaAttributeUpsertRequestDto.LovValueUpsertItem item = new MetaAttributeUpsertRequestDto.LovValueUpsertItem();
                item.setCode(existing.getCode());
                item.setName(existing.getValue());
                item.setLabel(existing.getLabel());
                merged.put(existing.getCode(), item);
            }
        }
        for (EnumWorkItem workItem : group.stream().sorted(Comparator.comparingInt(EnumWorkItem::rowNumber)).toList()) {
            if ("KEEP_EXISTING".equals(workItem.action)) {
                continue;
            }
            MetaAttributeUpsertRequestDto.LovValueUpsertItem item = new MetaAttributeUpsertRequestDto.LovValueUpsertItem();
            item.setCode(workItem.finalCode);
            item.setName(workItem.optionName);
            item.setLabel(workItem.displayLabel);
            merged.put(workItem.finalCode, item);
        }
        return new ArrayList<>(merged.values());
    }

    private Map<String, String> loadExistingEnumCodes(String businessDomain, String attributeKey) {
        MetaAttributeDefDetailDto detail = attributeQueryService.detail(businessDomain, attributeKey, true);
        Map<String, String> result = new LinkedHashMap<>();
        if (detail == null || detail.getLovValues() == null) {
            return result;
        }
        detail.getLovValues().forEach(item -> result.put(item.getCode(), item.getValue()));
        return result;
    }

    private void handleRowFailure(WorkbookImportSupport.JobState job,
                                  String stage,
                                  String sheetName,
                                  int rowNumber,
                                  String entityType,
                                  String entityKey,
                                  Exception ex,
                                  boolean atomic) {
        runtimeService.appendLog(job.getJobId(), log -> {
            log.setLevel("ERROR");
            log.setStage(stage);
            log.setSheetName(sheetName);
            log.setRowNumber(rowNumber);
            log.setEntityType(entityType);
            log.setEntityKey(entityKey);
            log.setAction(atomic ? "ROLLBACK" : "CONTINUE");
            log.setEventType("ROW_FAILED");
            log.setCode("IMPORT_ROW_FAILED");
            log.setMessage(ex.getMessage());
        });
    }

    private void incrementProgress(String jobId,
                                   String stage,
                                   String metric,
                                   int processed,
                                   int total) {
        runtimeService.mutateStatus(jobId, status -> {
            WorkbookImportJobStatusDto.EntityProgressDto entity = switch (stage) {
                case WorkbookImportSupport.STAGE_CATEGORIES -> status.getProgress().getCategories();
                case WorkbookImportSupport.STAGE_ATTRIBUTES -> status.getProgress().getAttributes();
                case WorkbookImportSupport.STAGE_ENUM_OPTIONS -> status.getProgress().getEnumOptions();
                default -> null;
            };
            if (entity != null) {
                entity.setProcessed(processed);
                switch (metric) {
                    case "created" -> entity.setCreated(entity.getCreated() + 1);
                    case "updated" -> entity.setUpdated(entity.getUpdated() + 1);
                    case "skipped" -> entity.setSkipped(entity.getSkipped() + 1);
                    case "failed" -> entity.setFailed(entity.getFailed() + 1);
                    default -> {
                    }
                }
            }
            status.setStagePercent(total <= 0 ? 100 : Math.max(0, Math.min(100, (processed * 100) / total)));
        });
    }

    private String normalizeServiceDataType(String dataType) {
        if (dataType == null) {
            return null;
        }
        if ("multi_enum".equalsIgnoreCase(dataType)) {
            return "multi-enum";
        }
        return dataType;
    }

    private String resolveParentFinalCode(CategoryWorkItem item, Map<String, CategoryWorkItem> byPath) {
        if (item.parentPath == null) {
            return null;
        }
        CategoryWorkItem parent = byPath.get(item.businessDomain + "::" + item.parentPath);
        if (parent != null) {
            return parent.finalCode;
        }
        return pathLeaf(item.parentPath);
    }

    private String resolveParentFinalPath(CategoryWorkItem item, Map<String, CategoryWorkItem> byPath) {
        if (item.parentPath == null) {
            return null;
        }
        CategoryWorkItem parent = byPath.get(item.businessDomain + "::" + item.parentPath);
        return parent == null ? item.parentPath : parent.finalPath;
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
        if (path == null || path.isBlank()) {
            return null;
        }
        int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 ? path : path.substring(lastSlash + 1);
    }

    private String normalizeOperator(String operator) {
        return operator == null || operator.isBlank() ? "system" : operator.trim();
    }

    private record ExecutionPlan(List<CategoryWorkItem> categories,
                                 List<AttributeWorkItem> attributes,
                                 List<EnumWorkItem> enums) {
    }

    private static final class CategoryWorkItem {
        private final String sheetName;
        private final int rowNumber;
        private final String businessDomain;
        private final String excelCode;
        private final String categoryPath;
        private final String categoryName;
        private final String parentPath;
        private String finalCode;
        private String finalPath;
        private String action;
        private final String codeMode;

        private CategoryWorkItem(String sheetName,
                                 int rowNumber,
                                 String businessDomain,
                                 String excelCode,
                                 String categoryPath,
                                 String categoryName,
                                 String parentPath,
                                 String finalCode,
                                 String finalPath,
                                 String action,
                                 String codeMode) {
            this.sheetName = sheetName;
            this.rowNumber = rowNumber;
            this.businessDomain = businessDomain;
            this.excelCode = excelCode;
            this.categoryPath = categoryPath;
            this.categoryName = categoryName;
            this.parentPath = parentPath;
            this.finalCode = finalCode;
            this.finalPath = finalPath;
            this.action = action;
            this.codeMode = codeMode;
        }

        public int rowNumber() {
            return rowNumber;
        }
    }

    private static final class AttributeWorkItem {
        private final String sheetName;
        private final int rowNumber;
        private final String categoryReferenceCode;
        private final String attributeCode;
        private final String attributeName;
        private final String attributeField;
        private final String description;
        private final String dataType;
        private final String unit;
        private final String defaultValue;
        private final Boolean required;
        private final Boolean unique;
        private final Boolean searchable;
        private final Boolean hidden;
        private final Boolean readOnly;
        private final BigDecimal minValue;
        private final BigDecimal maxValue;
        private final BigDecimal step;
        private final Integer precision;
        private final String trueLabel;
        private final String falseLabel;
        private String finalCategoryCode;
        private String finalCode;
        private String action;
        private final String codeMode;

        private AttributeWorkItem(String sheetName,
                                  int rowNumber,
                                  String categoryReferenceCode,
                                  String attributeCode,
                                  String attributeName,
                                  String attributeField,
                                  String description,
                                  String dataType,
                                  String unit,
                                  String defaultValue,
                                  Boolean required,
                                  Boolean unique,
                                  Boolean searchable,
                                  Boolean hidden,
                                  Boolean readOnly,
                                  BigDecimal minValue,
                                  BigDecimal maxValue,
                                  BigDecimal step,
                                  Integer precision,
                                  String trueLabel,
                                  String falseLabel,
                                  String finalCategoryCode,
                                  String finalCode,
                                  String action,
                                  String codeMode) {
            this.sheetName = sheetName;
            this.rowNumber = rowNumber;
            this.categoryReferenceCode = categoryReferenceCode;
            this.attributeCode = attributeCode;
            this.attributeName = attributeName;
            this.attributeField = attributeField;
            this.description = description;
            this.dataType = dataType;
            this.unit = unit;
            this.defaultValue = defaultValue;
            this.required = required;
            this.unique = unique;
            this.searchable = searchable;
            this.hidden = hidden;
            this.readOnly = readOnly;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.step = step;
            this.precision = precision;
            this.trueLabel = trueLabel;
            this.falseLabel = falseLabel;
            this.finalCategoryCode = finalCategoryCode;
            this.finalCode = finalCode;
            this.action = action;
            this.codeMode = codeMode;
        }

        public int rowNumber() {
            return rowNumber;
        }

        private String businessDomain(List<CategoryWorkItem> categories) {
            return categories.stream()
                    .filter(item -> Objects.equals(item.excelCode, categoryReferenceCode) || Objects.equals(item.finalCode, finalCategoryCode))
                    .map(item -> item.businessDomain)
                    .findFirst()
                    .orElseGet(() -> categories.stream()
                            .filter(item -> Objects.equals(item.finalCode, finalCategoryCode))
                            .map(candidate -> candidate.businessDomain)
                            .findFirst()
                            .orElse("MATERIAL"));
        }
    }

    private static final class EnumWorkItem {
        private final String sheetName;
        private final int rowNumber;
        private final String categoryReferenceCode;
        private final String attributeCode;
        private final String optionCode;
        private final String optionName;
        private final String displayLabel;
        private String finalCategoryCode;
        private String finalAttributeCode;
        private String finalCode;
        private String action;
        private final String codeMode;

        private EnumWorkItem(String sheetName,
                             int rowNumber,
                             String categoryReferenceCode,
                             String attributeCode,
                             String optionCode,
                             String optionName,
                             String displayLabel,
                             String finalCategoryCode,
                             String finalAttributeCode,
                             String finalCode,
                             String action,
                             String codeMode) {
            this.sheetName = sheetName;
            this.rowNumber = rowNumber;
            this.categoryReferenceCode = categoryReferenceCode;
            this.attributeCode = attributeCode;
            this.optionCode = optionCode;
            this.optionName = optionName;
            this.displayLabel = displayLabel;
            this.finalCategoryCode = finalCategoryCode;
            this.finalAttributeCode = finalAttributeCode;
            this.finalCode = finalCode;
            this.action = action;
            this.codeMode = codeMode;
        }

        public int rowNumber() {
            return rowNumber;
        }

        private String businessDomain(List<CategoryWorkItem> categories) {
            return categories.stream()
                    .filter(item -> Objects.equals(item.excelCode, categoryReferenceCode) || Objects.equals(item.finalCode, finalCategoryCode))
                    .map(item -> item.businessDomain)
                    .findFirst()
                    .orElse("MATERIAL");
        }
    }
}