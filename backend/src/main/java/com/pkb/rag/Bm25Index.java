package com.pkb.rag;

import com.pkb.dao.ChunkDao;
import com.pkb.knowledge.Chunk;
import com.pkb.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量 BM25 全文检索：复用现有中文分词（TextUtil.tokenize）自建倒排索引，内存态、按需重建。
 * 零外部依赖；默认与向量检索做 RRF 融合（见 RetrievalService）。
 */
@Component
public class Bm25Index {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final ChunkDao chunkDao;
    private final Map<Long, KbIndex> indexes = new ConcurrentHashMap<>();

    public Bm25Index(ChunkDao chunkDao) {
        this.chunkDao = chunkDao;
    }

    /** 文档删除 / 重新索引后调用，下次检索按需重建 */
    public void invalidate(long kbId) {
        indexes.remove(kbId);
    }

    /** 入库流水线增量更新（同一 kb 按文档顺序调用） */
    public void add(long kbId, long chunkId, String content) {
        indexes.computeIfAbsent(kbId, this::rebuild).add(chunkId, content);
    }

    /** 检索：返回 BM25 分数 Top-N */
    public List<Scored> search(long kbId, String query, int limit) {
        KbIndex idx = indexes.computeIfAbsent(kbId, this::rebuild);
        return idx.search(query, limit);
    }

    private KbIndex rebuild(long kbId) {
        KbIndex idx = new KbIndex();
        for (Chunk c : chunkDao.findAllByKb(kbId)) {
            if (c.getContent() != null) {
                idx.add(c.getId(), c.getContent());
            }
        }
        return idx;
    }

    /** BM25 命中（chunkId + 分数） */
    public record Scored(long chunkId, double score) {
    }

    /** 单个知识库的倒排索引 */
    static class KbIndex {
        /** term -> chunkId -> tf */
        final Map<String, Map<Long, Integer>> postings = new HashMap<>();
        final Map<Long, Integer> docLen = new HashMap<>();
        int docCount;
        double avgDocLen;

        void add(long chunkId, String content) {
            Map<String, Integer> tf = TextUtil.termFreq(content);
            int len = tf.values().stream().mapToInt(Integer::intValue).sum();
            if (len == 0) {
                return;
            }
            docLen.put(chunkId, len);
            avgDocLen = (avgDocLen * docCount + len) / (docCount + 1);
            docCount++;
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                postings.computeIfAbsent(e.getKey(), k -> new HashMap<>()).put(chunkId, e.getValue());
            }
        }

        List<Scored> search(String query, int limit) {
            List<String> terms = TextUtil.tokenize(query);
            if (terms.isEmpty() || docCount == 0) {
                return List.of();
            }
            Map<Long, Double> acc = new HashMap<>();
            for (String term : new HashSet<>(terms)) {
                Map<Long, Integer> docs = postings.get(term);
                if (docs == null) {
                    continue;
                }
                int df = docs.size();
                double idf = Math.log(1 + (docCount - df + 0.5) / (df + 0.5));
                for (Map.Entry<Long, Integer> d : docs.entrySet()) {
                    long cid = d.getKey();
                    int tf = d.getValue();
                    int dl = docLen.getOrDefault(cid, 1);
                    double score = idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * dl / Math.max(1e-6, avgDocLen)));
                    acc.merge(cid, score, Double::sum);
                }
            }
            List<Scored> out = new ArrayList<>(acc.size());
            acc.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .limit(limit)
                    .forEach(e -> out.add(new Scored(e.getKey(), e.getValue())));
            return out;
        }
    }
}
