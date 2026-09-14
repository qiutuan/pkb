package com.pkb.rag;

import com.pkb.util.TextUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 混合重排：向量分数（0.65）+ 轻量关键词分数（0.35，中文二元组/英文词）。
 * 零外部依赖、中文友好，默认开启。
 */
public class HybridReranker implements Reranker {

    private static final double VEC_W = 0.65;
    private static final double KW_W = 0.35;

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        List<Scored> scored = new ArrayList<>();
        for (RetrievedChunk c : candidates) {
            double kw = TextUtil.keywordScore(query, c.content());
            double finalScore = VEC_W * Math.max(0, c.score()) + KW_W * kw;
            scored.add(new Scored(c, finalScore));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<RetrievedChunk> out = new ArrayList<>();
        for (Scored s : scored) {
            if (topK > 0 && out.size() >= topK) {
                break;
            }
            // 重新打包分数（保留来源标记）
            out.add(new RetrievedChunk(s.chunk().chunkId(), s.chunk().kbId(), s.chunk().docId(),
                    s.chunk().docName(), s.chunk().position(), s.chunk().content(), s.score(), s.chunk().source()));
        }
        return out;
    }

    private record Scored(RetrievedChunk chunk, double score) {
    }
}
