package com.pkb.vector.embedded;

import java.util.List;

/**
 * 向量索引接口（内置模式）。
 */
public interface Index {

    void insert(long id, float[] normalizedVec);

    void remove(long id);

    /** 返回按相似度降序的 topK 结果（score 为余弦相似度） */
    List<Hit> search(float[] query, int topK);

    int size();

    record Hit(long id, float score) {
    }
}
