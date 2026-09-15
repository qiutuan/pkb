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
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        // 查询优化：改写 / HyDE（各自默认关闭；开启时各增加一次 LLM 调用）
        String effQuery = req.query();
        if (queryRewrite) {
            effQuery = rewriteQuery(req.query(), req.history());
        }
        String hydeText = null;
        if (hyde) {
            hydeText = hyde(req.query());
        }
        // 向量侧用 HyDE 文本（若有），关键词/重排侧用改写后的查询
        String vectorQuery = hydeText != null ? hydeText : effQuery;

        List<RetrievedChunk> vectorHits = new ArrayList<>();
        List<RetrievedChunk> keywordHits = new ArrayList<>();
        Map<Long, RetrievedChunk> graphHits = new HashMap<>();
        Map<Long, String> docNameCache = new HashMap<>();

        for (Long kbId : req.kbIds()) {
            KnowledgeBase kb = kbService.require(kbId);
            try {
                // —— 向量召回 ——
                float[] qv = embeddingService.embedOne(vectorQuery, embeddingService.providerFor(kb));
                VectorStore vs = vectorStoreFactory.get();
                List<VectorStore.ScoredId> hits = vs.search(kbId, qv, recallTop, hybrid ? 0 : minScore);
                for (VectorStore.ScoredId hit : hits) {
                    Chunk c = chunkById(kbId, hit.chunkId());
                    if (c == null) {
                        continue;
                    }
                    vectorHits.add(toRetrieved(c, kbId, docName(c, docNameCache), hit.score(), "vector"));
                }
                // —— BM25 全文召回（混合检索开启时） ——
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

            // —— 图谱召回（保留原有逻辑） ——
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

        // 分数归一化（min-max）仅在开启时应用
        if ("minmax".equalsIgnoreCase(scoreNorm)) {
            normalizeScores(vectorHits);
        }
        if (!hybrid) {
            // 纯向量模式：按向量分过滤阈值（与原行为一致），合并图谱召回
            List<RetrievedChunk> out = new ArrayList<>();
            for (RetrievedChunk v : vectorHits) {
                if (v.score() >= minScore) {
                    out.add(v);
                }
            }
            mergeGraph(out, graphHits);
            if (out.isEmpty()) {
                return List.of();
            }
            return rerankerFactory.get(rerankMode, rerankProviderId).rerank(effQuery, out, topK);
        }

        // 混合模式：RRF 融合（向量 + 关键词），阈值仍按向量分过滤
        List<RetrievedChunk> fused = fuse(vectorHits, keywordHits, minScore);
        mergeGraph(fused, graphHits);
        if (fused.isEmpty()) {
            return List.of();
        }
        return rerankerFactory.get(rerankMode, rerankProviderId).rerank(effQuery, fused, topK);
    }

    /** 图谱命中合并：已存在则标记 both 并取高分，否则加入 */
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

    /** RRF 融合（k=60）：向量与关键词各自按排名给分后求和 */
    private List<RetrievedChunk> fuse(List<RetrievedChunk> vectorHits, List<RetrievedChunk> keywordHits, double minScore) {
        Map<Long, RetrievedChunk> byId = new LinkedHashMap<>();
        Map<Long, Double> score = new HashMap<>();
        Map<Long, String> source = new HashMap<>();
        List<RetrievedChunk> ordered = new ArrayList<>();

        // 先按向量分降序排（保证阈值过滤语义稳定）
        List<RetrievedChunk> vec = new ArrayList<>(vectorHits);
        vec.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        List<RetrievedChunk> kw = new ArrayList<>(keywordHits);
        kw.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());

        int rank = 1;
        for (RetrievedChunk v : vec) {
            if (v.score() < minScore) {
                continue; // 阈值过滤基于向量分（与纯向量模式语义一致）
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

    /** min-max 归一化向量分到 [0,1]；全等分时置 0.5 */
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

    /** 查询改写：结合对话历史将口语化/指代问题改写为独立检索式（一次 LLM 调用） */
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

    /** HyDE：生成假设答案片段辅助向量召回（一次 LLM 调用） */
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

    /** 构造检索结果：父子分块时展开为父块完整内容（meta.parent） */
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
}
