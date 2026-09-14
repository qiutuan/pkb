package com.pkb.model;

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
import java.util.Map;

@RestController
@RequestMapping("/api/providers")
public class ModelProviderController {

    private final ModelProviderService service;

    public ModelProviderController(ModelProviderService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ProviderView>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/defaults")
    public ApiResponse<Map<String, Object>> defaults() {
        return ApiResponse.ok(service.defaults());
    }

    @PostMapping
    public ApiResponse<ProviderView> create(@RequestBody ModelProvider p) {
        p.setId(null);
        return ApiResponse.ok(service.save(p));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProviderView> update(@PathVariable long id, @RequestBody ModelProvider p) {
        p.setId(id);
        return ApiResponse.ok(service.save(p));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    /** 启用/停用（列表内联开关） */
    @PostMapping("/{id}/status")
    public ApiResponse<ProviderView> toggle(@PathVariable long id, @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return ApiResponse.ok(service.toggle(id, enabled));
    }

    @PostMapping("/{id}/test")
    public ApiResponse<Map<String, Object>> test(@PathVariable long id) {
        return ApiResponse.ok(service.test(id));
    }

    @PostMapping("/ollama/models")
    public ApiResponse<List<String>> ollamaModels(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.ollamaModels(body.get("baseUrl")));
    }
}
