package com.pkb.rag;

import java.util.List;

/**
 * 流式对话请求。
 */
public record ChatStreamRequest(
        Long sessionId,
        String query,
        List<Long> kbIds,
        Integer topK,
        Double minScore,
        String rerank,
        Boolean graphRag,
        String imageBase64,
        String imageMime) {
}
