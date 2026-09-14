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
        /** 会话指定聊天模型 Provider id（可选，写入会话记忆） */
        Long modelProviderId,
        String imageBase64,
        String imageMime) {
}
