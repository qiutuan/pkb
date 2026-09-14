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
@RequestMapping("/api/kbs")
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<KnowledgeBaseService.KbView>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseService.KbView> get(@PathVariable long id) {
        return ApiResponse.ok(service.toView(service.require(id)));
    }

    @PostMapping
    public ApiResponse<KnowledgeBaseService.KbView> create(@RequestBody KnowledgeBase kb) {
        kb.setId(null);
        return ApiResponse.ok(service.toView(service.save(kb)));
    }

    @PutMapping("/{id}")
    public ApiResponse<KnowledgeBaseService.KbView> update(@PathVariable long id, @RequestBody KnowledgeBase kb) {
        kb.setId(id);
        return ApiResponse.ok(service.toView(service.save(kb)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ApiResponse.ok();
    }
}
