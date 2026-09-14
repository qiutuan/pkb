package com.pkb.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 不重排：按向量分数排序。
 */
public class NoneReranker implements Reranker {

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        List<RetrievedChunk> list = new ArrayList<>(candidates);
        list.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        return topK > 0 && list.size() > topK ? new ArrayList<>(list.subList(0, topK)) : list;
    }
}
