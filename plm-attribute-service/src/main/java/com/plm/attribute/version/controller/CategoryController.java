package com.plm.attribute.version.controller;

import com.plm.attribute.version.service.CategoryService;
import com.plm.common.domain.Category;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @PostMapping
    public ResponseEntity<Category> create(@RequestBody CreateCategoryRequest req) {
        Category c = categoryService.create(req.getName(), req.getDescription());
        return ResponseEntity.ok(c);
    }

    @GetMapping
    public List<Category> list() {
        return categoryService.list();
    }

    public static class CreateCategoryRequest {
        private String name;
        private String description;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
