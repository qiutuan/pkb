package com.pkb.graph;

import com.pkb.common.BusinessException;
import com.pkb.dao.GraphDao;
import com.pkb.knowledge.EmbeddingService;
import com.pkb.knowledge.KnowledgeBase;
import com.pkb.model.ModelFactory;
import com.pkb.model.ModelProvider;
import com.pkb.model.ModelProviderService;
import com.pkb.settings.SettingsService;
import com.pkb.util.JsonUtil;
import com.pkb.util.TextUtil;
import com.pkb.util.VecCodec;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱抽取：LLM 从片段中抽取实体与关系，去重合并、建立溯源、生成实体向量。
 */
@Slf4j
@Service
public class GraphExtractionService {

    private final GraphDao graphDao;
    private final ModelProviderService providerService;
    private final ModelFactory factory;
    private final SettingsService settings;
    private final EmbeddingService embeddingService;

    public GraphExtractionService(GraphDao graphDao, ModelProviderService providerService, ModelFactory factory,
                                  SettingsService settings, EmbeddingService embeddingService) {
        this.graphDao = graphDao;
        this.providerService = providerService;
        this.factory = factory;
        this.settings = settings;
        this.embeddingService = embeddingService;
    }

    /** 对一批片段抽取（自动入库时按文档调用） */
    public void extractForChunks(KnowledgeBase kb, List<Long> chunkIds, List<String> contents) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        int batch = settings.graphExtractBatch();
        for (int i = 0; i < chunkIds.size(); i += batch) {
            int end = Math.min(i + batch, chunkIds.size());
            List<Long> ids = chunkIds.subList(i, end);
            List<String> texts = contents.subList(i, end);
            try {
                processBatch(kb, ids, texts);
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                log.warn("图谱抽取批次失败: {}", e.getMessage());
            }
        }
    }

    private void processBatch(KnowledgeBase kb, List<Long> chunkIds, List<String> texts) {
        ModelProvider chat = extractionProvider();
        ChatModel model = factory.chatModel(chat);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            sb.append("【片段 ").append(i + 1).append("】\n").append(texts.get(i)).append('\n');
        }
        String resp = model.chat(ChatRequest.builder().messages(List.of(
                        SystemMessage.from(settings.extractPrompt()),
                        UserMessage.from("请从以下文本中抽取实体与关系，只输出 JSON。\n\n" + sb
                                + "\n输出格式：{\"entities\":[{\"name\":\"实体名\",\"type\":\"类型\",\"description\":\"描述\"}],"
                                + "\"relations\":[{\"source\":\"源实体名\",\"target\":\"目标实体名\",\"type\":\"关系\",\"description\":\"描述\"}]}")))
                .build()).aiMessage().text();

        String json = JsonUtil.extractFirstJsonObject(resp);
        if (json == null) {
            log.warn("图谱抽取输出无 JSON，跳过该批");
            return;
        }
        ExtractionResult result = JsonUtil.fromJson(json, ExtractionResult.class);
        if (result == null || result.getEntities() == null) {
            return;
        }

        // 实体去重合并 + 溯源
        Map<String, GraphEntity> entityByName = new LinkedHashMap<>();
        double mergeThreshold = settings.entityMergeThreshold();
        for (ExtractionResult.EntityDto e : result.getEntities()) {
            String name = e.getName() == null ? "" : e.getName().trim();
            if (name.isBlank()) {
                continue;
            }
            GraphEntity entity = findOrCreate(kb, name, e.getType(), e.getDescription(), mergeThreshold);
            if (entity == null) {
                continue;
            }
            entityByName.putIfAbsent(TextUtil.normalizeName(name), entity);
            for (long cid : chunkIds) {
                graphDao.linkEntityChunk(entity.getId(), cid);
            }
        }

        // 关系
        if (result.getRelations() != null) {
            for (ExtractionResult.RelationDto r : result.getRelations()) {
                String src = r.getSource() == null ? "" : r.getSource().trim();
                String tgt = r.getTarget() == null ? "" : r.getTarget().trim();
                if (src.isBlank() || tgt.isBlank() || src.equals(tgt)) {
                    continue;
                }
                GraphEntity s = resolveEntity(kb, src, entityByName);
                GraphEntity t = resolveEntity(kb, tgt, entityByName);
                if (s == null || t == null) {
                    continue;
                }
                String type = r.getType() == null ? "" : r.getType().trim();
                if (graphDao.findRelation(kb.getId(), s.getId(), t.getId(), type) == null) {
                    GraphRelation rel = new GraphRelation();
                    rel.setKbId(kb.getId());
                    rel.setSourceId(s.getId());
                    rel.setTargetId(t.getId());
                    rel.setRelationType(type);
                    rel.setDescription(r.getDescription() == null ? "" : r.getDescription().trim());
                    graphDao.insertRelation(rel);
                }
            }
        }
    }

    private GraphEntity resolveEntity(KnowledgeBase kb, String name, Map<String, GraphEntity> batchEntities) {
        GraphEntity e = batchEntities.get(TextUtil.normalizeName(name));
        if (e != null) {
            return e;
        }
        GraphEntity existing = graphDao.findEntityByName(kb.getId(), name);
        if (existing != null) {
            return existing;
        }
        // 关系指向了批内未出现的实体：补建实体（无溯源）
        GraphEntity ne = new GraphEntity();
        ne.setKbId(kb.getId());
        ne.setName(name);
        ne.setEntityType("未分类");
        ne.setDescription("");
        long id = graphDao.insertEntity(ne);
        ne.setId(id);
        embedEntity(kb, ne);
        return ne;
    }

    private GraphEntity findOrCreate(KnowledgeBase kb, String name, String type, String description, double threshold) {
        GraphEntity existing = graphDao.findEntityByName(kb.getId(), name);
        if (existing != null) {
            String desc = description == null ? "" : description.trim();
            if (desc.length() > existing.getDescription().length()) {
                existing.setDescription(desc);
                graphDao.updateEntity(existing);
            }
            return existing;
        }
        // 模糊合并：相似度超过阈值则并入已有实体；处于模糊区间时交由「图谱构建 Prompt」的 LLM 决策
        String norm = TextUtil.normalizeName(name);
        for (GraphEntity cand : graphDao.searchEntitiesByName(kb.getId(), name.substring(0, Math.min(2, name.length())))) {
            double sim = TextUtil.levenshteinSimilarity(norm, TextUtil.normalizeName(cand.getName()));
            if (sim >= threshold) {
                return cand;
            }
            if (sim >= threshold - 0.15) {
                Boolean same = askMergeDecision(kb, name, cand);
                if (Boolean.TRUE.equals(same)) {
                    return cand;
                }
            }
        }
        GraphEntity ne = new GraphEntity();
        ne.setKbId(kb.getId());
        ne.setName(name);
        ne.setEntityType(type == null || type.isBlank() ? "概念" : type.trim());
        ne.setDescription(description == null ? "" : description.trim());
        long id = graphDao.insertEntity(ne);
        ne.setId(id);
        embedEntity(kb, ne);
        return ne;
    }

    private void embedEntity(KnowledgeBase kb, GraphEntity e) {
        try {
            ModelProvider provider = embeddingService.providerFor(kb);
            String text = e.getName() + "，" + e.getEntityType() + "，" + e.getDescription();
            float[] vec = embeddingService.embedOne(text, provider);
            graphDao.saveEntityEmbedding(e.getId(), VecCodec.toBytes(vec));
        } catch (Exception ex) {
            log.debug("实体向量化跳过: {} ({})", e.getName(), ex.getMessage());
        }
    }

    /** 抽取专用模型：设置页指定（需启用且有聊天模型）→ 否则默认聊天模型 */
    private ModelProvider extractionProvider() {
        long pid = settings.graphExtractProvider();
        if (pid > 0) {
            return providerService.requireEnabled(pid);
        }
        return providerService.defaultChatProvider();
    }

    /** 使用「图谱构建 Prompt」让 LLM 判断两个实体是否合并；任何失败返回 null（不合并，不阻塞流程） */
    private Boolean askMergeDecision(KnowledgeBase kb, String name, GraphEntity cand) {
        try {
            ChatModel model = factory.chatModel(extractionProvider());
            String user = "实体 A：" + name + "\n实体 B：" + cand.getName()
                    + "\n\n请判断 A 与 B 是否为同一事物，只输出 JSON。";
            String resp = model.chat(ChatRequest.builder().messages(List.of(
                            SystemMessage.from(settings.graphBuildPrompt()),
                            UserMessage.from(user)))
                    .build()).aiMessage().text();
            String json = JsonUtil.extractFirstJsonObject(resp);
            if (json == null) {
                return null;
            }
            Map<String, Object> m = JsonUtil.fromJson(json, Map.class);
            Object v = m == null ? null : m.get("merge");
            if (v == null) {
                return null;
            }
            return "true".equalsIgnoreCase(String.valueOf(v).trim());
        } catch (Exception e) {
            log.debug("实体合并决策跳过: {} ≈ {} ({})", name, cand.getName(), e.getMessage());
            return null;
        }
    }

    @Data
    public static class ExtractionResult {
        private List<EntityDto> entities;
        private List<RelationDto> relations;

        @Data
        public static class EntityDto {
            private String name;
            private String type;
            private String description;
        }

        @Data
        public static class RelationDto {
            private String source;
            private String target;
            private String type;
            private String description;
        }
    }
}
