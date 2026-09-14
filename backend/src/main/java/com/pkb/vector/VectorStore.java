package com.pkb.vector;

import java.util.List;

/**
 * 向量存储抽象：embedded（内置 SQLite+HNSW/Flat）与 pgvector 可无缝替换。
 */
public interface VectorStore {

    /** 新增/更新向量（向量会被归一化后存储） */
    void add(long chunkId, long kbId, float[] vector);

    /** 删除指定知识库下的片段向量 */
    void deleteChunks(long kbId, List<Long> chunkIds);

    /** 删除整个知识库向量 */
    void deleteByKb(long kbId);

    /** 检索 topK 个相似片段，返回余弦相似度分数 */
    List<ScoredId> search(long kbId, float[] query, int topK, double minScore);

    long count(long kbId);

    record ScoredId(long chunkId, float score) {
    }
}
