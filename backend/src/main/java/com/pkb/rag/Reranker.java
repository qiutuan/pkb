package com.pkb.rag;

import java.util.List;

/**
 * 重排器接口：对候选片段重新打分排序。
 */
public interface Reranker {

    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK);
}
