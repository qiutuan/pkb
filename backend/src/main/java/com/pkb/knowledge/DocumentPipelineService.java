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
import com.pkb.settings.SettingsService;
import com.pkb.vector.VectorStore;
import com.pkb.vector.VectorStoreFactory;
import dev.langchain4j.data.message.ImageContent;
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
    private final ThreadPoolTaskExecutor executor;

    public DocumentPipelineService(DocumentDao documentDao, KnowledgeBaseDao kbDao, ChunkDao chunkDao,
                                   GraphDao graphDao, EmbeddingService embeddingService,
                                   VectorStoreFactory vectorStoreFactory, ModelProviderService providerService,
                                   ModelFactory modelFactory, SettingsService settingsService,
                                   GraphExtractionService graphExtractionService,
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
            // 1. 解析
            documentDao.updateStatus(docId, "PROCESSING", 0.05, null);
            String text = extractText(doc, kb);
            documentDao.updateStatus(docId, "PROCESSING", 0.3, null);
            if (text == null || text.isBlank()) {
                fail(docId, 0.3, "未能从文档中提取到文本内容");
                return;
            }

            // 2. 分块
            com.pkb.knowledge.splitter.ChunkSplitter splitter =
                    com.pkb.knowledge.splitter.SplitterFactory.create(
                            kb.getChunkStrategy(), kb.getChunkSize(), kb.getChunkOverlap());
            List<String> chunks = splitter.split(text);
            if (chunks.isEmpty()) {
                fail(docId, 0.5, "未生成任何片段");
                return;
            }
            documentDao.updateStatus(docId, "PROCESSING", 0.5, null);

            // 3. 向量化
            ModelProvider provider = embeddingService.providerFor(kb);
            List<float[]> vectors = embeddingService.embedBatch(chunks, provider);

            // 4. 入库（片段行 + 向量）
            VectorStore vs = vectorStoreFactory.get();
            List<Long> chunkIds = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = new Chunk();
                c.setKbId(kb.getId());
                c.setDocId(docId);
                c.setPosition(i);
                c.setContent(chunks.get(i));
                c.setMeta("{\"fileName\":\"" + doc.getFileName() + "\"}");
                long cid = chunkDao.insert(c);
                vs.add(cid, kb.getId(), vectors.get(i));
                chunkIds.add(cid);
            }
            documentDao.updateChunkCount(docId, chunks.size());
            documentDao.updateStatus(docId, "INDEXED", 1.0, null);
            log.info("文档入库完成: {} → {} 个片段", doc.getFileName(), chunks.size());

            // 5. 图谱抽取（尽力而为，失败不影响入库）
            if (Boolean.TRUE.equals(kb.getGraphEnabled()) && settingsService.graphExtractOnIndex()) {
                try {
                    graphExtractionService.extractForChunks(kb, chunkIds, chunks);
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

    private String extractText(Document doc, KnowledgeBase kb) throws Exception {
        String fileName = doc.getFileName();
        // 媒体文件（多模态知识库）
        if (com.pkb.util.TextUtil.isMediaFile(fileName)) {
            if (!Boolean.TRUE.equals(kb.getMultimodal())) {
                throw new BusinessException("当前知识库为纯文本模式，不接受图片/视频等媒体文件");
            }
            return describeMedia(doc);
        }
        var parser = com.pkb.knowledge.parser.ParserFactory.forFile(fileName);
        if (parser == null) {
            throw new BusinessException("不支持的文档类型：" + fileName);
        }
        return parser.parse(Paths.get(doc.getFilePath()));
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
