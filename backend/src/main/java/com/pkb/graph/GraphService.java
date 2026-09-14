package com.pkb.graph;

import com.pkb.common.BusinessException;
import com.pkb.dao.ChunkDao;
import com.pkb.dao.GraphDao;
import com.pkb.knowledge.Chunk;
import com.pkb.knowledge.KnowledgeBase;
import com.pkb.knowledge.KnowledgeBaseService;
import com.pkb.settings.SettingsService;
import com.pkb.util.JsonUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 图谱服务：抽取任务管理、可视化数据、统计。
 */
@Slf4j
@Service
public class GraphService {

    private final GraphDao graphDao;
    private final ChunkDao chunkDao;
    private final GraphExtractionService extractionService;
    private final GraphRetrievalService retrievalService;
    private final KnowledgeBaseService kbService;
    private final SettingsService settings;
    private final ThreadPoolTaskExecutor executor;

    private final ConcurrentHashMap<Long, ExtractJob> jobs = new ConcurrentHashMap<>();

    public GraphService(GraphDao graphDao, ChunkDao chunkDao, GraphExtractionService extractionService,
                        GraphRetrievalService retrievalService, KnowledgeBaseService kbService,
                        SettingsService settings, @Qualifier("graphExecutor") ThreadPoolTaskExecutor executor) {
        this.graphDao = graphDao;
        this.chunkDao = chunkDao;
        this.extractionService = extractionService;
        this.retrievalService = retrievalService;
        this.kbService = kbService;
        this.settings = settings;
        this.executor = executor;
    }

    public Map<String, Object> stats(long kbId) {
        return Map.of(
                "kbId", kbId,
                "entities", graphDao.countEntities(kbId),
                "relations", graphDao.countRelations(kbId),
                "chunks", chunkDao.countByKb(kbId));
    }

    public void startExtract(long kbId) {
        kbService.require(kbId);
        ExtractJob job = jobs.get(kbId);
        if (job != null && job.isRunning()) {
            throw new BusinessException("抽取任务进行中，请稍候");
        }
        ExtractJob newJob = new ExtractJob();
        jobs.put(kbId, newJob);
        executor.execute(() -> runExtract(kbId, newJob));
    }

    private void runExtract(long kbId, ExtractJob job) {
        try {
            KnowledgeBase kb = kbService.require(kbId);
            List<Chunk> all = chunkDao.findAllByKb(kbId);
            job.setTotal(all.size());
            job.setMessage("开始抽取");
            int batch = settings.graphExtractBatch();
            graphDao.deleteByKb(kbId);
            for (int i = 0; i < all.size(); i += batch) {
                int end = Math.min(i + batch, all.size());
                List<Long> ids = new ArrayList<>();
                List<String> texts = new ArrayList<>();
                for (int j = i; j < end; j++) {
                    ids.add(all.get(j).getId());
                    texts.add(all.get(j).getContent());
                }
                try {
                    extractionService.extractForChunks(kb, ids, texts);
                } catch (Exception e) {
                    log.warn("图谱批量抽取失败: {}", e.getMessage());
                }
                job.getProcessed().set(end);
                job.setMessage("已抽取 " + end + "/" + all.size());
            }
            job.setMessage("抽取完成");
        } catch (Exception e) {
            job.setMessage("抽取失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            job.setRunning(false);
        }
    }

    public Map<String, Object> extractStatus(long kbId) {
        ExtractJob job = jobs.get(kbId);
        if (job == null) {
            return Map.of("running", false, "total", 0, "processed", 0, "message", "尚未执行过抽取");
        }
        return Map.of("running", job.isRunning(), "total", job.getTotal(),
                "processed", job.getProcessed().get(), "message", job.getMessage());
    }

    public void clear(long kbId) {
        kbService.require(kbId);
        graphDao.deleteByKb(kbId);
    }

    /**
     * 图谱可视化数据：支持 query 聚焦（命中实体 + 一跳邻居），否则返回全图（按度数截断）。
     */
    public Map<String, Object> graphData(long kbId, String query) {
        kbService.require(kbId);
        List<GraphEntity> entities = graphDao.findAllEntities(kbId);
        List<GraphRelation> relations = graphDao.findAllRelations(kbId);

        Map<Long, Integer> degree = new HashMap<>();
        for (GraphRelation r : relations) {
            degree.merge(r.getSourceId(), 1, Integer::sum);
            degree.merge(r.getTargetId(), 1, Integer::sum);
        }

        Set<Long> focus = new HashSet<>();
        if (query != null && !query.isBlank()) {
            KnowledgeBase kb = kbService.require(kbId);
            try {
                for (GraphRetrievalService.EntityHit h : retrievalService.matchEntities(query, kb, 8)) {
                    focus.add(h.entity().getId());
                }
            } catch (Exception e) {
                log.debug("实体匹配失败: {}", e.getMessage());
            }
        }

        Set<Long> keep = new LinkedHashSet<>();
        if (!focus.isEmpty()) {
            keep.addAll(focus);
            for (GraphRelation r : relations) {
                if (focus.contains(r.getSourceId())) {
                    keep.add(r.getTargetId());
                }
                if (focus.contains(r.getTargetId())) {
                    keep.add(r.getSourceId());
                }
            }
        } else {
            List<GraphEntity> sorted = new ArrayList<>(entities);
            sorted.sort(Comparator.comparingInt(e -> -degree.getOrDefault(e.getId(), 0)));
            for (GraphEntity e : sorted) {
                keep.add(e.getId());
                if (keep.size() >= 400) {
                    break;
                }
            }
        }

        Map<Long, GraphEntity> byId = new HashMap<>();
        for (GraphEntity e : entities) {
            byId.put(e.getId(), e);
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (long id : keep) {
            GraphEntity e = byId.get(id);
            if (e == null) {
                continue;
            }
            Map<String, Object> node = new HashMap<>();
            node.put("id", id);
            node.put("name", e.getName());
            node.put("type", e.getEntityType() == null ? "" : e.getEntityType());
            node.put("description", e.getDescription() == null ? "" : e.getDescription());
            node.put("degree", degree.getOrDefault(id, 0));
            node.put("matched", focus.contains(id));
            nodes.add(node);
        }

        List<Map<String, Object>> links = new ArrayList<>();
        for (GraphRelation r : relations) {
            if (keep.contains(r.getSourceId()) && keep.contains(r.getTargetId())) {
                Map<String, Object> link = new HashMap<>();
                link.put("source", r.getSourceId());
                link.put("target", r.getTargetId());
                link.put("type", r.getRelationType());
                link.put("description", r.getDescription());
                links.add(link);
            }
        }
        return Map.of("nodes", nodes, "links", links, "matched", new ArrayList<>(focus));
    }

    /**
     * 多知识库合并图谱数据：节点以 "kbId:entityId" 复合 id 去重合并，保留单库接口不变。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> multiGraphData(List<Long> kbIds, String query) {
        Map<String, Map<String, Object>> nodeById = new LinkedHashMap<>();
        Map<String, Map<String, Object>> linkKey = new LinkedHashMap<>();
        Set<String> matched = new LinkedHashSet<>();
        // 第一遍：聚合节点（复合 id）
        for (long kbId : kbIds) {
            Map<String, Object> data = graphData(kbId, query);
            for (Map<String, Object> nd : (List<Map<String, Object>>) data.getOrDefault("nodes", List.of())) {
                long eid = ((Number) nd.get("id")).longValue();
                String key = kbId + ":" + eid;
                Map<String, Object> merged = nodeById.get(key);
                if (merged == null) {
                    nd.put("id", key);
                    nd.put("_kid", kbId);
                    nd.put("_eid", eid);
                    nodeById.put(key, nd);
                } else {
                    merged.put("degree", Math.max(((Number) merged.getOrDefault("degree", 0)).intValue(),
                            ((Number) nd.getOrDefault("degree", 0)).intValue()));
                }
            }
            for (Object m : (List<Object>) data.getOrDefault("matched", List.of())) {
                matched.add(String.valueOf(m));
            }
        }
        // 第二遍：聚合边（仅保留两端节点均展示的边，按复合 id 对去重）
        for (long kbId : kbIds) {
            Map<String, Object> data = graphData(kbId, query);
            for (Map<String, Object> lk : (List<Map<String, Object>>) data.getOrDefault("links", List.of())) {
                String sk = kbId + ":" + ((Number) lk.get("source")).longValue();
                String tk = kbId + ":" + ((Number) lk.get("target")).longValue();
                if (!nodeById.containsKey(sk) || !nodeById.containsKey(tk)) {
                    continue;
                }
                String lkey = sk.compareTo(tk) < 0 ? sk + "→" + tk : tk + "→" + sk;
                if (!linkKey.containsKey(lkey)) {
                    Map<String, Object> link = new HashMap<>();
                    link.put("source", sk);
                    link.put("target", tk);
                    link.put("type", lk.get("type"));
                    link.put("description", lk.get("description"));
                    linkKey.put(lkey, link);
                }
            }
        }
        return Map.of("nodes", new ArrayList<>(nodeById.values()),
                "links", new ArrayList<>(linkKey.values()),
                "matched", new ArrayList<>(matched));
    }

    /** 多知识库合并统计 */
    public Map<String, Object> multiStats(List<Long> kbIds) {
        long entities = 0, relations = 0, chunks = 0;
        for (long kbId : kbIds) {
            entities += graphDao.countEntities(kbId);
            relations += graphDao.countRelations(kbId);
            chunks += chunkDao.countByKb(kbId);
        }
        return Map.of("kbIds", kbIds, "entities", entities, "relations", relations, "chunks", chunks);
    }

    /** 实体溯源片段 */
    public List<Map<String, Object>> entityChunks(long entityId) {
        GraphEntity e = graphDao.findEntityById(entityId);
        if (e == null) {
            throw new BusinessException("实体不存在");
        }
        List<Chunk> chunks = chunkDao.findByIds(graphDao.chunkIdsOfEntity(entityId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Chunk c : chunks) {
            String docName = c.getMeta() == null ? "" : c.getMeta();
            try {
                if (c.getMeta() != null && c.getMeta().contains("fileName")) {
                    Map<String, Object> meta = JsonUtil.fromJson(c.getMeta(), Map.class);
                    docName = String.valueOf(meta.getOrDefault("fileName", ""));
                }
            } catch (Exception ignored) {
            }
            out.add(Map.of("chunkId", c.getId(), "docName", docName, "position", c.getPosition(), "content", c.getContent()));
        }
        return out;
    }

    @Data
    public static class ExtractJob {
        private volatile boolean running = true;
        private volatile int total = 0;
        private final AtomicInteger processed = new AtomicInteger(0);
        private volatile String message = "";
    }
}
