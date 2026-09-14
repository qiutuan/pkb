package com.pkb.graph;

import com.pkb.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final GraphService service;

    public GraphController(GraphService service) {
        this.service = service;
    }

    @GetMapping("/{kbId}/stats")
    public ApiResponse<Map<String, Object>> stats(@PathVariable long kbId) {
        return ApiResponse.ok(service.stats(kbId));
    }

    @GetMapping("/{kbId}/data")
    public ApiResponse<Map<String, Object>> data(@PathVariable long kbId,
                                                 @RequestParam(required = false) String query) {
        return ApiResponse.ok(service.graphData(kbId, query));
    }

    @PostMapping("/{kbId}/extract")
    public ApiResponse<Void> extract(@PathVariable long kbId) {
        service.startExtract(kbId);
        return ApiResponse.ok();
    }

    @GetMapping("/{kbId}/extract-status")
    public ApiResponse<Map<String, Object>> extractStatus(@PathVariable long kbId) {
        return ApiResponse.ok(service.extractStatus(kbId));
    }

    @PostMapping("/{kbId}/clear")
    public ApiResponse<Void> clear(@PathVariable long kbId) {
        service.clear(kbId);
        return ApiResponse.ok();
    }

    @GetMapping("/entities/{entityId}/chunks")
    public ApiResponse<List<Map<String, Object>>> entityChunks(@PathVariable long entityId) {
        return ApiResponse.ok(service.entityChunks(entityId));
    }
}
