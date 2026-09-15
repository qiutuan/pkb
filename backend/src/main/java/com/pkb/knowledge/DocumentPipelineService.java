package com.pkb.knowledge;

import com.pkb.common.BusinessException;
import com.pkb.dao.ChunkDao;
import com.pkb.dao.DocumentDao;
import com.pkb.dao.GraphDao;
import com.pkb.dao.KnowledgeBaseDao;
import com.pkb.graph.GraphExtractionService;
import com.pkb.model.ModelFactory;
import com.pkb.model.ModelProvider;
import com.pkb.model.ModelProviderService;
import com.pkb.rag.Bm25Index;
import com.pkb.settings.SettingsService;
import com.pkb.vector.VectorStore;
import com.pkb.vector.VectorStoreFactory;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 文档入库流水线：解析 → 分块 → 向量化 → 入库（→ 图谱抽取）。
 * 全程可追踪：状态/进度/失败原因落库；失败可重试、可重新索引、不丢数据。
 */
@Slf4j
@Service
public class DocumentPipelineService {

    private final DocumentDao documentDao;
    private final KnowledgeBaseDao kbDao;
    private final ChunkDao chunkDao;
    private final GraphDao graphDao;
    private final EmbeddingService embeddingService;
    private final VectorStoreFactory vectorStoreFactory;
    private final ModelProviderService providerService;
    private final ModelFactory modelFactory;
    private final SettingsService settingsService;
    private final GraphExtractionService graphExtractionService;
    private final Bm25Index bm25;
    private final ThreadPoolTaskExecutor executor;

    public DocumentPipelineService(DocumentDao documentDao, KnowledgeBaseDao kbDao, ChunkDao chunkDao,
                                   GraphDao graphDao, EmbeddingService embeddingService,
                                   VectorStoreFactory vectorStoreFactory, ModelProviderService providerService,
                                   ModelFactory modelFactory, SettingsService settingsService,
                                   GraphExtractionService graphExtractionService, Bm25Index bm25,
                                   @Qualifier("pipelineExecutor") ThreadPoolTaskExecutor executor) {
        this.documentDao = documentDao;
        this.kbDao = kbDao;
        this.chunkDao = chunkDao;
        this.graphDao = graphDao;
        this.embeddingService = embeddingService;
        this.vectorStoreFactory = vectorStoreFactory;
        this.providerService = providerService;
        this.modelFactory = modelFactory;
        this.settingsService = settingsService;
        this.graphExtractionService = graphExtractionService;
        this.bm25 = bm25;
        this.executor = executor;
    }

    /** 提交文档处理任务 */
    public void submit(long docId) {
        executor.execute(() -> process(docId));
    }

    /** 失败重试 */
    public void retry(long docId) {
        Document doc = documentDao.findById(docId);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        if (doc.getFilePath() == null || !Files.exists(Paths.get(doc.getFilePath()))) {
            throw new BusinessException("文档源文件已丢失，无法重试");
        }
        documentDao.updateStatus(docId, "PENDING", 0d, null);
        submit(docId);
    }

    /** 重新索引：清掉旧片段与向量后重跑 */
    public void reindex(long docId) {
        Document doc = documentDao.findById(docId);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        KnowledgeBase kb = kbDao.findById(doc.getKbId());
        if (kb == null) {
            throw new BusinessException("知识库不存在");
        }
        bm25.invalidate(kb.getId());
        List<Long> oldIds = chunkDao.findIdsByDoc(docId);
        vectorStoreFactory.get().deleteChunks(kb.getId(), oldIds);
        graphDao.deleteEntityChunkByChunkIds(oldIds);
        chunkDao.deleteByDoc(docId);
        documentDao.updateChunkCount(docId, 0);
        documentDao.updateStatus(docId, "PENDING", 0d, null);
        submit(docId);
    }

    private void process(long docId) {
        Document doc = documentDao.findById(docId);
        if (doc == null) {
            return;
        }
        KnowledgeBase kb = kbDao.findById(doc.getKbId());
        if (kb == null) {
            fail(docId, 0.05, "知识库不存在");
            return;
        }
        try {
            // 1. 解析（表格按知识库策略：转文本 / 逐行 JSON / 摘要+明细）
            documentDao.updateStatus(docId, "PROCESSING", 0.05, null);
            List<ChunkItem> items = extractContent(doc, kb);
            documentDao.updateStatus(docId, "PROCESSING", 0.3, null);
            if (items.isEmpty()) {
                fail(docId, 0.3, "未能从文档中提取到文本内容");
                return;
            }
            documentDao.updateStatus(docId, "PROCESSING", 0.5, null);

            // 2.5 Contextual 模式：LLM 为每个片段生成文档上下文头并拼入向量文本（一次性入库成本）
            if (Boolean.TRUE.equals(kb.getContextual())) {
                applyContextual(items);
            }

            // 3. 向量化
            ModelProvider provider = embeddingService.providerFor(kb);
            List<String> texts = items.stream().map(ChunkItem::content).toList();
            List<float[]> vectors = embeddingService.embedBatch(texts, provider);

            // 4. 入库（片段行 + 向量；父子分块时子块 meta 携带父块全文）
            VectorStore vs = vectorStoreFactory.get();
            List<Long> chunkIds = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                ChunkItem item = items.get(i);
                Chunk c = new Chunk();
                c.setKbId(kb.getId());
                c.setDocId(docId);
                c.setPosition(i);
                c.setContent(item.content());
                java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
                meta.put("fileName", doc.getFileName());
                if (item.parent() != null) {
                    meta.put("parent", item.parent());
                }
                c.setMeta(com.pkb.util.JsonUtil.toJson(meta));
                long cid = chunkDao.insert(c);
                vs.add(cid, kb.getId(), vectors.get(i));
                bm25.add(kb.getId(), cid, c.getContent());
                chunkIds.add(cid);
            }
            documentDao.updateChunkCount(docId, items.size());
            documentDao.updateStatus(docId, "INDEXED", 1.0, null);
            log.info("文档入库完成: {} → {} 个片段", doc.getFileName(), items.size());

            // 5. 图谱抽取（尽力而为，失败不影响入库）
            if (Boolean.TRUE.equals(kb.getGraphEnabled()) && settingsService.graphExtractOnIndex()) {
                try {
                    graphExtractionService.extractForChunks(kb, chunkIds, texts);
                } catch (Exception e) {
                    log.warn("文档 {} 图谱抽取失败: {}", doc.getFileName(), e.getMessage());
                }
            }
        } catch (BusinessException e) {
            fail(docId, null, e.getMessage());
        } catch (Exception e) {
            log.error("文档处理失败: {} ({})", doc.getFileName(), e.getMessage());
            fail(docId, null, shortError(e));
        }
    }

    /** 分块入口：parent_child 返回子块 + 父块；其余策略返回普通块（parent=null） */
    private List<ChunkItem> splitToItems(KnowledgeBase kb, String text) {
        List<ChunkItem> items = new ArrayList<>();
        if ("parent_child".equals(kb.getChunkStrategy())) {
            com.pkb.knowledge.splitter.ParentChildSplitter pcs =
                    new com.pkb.knowledge.splitter.ParentChildSplitter(kb.getChunkSize(), kb.getChunkOverlap());
            for (com.pkb.knowledge.splitter.ParentChildSplitter.ParentChild pc : pcs.splitWithParents(text)) {
                items.add(new ChunkItem(pc.child(), pc.parent()));
            }
        } else {
            com.pkb.knowledge.splitter.ChunkSplitter splitter =
                    com.pkb.knowledge.splitter.SplitterFactory.create(
                            kb.getChunkStrategy(), kb.getChunkSize(), kb.getChunkOverlap());
            for (String s : splitter.split(text)) {
                items.add(new ChunkItem(s, null));
            }
        }
        return items;
    }

    /** Contextual：批量（每批 8 个）用聊天模型生成一句文档上下文头，拼到片段前；失败片段跳过不阻塞入库 */
    private void applyContextual(List<ChunkItem> items) {
        try {
            ModelProvider chat = providerService.defaultChatProvider();
            ChatModel model = modelFactory.chatModel(chat);
            int batchSize = 8;
            for (int i = 0; i < items.size(); i += batchSize) {
                int end = Math.min(i + batchSize, items.size());
                StringBuilder prompt = new StringBuilder();
                prompt.append("你是文档上下文标注助手。请为下面每个片段生成一句上下文头，说明它所属文档与讨论主题，一句话不超过 40 字，如“本文档为《XX规范》第 3 节，讨论……”。严格按“序号: 上下文”格式输出，一行一条，不要输出其他内容。\n\n");
                for (int j = i; j < end; j++) {
                    prompt.append(j - i + 1).append(": ").append(items.get(j).content()).append("\n\n");
                }
                String r = model.chat(dev.langchain4j.model.chat.request.ChatRequest.builder()
                        .messages(List.of(dev.langchain4j.data.message.UserMessage.from(prompt.toString())))
                        .build()).aiMessage().text();
                if (r == null || r.isBlank()) {
                    continue;
                }
                java.util.regex.Pattern pat = java.util.regex.Pattern.compile("^\\s*(\\d+)[:：]\\s*(.+)$");
                for (String line : r.split("\n")) {
                    java.util.regex.Matcher m = pat.matcher(line);
                    if (!m.find()) {
                        continue;
                    }
                    int idx = Integer.parseInt(m.group(1)) - 1;
                    String head = m.group(2).trim();
                    if (idx >= 0 && idx < end - i && !head.isBlank()) {
                        ChunkItem item = items.get(i + idx);
                        item.content = head + "\n" + item.content;
                    }
                }
            }
            log.info("Contextual 标注完成（{} 个片段）", items.size());
        } catch (Exception e) {
            log.warn("Contextual 生成失败，跳过本次标注: {}", e.getMessage());
        }
    }

    /** 入库片段：content 为向量化/入库文本；parent 为父块全文（父子分块时非空） */
    private static final class ChunkItem {
        private String content;
        private final String parent;

        ChunkItem(String content, String parent) {
            this.content = content;
            this.parent = parent;
        }

        String content() {
            return content;
        }

        String parent() {
            return parent;
        }
    }

    private void fail(long docId, Double progress, String message) {
        documentDao.updateStatus(docId, "FAILED", progress == null ? 0.5 : progress,
                message == null ? "未知错误" : message);
    }

    private String shortError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            return e.getClass().getSimpleName();
        }
        return msg.length() > 300 ? msg.substring(0, 300) : msg;
    }

    /** 内容提取：普通文档 → 文本分块；表格按知识库策略（转文本 / 逐行 JSON / 摘要+明细）产出块 */
    private List<ChunkItem> extractContent(Document doc, KnowledgeBase kb) throws Exception {
        String fileName = doc.getFileName();
        // 媒体文件（多模态知识库）
        if (com.pkb.util.TextUtil.isMediaFile(fileName)) {
            if (!Boolean.TRUE.equals(kb.getMultimodal())) {
                throw new BusinessException("当前知识库为纯文本模式，不接受图片/视频等媒体文件");
            }
            return List.of(new ChunkItem(describeMedia(doc), null));
        }
        var parser = com.pkb.knowledge.parser.ParserFactory.forFile(fileName);
        if (parser == null) {
            throw new BusinessException("不支持的文档类型：" + fileName);
        }
        String strategy = kb.getTableStrategy() == null ? "table_text" : kb.getTableStrategy();
        if (parser instanceof com.pkb.knowledge.parser.ChunkedParser cp
                && ("table_json".equals(strategy) || "table_summary".equals(strategy))) {
            List<String> rows = cp.parseChunks(Paths.get(doc.getFilePath()));
            List<ChunkItem> items = new ArrayList<>();
            for (String row : rows) {
                items.add(new ChunkItem(row, null));
            }
            if ("table_summary".equals(strategy) && !rows.isEmpty()) {
                // Sheet 摘要：取前 15 行明细交给 LLM 生成一句话概述，作为首个块；失败不影响明细入库
                String summary = summarizeSheet(kb, fileName, rows.subList(0, Math.min(15, rows.size())));
                if (summary != null) {
                    items.add(0, new ChunkItem("【表格摘要】" + summary, null));
                }
            }
            return items;
        }
        String text = parser.parse(Paths.get(doc.getFilePath()));
        return splitToItems(kb, text);
    }

    /** 表格 Sheet 摘要（table_summary 策略）：基于前若干行明细让 LLM 生成概述 */
    private String summarizeSheet(KnowledgeBase kb, String fileName, List<String> rows) {
        try {
            ModelProvider chat = providerService.defaultChatProvider();
            ChatModel model = modelFactory.chatModel(chat);
            StringBuilder sample = new StringBuilder();
            for (int i = 0; i < rows.size(); i++) {
                sample.append(i + 1).append(". ").append(rows.get(i)).append("\n");
            }
            String prompt = "下面是表格文件「" + fileName + "」的前 " + rows.size() + " 行数据（JSON 格式）。"
                    + "请用一句话（60 字以内）概括这张表的内容、主要列与用途，用于知识库检索索引。只输出概括本身。\n\n" + sample;
            String r = model.chat(dev.langchain4j.model.chat.request.ChatRequest.builder()
                    .messages(List.of(dev.langchain4j.data.message.UserMessage.from(prompt))).build())
                    .aiMessage().text();
            if (r == null || r.isBlank()) {
                return null;
            }
            return r.trim();
        } catch (Exception e) {
            log.warn("表格摘要生成失败，跳过（不影响明细入库）: {}", e.getMessage());
            return null;
        }
    }

    /** 用多模态聊天模型描述媒体内容用于向量化（不做自研 OCR/抽帧）；大文件仅索引文件名防 OOM */
    private static final long MAX_MEDIA_DESCRIBE_BYTES = 8L * 1024 * 1024;

    private String describeMedia(Document doc) {
        String fileName = doc.getFileName();
        String fallback = "【媒体文件】文件名：" + fileName + "（文件较大或模型暂无法理解，仅索引文件名）";
        try {
            Path path = Paths.get(doc.getFilePath());
            long size = Files.size(path);
            if (size > MAX_MEDIA_DESCRIBE_BYTES) {
                log.info("媒体文件过大（{} MB），仅索引文件名: {}", size / 1024 / 1024, fileName);
                return fallback;
            }
            ModelProvider chat = providerService.defaultChatProvider();
            byte[] bytes = Files.readAllBytes(path);
            String b64 = Base64.getEncoder().encodeToString(bytes);
            String mime = mimeByExt(fileName);
            var model = modelFactory.chatModel(chat);
            String desc = model.chat(dev.langchain4j.model.chat.request.ChatRequest.builder()
                    .messages(List.of(UserMessage.from(
                            TextContent.from("请用中文简要描述这份图片/视频的内容（50 字以内），用于个人知识库检索索引。"),
                            ImageContent.from(b64, mime))))
                    .build()).aiMessage().text();
            if (desc == null || desc.isBlank()) {
                return fallback;
            }
            return "【媒体文件】文件名：" + fileName + "\n内容描述：" + desc.trim();
        } catch (Exception e) {
            log.warn("媒体理解失败，使用文件名兜底: {}", e.getMessage());
            return fallback;
        }
    }

    private String mimeByExt(String fileName) {
        String ext = com.pkb.util.TextUtil.extOf(fileName);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "svg" -> "image/svg+xml";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            default -> "application/octet-stream";
        };
    }
}
