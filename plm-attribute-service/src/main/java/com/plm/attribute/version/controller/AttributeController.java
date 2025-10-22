package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.AttributeService;
import com.plm.common.domain.Attribute;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/attributes")
public class AttributeController {
    private final AttributeService attributeService;

    public AttributeController(AttributeService attributeService) {
        this.attributeService = attributeService;
    }

    @PostMapping
    public ResponseEntity<Attribute> create(@RequestBody CreateAttributeRequest req) {
        Attribute a = attributeService.create(req.getCategoryId(), req.getName(), req.getType(), req.getUnit(), req.getLovCode(), req.getSortOrder(), req.getDescription());
        return ResponseEntity.ok(a);
    }

    @GetMapping("/by-category/{categoryId}")
    public List<Attribute> listByCategory(@PathVariable UUID categoryId) {
        return attributeService.listByCategory(categoryId);
    }

    public static class CreateAttributeRequest {
        private UUID categoryId; private String name; private String type; private String unit; private String lovCode; private Integer sortOrder; private String description;
        public UUID getCategoryId() { return categoryId; }
        public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public String getLovCode() { return lovCode; }
        public void setLovCode(String lovCode) { this.lovCode = lovCode; }
        public Integer getSortOrder() { return sortOrder; }
        public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
