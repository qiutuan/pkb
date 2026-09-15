package com.pkb.rag;

import com.pkb.common.ApiResponse;
import com.pkb.config.PkbProperties;
import com.pkb.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索接口：常规检索 / 分阶段检索测试（debug）/ 多组问题评估（eval，结果落 data/eval/）。
 */
@Slf4j
@RestController
@RequestMapping("/api/retrieval")
public class RetrievalController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final RetrievalService retrievalService;
    private final PkbProperties props;

    public RetrievalController(RetrievalService retrievalService, PkbProperties props) {
        this.retrievalService = retrievalService;
        this.props = props;
    }

    @PostMapping
    public ApiResponse<List<RetrievedChunk>> retrieve(@RequestBody RetrieveRequest req) {
        return ApiResponse.ok(retrievalService.retrieve(req));
    }

    /** 分阶段检索：向量 / 关键词 / 融合 / 重排 四列 + 分数分布 */
    @PostMapping("/debug")
    public ApiResponse<Map<String, Object>> debug(@RequestBody RetrieveRequest req) {
        return ApiResponse.ok(retrievalService.debug(req));
    }

    /** 多组问题评估：每问记录 Hit@K，汇总后落 data/eval/eval-<时间>.json */
    @PostMapping("/eval")
    public ApiResponse<Map<String, Object>> eval(@RequestBody EvalRequest req) {
        if (req.questions() == null || req.questions().isEmpty()) {
            throw new com.pkb.common.BusinessException("请至少输入一组测试问题");
        }
        if (req.kbIds() == null || req.kbIds().isEmpty()) {
            throw new com.pkb.common.BusinessException("请至少选择一个知识库");
        }
        int topK = req.topK() == null || req.topK() <= 0 ? 8 : Math.min(req.topK(), 50);
        List<Map<String, Object>> details = new ArrayList<>();
        int hitCount = 0;
        for (EvalQuestion q : req.questions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("query", q.query());
            List<String> expected = q.expectedDocs() == null ? List.of() : q.expectedDocs();
            row.put("expectedDocs", expected);
            List<RetrievedChunk> hits;
            try {
                hits = retrievalService.retrieve(new RetrieveRequest(
                        req.kbIds(), q.query(), topK, req.minScore(), req.rerank(), req.graphRag(),
                        req.hybrid(), req.scoreNorm(), req.queryRewrite(), req.hyde(), req.rerankProviderId(), null));
            } catch (Exception e) {
                row.put("error", String.valueOf(e.getMessage()));
                row.put("hit", false);
                details.add(row);
                continue;
            }
            boolean hit = false;
            List<String> got = new ArrayList<>();
            for (RetrievedChunk h : hits) {
                got.add(h.docName());
                if (!expected.isEmpty() && expected.contains(h.docName())) {
                    hit = true;
                }
            }
            row.put("topDocs", got);
            row.put("hit", hit);
            if (hit) {
                hitCount++;
            }
            details.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", req.questions().size());
        out.put("hit", hitCount);
        out.put("hitRate", req.questions().isEmpty() ? 0 : Math.round(hitCount * 1000.0 / req.questions().size()) / 10.0);
        out.put("topK", topK);
        out.put("details", details);
        persistEval(out);
        return ApiResponse.ok(out);
    }

    /** 评估结果落盘 <data-dir>/eval/eval-<时间>.json（便于对比调参） */
    private void persistEval(Map<String, Object> out) {
        try {
            Path dir = Paths.get(props.getDataDir()).toAbsolutePath().normalize().resolve("eval");
            Files.createDirectories(dir);
            Path file = dir.resolve("eval-" + LocalDateTime.now().format(FMT) + ".json");
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, JsonUtil.toJson(out).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            log.info("检索评估结果已落盘: {}", file);
        } catch (Exception e) {
            log.warn("评估结果落盘失败: {}", e.getMessage());
        }
    }

    public record EvalRequest(List<Long> kbIds, List<EvalQuestion> questions, Integer topK, Double minScore,
                              String rerank, Boolean graphRag, Boolean hybrid, String scoreNorm,
                              Boolean queryRewrite, Boolean hyde, Long rerankProviderId) {
    }

    public record EvalQuestion(String query, List<String> expectedDocs) {
    }
}
