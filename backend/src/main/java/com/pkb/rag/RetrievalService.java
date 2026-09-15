package com.pkb.rag;

import com.pkb.common.BusinessException;
import com.pkb.dao.ChunkDao;
import com.pkb.dao.DocumentDao;
import com.pkb.graph.GraphRetrievalService;
import com.pkb.knowledge.Chunk;
import com.pkb.knowledge.Document;
import com.pkb.knowledge.EmbeddingService;
import com.pkb.knowledge.KnowledgeBase;
import com.pkb.knowledge.KnowledgeBaseService;
import com.pkb.model.ModelFactory;
import com.pkb.model.ModelProvider;
import com.pkb.model.ModelProviderService;
import com.pkb.settings.SettingsService;
import com.pkb.util.JsonUtil;
import com.pkb.vector.VectorStore;
import com.pkb.vector.VectorStoreFactory;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产级检索管线：
 * 查询优化（改写 / HyDE，可选）→ 向量召回（可归一化）+ BM25 全文召回 → RRF 融合（k=60）→ 图谱召回合并 → 重排 → topK。
 * 默认开启混合检索；关闭时回退为纯向量召回（与原行为一致），保证向后兼容。
 */
@Slf4j
@Service
public class RetrievalService {

    private static final double RRF_K = 60.0;

    private final VectorStoreFactory vectorStoreFactory;
    private final KnowledgeBaseService kbService;
    private final EmbeddingService embeddingService;
    private final GraphRetrievalService graphRetrievalService;
    private final RerankerFactory rerankerFactory;
    private final SettingsService settings;
    private final ChunkDao chunkDao;
    private final DocumentDao documentDao;
    private final Bm25Index bm25;
    private final ModelProviderService providerService;
    private final ModelFactory modelFactory;

    public RetrievalService(VectorStoreFactory vectorStoreFactory, KnowledgeBaseService kbService,
                            EmbeddingService embeddingService, GraphRetrievalService graphRetrievalService,
                            RerankerFactory rerankerFactory, SettingsService settings,
                            ChunkDao chunkDao, DocumentDao documentDao, Bm25Index bm25,
                            ModelProviderService providerService, ModelFactory modelFactory) {
        this.vectorStoreFactory = vectorStoreFactory;
        this.kbService = kbService;
        this.embeddingService = embeddingService;
        this.graphRetrievalService = graphRetrievalService;
        this.rerankerFactory = rerankerFactory;
        this.settings = settings;
        this.chunkDao = chunkDao;
        this.documentDao = documentDao;
        this.bm25 = bm25;
        this.providerService = providerService;
        this.modelFactory = modelFactory;
    }

    public List<RetrievedChunk> retrieve(RetrieveRequest req) {
        return stage(req).reranked();
    }

    /** 分阶段检索（检索测试面板用）：向量 / 关键词 / 融合 / 重排 + 分数分布 */
    public Map<String, Object> debug(RetrieveRequest req) {
        Stages s = stage(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vector", s.vector());
        out.put("keyword", s.keyword());
        out.put("fused", s.fused());
        out.put("reranked", s.reranked());
        out.put("distribution", s.distribution());
        return out;
    }

    private Stages stage(RetrieveRequest req) {
        if (req.query() == null || req.query().isBlank()) {
            throw new BusinessException("查询内容为空");
        }
        if (req.kbIds() == null || req.kbIds().isEmpty()) {
            throw new BusinessException("请至少选择一个知识库");
        }
        int topK = Math.min(50, req.topK() != null && req.topK() > 0 ? req.topK() : settings.ragTopK());
        double minScore = req.minScore() != null ? req.minScore() : settings.ragMinScore();
        String rerankMode = req.rerank() != null && !req.rerank().isBlank() ? req.rerank() : settings.ragRerank();
        boolean graphRag = req.graphRag() == null || req.graphRag();
        boolean hybrid = req.hybrid() != null ? req.hybrid() : settings.ragHybrid();
        String scoreNorm = req.scoreNorm() != null && !req.scoreNorm().isBlank() ? req.scoreNorm() : settings.ragScoreNorm();
        boolean queryRewrite = req.queryRewrite() != null ? req.queryRewrite() : settings.ragQueryRewrite();
        boolean hyde = req.hyde() != null ? req.hyde() : settings.ragHyde();
        int recallTop = Math.min(100, topK * settings.ragRecallMultiplier());
        Long rerankProviderId = req.rerankProviderId() != null ? req.rerankProviderId() : settings.ragRerankProvider();

        String effQuery = req.query();
        if (queryRewrite) {
            effQuery = rewriteQuery(req.query(), req.history());
        }
        String hydeText = null;
        if (hyde) {
            hydeText = hyde(req.query());
        }
        String vectorQuery = hydeText != null ? hydeText : effQuery;

        List<RetrievedChunk> vectorHits = new ArrayList<>();
        List<RetrievedChunk> keywordHits = new ArrayList<>();
        List<Double> rawVectorScores = new ArrayList<>();
        Map<Long, RetrievedChunk> graphHits = new HashMap<>();
        Map<Long, String> docNameCache = new HashMap<>();

        for (Long kbId : req.kbIds()) {
            KnowledgeBase kb = kbService.require(kbId);
            try {
                float[] qv = embeddingService.embedOne(vectorQuery, embeddingService.providerFor(kb));
                VectorStore vs = vectorStoreFactory.get();
                List<VectorStore.ScoredId> hits = vs.search(kbId, qv, recallTop, hybrid ? 0 : minScore);
                for (VectorStore.ScoredId hit : hits) {
                    Chunk c = chunkById(kbId, hit.chunkId());
                    if (c == null) {
                        continue;
                    }
                    rawVectorScores.add((double) hit.score());
                    vectorHits.add(toRetrieved(c, kbId, docName(c, docNameCache), hit.score(), "vector"));
                }
                if (hybrid) {
                    for (Bm25Index.Scored s : bm25.search(kbId, effQuery, recallTop)) {
                        Chunk c = chunkById(kbId, s.chunkId());
                        if (c == null) {
                            continue;
                        }
                        keywordHits.add(toRetrieved(c, kbId, docName(c, docNameCache), s.score(), "keyword"));
                    }
                }
            } catch (BusinessException e) {
                log.warn("知识库 {} 向量检索跳过: {}", kbId, e.getMessage());
            }

            if (graphRag && Boolean.TRUE.equals(kb.getGraphEnabled())) {
                try {
                    List<RetrievedChunk> graphChunks = graphRetrievalService.retrieveChunks(req.query(), kb, settings.graphChunks());
                    for (RetrievedChunk gc : graphChunks) {
                        graphHits.putIfAbsent(gc.chunkId(), gc);
                    }
                } catch (Exception e) {
                    log.warn("图谱召回失败: {}", e.getMessage());
                }
            }
        }

        Map<String, Object> distribution = scoreDistribution(rawVectorScores, scoreNorm);

        if ("minmax".equalsIgnoreCase(scoreNorm)) {
            normalizeScores(vectorHits);
        }
        if (!hybrid) {
            List<RetrievedChunk> out = new ArrayList<>();
            for (RetrievedChunk v : vectorHits) {
                if (v.score() >= minScore) {
                    out.add(v);
                }
            }
            mergeGraph(out, graphHits);
            List<RetrievedChunk> reranked = out.isEmpty() ? List.of()
                    : rerankerFactory.get(rerankMode, rerankProviderId).rerank(effQuery, out, topK);
            return new Stages(vectorHits, List.of(), out, reranked, distribution);
        }

        List<RetrievedChunk> fused = fuse(vectorHits, keywordHits, minScore);
        mergeGraph(fused, graphHits);
        List<RetrievedChunk> reranked = fused.isEmpty() ? List.of()
                : rerankerFactory.get(rerankMode, rerankProviderId).rerank(effQuery, fused, topK);
        return new Stages(vectorHits, keywordHits, fused, reranked, distribution);
    }

    /** 分数分布与阈值建议（供检索测试校准） */
    private Map<String, Object> scoreDistribution(List<Double> scores, String scoreNorm) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (scores.isEmpty()) {
            out.put("count", 0);
            out.put("suggestion", "无向量命中，建议降低阈值或检查知识库是否已入库");
            return out;
        }
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
        List<Double> sorted = new ArrayList<>(scores);
        sorted.sort(Double::compareTo);
        for (double s : sorted) {
            min = Math.min(min, s);
            max = Math.max(max, s);
            sum += s;
        }
        double avg = sum / sorted.size();
        double p50 = sorted.get(sorted.size() / 2);
        out.put("count", sorted.size());
        out.put("min", round4(min));
        out.put("max", round4(max));
        out.put("avg", round4(avg));
        out.put("p50", round4(p50));
        String tip = "minmax".equalsIgnoreCase(scoreNorm)
                ? "已开启 min-max 归一化，建议阈值 0.3–0.5；当前实际区间 " + round4(min) + "–" + round4(max)
                : "未归一化，各 Embedding 模型分布差异大；建议阈值 " + round4(Math.max(0, Math.round(avg * 20) / 20.0))
                + "–" + round4(Math.min(1, Math.round((p50 + (max - p50) * 0.3) * 20) / 20.0)) + " 之间试调";
        out.put("suggestion", tip);
        return out;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private void mergeGraph(List<RetrievedChunk> list, Map<Long, RetrievedChunk> graphHits) {
        if (graphHits.isEmpty()) {
            return;
        }
        Map<Long, Integer> pos = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            pos.put(list.get(i).chunkId(), i);
        }
        for (RetrievedChunk gc : graphHits.values()) {
            Integer i = pos.get(gc.chunkId());
            if (i == null) {
                list.add(gc);
            } else {
                RetrievedChunk prev = list.get(i);
                double score = Math.max(prev.score(), gc.score());
                list.set(i, new RetrievedChunk(prev.chunkId(), prev.kbId(), prev.docId(), prev.docName(),
                        prev.position(), prev.content(), score, "both"));
            }
        }
    }

    private List<RetrievedChunk> fuse(List<RetrievedChunk> vectorHits, List<RetrievedChunk> keywordHits, double minScore) {
        Map<Long, RetrievedChunk> byId = new LinkedHashMap<>();
        Map<Long, Double> score = new HashMap<>();
        Map<Long, String> source = new HashMap<>();
        List<RetrievedChunk> ordered = new ArrayList<>();

        List<RetrievedChunk> vec = new ArrayList<>(vectorHits);
        vec.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        List<RetrievedChunk> kw = new ArrayList<>(keywordHits);
        kw.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());

        int rank = 1;
        for (RetrievedChunk v : vec) {
            if (v.score() < minScore) {
                continue;
            }
            score.merge(v.chunkId(), 1.0 / (RRF_K + rank), Double::sum);
            source.merge(v.chunkId(), "vector", (a, b) -> "both");
            byId.putIfAbsent(v.chunkId(), v);
            rank++;
        }
        rank = 1;
        for (RetrievedChunk k : kw) {
            score.merge(k.chunkId(), 1.0 / (RRF_K + rank), Double::sum);
            source.merge(k.chunkId(), "keyword", (a, b) -> "both");
            byId.putIfAbsent(k.chunkId(), k);
            rank++;
        }
        for (Map.Entry<Long, Double> e : score.entrySet()) {
            RetrievedChunk c = byId.get(e.getKey());
            ordered.add(new RetrievedChunk(c.chunkId(), c.kbId(), c.docId(), c.docName(), c.position(),
                    c.content(), e.getValue(), source.get(e.getKey())));
        }
        ordered.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        return ordered;
    }

    private void normalizeScores(List<RetrievedChunk> hits) {
        if (hits.size() < 2) {
            return;
        }
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (RetrievedChunk h : hits) {
            min = Math.min(min, h.score());
            max = Math.max(max, h.score());
        }
        if (max - min < 1e-9) {
            return;
        }
        for (int i = 0; i < hits.size(); i++) {
            RetrievedChunk h = hits.get(i);
            double norm = (h.score() - min) / (max - min);
            hits.set(i, new RetrievedChunk(h.chunkId(), h.kbId(), h.docId(), h.docName(), h.position(),
                    h.content(), norm, h.source()));
        }
    }

    private String rewriteQuery(String query, List<Map<String, Object>> history) {
        try {
            ChatModel m = modelFactory.chatModel(providerService.defaultChatProvider());
            StringBuilder prompt = new StringBuilder("你是检索查询改写助手。请把用户的提问改写成一个独立、明确、适合全文检索的查询式，只输出改写结果本身，不要解释。\n\n");
            if (history != null && !history.isEmpty()) {
                prompt.append("对话历史（最近几轮）：\n");
                for (Map<String, Object> h : history) {
                    Object role = h.get("role");
                    Object content = h.get("content");
                    if (content != null) {
                        prompt.append("【").append(role).append("】").append(content).append("\n");
                    }
                }
                prompt.append("\n");
            }
            prompt.append("当前问题：").append(query);
            String r = m.chat(ChatRequest.builder().messages(List.of(UserMessage.from(prompt.toString()))).build())
                    .aiMessage().text();
            if (r == null || r.isBlank()) {
                return query;
            }
            String t = r.trim();
            return t.length() > 200 ? t.substring(0, 200) : t;
        } catch (Exception e) {
            log.warn("查询改写失败，使用原问题: {}", e.getMessage());
            return query;
        }
    }

    private String hyde(String query) {
        try {
            ChatModel m = modelFactory.chatModel(providerService.defaultChatProvider());
            String prompt = "请针对下面的问题，写一段假设性的知识库文档片段（用陈述事实的文档口吻，200 字以内），用于辅助向量检索召回。只输出片段本身。\n\n问题：" + query;
            String r = m.chat(ChatRequest.builder().messages(List.of(UserMessage.from(prompt))).build())
                    .aiMessage().text();
            if (r == null || r.isBlank()) {
                return null;
            }
            String t = r.trim();
            return t.length() > 400 ? t.substring(0, 400) : t;
        } catch (Exception e) {
            log.warn("HyDE 生成失败，使用原问题向量: {}", e.getMessage());
            return null;
        }
    }

    private RetrievedChunk toRetrieved(Chunk c, long kbId, String docName, double score, String source) {
        String content = c.getContent();
        try {
            if (c.getMeta() != null && !c.getMeta().isBlank()) {
                Map<String, Object> meta = JsonUtil.fromJson(c.getMeta(), Map.class);
                Object parent = meta.get("parent");
                if (parent != null) {
                    content = String.valueOf(parent);
                }
            }
        } catch (Exception ignored) {
        }
        return new RetrievedChunk(c.getId(), kbId, c.getDocId(), docName,
                c.getPosition() == null ? 0 : c.getPosition(), content, score, source);
    }

    private Chunk chunkById(long kbId, long chunkId) {
        List<Chunk> list = chunkDao.findByIds(List.of(chunkId));
        return list.isEmpty() ? null : list.get(0);
    }

    private String docName(Chunk c, Map<Long, String> cache) {
        try {
            if (c.getMeta() != null && !c.getMeta().isBlank() && !"{}".equals(c.getMeta())) {
                Map<String, Object> meta = JsonUtil.fromJson(c.getMeta(), Map.class);
                Object name = meta.get("fileName");
                if (name != null) {
                    return String.valueOf(name);
                }
            }
        } catch (Exception ignored) {
        }
        return cache.computeIfAbsent(c.getDocId(), did -> {
            Document d = documentDao.findById(did);
            return d == null ? "文档" + did : d.getFileName();
        });
    }

    /** 检索分阶段结果 */
    public record Stages(List<RetrievedChunk> vector, List<RetrievedChunk> keyword, List<RetrievedChunk> fused,
                         List<RetrievedChunk> reranked, Map<String, Object> distribution) {
    }
}
