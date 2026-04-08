package com.plm.attribute.version.service.workbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.plm.attribute.version.service.MetaAttributeManageService;
import com.plm.attribute.version.service.MetaAttributeQueryService;
import com.plm.attribute.version.service.MetaCategoryCrudService;
import com.plm.attribute.version.service.MetaCategoryHierarchyService;
import com.plm.attribute.version.service.MetaCodeRuleService;
import com.plm.attribute.version.service.MetaCodeRuleSetService;
import com.plm.attribute.version.service.AttributeStructureJsonSupport;
import com.plm.common.api.dto.attribute.MetaAttributeDefDetailDto;
import com.plm.common.api.dto.attribute.MetaAttributeUpsertRequestDto;
import com.plm.common.api.dto.category.CreateCategoryRequestDto;
import com.plm.common.api.dto.category.UpdateCategoryRequestDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportDryRunResponseDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportJobStatusDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportPostProcessResponseDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportStartRequestDto;
import com.plm.common.api.dto.imports.workbook.WorkbookImportStartResponseDto;
import com.plm.common.version.domain.MetaAttributeDef;
import com.plm.common.version.domain.MetaAttributeVersion;
import com.plm.common.version.domain.MetaCategoryDef;
import com.plm.common.version.domain.MetaCategoryVersion;
import com.plm.common.version.domain.MetaLovDef;
import com.plm.common.version.domain.MetaLovVersion;
import com.plm.common.version.util.AttributeLovImportUtils;
import com.plm.infrastructure.version.repository.MetaAttributeDefRepository;
import com.plm.infrastructure.version.repository.MetaAttributeVersionRepository;
import com.plm.infrastructure.version.repository.MetaCategoryDefRepository;
import com.plm.infrastructure.version.repository.MetaCategoryVersionRepository;
import com.plm.infrastructure.version.repository.MetaLovDefRepository;
import com.plm.infrastructure.version.repository.MetaLovVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class WorkbookImportExecutionService {

    private static final String WRITE_MODE_CATEGORY_CREATE = "CATEGORY_CREATE";
    private static final String WRITE_MODE_CATEGORY_UPDATE = "CATEGORY_UPDATE";
    private static final String WRITE_MODE_CATEGORY_SKIP = "CATEGORY_SKIP";
    private static final String WRITE_MODE_CATEGORY_CONFLICT = "CATEGORY_CONFLICT";
    private static final String WRITE_MODE_ATTRIBUTE_CREATE = "ATTRIBUTE_CREATE";
    private static final String WRITE_MODE_ATTRIBUTE_UPDATE = "ATTRIBUTE_UPDATE";
    private static final String WRITE_MODE_ATTRIBUTE_SKIP = "ATTRIBUTE_SKIP";
    private static final String WRITE_MODE_ATTRIBUTE_CONFLICT = "ATTRIBUTE_CONFLICT";
    private static final String WRITE_MODE_ENUM_CREATE = "ENUM_CREATE";
    private static final String WRITE_MODE_ENUM_UPDATE = "ENUM_UPDATE";
    private static final String WRITE_MODE_ENUM_SKIP = "ENUM_SKIP";
    private static final String WRITE_MODE_ENUM_CONFLICT = "ENUM_CONFLICT";

    private static final String MODE_EXCEL_MANUAL = "EXCEL_MANUAL";
    private static final String MODE_SYSTEM_RULE_AUTO = "SYSTEM_RULE_AUTO";
    private static final String EXECUTION_MODE_GLOBAL_TX = "GLOBAL_TX";
    private static final String EXECUTION_MODE_STAGE_TX = "STAGE_TX";
    private static final String EXECUTION_MODE_STAGING_ATOMIC = "STAGING_ATOMIC";

    private final WorkbookImportRuntimeService runtimeService;
    private final MetaCategoryCrudService categoryCrudService;
    private final MetaCategoryHierarchyService categoryHierarchyService;
    private final MetaAttributeManageService attributeManageService;
    private final MetaAttributeQueryService attributeQueryService;
    private final MetaCategoryDefRepository categoryDefRepository;
    private final MetaAttributeDefRepository attributeDefRepository;
    private final MetaCategoryVersionRepository categoryVersionRepository;
    private final MetaAttributeVersionRepository attributeVersionRepository;
    private final MetaLovDefRepository lovDefRepository;
    private final MetaLovVersionRepository lovVersionRepository;
    private final MetaCodeRuleService metaCodeRuleService;
    private final MetaCodeRuleSetService metaCodeRuleSetService;
    private final TransactionTemplate transactionTemplate;
    private final Executor taskExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorkbookImportExecutionService(WorkbookImportRuntimeService runtimeService,
                                          MetaCategoryCrudService categoryCrudService,
                                          MetaCategoryHierarchyService categoryHierarchyService,
                                          MetaAttributeManageService attributeManageService,
                                          MetaAttributeQueryService attributeQueryService,
                                          MetaCategoryDefRepository categoryDefRepository,
                                          MetaAttributeDefRepository attributeDefRepository,
                                          MetaCategoryVersionRepository categoryVersionRepository,
                                          MetaAttributeVersionRepository attributeVersionRepository,
                                          MetaLovDefRepository lovDefRepository,
                                          MetaLovVersionRepository lovVersionRepository,
                                          MetaCodeRuleService metaCodeRuleService,
                                          MetaCodeRuleSetService metaCodeRuleSetService,
                                          PlatformTransactionManager transactionManager,
                                          @org.springframework.beans.factory.annotation.Qualifier("workbookImportTaskExecutor") Executor taskExecutor) {
        this.runtimeService = runtimeService;
        this.categoryCrudService = categoryCrudService;
        this.categoryHierarchyService = categoryHierarchyService;
        this.attributeManageService = attributeManageService;
        this.attributeQueryService = attributeQueryService;
        this.categoryDefRepository = categoryDefRepository;
        this.attributeDefRepository = attributeDefRepository;
        this.categoryVersionRepository = categoryVersionRepository;
        this.attributeVersionRepository = attributeVersionRepository;
        this.lovDefRepository = lovDefRepository;
        this.lovVersionRepository = lovVersionRepository;
        this.metaCodeRuleService = metaCodeRuleService;
        this.metaCodeRuleSetService = metaCodeRuleSetService;
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
    }

    public WorkbookImportStartResponseDto startImport(WorkbookImportStartRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("importSessionId or dryRunJobId is required");
        }
        String importSessionId = runtimeService.resolveImportSessionId(request.getImportSessionId(), request.getDryRunJobId());
        WorkbookImportSupport.ImportSessionState session = runtimeService.getSession(importSessionId);
        WorkbookImportDryRunResponseDto response = session.response();
        if (response.getSummary() == null || !Boolean.TRUE.equals(response.getSummary().getCanImport())) {
            throw new IllegalArgumentException("dry-run contains errors; import is not allowed");
        }

        String operator = normalizeOperator(request.getOperator() == null ? session.operator() : request.getOperator());
        ResolvedExecutionOptions executionOptions = resolveExecutionOptions(request);
        WorkbookImportSupport.JobState job = runtimeService.createJob(session, operator, executionOptions.atomic(), executionOptions.executionMode());
        taskExecutor.execute(() -> executeJob(job.getJobId()));

        WorkbookImportStartResponseDto dto = new WorkbookImportStartResponseDto();
        dto.setJobId(job.getJobId());
        dto.setImportSessionId(job.getImportSessionId());
        dto.setStatus(WorkbookImportSupport.STATUS_QUEUED);
        dto.setAtomic(executionOptions.atomic());
        dto.setExecutionMode(executionOptions.executionMode());
        dto.setCreatedAt(OffsetDateTime.now());
        return dto;
    }

    public void executeJob(String jobId) {
        WorkbookImportSupport.JobState job = runtimeService.getJob(jobId);
        WorkbookImportSupport.ImportSessionState session = runtimeService.getSession(job.getImportSessionId());
        try {
            if (EXECUTION_MODE_GLOBAL_TX.equals(job.getExecutionMode())) {
                transactionTemplate.executeWithoutResult(status -> executePlan(job, session, true));
            } else if (EXECUTION_MODE_STAGE_TX.equals(job.getExecutionMode())) {
                executePlanByStageTransactions(job, session, job.isAtomic());
            } else if (EXECUTION_MODE_STAGING_ATOMIC.equals(job.getExecutionMode())) {
                executePlanWithStagingAtomic(job, session);
            } else {
                throw new IllegalArgumentException("unsupported workbook import execution mode: " + job.getExecutionMode());
            }
            if (EXECUTION_MODE_STAGING_ATOMIC.equals(job.getExecutionMode())) {
                cleanupStagedPlanAfterSuccess(job);
            }
            runtimeService.completeJob(jobId);
        } catch (Exception ex) {
            if (EXECUTION_MODE_STAGING_ATOMIC.equals(job.getExecutionMode())) {
                retainStagedPlanForRetry(job);
            }
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

    public WorkbookImportPostProcessResponseDto rebuildCategoryClosure(String jobId, String operator) {
        WorkbookImportSupport.JobState job = runtimeService.getJob(jobId);
        if (!WorkbookImportSupport.JOB_TYPE_IMPORT.equals(job.getJobType())) {
            throw new IllegalArgumentException("post-process is only supported for import jobs");
        }
        WorkbookImportJobStatusDto status = runtimeService.getJobStatus(jobId);
        if (!WorkbookImportSupport.STATUS_COMPLETED.equals(status.getStatus())
                && !WorkbookImportSupport.STATUS_FAILED.equals(status.getStatus())) {
            throw new IllegalArgumentException("post-process is only allowed after the import job reaches a terminal state");
        }
        Map<String, Object> rebuildResult = transactionTemplate.execute(action -> categoryHierarchyService.rebuildClosure());
        runtimeService.appendLog(jobId, log -> {
            log.setLevel("INFO");
            log.setStage(WorkbookImportSupport.STAGE_FINALIZING);
            log.setEntityType("CATEGORY_CLOSURE");
            log.setEntityKey("GLOBAL");
            log.setAction("REBUILD_CLOSURE");
            log.setEventType("POST_PROCESS");
            log.setCode("CATEGORY_CLOSURE_REBUILD_COMPENSATED");
            log.setMessage("已执行分类闭包表补偿重建");
            log.setDetails(rebuildResult == null ? Map.of() : rebuildResult);
        });

        WorkbookImportPostProcessResponseDto response = new WorkbookImportPostProcessResponseDto();
        response.setJobId(jobId);
        response.setAction("REBUILD_CLOSURE");
        response.setStatus("COMPLETED");
        response.setExecutionMode(job.getExecutionMode());
        response.setExecutedAt(OffsetDateTime.now());
        response.setDetails(rebuildResult == null ? Map.of() : rebuildResult);
        return response;
    }

    private void executePlan(WorkbookImportSupport.JobState job,
                             WorkbookImportSupport.ImportSessionState session,
                             boolean atomic) {
        WorkbookImportSupport.ExecutionPlanSnapshot snapshot = requireExecutionPlan(session);
        ExecutionPlan plan = buildPlan(snapshot);

        runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_PREPARING, WorkbookImportSupport.STAGE_PREPARING);
        if (shouldReserveCodes(snapshot)) {
            reserveCategoryCodes(job, plan, session);
            reserveAttributeCodes(job, plan, session);
            reserveEnumCodes(job, plan, session);
        }

        runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_IMPORTING_CATEGORIES, WorkbookImportSupport.STAGE_CATEGORIES);
        importCategories(job, plan, atomic);

        runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_IMPORTING_ATTRIBUTES, WorkbookImportSupport.STAGE_ATTRIBUTES);
        importAttributes(job, plan, atomic);

        runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_IMPORTING_ENUM_OPTIONS, WorkbookImportSupport.STAGE_ENUM_OPTIONS);
        importEnumOptions(job, plan, atomic);

        runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_FINALIZING, WorkbookImportSupport.STAGE_FINALIZING);
        finalizeImport(job, plan);
    }

    private void executePlanByStageTransactions(WorkbookImportSupport.JobState job,
                                                WorkbookImportSupport.ImportSessionState session,
                                                boolean atomic) {
        WorkbookImportSupport.ExecutionPlanSnapshot snapshot = requireExecutionPlan(session);
        ExecutionPlan plan = buildPlan(snapshot);

        runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_PREPARING, WorkbookImportSupport.STAGE_PREPARING);
        transactionTemplate.executeWithoutResult(status -> {
            if (shouldReserveCodes(snapshot)) {
                reserveCategoryCodes(job, plan, session);
            }
            runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_IMPORTING_CATEGORIES, WorkbookImportSupport.STAGE_CATEGORIES);
            importCategories(job, plan, atomic);
        });

        runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_PREPARING, WorkbookImportSupport.STAGE_PREPARING);
        transactionTemplate.executeWithoutResult(status -> {
            if (shouldReserveCodes(snapshot)) {
                reserveAttributeCodes(job, plan, session);
            }
            runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_IMPORTING_ATTRIBUTES, WorkbookImportSupport.STAGE_ATTRIBUTES);
            importAttributes(job, plan, atomic);
        });

        runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_PREPARING, WorkbookImportSupport.STAGE_PREPARING);
        transactionTemplate.executeWithoutResult(status -> {
            if (shouldReserveCodes(snapshot)) {
                reserveEnumCodes(job, plan, session);
            }
            runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_IMPORTING_ENUM_OPTIONS, WorkbookImportSupport.STAGE_ENUM_OPTIONS);
            importEnumOptions(job, plan, atomic);
        });

        runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_FINALIZING, WorkbookImportSupport.STAGE_FINALIZING);
        transactionTemplate.executeWithoutResult(status -> finalizeImport(job, plan));
    }

    private void executePlanWithStagingAtomic(WorkbookImportSupport.JobState job,
                                              WorkbookImportSupport.ImportSessionState session) {
        runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_PREPARING, WorkbookImportSupport.STAGE_PREPARING);
        ExecutionPlan stagedPlan = prepareStagedExecutionPlan(job, session);

        transactionTemplate.executeWithoutResult(status -> {
            runtimeService.appendLog(job.getJobId(), log -> {
                log.setLevel("INFO");
                log.setStage(WorkbookImportSupport.STAGE_PREPARING);
                log.setEventType("STAGING_MERGE");
                log.setCode("WORKBOOK_STAGING_MERGE_STARTED");
                log.setMessage("staged plan 已准备完成，开始原子 merge");
                log.setDetails(Map.of(
                        "executionMode", job.getExecutionMode(),
                        "categoryCount", stagedPlan.categories.size(),
                        "attributeCount", stagedPlan.attributes.size(),
                        "enumOptionCount", stagedPlan.enums.size()));
            });

            runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_IMPORTING_CATEGORIES, WorkbookImportSupport.STAGE_CATEGORIES);
            importCategories(job, stagedPlan, true);

            runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_IMPORTING_ATTRIBUTES, WorkbookImportSupport.STAGE_ATTRIBUTES);
            importAttributes(job, stagedPlan, true);

            runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_IMPORTING_ENUM_OPTIONS, WorkbookImportSupport.STAGE_ENUM_OPTIONS);
            importEnumOptions(job, stagedPlan, true);

            runtimeService.setStage(job.getJobId(), WorkbookImportSupport.STATUS_FINALIZING, WorkbookImportSupport.STAGE_FINALIZING);
            finalizeImport(job, stagedPlan);
        });
    }

    private WorkbookImportSupport.ExecutionPlanSnapshot requireExecutionPlan(WorkbookImportSupport.ImportSessionState session) {
        WorkbookImportSupport.ExecutionPlanSnapshot snapshot = session.executionPlan();
        if (snapshot == null) {
            throw new IllegalArgumentException("workbook import execution plan not found: importSessionId=" + session.importSessionId());
        }
        return snapshot;
    }

    private ExecutionPlan buildPlan(WorkbookImportSupport.ExecutionPlanSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("workbook import execution plan not found");
        }

        List<CategoryWorkItem> categories = snapshot.categories().stream()
            .map(this::toCategoryWorkItem)
            .sorted(Comparator.comparingInt(CategoryWorkItem::rowNumber))
            .toList();

        List<AttributeWorkItem> attributes = snapshot.attributes().stream()
            .map(this::toAttributeWorkItem)
            .sorted(Comparator.comparingInt(AttributeWorkItem::rowNumber))
            .toList();

        List<EnumWorkItem> enums = snapshot.enumOptions().stream()
            .map(this::toEnumWorkItem)
            .sorted(Comparator.comparingInt(EnumWorkItem::rowNumber))
            .toList();

        return new ExecutionPlan(categories, attributes, enums);
    }

    private ExecutionPlan prepareStagedExecutionPlan(WorkbookImportSupport.JobState job,
                                                     WorkbookImportSupport.ImportSessionState session) {
        WorkbookImportSupport.ExecutionPlanSnapshot existingStagedPlan = session.stagedExecutionPlan();
        if (existingStagedPlan != null) {
            runtimeService.appendLog(job.getJobId(), log -> {
                log.setLevel("INFO");
                log.setStage(WorkbookImportSupport.STAGE_PREPARING);
                log.setEventType("STAGING_PREPARED");
                log.setCode("WORKBOOK_STAGING_PLAN_REUSED");
                log.setMessage("检测到已持久化 staged plan，直接复用");
                log.setDetails(Map.of(
                        "executionMode", job.getExecutionMode(),
                        "categoryCount", existingStagedPlan.categories().size(),
                        "attributeCount", existingStagedPlan.attributes().size(),
                        "enumOptionCount", existingStagedPlan.enumOptions().size()));
            });
            return buildPlan(existingStagedPlan);
        }

        WorkbookImportSupport.ExecutionPlanSnapshot snapshot = requireExecutionPlan(session);
        ExecutionPlan plan = buildPlan(snapshot);
        if (shouldReserveCodes(snapshot)) {
            reserveCategoryCodes(job, plan, session);
            reserveAttributeCodes(job, plan, session);
            reserveEnumCodes(job, plan, session);
        }

        WorkbookImportSupport.ExecutionPlanSnapshot stagedPlan = toExecutionPlanSnapshot(plan);
        runtimeService.saveStagedExecutionPlan(session.importSessionId(), stagedPlan);
        runtimeService.appendLog(job.getJobId(), log -> {
            log.setLevel("INFO");
            log.setStage(WorkbookImportSupport.STAGE_PREPARING);
            log.setEventType("STAGING_PREPARED");
            log.setCode("WORKBOOK_STAGING_PLAN_PREPARED");
            log.setMessage("execution plan 已写入 staging snapshot");
            log.setDetails(Map.of(
                    "executionMode", job.getExecutionMode(),
                    "categoryCount", stagedPlan.categories().size(),
                    "attributeCount", stagedPlan.attributes().size(),
                    "enumOptionCount", stagedPlan.enumOptions().size()));
        });
        return plan;
    }

    private void cleanupStagedPlanAfterSuccess(WorkbookImportSupport.JobState job) {
        runtimeService.clearStagedExecutionPlan(job.getImportSessionId());
        runtimeService.appendLog(job.getJobId(), log -> {
            log.setLevel("INFO");
            log.setStage(WorkbookImportSupport.STAGE_FINALIZING);
            log.setEventType("STAGING_CLEANUP");
            log.setCode("WORKBOOK_STAGING_PLAN_CLEARED");
            log.setMessage("staged plan 已在成功导入后清理，避免重复复用旧号段");
            log.setDetails(Map.of(
                    "executionMode", job.getExecutionMode(),
                    "importSessionId", job.getImportSessionId()));
        });
    }

    private void retainStagedPlanForRetry(WorkbookImportSupport.JobState job) {
        runtimeService.appendLog(job.getJobId(), log -> {
            log.setLevel("WARN");
            log.setStage(runtimeService.getJobStatus(job.getJobId()).getCurrentStage());
            log.setEventType("STAGING_RETRY_READY");
            log.setCode("WORKBOOK_STAGING_PLAN_RETAINED");
            log.setMessage("staged plan 已保留，可在修正问题后直接重试");
            log.setDetails(Map.of(
                    "executionMode", job.getExecutionMode(),
                    "importSessionId", job.getImportSessionId()));
        });
    }

        private CategoryWorkItem toCategoryWorkItem(WorkbookImportSupport.CategoryPlanItem item) {
        return new CategoryWorkItem(
            item.sheetName(),
            item.rowNumber(),
            item.businessDomain(),
            item.excelReferenceCode(),
            item.categoryPath(),
            item.categoryName(),
            item.parentPath(),
            item.resolvedFinalCode(),
            item.resolvedFinalPath(),
            item.resolvedAction(),
            item.resolvedWriteMode(),
            item.codeMode());
        }

        private AttributeWorkItem toAttributeWorkItem(WorkbookImportSupport.AttributePlanItem item) {
        return new AttributeWorkItem(
            item.sheetName(),
            item.rowNumber(),
            item.businessDomain(),
            item.categoryReferenceCode(),
            item.attributeReferenceCode(),
            item.attributeName(),
            item.attributeField(),
            item.description(),
            item.dataType(),
            item.unit(),
            item.defaultValue(),
            item.required(),
            item.unique(),
            item.searchable(),
            item.hidden(),
            item.readOnly(),
            item.minValue(),
            item.maxValue(),
            item.step(),
            item.precision(),
            item.trueLabel(),
            item.falseLabel(),
            item.resolvedCategoryCode(),
            item.resolvedFinalCode(),
            item.resolvedAction(),
            item.resolvedWriteMode(),
            item.existingAttributeId(),
            item.newStructureHash(),
            item.codeMode());
        }

        private EnumWorkItem toEnumWorkItem(WorkbookImportSupport.EnumPlanItem item) {
        return new EnumWorkItem(
            item.sheetName(),
            item.rowNumber(),
            item.businessDomain(),
            item.categoryReferenceCode(),
            item.attributeReferenceCode(),
            item.optionReferenceCode(),
            item.optionName(),
            item.displayLabel(),
            item.resolvedCategoryCode(),
            item.resolvedAttributeCode(),
            item.resolvedFinalCode(),
            item.resolvedAction(),
            item.resolvedWriteMode(),
            item.codeMode());
        }

            private WorkbookImportSupport.ExecutionPlanSnapshot toExecutionPlanSnapshot(ExecutionPlan plan) {
            List<WorkbookImportSupport.CategoryPlanItem> categories = plan.categories.stream()
                .map(item -> new WorkbookImportSupport.CategoryPlanItem(
                    item.sheetName,
                    item.rowNumber,
                    item.businessDomain,
                    item.excelCode,
                    item.categoryPath,
                    item.categoryName,
                    item.parentPath,
                    item.finalCode,
                    item.finalPath,
                    item.action,
                    item.writeMode,
                    null,
                    null,
                    null,
                    !WRITE_MODE_CATEGORY_SKIP.equals(item.writeMode),
                    item.codeMode))
                .toList();
            List<WorkbookImportSupport.AttributePlanItem> attributes = plan.attributes.stream()
                .map(item -> new WorkbookImportSupport.AttributePlanItem(
                    item.sheetName,
                    item.rowNumber,
                    item.businessDomain,
                    item.categoryReferenceCode,
                    null,
                    item.attributeCode,
                    item.attributeName,
                    item.attributeField,
                    item.description,
                    item.dataType,
                    item.unit,
                    item.defaultValue,
                    item.required,
                    item.unique,
                    item.searchable,
                    item.hidden,
                    item.readOnly,
                    item.minValue,
                    item.maxValue,
                    item.step,
                    item.precision,
                    item.trueLabel,
                    item.falseLabel,
                    item.finalCategoryCode,
                    item.finalCode,
                    item.action,
                    item.writeMode,
                    item.existingAttributeId,
                    null,
                    item.newStructureHash,
                    !WRITE_MODE_ATTRIBUTE_SKIP.equals(item.writeMode),
                    item.codeMode))
                .toList();
            List<WorkbookImportSupport.EnumPlanItem> enumOptions = plan.enums.stream()
                .map(item -> new WorkbookImportSupport.EnumPlanItem(
                    item.sheetName,
                    item.rowNumber,
                    item.businessDomain,
                    item.categoryReferenceCode,
                    item.attributeCode,
                    item.optionCode,
                    item.optionName,
                    item.displayLabel,
                    item.finalCategoryCode,
                    item.finalAttributeCode,
                    item.finalCode,
                    item.action,
                    item.writeMode,
                    null,
                    null,
                    null,
                    !WRITE_MODE_ENUM_SKIP.equals(item.writeMode),
                    item.codeMode))
                .toList();
            return new WorkbookImportSupport.ExecutionPlanSnapshot(categories, attributes, enumOptions, Boolean.TRUE);
            }

    private boolean shouldReserveCodes(WorkbookImportSupport.ExecutionPlanSnapshot snapshot) {
        return snapshot == null || !Boolean.TRUE.equals(snapshot.reservedCodesLocked());
    }

    private void reserveCategoryCodes(WorkbookImportSupport.JobState job,
                                      ExecutionPlan plan,
                                      WorkbookImportSupport.ImportSessionState session) {
        Map<String, List<CategoryWorkItem>> childrenByParent = new LinkedHashMap<>();
        for (CategoryWorkItem item : plan.categories) {
            String parentKey = item.businessDomain + "::" + (item.parentPath == null ? "<ROOT>" : item.parentPath);
            childrenByParent.computeIfAbsent(parentKey, ignored -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<String, List<CategoryWorkItem>> entry : childrenByParent.entrySet()) {
            String parentKey = entry.getKey();
            if (!parentKey.endsWith("::<ROOT>")) {
                continue;
            }
            String businessDomain = parentKey.substring(0, parentKey.length() - "::<ROOT>".length());
            reserveCategoryGroup(job, session, childrenByParent, businessDomain, null, null, entry.getValue());
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
            if (!WRITE_MODE_ATTRIBUTE_CREATE.equals(item.writeMode)) {
                continue;
            }
            grouped.computeIfAbsent(item.businessDomain + "::" + item.finalCategoryCode, ignored -> new ArrayList<>()).add(item);
        }
        for (List<AttributeWorkItem> group : grouped.values()) {
            AttributeWorkItem sample = group.get(0);
            Map<String, String> context = new LinkedHashMap<>();
            context.put("BUSINESS_DOMAIN", sample.businessDomain);
            context.put("CATEGORY_CODE", sample.finalCategoryCode);
            String ruleCode = metaCodeRuleSetService.resolveAttributeRuleCode(sample.businessDomain);
            MetaCodeRuleService.ReservedCodeBatchResult reserved = metaCodeRuleService.reserveCodes(
                    ruleCode,
                    "ATTRIBUTE",
                    context,
                    group.size(),
                    job.getOperator(),
                    false
            );
            List<String> codes = reserved.codes();
            for (int index = 0; index < group.size(); index++) {
                group.get(index).finalCode = index < codes.size() ? codes.get(index) : group.get(index).attributeCode;
            }
            if (!group.isEmpty()) {
                String reservedStartCode = codes.get(0);
                String reservedEndCode = codes.get(codes.size() - 1);
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
            String businessDomain = item.businessDomain;
            attributesByReference.put(businessDomain + "::" + item.categoryReferenceCode + "::" + item.attributeCode, item);
            attributesByFinal.put(businessDomain + "::" + item.finalCategoryCode + "::" + item.finalCode, item);
        }

        Map<String, List<EnumWorkItem>> grouped = new LinkedHashMap<>();
        for (EnumWorkItem item : plan.enums) {
            CategoryWorkItem category = categoriesByReference.get(item.categoryReferenceCode);
            if (category != null) {
                item.finalCategoryCode = category.finalCode;
            }
            String businessDomain = item.businessDomain;
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
            if (!WRITE_MODE_ENUM_CREATE.equals(item.writeMode)) {
                continue;
            }
            grouped.computeIfAbsent(businessDomain + "::" + item.finalCategoryCode + "::" + item.finalAttributeCode,
                    ignored -> new ArrayList<>()).add(item);
        }
        for (List<EnumWorkItem> group : grouped.values()) {
            EnumWorkItem sample = group.get(0);
            Map<String, String> context = new LinkedHashMap<>();
            context.put("BUSINESS_DOMAIN", sample.businessDomain);
            context.put("CATEGORY_CODE", sample.finalCategoryCode);
            context.put("ATTRIBUTE_CODE", sample.finalAttributeCode);
            String ruleCode = metaCodeRuleSetService.resolveLovRuleCode(sample.businessDomain);
            MetaCodeRuleService.ReservedCodeBatchResult reserved = metaCodeRuleService.reserveCodes(
                    ruleCode,
                    "LOV_VALUE",
                    context,
                    group.size(),
                    job.getOperator(),
                    false
            );
            List<String> codes = reserved.codes();
            for (int index = 0; index < group.size(); index++) {
                group.get(index).finalCode = index < codes.size() ? codes.get(index) : group.get(index).optionCode;
            }
            if (!group.isEmpty()) {
                String reservedStartCode = codes.get(0);
                String reservedEndCode = codes.get(codes.size() - 1);
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

    private void reserveCategoryGroup(WorkbookImportSupport.JobState job,
                                      WorkbookImportSupport.ImportSessionState session,
                                      Map<String, List<CategoryWorkItem>> childrenByParent,
                                      String businessDomain,
                                      String parentPath,
                                      String parentFinalPath,
                                      List<CategoryWorkItem> siblings) {
        if (siblings == null || siblings.isEmpty()) {
            return;
        }

        String parentFinalCode = parentPath == null ? null : pathLeaf(parentFinalPath);
        List<CategoryWorkItem> autoItems = new ArrayList<>();
        for (CategoryWorkItem item : siblings) {
            if (MODE_EXCEL_MANUAL.equals(item.codeMode)
                    || (item.finalCode != null
                    && MODE_EXCEL_MANUAL.equals(session.options().getCodingOptions().getCategoryCodeMode()))) {
                item.finalCode = item.excelCode;
                item.finalPath = appendPath(parentFinalPath, item.finalCode);
                continue;
            }
            if (MODE_SYSTEM_RULE_AUTO.equals(item.codeMode) && WRITE_MODE_CATEGORY_CREATE.equals(item.writeMode)) {
                autoItems.add(item);
            }
        }

        if (!autoItems.isEmpty()) {
            Map<String, String> context = new LinkedHashMap<>();
            context.put("BUSINESS_DOMAIN", businessDomain);
            if (parentFinalCode != null) {
                context.put("PARENT_CODE", parentFinalCode);
            }
            String ruleCode = metaCodeRuleSetService.resolveCategoryRuleCode(businessDomain);
            MetaCodeRuleService.ReservedCodeBatchResult reserved = metaCodeRuleService.reserveCodes(
                    ruleCode,
                    "CATEGORY",
                    context,
                    autoItems.size(),
                    job.getOperator(),
                    false
            );
            List<String> codes = reserved.codes();
            for (int index = 0; index < autoItems.size(); index++) {
                CategoryWorkItem item = autoItems.get(index);
                item.finalCode = index < codes.size() ? codes.get(index) : item.excelCode;
                item.finalPath = appendPath(parentFinalPath, item.finalCode);
            }
            String reservedStartCode = codes.get(0);
            String reservedEndCode = codes.get(codes.size() - 1);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("businessDomain", businessDomain);
            if (parentFinalCode != null) {
                details.put("parentCode", parentFinalCode);
            }
            details.put("reservedCount", autoItems.size());
            details.put("startCode", reservedStartCode);
            details.put("endCode", reservedEndCode);
            runtimeService.appendLog(job.getJobId(), log -> {
                log.setLevel("INFO");
                log.setStage(WorkbookImportSupport.STAGE_PREPARING);
                log.setEventType("SEQUENCE_RESERVED");
                log.setCode("CATEGORY_CODE_SEQUENCE_RESERVED");
                log.setMessage("分类编码规则已预留号段");
                log.setDetails(details);
            });
        }

        for (CategoryWorkItem item : siblings) {
            String childKey = item.businessDomain + "::" + item.categoryPath;
            reserveCategoryGroup(job, session, childrenByParent, item.businessDomain, item.categoryPath, item.finalPath, childrenByParent.get(childKey));
        }
    }

    private void importCategories(WorkbookImportSupport.JobState job, ExecutionPlan plan, boolean atomic) {
        int total = plan.categories.size();
        int processed = 0;
        for (CategoryWorkItem item : plan.categories) {
            try {
                if (WRITE_MODE_CATEGORY_CONFLICT.equals(item.writeMode)) {
                    throw new IllegalArgumentException("category duplicate conflict: " + item.finalCode);
                }
                if (WRITE_MODE_CATEGORY_SKIP.equals(item.writeMode)) {
                    runtimeService.appendLog(job.getJobId(), log -> {
                        log.setLevel("INFO");
                        log.setStage(WorkbookImportSupport.STAGE_CATEGORIES);
                        log.setSheetName(item.sheetName);
                        log.setRowNumber(item.rowNumber);
                        log.setEntityType("CATEGORY");
                        log.setEntityKey(item.finalCode);
                        log.setAction(item.action);
                        log.setEventType("ROW_PROCESSED");
                        log.setCode("CATEGORY_SKIPPED");
                        log.setMessage("分类无需写入，已跳过");
                    });
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_CATEGORIES, "skipped", "CATEGORY", item.businessDomain, ++processed, total);
                    continue;
                }

                MetaCategoryDef parent = resolveParentCategory(item, plan.categories);
                if (WRITE_MODE_CATEGORY_UPDATE.equals(item.writeMode)) {
                    MetaCategoryDef existing = categoryDefRepository.findByBusinessDomainAndCodeKey(item.businessDomain, item.finalCode).orElseThrow();
                    UpdateCategoryRequestDto request = new UpdateCategoryRequestDto();
                    request.setCode(item.finalCode);
                    request.setName(item.categoryName);
                    request.setBusinessDomain(item.businessDomain);
                    request.setParentId(parent == null ? null : parent.getId());
                    request.setStatus("active");
                    categoryCrudService.update(existing.getId(), request, job.getOperator(), false);
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_CATEGORIES, "updated", "CATEGORY", item.businessDomain, ++processed, total);
                } else {
                    CreateCategoryRequestDto request = new CreateCategoryRequestDto();
                    request.setCode(item.finalCode);
                    request.setGenerationMode(MODE_SYSTEM_RULE_AUTO.equals(item.codeMode) ? "AUTO_RESERVED" : "MANUAL");
                    request.setName(item.categoryName);
                    request.setBusinessDomain(item.businessDomain);
                    request.setParentId(parent == null ? null : parent.getId());
                    request.setStatus("active");
                    categoryCrudService.create(request, job.getOperator());
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_CATEGORIES, "created", "CATEGORY", item.businessDomain, ++processed, total);
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
                incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_CATEGORIES, "failed", "CATEGORY", item.businessDomain, ++processed, total);
                if (atomic) {
                    throw ex;
                }
            }
        }
    }

    private void importAttributes(WorkbookImportSupport.JobState job, ExecutionPlan plan, boolean atomic) {
        if (!atomic) {
            importAttributesRowWise(job, plan, false);
            return;
        }

        int total = plan.attributes.size();
        int processed = 0;
        List<AttributeWorkItem> writable = new ArrayList<>();
        for (AttributeWorkItem item : plan.attributes) {
            if (WRITE_MODE_ATTRIBUTE_CONFLICT.equals(item.writeMode)) {
                throw new IllegalArgumentException("attribute duplicate conflict: " + item.finalCode);
            }
            if (WRITE_MODE_ATTRIBUTE_SKIP.equals(item.writeMode)) {
                runtimeService.appendLog(job.getJobId(), log -> {
                    log.setLevel("INFO");
                    log.setStage(WorkbookImportSupport.STAGE_ATTRIBUTES);
                    log.setSheetName(item.sheetName);
                    log.setRowNumber(item.rowNumber);
                    log.setEntityType("ATTRIBUTE");
                    log.setEntityKey(item.finalCategoryCode + "/" + item.finalCode);
                    log.setAction(item.action);
                    log.setEventType("ROW_PROCESSED");
                    log.setCode("ATTRIBUTE_SKIPPED");
                    log.setMessage("属性无需写入，已跳过");
                });
                incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ATTRIBUTES, "skipped", "ATTRIBUTE", item.businessDomain, ++processed, total);
                continue;
            }
            writable.add(item);
        }

        if (!writable.isEmpty()) {
            batchUpsertAttributes(job, writable);
            long createCount = writable.stream().filter(item -> WRITE_MODE_ATTRIBUTE_CREATE.equals(item.writeMode)).count();
            long updateCount = writable.size() - createCount;
            runtimeService.appendLog(job.getJobId(), log -> {
                log.setLevel("INFO");
                log.setStage(WorkbookImportSupport.STAGE_ATTRIBUTES);
                log.setEventType("BATCH_UPSERT");
                log.setCode("ATTRIBUTE_BATCH_UPSERTED");
                log.setMessage("属性阶段已按批量 upsert 写入");
                log.setDetails(Map.of(
                        "writeCount", writable.size(),
                        "createCount", createCount,
                        "updateCount", updateCount));
            });
        }

        for (AttributeWorkItem item : writable) {
            String metric = WRITE_MODE_ATTRIBUTE_UPDATE.equals(item.writeMode) ? "updated" : "created";
            incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ATTRIBUTES, metric, "ATTRIBUTE", item.businessDomain, ++processed, total);
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
        }
    }

    private void importAttributesRowWise(WorkbookImportSupport.JobState job, ExecutionPlan plan, boolean atomic) {
        int total = plan.attributes.size();
        int processed = 0;
        for (AttributeWorkItem item : plan.attributes) {
            try {
                if (WRITE_MODE_ATTRIBUTE_CONFLICT.equals(item.writeMode)) {
                    throw new IllegalArgumentException("attribute duplicate conflict: " + item.finalCode);
                }
                if (WRITE_MODE_ATTRIBUTE_SKIP.equals(item.writeMode)) {
                    runtimeService.appendLog(job.getJobId(), log -> {
                        log.setLevel("INFO");
                        log.setStage(WorkbookImportSupport.STAGE_ATTRIBUTES);
                        log.setSheetName(item.sheetName);
                        log.setRowNumber(item.rowNumber);
                        log.setEntityType("ATTRIBUTE");
                        log.setEntityKey(item.finalCategoryCode + "/" + item.finalCode);
                        log.setAction(item.action);
                        log.setEventType("ROW_PROCESSED");
                        log.setCode("ATTRIBUTE_SKIPPED");
                        log.setMessage("属性无需写入，已跳过");
                    });
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ATTRIBUTES, "skipped", "ATTRIBUTE", item.businessDomain, ++processed, total);
                    continue;
                }

                MetaAttributeUpsertRequestDto request = toAttributeRequest(item, false);
                if (WRITE_MODE_ATTRIBUTE_UPDATE.equals(item.writeMode)) {
                    attributeManageService.update(item.businessDomain, item.finalCategoryCode, item.finalCode, request, job.getOperator());
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ATTRIBUTES, "updated", "ATTRIBUTE", item.businessDomain, ++processed, total);
                } else {
                    attributeManageService.create(item.businessDomain, item.finalCategoryCode, request, job.getOperator());
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ATTRIBUTES, "created", "ATTRIBUTE", item.businessDomain, ++processed, total);
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
                incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ATTRIBUTES, "failed", "ATTRIBUTE", item.businessDomain, ++processed, total);
                if (atomic) {
                    throw ex;
                }
            }
        }
    }

    private void importEnumOptions(WorkbookImportSupport.JobState job, ExecutionPlan plan, boolean atomic) {
        if (!atomic) {
            importEnumOptionsRowWise(job, plan, false);
            return;
        }

        int total = plan.enums.size();
        int processed = 0;
        Map<String, List<EnumWorkItem>> grouped = new LinkedHashMap<>();
        for (EnumWorkItem item : plan.enums) {
            grouped.computeIfAbsent(item.finalCategoryCode + "::" + item.finalAttributeCode, ignored -> new ArrayList<>()).add(item);
        }

        List<List<EnumWorkItem>> writableGroups = new ArrayList<>();
        for (List<EnumWorkItem> group : grouped.values()) {
            EnumWorkItem first = group.get(0);
            if (group.stream().anyMatch(item -> WRITE_MODE_ENUM_CONFLICT.equals(item.writeMode))) {
                throw new IllegalArgumentException("enum option duplicate conflict: " + first.finalAttributeCode);
            }
            if (group.stream().allMatch(item -> WRITE_MODE_ENUM_SKIP.equals(item.writeMode))) {
                for (EnumWorkItem item : group) {
                    runtimeService.appendLog(job.getJobId(), log -> {
                        log.setLevel("INFO");
                        log.setStage(WorkbookImportSupport.STAGE_ENUM_OPTIONS);
                        log.setSheetName(item.sheetName);
                        log.setRowNumber(item.rowNumber);
                        log.setEntityType("ENUM_OPTION");
                        log.setEntityKey(item.finalCategoryCode + "/" + item.finalAttributeCode + "/" + item.finalCode);
                        log.setAction(item.action);
                        log.setEventType("ROW_PROCESSED");
                        log.setCode("ENUM_OPTION_SKIPPED");
                        log.setMessage("枚举值无需写入，已跳过");
                    });
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ENUM_OPTIONS, "skipped", "ENUM_OPTION", item.businessDomain, ++processed, total);
                }
                continue;
            }
            writableGroups.add(group);
        }

        if (!writableGroups.isEmpty()) {
            batchUpsertEnumOptions(job, writableGroups);
            long writeCount = writableGroups.stream().mapToLong(List::size).sum();
            runtimeService.appendLog(job.getJobId(), log -> {
                log.setLevel("INFO");
                log.setStage(WorkbookImportSupport.STAGE_ENUM_OPTIONS);
                log.setEventType("BATCH_UPSERT");
                log.setCode("ENUM_OPTION_BATCH_UPSERTED");
                log.setMessage("枚举值阶段已按批量 upsert 写入");
                log.setDetails(Map.of(
                        "groupCount", writableGroups.size(),
                        "writeCount", writeCount));
            });
        }

        for (List<EnumWorkItem> group : writableGroups) {
            for (EnumWorkItem item : group) {
                if (WRITE_MODE_ENUM_SKIP.equals(item.writeMode)) {
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ENUM_OPTIONS, "skipped", "ENUM_OPTION", item.businessDomain, ++processed, total);
                    runtimeService.appendLog(job.getJobId(), log -> {
                        log.setLevel("INFO");
                        log.setStage(WorkbookImportSupport.STAGE_ENUM_OPTIONS);
                        log.setSheetName(item.sheetName);
                        log.setRowNumber(item.rowNumber);
                        log.setEntityType("ENUM_OPTION");
                        log.setEntityKey(item.finalCategoryCode + "/" + item.finalAttributeCode + "/" + item.finalCode);
                        log.setAction(item.action);
                        log.setEventType("ROW_PROCESSED");
                        log.setCode("ENUM_OPTION_SKIPPED");
                        log.setMessage("枚举值无需写入，已跳过");
                    });
                } else {
                    String metric = WRITE_MODE_ENUM_UPDATE.equals(item.writeMode) ? "updated" : "created";
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ENUM_OPTIONS, metric, "ENUM_OPTION", item.businessDomain, ++processed, total);
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
            }
        }
    }

    private void importEnumOptionsRowWise(WorkbookImportSupport.JobState job, ExecutionPlan plan, boolean atomic) {
        int total = plan.enums.size();
        int processed = 0;
        Map<String, List<EnumWorkItem>> grouped = new LinkedHashMap<>();
        for (EnumWorkItem item : plan.enums) {
            grouped.computeIfAbsent(item.finalCategoryCode + "::" + item.finalAttributeCode, ignored -> new ArrayList<>()).add(item);
        }

        for (List<EnumWorkItem> group : grouped.values()) {
            try {
                EnumWorkItem first = group.get(0);
                if (group.stream().anyMatch(item -> WRITE_MODE_ENUM_CONFLICT.equals(item.writeMode))) {
                    throw new IllegalArgumentException("enum option duplicate conflict: " + first.finalAttributeCode);
                }
                if (group.stream().allMatch(item -> WRITE_MODE_ENUM_SKIP.equals(item.writeMode))) {
                    for (EnumWorkItem item : group) {
                        runtimeService.appendLog(job.getJobId(), log -> {
                            log.setLevel("INFO");
                            log.setStage(WorkbookImportSupport.STAGE_ENUM_OPTIONS);
                            log.setSheetName(item.sheetName);
                            log.setRowNumber(item.rowNumber);
                            log.setEntityType("ENUM_OPTION");
                            log.setEntityKey(item.finalCategoryCode + "/" + item.finalAttributeCode + "/" + item.finalCode);
                            log.setAction(item.action);
                            log.setEventType("ROW_PROCESSED");
                            log.setCode("ENUM_OPTION_SKIPPED");
                            log.setMessage("枚举值无需写入，已跳过");
                        });
                        incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ENUM_OPTIONS, "skipped", "ENUM_OPTION", item.businessDomain, ++processed, total);
                    }
                    continue;
                }

                MetaAttributeDefDetailDto detail = attributeQueryService.detail(first.businessDomain, first.finalAttributeCode, true);
                if (detail == null || detail.getLatestVersion() == null) {
                    throw new IllegalArgumentException("attribute not found for enum import: " + first.finalAttributeCode);
                }
                MetaAttributeUpsertRequestDto request = toAttributeRequest(detail, first.finalAttributeCode);
                request.setLovValues(mergeEnumValues(detail, group));
                attributeManageService.update(first.businessDomain, first.finalCategoryCode, first.finalAttributeCode, request, job.getOperator());

                for (EnumWorkItem item : group) {
                    if (WRITE_MODE_ENUM_SKIP.equals(item.writeMode)) {
                        incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ENUM_OPTIONS, "skipped", "ENUM_OPTION", item.businessDomain, ++processed, total);
                        runtimeService.appendLog(job.getJobId(), log -> {
                            log.setLevel("INFO");
                            log.setStage(WorkbookImportSupport.STAGE_ENUM_OPTIONS);
                            log.setSheetName(item.sheetName);
                            log.setRowNumber(item.rowNumber);
                            log.setEntityType("ENUM_OPTION");
                            log.setEntityKey(item.finalCategoryCode + "/" + item.finalAttributeCode + "/" + item.finalCode);
                            log.setAction(item.action);
                            log.setEventType("ROW_PROCESSED");
                            log.setCode("ENUM_OPTION_SKIPPED");
                            log.setMessage("枚举值无需写入，已跳过");
                        });
                    } else {
                        String metric = WRITE_MODE_ENUM_UPDATE.equals(item.writeMode) ? "updated" : "created";
                        incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ENUM_OPTIONS, metric, "ENUM_OPTION", item.businessDomain, ++processed, total);
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
                }
            } catch (Exception ex) {
                for (EnumWorkItem item : group) {
                    handleRowFailure(job, WorkbookImportSupport.STAGE_ENUM_OPTIONS, item.sheetName, item.rowNumber, "ENUM_OPTION", item.finalCode, ex, atomic);
                    incrementProgress(job.getJobId(), WorkbookImportSupport.STAGE_ENUM_OPTIONS, "failed", "ENUM_OPTION", item.businessDomain, ++processed, total);
                }
                if (atomic) {
                    throw ex;
                }
            }
        }
    }

    private void batchUpsertAttributes(WorkbookImportSupport.JobState job,
                                       List<AttributeWorkItem> writable) {
        Map<String, MetaCategoryDef> categoryByDomainCode = loadCategoryDefs(writable);
        Map<UUID, MetaCategoryVersion> categoryVersionByCategoryId = loadCategoryVersions(categoryByDomainCode.values());

        List<UUID> existingIds = writable.stream()
                .map(item -> item.existingAttributeId)
                .filter(Objects::nonNull)
                .toList();
        Map<UUID, MetaAttributeDef> existingDefsById = attributeDefRepository.findAllById(existingIds).stream()
                .collect(LinkedHashMap::new, (map, item) -> map.put(item.getId(), item), Map::putAll);
        Map<UUID, MetaAttributeVersion> latestAttributeVersions = loadAttributeVersions(existingDefsById.values());

        Map<String, Integer> attributeRuleVersionCache = new LinkedHashMap<>();
        List<AttributeBinding> createBindings = new ArrayList<>();
        List<AttributeBinding> updateBindings = new ArrayList<>();

        for (AttributeWorkItem item : writable) {
            MetaCategoryDef categoryDef = categoryByDomainCode.get(composeDomainCodeKey(item.businessDomain, item.finalCategoryCode));
            MetaCategoryVersion categoryVersion = categoryDef == null ? null : categoryVersionByCategoryId.get(categoryDef.getId());
            if (categoryDef == null || categoryVersion == null) {
                throw new IllegalArgumentException("category latest version not found for attribute import: " + item.finalCategoryCode);
            }
            if (WRITE_MODE_ATTRIBUTE_CREATE.equals(item.writeMode)) {
                MetaAttributeDef def = new MetaAttributeDef();
                def.setCategoryDef(categoryDef);
                def.setBusinessDomain(item.businessDomain);
                def.setKey(item.finalCode);
                def.setLovFlag(isEnumLike(item.dataType));
                def.setAutoBindKey(item.finalCode);
                def.setKeyManualOverride(!MODE_SYSTEM_RULE_AUTO.equals(item.codeMode));
                def.setKeyFrozen(Boolean.FALSE);
                def.setGeneratedRuleCode(metaCodeRuleSetService.resolveAttributeRuleCode(item.businessDomain));
                def.setGeneratedRuleVersionNo(resolveAttributeRuleVersion(item.businessDomain, attributeRuleVersionCache));
                def.setCreatedBy(job.getOperator());
                createBindings.add(new AttributeBinding(item, def, categoryVersion, null));
            } else {
                MetaAttributeDef existingDef = existingDefsById.get(item.existingAttributeId);
                if (existingDef == null) {
                    throw new IllegalArgumentException("existing attribute definition not found: " + item.finalCode);
                }
                boolean nextLovFlag = isEnumLike(item.dataType);
                if (!Objects.equals(existingDef.getLovFlag(), nextLovFlag)) {
                    existingDef.setLovFlag(nextLovFlag);
                }
                updateBindings.add(new AttributeBinding(item, existingDef, categoryVersion, latestAttributeVersions.get(existingDef.getId())));
            }
        }

        if (!createBindings.isEmpty()) {
            attributeDefRepository.saveAll(createBindings.stream().map(AttributeBinding::def).toList());
        }
        if (!updateBindings.isEmpty()) {
            attributeDefRepository.saveAll(updateBindings.stream().map(AttributeBinding::def).toList());
        }

        List<MetaAttributeVersion> latestToDeactivate = new ArrayList<>();
        List<MetaAttributeVersion> newVersions = new ArrayList<>();
        for (AttributeBinding binding : createBindings) {
            newVersions.add(buildAttributeVersion(binding.item(), binding.def(), binding.categoryVersion(), null, job.getOperator()));
        }
        for (AttributeBinding binding : updateBindings) {
            MetaAttributeVersion latest = binding.latestVersion();
            if (latest == null) {
                throw new IllegalArgumentException("latest attribute version not found: " + binding.item().finalCode);
            }
            latest.setIsLatest(Boolean.FALSE);
            latestToDeactivate.add(latest);
            newVersions.add(buildAttributeVersion(binding.item(), binding.def(), binding.categoryVersion(), latest, job.getOperator()));
        }
        if (!latestToDeactivate.isEmpty()) {
            attributeVersionRepository.saveAll(latestToDeactivate);
        }
        if (!newVersions.isEmpty()) {
            attributeVersionRepository.saveAll(newVersions);
        }
    }

    private void batchUpsertEnumOptions(WorkbookImportSupport.JobState job,
                                        List<List<EnumWorkItem>> writableGroups) {
        Map<String, Set<String>> attributeKeysByDomain = new LinkedHashMap<>();
        for (List<EnumWorkItem> group : writableGroups) {
            EnumWorkItem first = group.get(0);
            attributeKeysByDomain.computeIfAbsent(first.businessDomain, ignored -> new LinkedHashSet<>()).add(first.finalAttributeCode);
        }

        Map<String, MetaAttributeDef> attributeByDomainKey = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : attributeKeysByDomain.entrySet()) {
            for (MetaAttributeDef def : attributeDefRepository.findActiveByBusinessDomainAndKeyIn(entry.getKey(), entry.getValue())) {
                attributeByDomainKey.put(composeDomainCodeKey(def.getBusinessDomain(), def.getKey()), def);
            }
        }
        Map<UUID, MetaAttributeVersion> latestAttributeVersions = loadAttributeVersions(attributeByDomainKey.values());

        Map<UUID, MetaLovDef> lovDefByAttributeId = lovDefRepository.findByAttributeDefIn(attributeByDomainKey.values()).stream()
                .filter(item -> item.getStatus() == null || !"deleted".equalsIgnoreCase(item.getStatus().trim()))
                .collect(LinkedHashMap::new, (map, item) -> map.put(item.getAttributeDef().getId(), item), Map::putAll);

        List<EnumBinding> bindings = new ArrayList<>();
        List<MetaLovDef> lovDefsToCreate = new ArrayList<>();
        for (List<EnumWorkItem> group : writableGroups) {
            EnumWorkItem first = group.get(0);
            MetaAttributeDef attributeDef = attributeByDomainKey.get(composeDomainCodeKey(first.businessDomain, first.finalAttributeCode));
            if (attributeDef == null) {
                throw new IllegalArgumentException("attribute not found for enum import: " + first.finalAttributeCode);
            }
            MetaAttributeVersion latestAttributeVersion = latestAttributeVersions.get(attributeDef.getId());
            if (latestAttributeVersion == null) {
                throw new IllegalArgumentException("attribute latest version not found for enum import: " + first.finalAttributeCode);
            }
            MetaLovDef lovDef = lovDefByAttributeId.get(attributeDef.getId());
            if (lovDef == null) {
                lovDef = new MetaLovDef();
                lovDef.setAttributeDef(attributeDef);
                lovDef.setBusinessDomain(first.businessDomain);
                lovDef.setKey(resolveLovKey(latestAttributeVersion.getLovKey(), first.finalAttributeCode));
                lovDef.setSourceAttributeKey(first.finalAttributeCode);
                lovDef.setCreatedBy(job.getOperator());
                lovDefsToCreate.add(lovDef);
                lovDefByAttributeId.put(attributeDef.getId(), lovDef);
            }
            bindings.add(new EnumBinding(group, attributeDef, latestAttributeVersion, lovDef));
        }

        if (!lovDefsToCreate.isEmpty()) {
            lovDefRepository.saveAll(lovDefsToCreate);
        }

        Map<UUID, MetaLovVersion> latestLovVersions = loadLovVersions(bindings.stream().map(EnumBinding::lovDef).toList());
        List<MetaLovVersion> latestToDeactivate = new ArrayList<>();
        List<MetaLovVersion> newVersions = new ArrayList<>();
        for (EnumBinding binding : bindings) {
            MetaLovVersion latest = latestLovVersions.get(binding.lovDef().getId());
            LinkedHashMap<String, MetaAttributeUpsertRequestDto.LovValueUpsertItem> merged = parseExistingLovValues(latest);
            for (EnumWorkItem item : binding.group().stream().sorted(Comparator.comparingInt(EnumWorkItem::rowNumber)).toList()) {
                if (WRITE_MODE_ENUM_SKIP.equals(item.writeMode)) {
                    continue;
                }
                MetaAttributeUpsertRequestDto.LovValueUpsertItem lovItem = new MetaAttributeUpsertRequestDto.LovValueUpsertItem();
                lovItem.setCode(item.finalCode);
                lovItem.setName(item.optionName);
                lovItem.setLabel(item.displayLabel);
                merged.put(item.finalCode, lovItem);
            }
            String valueJson = buildLovValueJson(new ArrayList<>(merged.values()));
            String hash = AttributeLovImportUtils.jsonHash(valueJson);
            if (latest != null && Objects.equals(latest.getHash(), hash)) {
                continue;
            }
            if (latest != null) {
                latest.setIsLatest(Boolean.FALSE);
                latestToDeactivate.add(latest);
            }
            MetaLovVersion version = new MetaLovVersion();
            version.setLovDef(binding.lovDef());
            version.setAttributeVersion(binding.latestAttributeVersion());
            version.setVersionNo(latest == null ? 1 : latest.getVersionNo() + 1);
            version.setValueJson(valueJson);
            version.setHash(hash);
            version.setIsLatest(Boolean.TRUE);
            version.setCreatedBy(job.getOperator());
            newVersions.add(version);
        }
        if (!latestToDeactivate.isEmpty()) {
            lovVersionRepository.saveAll(latestToDeactivate);
        }
        if (!newVersions.isEmpty()) {
            lovVersionRepository.saveAll(newVersions);
        }
    }

    private Map<String, MetaCategoryDef> loadCategoryDefs(List<AttributeWorkItem> items) {
        Map<String, Set<String>> categoryCodesByDomain = new LinkedHashMap<>();
        for (AttributeWorkItem item : items) {
            categoryCodesByDomain.computeIfAbsent(item.businessDomain, ignored -> new LinkedHashSet<>()).add(item.finalCategoryCode);
        }
        Map<String, MetaCategoryDef> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : categoryCodesByDomain.entrySet()) {
            for (MetaCategoryDef def : categoryDefRepository.findByBusinessDomainAndCodeKeyIn(entry.getKey(), entry.getValue())) {
                result.put(composeDomainCodeKey(def.getBusinessDomain(), def.getCodeKey()), def);
            }
        }
        return result;
    }

    private Map<UUID, MetaCategoryVersion> loadCategoryVersions(Iterable<MetaCategoryDef> defs) {
        List<MetaCategoryDef> values = new ArrayList<>();
        defs.forEach(values::add);
        return categoryVersionRepository.findByCategoryDefInAndIsLatestTrue(values).stream()
                .collect(LinkedHashMap::new, (map, item) -> map.put(item.getCategoryDef().getId(), item), Map::putAll);
    }

    private Map<UUID, MetaAttributeVersion> loadAttributeVersions(Iterable<MetaAttributeDef> defs) {
        List<MetaAttributeDef> values = new ArrayList<>();
        defs.forEach(values::add);
        return attributeVersionRepository.findByAttributeDefInAndIsLatestTrue(values).stream()
                .collect(LinkedHashMap::new, (map, item) -> map.put(item.getAttributeDef().getId(), item), Map::putAll);
    }

    private Map<UUID, MetaLovVersion> loadLovVersions(Iterable<MetaLovDef> defs) {
        List<MetaLovDef> values = new ArrayList<>();
        defs.forEach(values::add);
        return lovVersionRepository.findByLovDefInAndIsLatestTrue(values).stream()
                .collect(LinkedHashMap::new, (map, item) -> map.put(item.getLovDef().getId(), item), Map::putAll);
    }

    private MetaAttributeVersion buildAttributeVersion(AttributeWorkItem item,
                                                      MetaAttributeDef def,
                                                      MetaCategoryVersion categoryVersion,
                                                      MetaAttributeVersion latest,
                                                      String operator) {
        MetaAttributeVersion version = new MetaAttributeVersion();
        version.setAttributeDef(def);
        version.setCategoryVersion(categoryVersion);
        version.setVersionNo(latest == null ? 1 : latest.getVersionNo() + 1);
        version.setStructureJson(buildAttributeStructureJson(item, latest == null ? null : latest.getLovKey()));
        version.setHash(item.newStructureHash != null ? item.newStructureHash : AttributeLovImportUtils.jsonHash(version.getStructureJson()));
        version.setIsLatest(Boolean.TRUE);
        version.setCreatedBy(operator);
        return version;
    }

    private String buildAttributeStructureJson(AttributeWorkItem item, String existingLovKey) {
        String lovKey = resolveLovKey(existingLovKey, item.finalCode);
        return AttributeStructureJsonSupport.toJson(objectMapper,
                new AttributeStructureJsonSupport.AttributeStructureSpec(
                        item.attributeName,
                        item.dataType,
                        item.description,
                        item.attributeField,
                        item.unit,
                        item.defaultValue,
                        item.required,
                        item.unique,
                        item.hidden,
                        item.readOnly,
                        item.searchable,
                        item.minValue,
                        item.maxValue,
                        item.step,
                        item.precision,
                        item.trueLabel,
                        item.falseLabel,
                        lovKey));
    }

    private LinkedHashMap<String, MetaAttributeUpsertRequestDto.LovValueUpsertItem> parseExistingLovValues(MetaLovVersion latest) {
        LinkedHashMap<String, MetaAttributeUpsertRequestDto.LovValueUpsertItem> values = new LinkedHashMap<>();
        if (latest == null || latest.getValueJson() == null || latest.getValueJson().isBlank()) {
            return values;
        }
        try {
            JsonNode items = objectMapper.readTree(latest.getValueJson()).path("values");
            if (!items.isArray()) {
                return values;
            }
            for (JsonNode item : items) {
                String code = trimToNull(item.path("code").asText(null));
                if (code == null) {
                    continue;
                }
                MetaAttributeUpsertRequestDto.LovValueUpsertItem value = new MetaAttributeUpsertRequestDto.LovValueUpsertItem();
                value.setCode(code);
                value.setName(trimToNull(item.path("name").asText(null)));
                value.setLabel(trimToNull(item.path("label").asText(null)));
                values.put(code, value);
            }
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
        return values;
    }

    private String buildLovValueJson(List<MetaAttributeUpsertRequestDto.LovValueUpsertItem> items) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode values = objectMapper.createArrayNode();
        int order = 1;
        for (MetaAttributeUpsertRequestDto.LovValueUpsertItem item : items) {
            if (item == null) {
                continue;
            }
            String code = trimToNull(item.getCode());
            String name = trimToNull(item.getName());
            if (code == null && name == null) {
                continue;
            }
            ObjectNode value = objectMapper.createObjectNode();
            if (code != null) {
                value.put("code", code);
            }
            if (name != null) {
                value.put("name", name);
            }
            String label = trimToNull(item.getLabel());
            if (label != null) {
                value.put("label", label);
            }
            value.put("order", order++);
            value.put("active", true);
            values.add(value);
        }
        root.set("values", values);
        return root.toString();
    }

    private int resolveAttributeRuleVersion(String businessDomain,
                                           Map<String, Integer> cache) {
        return cache.computeIfAbsent(businessDomain, ignored -> {
            String ruleCode = metaCodeRuleSetService.resolveAttributeRuleCode(businessDomain);
            return metaCodeRuleService.detail(ruleCode).getLatestVersionNo();
        });
    }

    private boolean isEnumLike(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return false;
        }
        String normalized = dataType.trim().toLowerCase(Locale.ROOT);
        return "enum".equals(normalized) || "multi-enum".equals(normalized) || "multi_enum".equals(normalized);
    }

    private String resolveLovKey(String existingLovKey, String attributeCode) {
        String normalizedExisting = trimToNull(existingLovKey);
        if (normalizedExisting != null) {
            return normalizedExisting;
        }
        String normalizedAttributeCode = trimToNull(attributeCode);
        return normalizedAttributeCode == null ? null : normalizedAttributeCode + "_LOV";
    }

    private String composeDomainCodeKey(String businessDomain, String code) {
        return (businessDomain == null ? "" : businessDomain) + "::" + (code == null ? "" : code);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
            if (WRITE_MODE_ENUM_SKIP.equals(workItem.writeMode)) {
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

    private void finalizeImport(WorkbookImportSupport.JobState job, ExecutionPlan plan) {
        long categoryWrites = plan.categories.stream()
                .filter(item -> WRITE_MODE_CATEGORY_CREATE.equals(item.writeMode) || WRITE_MODE_CATEGORY_UPDATE.equals(item.writeMode))
                .count();
        runtimeService.appendLog(job.getJobId(), log -> {
            log.setLevel("INFO");
            log.setStage(WorkbookImportSupport.STAGE_FINALIZING);
            log.setEventType("POST_PROCESS");
            log.setCode(categoryWrites > 0 ? "CATEGORY_CLOSURE_INCREMENTAL_REUSED" : "CATEGORY_CLOSURE_REFRESH_NOT_REQUIRED");
            log.setMessage(categoryWrites > 0
                    ? "分类闭包关系已由分类 CRUD 增量维护，跳过全量重建"
                    : "本次导入没有分类写入，闭包关系无需刷新");
            log.setDetails(Map.of(
                    "strategy", categoryWrites > 0 ? "CRUD_INCREMENTAL" : "NO_CATEGORY_WRITES",
                    "categoryWriteCount", categoryWrites,
                    "globalRebuildTriggered", false,
                    "executionMode", job.getExecutionMode()));
        });
    }

    private ResolvedExecutionOptions resolveExecutionOptions(WorkbookImportStartRequestDto request) {
        String normalizedExecutionMode = normalizeExecutionMode(request.getExecutionMode());
        Boolean requestedAtomic = request.getAtomic();
        if (normalizedExecutionMode == null) {
            if (requestedAtomic == null || Boolean.TRUE.equals(requestedAtomic)) {
                return new ResolvedExecutionOptions(true, EXECUTION_MODE_GLOBAL_TX);
            }
            return new ResolvedExecutionOptions(false, EXECUTION_MODE_STAGE_TX);
        }
        return switch (normalizedExecutionMode) {
            case EXECUTION_MODE_GLOBAL_TX -> {
                boolean atomic = requestedAtomic == null || Boolean.TRUE.equals(requestedAtomic);
                if (!atomic) {
                    throw new IllegalArgumentException("executionMode GLOBAL_TX requires atomic=true");
                }
                yield new ResolvedExecutionOptions(true, EXECUTION_MODE_GLOBAL_TX);
            }
            case EXECUTION_MODE_STAGE_TX -> new ResolvedExecutionOptions(requestedAtomic == null || Boolean.TRUE.equals(requestedAtomic), EXECUTION_MODE_STAGE_TX);
            case EXECUTION_MODE_STAGING_ATOMIC -> {
                boolean atomic = requestedAtomic == null || Boolean.TRUE.equals(requestedAtomic);
                if (!atomic) {
                    throw new IllegalArgumentException("executionMode STAGING_ATOMIC requires atomic=true");
                }
                yield new ResolvedExecutionOptions(true, EXECUTION_MODE_STAGING_ATOMIC);
            }
            default -> throw new IllegalArgumentException("unsupported workbook import execution mode: " + normalizedExecutionMode);
        };
    }

    private String normalizeExecutionMode(String executionMode) {
        if (executionMode == null || executionMode.isBlank()) {
            return null;
        }
        return executionMode.trim().toUpperCase(Locale.ROOT).replace('-', '_');
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
                                   String entityType,
                                   String businessDomain,
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
            status.setCurrentEntityType(entityType);
            status.setCurrentBusinessDomain(businessDomain);
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

    private record ResolvedExecutionOptions(boolean atomic, String executionMode) {
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
        private final String writeMode;
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
                                 String writeMode,
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
            this.writeMode = writeMode;
            this.codeMode = codeMode;
        }

        public int rowNumber() {
            return rowNumber;
        }
    }

    private static final class AttributeWorkItem {
        private final String sheetName;
        private final int rowNumber;
        private final String businessDomain;
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
        private final String writeMode;
        private final UUID existingAttributeId;
        private final String newStructureHash;
        private final String codeMode;

        private AttributeWorkItem(String sheetName,
                                  int rowNumber,
                                  String businessDomain,
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
                                  String writeMode,
                                  UUID existingAttributeId,
                                  String newStructureHash,
                                  String codeMode) {
            this.sheetName = sheetName;
            this.rowNumber = rowNumber;
            this.businessDomain = businessDomain;
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
            this.writeMode = writeMode;
            this.existingAttributeId = existingAttributeId;
            this.newStructureHash = newStructureHash;
            this.codeMode = codeMode;
        }

        public int rowNumber() {
            return rowNumber;
        }
    }

    private static final class EnumWorkItem {
        private final String sheetName;
        private final int rowNumber;
        private final String businessDomain;
        private final String categoryReferenceCode;
        private final String attributeCode;
        private final String optionCode;
        private final String optionName;
        private final String displayLabel;
        private String finalCategoryCode;
        private String finalAttributeCode;
        private String finalCode;
        private String action;
        private final String writeMode;
        private final String codeMode;

        private EnumWorkItem(String sheetName,
                             int rowNumber,
                             String businessDomain,
                             String categoryReferenceCode,
                             String attributeCode,
                             String optionCode,
                             String optionName,
                             String displayLabel,
                             String finalCategoryCode,
                             String finalAttributeCode,
                             String finalCode,
                             String action,
                             String writeMode,
                             String codeMode) {
            this.sheetName = sheetName;
            this.rowNumber = rowNumber;
            this.businessDomain = businessDomain;
            this.categoryReferenceCode = categoryReferenceCode;
            this.attributeCode = attributeCode;
            this.optionCode = optionCode;
            this.optionName = optionName;
            this.displayLabel = displayLabel;
            this.finalCategoryCode = finalCategoryCode;
            this.finalAttributeCode = finalAttributeCode;
            this.finalCode = finalCode;
            this.action = action;
            this.writeMode = writeMode;
            this.codeMode = codeMode;
        }

        public int rowNumber() {
            return rowNumber;
        }
    }

    private record AttributeBinding(AttributeWorkItem item,
                                    MetaAttributeDef def,
                                    MetaCategoryVersion categoryVersion,
                                    MetaAttributeVersion latestVersion) {
    }

    private record EnumBinding(List<EnumWorkItem> group,
                               MetaAttributeDef attributeDef,
                               MetaAttributeVersion latestAttributeVersion,
                               MetaLovDef lovDef) {
    }
}