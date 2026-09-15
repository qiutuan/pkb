package com.pkb.knowledge;

import com.pkb.common.BusinessException;
import com.pkb.dao.CategoryDao;
import com.pkb.dao.ChunkDao;
import com.pkb.dao.DocumentDao;
import com.pkb.dao.GraphDao;
import com.pkb.dao.KnowledgeBaseDao;
import com.pkb.dao.ModelProviderDao;
import com.pkb.rag.Bm25Index;
import com.pkb.vector.VectorStore;
import com.pkb.vector.VectorStoreFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseDao dao;
    private final CategoryDao categoryDao;
    private final DocumentDao documentDao;
    private final ChunkDao chunkDao;
    private final GraphDao graphDao;
    private final ModelProviderDao providerDao;
    private final VectorStoreFactory vectorStoreFactory;
    private final Bm25Index bm25;

    public KnowledgeBaseService(KnowledgeBaseDao dao, CategoryDao categoryDao, DocumentDao documentDao,
                                ChunkDao chunkDao, GraphDao graphDao, ModelProviderDao providerDao,
                                VectorStoreFactory vectorStoreFactory, Bm25Index bm25) {
        this.dao = dao;
        this.categoryDao = categoryDao;
        this.documentDao = documentDao;
        this.chunkDao = chunkDao;
        this.graphDao = graphDao;
        this.providerDao = providerDao;
        this.vectorStoreFactory = vectorStoreFactory;
        this.bm25 = bm25;
    }

    public List<KbView> list() {
        return dao.findAll().stream().map(this::toView).toList();
    }

    public KnowledgeBase require(long id) {
        KnowledgeBase kb = dao.findById(id);
        if (kb == null) {
            throw new BusinessException("知识库不存在");
        }
        return kb;
    }

    public KnowledgeBase save(KnowledgeBase kb) {
        if (kb.getName() == null || kb.getName().isBlank()) {
            throw new BusinessException("请填写知识库名称");
        }
        if (kb.getCategoryId() != null && kb.getCategoryId() != 0 && categoryDao.findById(kb.getCategoryId()) == null) {
            throw new BusinessException("所选分类不存在");
        }
        if (kb.getEmbeddingProviderId() != null) {
            var ep = providerDao.findById(kb.getEmbeddingProviderId());
            if (ep == null) {
                throw new BusinessException("所选 Embedding 模型不存在");
            }
            if (ep.getEmbeddingModel() == null || ep.getEmbeddingModel().isBlank()) {
                throw new BusinessException("所选 Provider 未配置向量模型，请先补充或换选");
            }
        }
        if (kb.getChunkStrategy() == null || kb.getChunkStrategy().isBlank()) {
            kb.setChunkStrategy("fixed");
        }
        if (!java.util.Set.of("fixed", "paragraph", "parent_child").contains(kb.getChunkStrategy())) {
            throw new BusinessException("不支持的分块策略：" + kb.getChunkStrategy());
        }
        if (kb.getChunkSize() == null || kb.getChunkSize() < 100 || kb.getChunkSize() > 4000) {
            throw new BusinessException("分块大小需在 100–4000 之间");
        }
        if (kb.getChunkOverlap() == null || kb.getChunkOverlap() < 0 || kb.getChunkOverlap() > 500) {
            throw new BusinessException("分块重叠需在 0–500 之间");
        }
        if (kb.getChunkOverlap() >= kb.getChunkSize()) {
            throw new BusinessException("分块重叠必须小于分块大小");
        }
        if (kb.getId() == null) {
            kb.setId(dao.insert(kb));
        } else {
            dao.update(kb);
        }
        return dao.findById(kb.getId());
    }

    public void delete(long id) {
        KnowledgeBase kb = require(id);
        VectorStore vs = vectorStoreFactory.get();
        // 删除文档文件与记录
        for (var doc : documentDao.findByKb(id)) {
            if (doc.getFilePath() != null) {
                try {
                    Files.deleteIfExists(Paths.get(doc.getFilePath()));
                } catch (IOException ignored) {
                }
            }
            documentDao.delete(doc.getId());
        }
        chunkDao.deleteByKb(id);
        graphDao.deleteByKb(id);
        vs.deleteByKb(id);
        dao.delete(id);
    }

    public KbView toView(KnowledgeBase kb) {
        long docCount = documentDao.countByKb(kb.getId());
        long chunkCount = chunkDao.countByKb(kb.getId());
        long vectorCount = vectorStoreFactory.get().count(kb.getId());
        String categoryName = kb.getCategoryId() == null || kb.getCategoryId() == 0 ? ""
                : (categoryDao.findById(kb.getCategoryId()) == null ? "" : categoryDao.findById(kb.getCategoryId()).getName());
        String providerName = kb.getEmbeddingProviderId() == null ? ""
                : (providerDao.findById(kb.getEmbeddingProviderId()) == null ? "" : providerDao.findById(kb.getEmbeddingProviderId()).getName());
        // 文档索引状态统计（用于卡片状态徽标）
        long pending = documentDao.countByKbAndStatus(kb.getId(), "PENDING");
        long processing = documentDao.countByKbAndStatus(kb.getId(), "PROCESSING");
        long failed = documentDao.countByKbAndStatus(kb.getId(), "FAILED");
        return new KbView(kb, categoryName, providerName, docCount, chunkCount, vectorCount, pending, processing, failed);
    }

    public record KbView(KnowledgeBase kb, String categoryName, String providerName, long docCount, long chunkCount,
                         long vectorCount, long pendingDocs, long processingDocs, long failedDocs) {
    }
}
