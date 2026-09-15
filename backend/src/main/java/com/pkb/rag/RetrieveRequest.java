package com.pkb.rag;

import java.util.List;
import java.util.Map;

/**
 * 检索请求。
 */
public record RetrieveRequest(
        List<Long> kbIds,
        String query,
        Integer topK,
        Double minScore,
        String rerank,
        Boolean graphRag,
        Boolean hybrid,
        String scoreNorm,
        Boolean queryRewrite,
        Boolean hyde,
        Long rerankProviderId,
        List<Map<String, Object>> history) {
}
