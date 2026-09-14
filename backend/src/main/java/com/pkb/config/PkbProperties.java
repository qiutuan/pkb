package com.pkb.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * pkb.* 配置项。
 */
@Data
@ConfigurationProperties(prefix = "pkb")
public class PkbProperties {

    private String dataDir = "./data";

    private Vector vector = new Vector();

    private Pipeline pipeline = new Pipeline();

    private Security security = new Security();

    private Defaults defaults = new Defaults();

    @Data
    public static class Vector {
        private String mode = "embedded";
        private Embedded embedded = new Embedded();
        private Pgvector pgvector = new Pgvector();
    }

    @Data
    public static class Embedded {
        private String algorithm = "hnsw";
        private Hnsw hnsw = new Hnsw();
    }

    @Data
    public static class Hnsw {
        private int m = 16;
        private int efConstruction = 200;
        private int efSearch = 128;
        private long seed = 42L;
    }

    @Data
    public static class Pgvector {
        private String host = "localhost";
        private int port = 5432;
        private String database = "pkb";
        private String username = "pkb";
        private String password = "pkb";
        private String tablePrefix = "pkb_vectors_";
    }

    @Data
    public static class Pipeline {
        private int workers = 2;
        private int queueCapacity = 200;
    }

    @Data
    public static class Security {
        private String encryptKey = "";
    }

    @Data
    public static class Defaults {
        private String systemPrompt = "";
        private String extractPrompt = "";
        private String graphPrompt = "";
        private Chunk chunk = new Chunk();
        private Rag rag = new Rag();
        private Graph graph = new Graph();

        public String getSystemPromptOrDefault() {
            return systemPrompt == null || systemPrompt.isBlank()
                    ? "你是一个严谨的个人知识库问答助手，请基于提供的【参考资料】回答用户问题。引用时在句末标注来源编号，格式如 [1]。"
                    : systemPrompt;
        }

        public String getExtractPromptOrDefault() {
            return extractPrompt == null || extractPrompt.isBlank()
                    ? "从文本中抽取实体与关系，仅输出 JSON。"
                    : extractPrompt;
        }

        public String getGraphPromptOrDefault() {
            return graphPrompt == null || graphPrompt.isBlank()
                    ? "结合知识图谱中的实体关系组织回答。"
                    : graphPrompt;
        }
    }

    @Data
    public static class Chunk {
        private String strategy = "fixed";
        private int size = 600;
        private int overlap = 100;
    }

    @Data
    public static class Rag {
        private int topK = 8;
        private double minScore = 0.25;
        private String rerank = "hybrid";
        private int graphHop = 2;
        private int graphEntities = 5;
        private int graphChunks = 15;
        private int historyLimit = 10;
    }

    @Data
    public static class Graph {
        private int extractBatch = 4;
        private boolean extractOnIndex = true;
        private double entityMergeThreshold = 0.92;
    }

    /** 支持的环境变量别名（用于 docker-compose 注入） */
    public static Map<String, String> envAliases() {
        return Map.of(
                "PKB_DATA_DIR", "pkb.data-dir",
                "PKB_VECTOR_MODE", "pkb.vector.mode",
                "PKB_PG_HOST", "pkb.vector.pgvector.host",
                "PKB_PG_PORT", "pkb.vector.pgvector.port",
                "PKB_PG_DATABASE", "pkb.vector.pgvector.database",
                "PKB_PG_USERNAME", "pkb.vector.pgvector.username",
                "PKB_PG_PASSWORD", "pkb.vector.pgvector.password"
        );
    }
}
