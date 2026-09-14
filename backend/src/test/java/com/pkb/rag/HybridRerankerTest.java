package com.pkb.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HybridRerankerTest {

    private final HybridReranker reranker = new HybridReranker();

    private RetrievedChunk chunk(long id, double vecScore, String content) {
        return new RetrievedChunk(id, 1L, 1L, "doc.txt", 0, content, vecScore, "upload");
    }

    @Test
    void keywordMatchBoostsAbovePureVectorScore() {
        List<RetrievedChunk> candidates = List.of(
                chunk(1, 0.9, "完全无关的内容，讲的是天气与饮食。"),
                chunk(2, 0.5, "关于数据库索引与查询优化的详细说明。")
        );
        List<RetrievedChunk> out = reranker.rerank("数据库 索引 优化", candidates, 2);
        // 关键词命中应把 2 号排到 1 号之前
        assertEquals(2L, out.get(0).chunkId());
        assertEquals(1L, out.get(1).chunkId());
    }

    @Test
    void topKTruncates() {
        List<RetrievedChunk> candidates = List.of(
                chunk(1, 0.9, "alpha 主题"),
                chunk(2, 0.8, "alpha 主题"),
                chunk(3, 0.7, "alpha 主题")
        );
        assertEquals(2, reranker.rerank("alpha", candidates, 2).size());
    }

    @Test
    void negativeVectorScoreClampedToZero() {
        List<RetrievedChunk> candidates = List.of(
                chunk(1, -0.3, "alpha 主题"),
                chunk(2, 0.4, "完全无关的天气内容")
        );
        List<RetrievedChunk> out = reranker.rerank("alpha", candidates, 2);
        // 分数不为负；无关键词命中时向量主导
        assertTrue(out.get(0).score() >= 0);
        assertEquals(1L, out.get(0).chunkId());
    }

    @Test
    void emptyInputsReturnEmpty() {
        assertTrue(reranker.rerank("q", List.of(), 5).isEmpty());
    }
}
