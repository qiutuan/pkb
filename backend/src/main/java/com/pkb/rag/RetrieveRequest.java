package com.pkb.rag;

import java.util.List;

/**
 * 检索请求。
 */
public record RetrieveRequest(
        List<Long> kbIds,
        String query,
        Integer topK,
        Double minScore,
        String rerank,
        Boolean graphRag) {
}
