package com.pkb.graph;

import com.pkb.dao.ChunkDao;
import com.pkb.dao.GraphDao;
import com.pkb.knowledge.Chunk;
import com.pkb.knowledge.EmbeddingService;
import com.pkb.knowledge.KnowledgeBase;
import com.pkb.rag.RetrievedChunk;
import com.pkb.settings.SettingsService;
import com.pkb.util.JsonUtil;
import com.pkb.util.TextUtil;
import com.pkb.util.VecCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * GraphRAG 图谱召回：查询实体匹配 → 邻居扩展 → 溯源片段召回。
 */
@Slf4j
@Service
public class GraphRetrievalService {

    private final GraphDao graphDao;
    private final ChunkDao chunkDao;
    private final EmbeddingService embeddingService;
    private final SettingsService settings;

    public GraphRetrievalService(GraphDao graphDao, ChunkDao chunkDao, EmbeddingService embeddingService,
                                 SettingsService settings) {
        this.graphDao = graphDao;
        this.chunkDao = chunkDao;
        this.embeddingService = embeddingService;
        this.settings = settings;
    }

    /**
     * 图谱召回：返回与查询实体相关的溯源片段。
     */
    public List<RetrievedChunk> retrieveChunks(String query, KnowledgeBase kb, int limit) {
        if (graphDao.countEntities(kb.getId()) == 0) {
            return List.of();
        }
        List<EntityHit> matched = matchEntities(query, kb, settings.graphEntities());
        if (matched.isEmpty()) {
            return List.of();
        }

        // BFS 邻居扩展
        Set<Long> visited = new LinkedHashSet<>();
        for (EntityHit h : matched) {
            visited.add(h.entity().getId());
        }
        int hop = Math.max(1, settings.graphHop());
        Queue<Long> frontier = new LinkedList<>(visited);
        for (int h = 0; h < hop && !frontier.isEmpty(); h++) {
            int size = frontier.size();
            for (int i = 0; i < size; i++) {
                long eid = frontier.poll();
                for (GraphRelation r : graphDao.relationsOfEntity(kb.getId(), eid)) {
                    long other = r.getSourceId() == eid ? r.getTargetId() : r.getSourceId();
                    if (visited.add(other)) {
                        frontier.add(other);
                    }
                }
            }
        }

        // 溯源片段召回
        int chunkCap = Math.max(1, settings.graphChunks());
        Map<Long, Double> chunkScore = new HashMap<>();
        for (EntityHit h : matched) {
            for (long cid : graphDao.chunkIdsOfEntity(h.entity().getId())) {
                chunkScore.merge(cid, h.score(), Math::max);
            }
        }
        for (long eid : visited) {
            if (chunkScore.size() >= chunkCap * 3) {
                break;
            }
            for (long cid : graphDao.chunkIdsOfEntity(eid)) {
                chunkScore.putIfAbsent(cid, 0.5);
            }
        }

        List<Long> ids = new ArrayList<>(chunkScore.keySet());
        if (ids.size() > chunkCap) {
            ids = ids.subList(0, chunkCap);
        }
        List<Chunk> chunks = chunkDao.findByIds(ids);
        List<RetrievedChunk> out = new ArrayList<>();
        for (Chunk c : chunks) {
            double score = chunkScore.getOrDefault(c.getId(), 0.5);
            String docName = docNameOf(c);
            out.add(new RetrievedChunk(c.getId(), c.getKbId(), c.getDocId(), docName,
                    c.getPosition() == null ? 0 : c.getPosition(), c.getContent(), score, "graph"));
        }
        out.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    private String docNameOf(Chunk c) {
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
        return "文档" + c.getDocId();
    }

    /** 实体匹配：查询二元组 LIKE 召回 + 向量/文本重叠打分 */
    public List<EntityHit> matchEntities(String query, KnowledgeBase kb, int topN) {
        Set<String> grams = new LinkedHashSet<>();
        for (String t : TextUtil.tokenize(query)) {
            if (t.length() >= 2) {
                grams.add(t.startsWith("c") ? t.substring(1) : t);
            }
        }
        if (query != null) {
            String plain = query.replaceAll("[\\s\\p{Punct}]+", "");
            for (int i = 0; i + 1 < plain.length(); i++) {
                grams.add(plain.substring(i, i + 2));
            }
        }
        if (grams.isEmpty()) {
            return List.of();
        }

        Set<Long> seen = new LinkedHashSet<>();
        List<GraphEntity> candidates = new ArrayList<>();
        for (String g : grams) {
            for (GraphEntity e : graphDao.searchEntitiesByName(kb.getId(), g)) {
                if (seen.add(e.getId())) {
                    candidates.add(e);
                }
                if (candidates.size() >= 100) {
                    break;
                }
            }
            if (candidates.size() >= 100) {
                break;
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        float[] qv = null;
        try {
            qv = embeddingService.embedOne(query == null ? "" : query, embeddingService.providerFor(kb));
        } catch (Exception e) {
            log.debug("查询向量化失败，仅用文本重叠匹配: {}", e.getMessage());
        }

        List<EntityHit> hits = new ArrayList<>();
        for (GraphEntity e : candidates) {
            String name = e.getName() == null ? "" : e.getName();
            double overlap = 0;
            for (String g : grams) {
                if (name.contains(g)) {
                    overlap++;
                }
            }
            double overlapRatio = grams.isEmpty() ? 0 : overlap / grams.size();
            double nameInQuery = (query != null && query.contains(name)) ? 1 : 0;
            double embedScore = 0;
            if (qv != null) {
                byte[] eb = graphDao.loadEntityEmbedding(e.getId());
                if (eb != null) {
                    embedScore = TextUtil.cosine(qv, VecCodec.fromBytes(eb));
                }
            }
            double score = 0.4 * Math.max(0, embedScore) + 0.4 * overlapRatio + 0.2 * nameInQuery;
            hits.add(new EntityHit(e, score));
        }
        hits.sort(Comparator.comparingDouble(EntityHit::score).reversed());
        return hits.size() > topN ? new ArrayList<>(hits.subList(0, topN)) : hits;
    }

    public record EntityHit(GraphEntity entity, double score) {
    }
}
