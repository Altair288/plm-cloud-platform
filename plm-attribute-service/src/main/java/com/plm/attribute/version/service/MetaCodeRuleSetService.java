package com.plm.attribute.version.service;

import com.plm.common.api.dto.code.CodeRuleDetailDto;
import com.plm.common.api.dto.code.CodeRuleSetDetailDto;
import com.plm.common.api.dto.code.CodeRuleSetSaveRequestDto;
import com.plm.common.api.dto.code.CodeRuleSetSummaryDto;
import com.plm.common.version.domain.MetaCodeRule;
import com.plm.common.version.domain.MetaCodeRuleSet;
import com.plm.infrastructure.version.repository.MetaCodeRuleRepository;
import com.plm.infrastructure.version.repository.MetaCodeRuleSetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class MetaCodeRuleSetService {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final MetaCodeRuleSetRepository codeRuleSetRepository;
    private final MetaCodeRuleRepository codeRuleRepository;
    private final MetaCodeRuleService codeRuleService;

    public MetaCodeRuleSetService(MetaCodeRuleSetRepository codeRuleSetRepository,
                                  MetaCodeRuleRepository codeRuleRepository,
                                  MetaCodeRuleService codeRuleService) {
        this.codeRuleSetRepository = codeRuleSetRepository;
        this.codeRuleRepository = codeRuleRepository;
        this.codeRuleService = codeRuleService;
    }

    @Transactional(readOnly = true)
    public List<CodeRuleSetSummaryDto> list() {
        return codeRuleSetRepository.findAllByOrderByBusinessDomainAsc().stream()
                .map(this::toSummaryDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public CodeRuleSetDetailDto detail(String businessDomain) {
        MetaCodeRuleSet ruleSet = loadRuleSet(businessDomain);
        return toDetailDto(ruleSet);
    }

    @Transactional
    public CodeRuleSetDetailDto create(CodeRuleSetSaveRequestDto request, String operator) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String businessDomain = normalizeBusinessDomain(request.getBusinessDomain());
        if (codeRuleSetRepository.existsByBusinessDomain(businessDomain)) {
            throw new IllegalArgumentException("code rule set already exists: businessDomain=" + businessDomain);
        }

        MetaCodeRuleSet ruleSet = new MetaCodeRuleSet();
        ruleSet.setBusinessDomain(businessDomain);
        applySaveRequest(ruleSet, request, operator);
        ruleSet = codeRuleSetRepository.save(Objects.requireNonNull(ruleSet, "ruleSet"));
        return toDetailDto(ruleSet);
    }

    @Transactional
    public CodeRuleSetDetailDto update(String businessDomain, CodeRuleSetSaveRequestDto request, String operator) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        MetaCodeRuleSet ruleSet = loadRuleSet(businessDomain);
        String requestBusinessDomain = trimToNull(request.getBusinessDomain());
        if (requestBusinessDomain != null && !normalizeBusinessDomain(requestBusinessDomain).equals(ruleSet.getBusinessDomain())) {
            throw new IllegalArgumentException("businessDomain in path and body must match");
        }
        applySaveRequest(ruleSet, request, operator);
        ruleSet = codeRuleSetRepository.save(Objects.requireNonNull(ruleSet, "ruleSet"));
        return toDetailDto(ruleSet);
    }

    @Transactional
    public CodeRuleSetDetailDto publish(String businessDomain, String operator) {
        MetaCodeRuleSet ruleSet = loadRuleSet(businessDomain);
        validateBoundRules(ruleSet.getBusinessDomain(), ruleSet.getCategoryRuleCode(), "category", "CATEGORY");
        validateBoundRules(ruleSet.getBusinessDomain(), ruleSet.getAttributeRuleCode(), "attribute", "ATTRIBUTE");
        validateBoundRules(ruleSet.getBusinessDomain(), ruleSet.getLovRuleCode(), "lov", "LOV");

        codeRuleService.publish(ruleSet.getCategoryRuleCode(), operator);
        codeRuleService.publish(ruleSet.getAttributeRuleCode(), operator);
        codeRuleService.publish(ruleSet.getLovRuleCode(), operator);

        ruleSet.setStatus(STATUS_ACTIVE);
        ruleSet.setActive(Boolean.TRUE);
        ruleSet.setUpdatedAt(OffsetDateTime.now());
        ruleSet.setUpdatedBy(normalizeOperator(operator));
        ruleSet = codeRuleSetRepository.save(Objects.requireNonNull(ruleSet, "ruleSet"));
        return toDetailDto(ruleSet);
    }

    @Transactional(readOnly = true)
    public String resolveCategoryRuleCode(String businessDomain) {
        return resolveActiveRuleCode(businessDomain, RuleSlot.CATEGORY);
    }

    @Transactional(readOnly = true)
    public String resolveAttributeRuleCode(String businessDomain) {
        return resolveActiveRuleCode(businessDomain, RuleSlot.ATTRIBUTE);
    }

    @Transactional(readOnly = true)
    public String resolveLovRuleCode(String businessDomain) {
        return resolveActiveRuleCode(businessDomain, RuleSlot.LOV);
    }

    private String resolveActiveRuleCode(String businessDomain, RuleSlot ruleSlot) {
        MetaCodeRuleSet ruleSet = loadRuleSetForGeneration(businessDomain);
        String ruleCode = switch (ruleSlot) {
            case CATEGORY -> trimToNull(ruleSet.getCategoryRuleCode());
            case ATTRIBUTE -> trimToNull(ruleSet.getAttributeRuleCode());
            case LOV -> trimToNull(ruleSet.getLovRuleCode());
        };
        if (ruleCode == null) {
            throw new IllegalArgumentException(ruleSlot.errorCode + ": businessDomain=" + normalizeBusinessDomain(businessDomain));
        }
        MetaCodeRule rule = codeRuleRepository.findByCode(ruleCode)
                .orElseThrow(() -> new IllegalArgumentException(ruleSlot.errorCode + ": businessDomain="
                        + normalizeBusinessDomain(businessDomain) + ", ruleCode=" + ruleCode));
        if (!normalizeBusinessDomain(businessDomain).equals(rule.getBusinessDomain())) {
            throw new IllegalArgumentException(ruleSlot.errorCode + ": businessDomain="
                    + normalizeBusinessDomain(businessDomain) + ", ruleCode=" + ruleCode);
        }
        return ruleCode;
    }

    private MetaCodeRuleSet loadRuleSetForGeneration(String businessDomain) {
        MetaCodeRuleSet ruleSet = codeRuleSetRepository.findByBusinessDomain(normalizeBusinessDomain(businessDomain))
                .orElseThrow(() -> new IllegalArgumentException("CODE_RULE_SET_NOT_CONFIGURED: businessDomain="
                        + normalizeBusinessDomain(businessDomain)));
        if (!STATUS_ACTIVE.equalsIgnoreCase(ruleSet.getStatus()) || !Boolean.TRUE.equals(ruleSet.getActive())) {
            throw new IllegalArgumentException("CODE_RULE_SET_NOT_ACTIVE: businessDomain=" + ruleSet.getBusinessDomain());
        }
        return ruleSet;
    }

    private void applySaveRequest(MetaCodeRuleSet ruleSet, CodeRuleSetSaveRequestDto request, String operator) {
        String businessDomain = ruleSet.getBusinessDomain();
        ruleSet.setName(requireText(request.getName(), "name"));
        ruleSet.setRemark(trimToNull(request.getRemark()));
        ruleSet.setCategoryRuleCode(validateBoundRules(businessDomain, request.getCategoryRuleCode(), "category", "CATEGORY"));
        ruleSet.setAttributeRuleCode(validateBoundRules(businessDomain, request.getAttributeRuleCode(), "attribute", "ATTRIBUTE"));
        ruleSet.setLovRuleCode(validateBoundRules(businessDomain, request.getLovRuleCode(), "lov", "LOV"));
        ruleSet.setStatus(STATUS_DRAFT);
        ruleSet.setActive(Boolean.FALSE);
        ruleSet.setUpdatedAt(OffsetDateTime.now());
        ruleSet.setUpdatedBy(normalizeOperator(operator));
        if (ruleSet.getCreatedBy() == null) {
            ruleSet.setCreatedBy(normalizeOperator(operator));
        }
    }

    private String validateBoundRules(String businessDomain, String ruleCode, String expectedTargetType, String slotLabel) {
        String normalizedRuleCode = requireText(ruleCode, slotLabel.toLowerCase() + "RuleCode");
        MetaCodeRule rule = codeRuleRepository.findByCode(normalizedRuleCode)
                .orElseThrow(() -> new IllegalArgumentException(slotLabel + "_RULE_NOT_CONFIGURED: businessDomain="
                        + businessDomain + ", ruleCode=" + normalizedRuleCode));
        if (!businessDomain.equals(rule.getBusinessDomain())) {
            throw new IllegalArgumentException("code rule businessDomain mismatch: businessDomain=" + businessDomain
                    + ", ruleCode=" + normalizedRuleCode + ", ruleBusinessDomain=" + rule.getBusinessDomain());
        }
        if (!expectedTargetType.equalsIgnoreCase(rule.getTargetType())) {
            throw new IllegalArgumentException("code rule targetType mismatch: ruleCode=" + normalizedRuleCode
                    + ", expectedTargetType=" + expectedTargetType);
        }
        return normalizedRuleCode;
    }

    private CodeRuleSetSummaryDto toSummaryDto(MetaCodeRuleSet ruleSet) {
        CodeRuleSetSummaryDto dto = new CodeRuleSetSummaryDto();
        dto.setBusinessDomain(ruleSet.getBusinessDomain());
        dto.setName(ruleSet.getName());
        dto.setStatus(ruleSet.getStatus());
        dto.setActive(ruleSet.getActive());
        dto.setRemark(ruleSet.getRemark());
        dto.setCategoryRuleCode(ruleSet.getCategoryRuleCode());
        dto.setAttributeRuleCode(ruleSet.getAttributeRuleCode());
        dto.setLovRuleCode(ruleSet.getLovRuleCode());
        return dto;
    }

    private CodeRuleSetDetailDto toDetailDto(MetaCodeRuleSet ruleSet) {
        CodeRuleSetDetailDto dto = new CodeRuleSetDetailDto();
        dto.setBusinessDomain(ruleSet.getBusinessDomain());
        dto.setName(ruleSet.getName());
        dto.setStatus(ruleSet.getStatus());
        dto.setActive(ruleSet.getActive());
        dto.setRemark(ruleSet.getRemark());
        dto.setCategoryRuleCode(ruleSet.getCategoryRuleCode());
        dto.setAttributeRuleCode(ruleSet.getAttributeRuleCode());
        dto.setLovRuleCode(ruleSet.getLovRuleCode());

        Map<String, CodeRuleDetailDto> detailByRuleCode = codeRuleService.detailByRuleCodes(List.of(
            ruleSet.getCategoryRuleCode(),
            ruleSet.getAttributeRuleCode(),
            ruleSet.getLovRuleCode()
        ));
        Map<String, CodeRuleDetailDto> rules = new LinkedHashMap<>();
        rules.put("CATEGORY", detailByRuleCode.get(ruleSet.getCategoryRuleCode()));
        rules.put("ATTRIBUTE", detailByRuleCode.get(ruleSet.getAttributeRuleCode()));
        rules.put("LOV", detailByRuleCode.get(ruleSet.getLovRuleCode()));
        dto.setRules(rules);
        return dto;
    }

    private MetaCodeRuleSet loadRuleSet(String businessDomain) {
        String normalizedBusinessDomain = normalizeBusinessDomain(businessDomain);
        return codeRuleSetRepository.findByBusinessDomain(normalizedBusinessDomain)
                .orElseThrow(() -> new IllegalArgumentException("code rule set not found: businessDomain=" + normalizedBusinessDomain));
    }

    private String normalizeBusinessDomain(String businessDomain) {
        return requireText(businessDomain, "businessDomain").toUpperCase();
    }

    private String requireText(String value, String fieldName) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
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

    private enum RuleSlot {
        CATEGORY("CATEGORY_RULE_NOT_CONFIGURED"),
        ATTRIBUTE("ATTRIBUTE_RULE_NOT_CONFIGURED"),
        LOV("LOV_RULE_NOT_CONFIGURED");

        private final String errorCode;

        RuleSlot(String errorCode) {
            this.errorCode = errorCode;
        }
    }
}