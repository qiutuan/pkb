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
import com.pkb.settings.SettingsService;
import com.pkb.util.JsonUtil;
import com.pkb.vector.VectorStore;
import com.pkb.vector.VectorStoreFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索：多知识库向量检索 + 可选 GraphRAG 图谱召回 + 重排。
 */
@Slf4j
@Service
public class RetrievalService {

    private final VectorStoreFactory vectorStoreFactory;
    private final KnowledgeBaseService kbService;
    private final EmbeddingService embeddingService;
    private final GraphRetrievalService graphRetrievalService;
    private final RerankerFactory rerankerFactory;
    private final SettingsService settings;
    private final ChunkDao chunkDao;
    private final DocumentDao documentDao;

    public RetrievalService(VectorStoreFactory vectorStoreFactory, KnowledgeBaseService kbService,
                            EmbeddingService embeddingService, GraphRetrievalService graphRetrievalService,
                            RerankerFactory rerankerFactory, SettingsService settings,
                            ChunkDao chunkDao, DocumentDao documentDao) {
        this.vectorStoreFactory = vectorStoreFactory;
        this.kbService = kbService;
        this.embeddingService = embeddingService;
        this.graphRetrievalService = graphRetrievalService;
        this.rerankerFactory = rerankerFactory;
        this.settings = settings;
        this.chunkDao = chunkDao;
        this.documentDao = documentDao;
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

        List<RetrievedChunk> candidates = new ArrayList<>();
        Map<Long, RetrievedChunk> byId = new HashMap<>();
        Map<Long, String> docNameCache = new HashMap<>();

        for (Long kbId : req.kbIds()) {
            KnowledgeBase kb = kbService.require(kbId);
            try {
                float[] qv = embeddingService.embedOne(req.query(), embeddingService.providerFor(kb));
                VectorStore vs = vectorStoreFactory.get();
                List<VectorStore.ScoredId> hits = vs.search(kbId, qv, topK * 3, minScore);
                List<Long> ids = hits.stream().map(VectorStore.ScoredId::chunkId).toList();
                Map<Long, Chunk> chunkMap = new HashMap<>();
                for (Chunk c : chunkDao.findByIds(ids)) {
                    chunkMap.put(c.getId(), c);
                }
                for (VectorStore.ScoredId hit : hits) {
                    Chunk c = chunkMap.get(hit.chunkId());
                    if (c == null) {
                        continue;
                    }
                    String docName = docName(c, docNameCache);
                    RetrievedChunk rc = new RetrievedChunk(c.getId(), kbId, c.getDocId(), docName,
                            c.getPosition() == null ? 0 : c.getPosition(), c.getContent(),
                            hit.score(), "vector");
                    byId.putIfAbsent(c.getId(), rc);
                }
            } catch (BusinessException e) {
                log.warn("知识库 {} 检索跳过: {}", kbId, e.getMessage());
            }

            // GraphRAG 图谱召回
            if (graphRag && Boolean.TRUE.equals(kb.getGraphEnabled())) {
                try {
                    List<RetrievedChunk> graphChunks = graphRetrievalService.retrieveChunks(req.query(), kb, settings.graphChunks());
                    for (RetrievedChunk gc : graphChunks) {
                        RetrievedChunk prev = byId.get(gc.chunkId());
                        if (prev == null) {
                            byId.put(gc.chunkId(), gc);
                        } else {
                            // 双路命中：标记 both 并取更高分
                            double score = Math.max(prev.score(), gc.score());
                            byId.put(gc.chunkId(), new RetrievedChunk(prev.chunkId(), prev.kbId(), prev.docId(),
                                    prev.docName(), prev.position(), prev.content(), score, "both"));
                        }
                    }
                } catch (Exception e) {
                    log.warn("图谱召回失败: {}", e.getMessage());
                }
            }
        }
        candidates.addAll(byId.values());
        if (candidates.isEmpty()) {
            return List.of();
        }
        return rerankerFactory.get(rerankMode).rerank(req.query(), candidates, topK);
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
