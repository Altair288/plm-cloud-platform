package com.plm.attribute.version.service;

import com.plm.common.domain.Attribute;
import com.plm.infrastructure.code.CodeRuleGenerator;
import com.plm.infrastructure.repository.AttributeRepository;
import com.plm.infrastructure.repository.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AttributeService {
    private final AttributeRepository attributeRepository;
    private final CategoryRepository categoryRepository;
    private final CodeRuleGenerator codeRuleGenerator;

    public AttributeService(AttributeRepository attributeRepository, CategoryRepository categoryRepository, CodeRuleGenerator codeRuleGenerator) {
        this.attributeRepository = attributeRepository;
        this.categoryRepository = categoryRepository;
        this.codeRuleGenerator = codeRuleGenerator;
    }

    @Transactional
    public Attribute create(UUID categoryId, String name, String type, String unit, String lovCode, Integer sortOrder, String description) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new IllegalArgumentException("分类不存在: " + categoryId);
        }
        // 使用规则生成属性编码（示例 ruleCode 固定，可后续根据类型映射）
        String code = codeRuleGenerator.generate("ATTRIBUTE");
        if (attributeRepository.existsByCategoryIdAndCode(categoryId, code)) {
            throw new IllegalStateException("生成的属性编码在该分类下已存在: " + code);
        }
        Attribute a = new Attribute();
        a.setId(UUID.randomUUID());
        a.setCategoryId(categoryId);
        a.setName(name);
        a.setType(type);
        a.setUnit(unit);
        a.setLovCode(lovCode);
        a.setSortOrder(sortOrder == null ? 0 : sortOrder);
        a.setDescription(description);
        a.setCode(code);
        return attributeRepository.save(a);
    }

    public List<Attribute> listByCategory(UUID categoryId) {
        return attributeRepository.findByCategoryIdOrderBySortOrderAsc(categoryId);
    }
}
