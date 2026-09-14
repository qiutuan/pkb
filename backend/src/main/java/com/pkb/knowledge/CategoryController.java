package com.pkb.knowledge;

import com.pkb.common.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Category>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    public ApiResponse<Category> create(@RequestBody Category c) {
        c.setId(null);
        return ApiResponse.ok(service.save(c));
    }

    @PutMapping("/{id}")
    public ApiResponse<Category> update(@PathVariable long id, @RequestBody Category c) {
        c.setId(id);
        return ApiResponse.ok(service.save(c));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    /** 同级拖拽排序（ids 顺序即新顺序） */
    @PostMapping("/reorder")
    public ApiResponse<Void> reorder(@RequestBody(required = false) List<Long> ids) {
        service.reorder(ids == null ? List.of() : ids);
        return ApiResponse.ok();
    }
}
