package com.pkb.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Bm25IndexTest {

    private Bm25Index.KbIndex build() {
        Bm25Index.KbIndex idx = new Bm25Index.KbIndex();
        idx.add(1, "数据库索引用于加速查询，MySQL 使用 B+ 树作为默认索引结构。");
        idx.add(2, "Redis 是内存数据库，支持多种数据结构，常用于缓存。");
        idx.add(3, "今天天气很好，适合出去爬山运动。");
        return idx;
    }

    @Test
    void searchFindsChineseTermsAndRanks() {
        Bm25Index.KbIndex idx = build();
        List<Bm25Index.Scored> hits = idx.search("数据库索引", 3);
        assertFalse(hits.isEmpty());
        assertEquals(1, hits.get(0).chunkId());
        assertTrue(hits.get(0).score() > 0);
    }

    @Test
    void searchEmptyQueryReturnsEmpty() {
        Bm25Index.KbIndex idx = build();
        assertTrue(idx.search("", 3).isEmpty());
        assertTrue(idx.search(null, 3).isEmpty());
    }

    @Test
    void limitIsRespected() {
        Bm25Index.KbIndex idx = build();
        List<Bm25Index.Scored> hits = idx.search("的", 1); // 停用字应无命中
        assertTrue(hits.size() <= 1);
    }

    @Test
    void noMatchReturnsEmpty() {
        Bm25Index.KbIndex idx = build();
        assertTrue(idx.search("量子计算量子纠错", 3).isEmpty());
    }

    @Test
    void englishTermMatch() {
        Bm25Index.KbIndex idx = build();
        List<Bm25Index.Scored> hits = idx.search("redis cache", 3);
        assertFalse(hits.isEmpty());
        assertEquals(2, hits.get(0).chunkId());
    }
}
