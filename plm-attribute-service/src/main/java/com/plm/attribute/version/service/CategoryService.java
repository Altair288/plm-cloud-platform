package com.plm.attribute.version.service;

import com.plm.common.domain.Category;
import com.plm.infrastructure.code.CodeRuleGenerator;
import com.plm.infrastructure.repository.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CategoryService {
    private final CategoryRepository categoryRepository;
    private final CodeRuleGenerator codeRuleGenerator;

    public CategoryService(CategoryRepository categoryRepository, CodeRuleGenerator codeRuleGenerator) {
        this.categoryRepository = categoryRepository;
        this.codeRuleGenerator = codeRuleGenerator;
    }

    @Transactional
    public Category create(String name, String description) {
        if (categoryRepository.existsByCode(name)) { // 临时：如果存在同名 code 冲突（稍后 code 和 name 分离逻辑可调整）
            throw new IllegalArgumentException("分类名称导致编码冲突: " + name);
        }
        Category c = new Category();
        c.setId(UUID.randomUUID());
        c.setName(name);
        c.setDescription(description);
        // 通过规则生成 code（示例使用固定 ruleCode，可后续按类型映射）
        String code = codeRuleGenerator.generate("CATEGORY");
        c.setCode(code);
        return categoryRepository.save(c);
    }

    public List<Category> list() {
        return categoryRepository.findAll();
    }
}
