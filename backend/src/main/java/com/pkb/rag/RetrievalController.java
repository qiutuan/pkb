package com.pkb.rag;

import com.pkb.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 检索调试接口：返回召回片段与分数（供「检索预览」使用）。
 */
@RestController
@RequestMapping("/api/retrieval")
public class RetrievalController {

    private final RetrievalService retrievalService;

    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping
    public ApiResponse<List<RetrievedChunk>> retrieve(@RequestBody RetrieveRequest req) {
        return ApiResponse.ok(retrievalService.retrieve(req));
    }
}
