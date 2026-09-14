package com.pkb.knowledge;

import com.pkb.common.ApiResponse;
import com.pkb.common.BusinessException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DocumentController {

    private final KnowledgeBaseService kbService;
    private final DocumentPipelineService pipeline;
    private final com.pkb.dao.DocumentDao documentDao;
    private final com.pkb.dao.ChunkDao chunkDao;
    private final com.pkb.dao.GraphDao graphDao;
    private final com.pkb.vector.VectorStoreFactory vectorStoreFactory;
    private final com.pkb.config.PkbProperties props;

    public DocumentController(KnowledgeBaseService kbService, DocumentPipelineService pipeline,
                              com.pkb.dao.DocumentDao documentDao, com.pkb.dao.ChunkDao chunkDao,
                              com.pkb.dao.GraphDao graphDao,
                              com.pkb.vector.VectorStoreFactory vectorStoreFactory,
                              com.pkb.config.PkbProperties props) {
        this.kbService = kbService;
        this.pipeline = pipeline;
        this.documentDao = documentDao;
        this.chunkDao = chunkDao;
        this.graphDao = graphDao;
        this.vectorStoreFactory = vectorStoreFactory;
        this.props = props;
    }

    /** 上传文档（可多文件），纯文本模式拒绝媒体文件 */
    @PostMapping("/kbs/{kbId}/documents")
    public ApiResponse<List<Document>> upload(@PathVariable long kbId,
                                              @RequestParam("files") List<MultipartFile> files) {
        KnowledgeBase kb = kbService.require(kbId);
        List<Document> created = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                created.add(uploadOne(kb, file));
            }
        } catch (BusinessException e) {
            // 部分失败：已创建的文档照常入库
            if (created.isEmpty()) {
                throw e;
            }
        }
        return ApiResponse.ok(created);
    }

    private Document uploadOne(KnowledgeBase kb, MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw new BusinessException("文件名为空");
        }
        if (file.isEmpty()) {
            throw new BusinessException("文件内容为空：" + name);
        }
        boolean media = com.pkb.util.TextUtil.isMediaFile(name);
        if (media) {
            if (!Boolean.TRUE.equals(kb.getMultimodal())) {
                throw new BusinessException("当前知识库为纯文本模式，不接受图片/视频等媒体文件（请在知识库设置中开启多模态模式）");
            }
        } else if (!com.pkb.knowledge.parser.ParserFactory.supported(name)) {
            throw new BusinessException("不支持的文档类型：" + name + "（支持 txt/md/pdf/docx/doc；多模态模式另支持图片与视频）");
        }
        try {
            Path dir = Paths.get(props.getDataDir()).toAbsolutePath().normalize()
                    .resolve("files").resolve(String.valueOf(kb.getId()));
            Files.createDirectories(dir);
            String safeName = com.pkb.util.TextUtil.safeFileName(name);
            Path target = dir.resolve(System.currentTimeMillis() + "_" + safeName);

            Document doc = new Document();
            doc.setKbId(kb.getId());
            doc.setFileName(name);
            doc.setFileType(com.pkb.util.TextUtil.extOf(name));
            doc.setFileSize(file.getSize());
            doc.setFilePath(target.toString());
            doc.setStatus("PENDING");
            doc.setProgress(0d);
            long docId = documentDao.insert(doc);
            file.transferTo(target);
            pipeline.submit(docId);
            return documentDao.findById(docId);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("文件保存失败：" + e.getMessage());
        }
    }

    @GetMapping("/kbs/{kbId}/documents")
    public ApiResponse<List<Document>> list(@PathVariable long kbId) {
        return ApiResponse.ok(documentDao.findByKb(kbId));
    }

    @GetMapping("/documents/{id}/chunks")
    public ApiResponse<List<Chunk>> chunks(@PathVariable long id) {
        return ApiResponse.ok(chunkDao.findByDoc(id));
    }

    @DeleteMapping("/documents/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        com.pkb.knowledge.Document doc = documentDao.findById(id);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        List<Long> chunkIds = chunkDao.findIdsByDoc(id);
        vectorStoreFactory.get().deleteChunks(doc.getKbId(), chunkIds);
        graphDao.deleteEntityChunkByChunkIds(chunkIds);
        chunkDao.deleteByDoc(id);
        documentDao.delete(id);
        if (doc.getFilePath() != null) {
            try {
                Files.deleteIfExists(Paths.get(doc.getFilePath()));
            } catch (Exception ignored) {
            }
        }
        return ApiResponse.ok();
    }

    @PostMapping("/documents/{id}/retry")
    public ApiResponse<Void> retry(@PathVariable long id) {
        pipeline.retry(id);
        return ApiResponse.ok();
    }

    @PostMapping("/documents/{id}/reindex")
    public ApiResponse<Void> reindex(@PathVariable long id) {
        pipeline.reindex(id);
        return ApiResponse.ok();
    }

    /** 下载原始文件（供多模态对话/预览使用） */
    @GetMapping("/documents/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable long id) {
        com.pkb.knowledge.Document doc = documentDao.findById(id);
        if (doc == null || doc.getFilePath() == null || !Files.exists(Paths.get(doc.getFilePath()))) {
            throw new BusinessException("文件不存在");
        }
        Path path = Paths.get(doc.getFilePath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + doc.getFileName())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }
}
