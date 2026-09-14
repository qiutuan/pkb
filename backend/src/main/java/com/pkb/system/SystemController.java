package com.pkb.system;

import com.pkb.common.ApiResponse;
import com.pkb.config.PkbProperties;
import com.pkb.dao.ChatDao;
import com.pkb.dao.ChunkDao;
import com.pkb.dao.DocumentDao;
import com.pkb.dao.KnowledgeBaseDao;
import com.pkb.dao.ModelProviderDao;
import com.pkb.vector.VectorStoreFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final PkbProperties props;
    private final ModelProviderDao providerDao;
    private final KnowledgeBaseDao kbDao;
    private final DocumentDao documentDao;
    private final ChunkDao chunkDao;
    private final ChatDao chatDao;
    private final VectorStoreFactory vectorStoreFactory;

    public SystemController(PkbProperties props, ModelProviderDao providerDao, KnowledgeBaseDao kbDao,
                            DocumentDao documentDao, ChunkDao chunkDao, ChatDao chatDao,
                            VectorStoreFactory vectorStoreFactory) {
        this.props = props;
        this.providerDao = providerDao;
        this.kbDao = kbDao;
        this.documentDao = documentDao;
        this.chunkDao = chunkDao;
        this.chatDao = chatDao;
        this.vectorStoreFactory = vectorStoreFactory;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of("status", "UP", "time", System.currentTimeMillis()));
    }

    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "PKB 个人知识库系统");
        m.put("version", "1.0.0");
        m.put("vectorMode", vectorStoreFactory.mode());
        m.put("indexAlgorithm", props.getVector().getEmbedded().getAlgorithm());
        m.put("dataDir", Paths.get(props.getDataDir()).toAbsolutePath().normalize().toString());
        m.put("providers", providerDao.count());
        m.put("knowledgeBases", kbDao.count());
        m.put("documents", documentDao.countAll());
        m.put("chunks", chunkDao.countAll());
        m.put("chatSessions", chatDao.countSessions());
        return ApiResponse.ok(m);
    }
}
