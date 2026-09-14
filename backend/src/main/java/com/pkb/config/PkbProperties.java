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
        /** 单文件上传大小上限（MB），默认 200 */
        private int maxUploadMb = 200;
        /** 单次上传文件数量上限，默认 50（Tomcat multipart 单请求 part 数存在框架级上限，取安全值） */
        private int maxUploadFiles = 50;
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
        /** 图谱构建 Prompt：实体合并去重决策 */
        private String graphBuildPrompt = "";
        /** 图谱抽取专用模型 Provider id；0 表示跟随默认聊天模型 */
        private long graphExtractProvider = 0;
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

        public String getGraphBuildPromptOrDefault() {
            return graphBuildPrompt == null || graphBuildPrompt.isBlank()
                    ? "你是知识图谱构建引擎。请判断两个实体名称是否指向同一真实事物（考虑简称、别名、中英文译名），只输出 JSON：{\"merge\":true或false,\"reason\":\"不超过 20 字的原因\"}。"
                    : graphBuildPrompt;
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
