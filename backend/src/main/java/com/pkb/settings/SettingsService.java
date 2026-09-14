package com.pkb.settings;

import com.pkb.config.PkbProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设置中心：默认值来自 application.yml（pkb.defaults.*），界面修改写入 <data-dir>/settings.yml，
 * 实现 YAML / 界面双方式修改；「恢复默认」即删除覆盖文件。
 */
@Slf4j
@Service
public class SettingsService {

    public static final String K_SYSTEM_PROMPT = "systemPrompt";
    public static final String K_EXTRACT_PROMPT = "extractPrompt";
    public static final String K_GRAPH_PROMPT = "graphPrompt";
    public static final String K_GRAPH_BUILD_PROMPT = "graphBuildPrompt";
    public static final String K_CHUNK_STRATEGY = "chunkStrategy";
    public static final String K_CHUNK_SIZE = "chunkSize";
    public static final String K_CHUNK_OVERLAP = "chunkOverlap";
    public static final String K_RAG_TOP_K = "ragTopK";
    public static final String K_RAG_MIN_SCORE = "ragMinScore";
    public static final String K_RAG_RERANK = "ragRerank";
    public static final String K_RAG_GRAPH_HOP = "ragGraphHop";
    public static final String K_RAG_GRAPH_ENTITIES = "ragGraphEntities";
    public static final String K_RAG_GRAPH_CHUNKS = "ragGraphChunks";
    public static final String K_RAG_HISTORY_LIMIT = "ragHistoryLimit";
    public static final String K_GRAPH_EXTRACT_BATCH = "graphExtractBatch";
    public static final String K_GRAPH_EXTRACT_ON_INDEX = "graphExtractOnIndex";
    public static final String K_GRAPH_MERGE_THRESHOLD = "graphEntityMergeThreshold";
    /** 图谱抽取专用模型 Provider id；0 = 跟随默认聊天模型 */
    public static final String K_GRAPH_EXTRACT_PROVIDER = "graphExtractProvider";

    private final PkbProperties props;
    private final Yaml yaml;
    private final Path file;
    private final Object lock = new Object();

    public SettingsService(PkbProperties props) {
        this.props = props;
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        this.yaml = new Yaml(opts);
        this.file = Paths.get(props.getDataDir()).toAbsolutePath().normalize().resolve("settings.yml");
    }

    private Map<String, Object> load() {
        synchronized (lock) {
            if (Files.exists(file)) {
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    Object o = yaml.load(reader);
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> out = new HashMap<>();
                        m.forEach((k, v) -> out.put(String.valueOf(k), v));
                        return out;
                    }
                } catch (Exception e) {
                    log.warn("settings.yml 解析失败，将忽略: {}", e.getMessage());
                }
            }
            return new HashMap<>();
        }
    }

    public void putAll(Map<String, Object> overrides) {
        synchronized (lock) {
            Map<String, Object> merged = new LinkedHashMap<>(load());
            merged.putAll(overrides);
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                try (var writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    yaml.dump(merged, writer);
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new RuntimeException("保存设置失败: " + e.getMessage(), e);
            }
        }
    }

    /** 全部生效值（默认 + 覆盖） */
    public Map<String, Object> all() {
        Map<String, Object> out = new LinkedHashMap<>();
        PkbProperties.Defaults d = props.getDefaults();
        out.put(K_SYSTEM_PROMPT, d.getSystemPromptOrDefault());
        out.put(K_EXTRACT_PROMPT, d.getExtractPromptOrDefault());
        out.put(K_GRAPH_PROMPT, d.getGraphPromptOrDefault());
        out.put(K_GRAPH_BUILD_PROMPT, d.getGraphBuildPromptOrDefault());
        out.put(K_CHUNK_STRATEGY, d.getChunk().getStrategy());
        out.put(K_CHUNK_SIZE, d.getChunk().getSize());
        out.put(K_CHUNK_OVERLAP, d.getChunk().getOverlap());
        out.put(K_RAG_TOP_K, d.getRag().getTopK());
        out.put(K_RAG_MIN_SCORE, d.getRag().getMinScore());
        out.put(K_RAG_RERANK, d.getRag().getRerank());
        out.put(K_RAG_GRAPH_HOP, d.getRag().getGraphHop());
        out.put(K_RAG_GRAPH_ENTITIES, d.getRag().getGraphEntities());
        out.put(K_RAG_GRAPH_CHUNKS, d.getRag().getGraphChunks());
        out.put(K_RAG_HISTORY_LIMIT, d.getRag().getHistoryLimit());
        out.put(K_GRAPH_EXTRACT_BATCH, d.getGraph().getExtractBatch());
        out.put(K_GRAPH_EXTRACT_ON_INDEX, d.getGraph().isExtractOnIndex());
        out.put(K_GRAPH_MERGE_THRESHOLD, d.getGraph().getEntityMergeThreshold());
        out.put(K_GRAPH_EXTRACT_PROVIDER, d.getGraphExtractProvider());
        out.putAll(load());
        return out;
    }

    /** 重置单个键：删除覆盖文件中的对应项，恢复默认值 */
    public void resetKey(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        synchronized (lock) {
            Map<String, Object> m = load();
            if (m.remove(key) != null) {
                try {
                    Files.createDirectories(file.getParent());
                    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                    try (var writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                        yaml.dump(m, writer);
                    }
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    throw new RuntimeException("重置设置失败: " + e.getMessage(), e);
                }
            }
        }
    }

    public void reset() {
        synchronized (lock) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new RuntimeException("恢复默认失败: " + e.getMessage(), e);
            }
        }
    }

    // ===== 类型化读取 =====

    public String str(String key, String def) {
        Object v = load().get(key);
        return v == null ? def : String.valueOf(v);
    }

    public int intVal(String key, int def) {
        try {
            Object v = load().get(key);
            return v == null ? def : Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    public double doubleVal(String key, double def) {
        try {
            Object v = load().get(key);
            return v == null ? def : Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    public boolean boolVal(String key, boolean def) {
        Object v = load().get(key);
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    // 常用读取便捷方法
    public String systemPrompt() {
        return str(K_SYSTEM_PROMPT, props.getDefaults().getSystemPromptOrDefault());
    }

    public String extractPrompt() {
        return str(K_EXTRACT_PROMPT, props.getDefaults().getExtractPromptOrDefault());
    }

    public String graphPrompt() {
        return str(K_GRAPH_PROMPT, props.getDefaults().getGraphPromptOrDefault());
    }

    public String graphBuildPrompt() {
        return str(K_GRAPH_BUILD_PROMPT, props.getDefaults().getGraphBuildPromptOrDefault());
    }

    /** 图谱抽取专用模型 Provider id；0 = 跟随默认聊天模型 */
    public long graphExtractProvider() {
        return intVal(K_GRAPH_EXTRACT_PROVIDER, (int) props.getDefaults().getGraphExtractProvider());
    }

    public int ragTopK() {
        return intVal(K_RAG_TOP_K, props.getDefaults().getRag().getTopK());
    }

    public double ragMinScore() {
        return doubleVal(K_RAG_MIN_SCORE, props.getDefaults().getRag().getMinScore());
    }

    public String ragRerank() {
        return str(K_RAG_RERANK, props.getDefaults().getRag().getRerank());
    }

    public int graphHop() {
        return intVal(K_RAG_GRAPH_HOP, props.getDefaults().getRag().getGraphHop());
    }

    public int graphEntities() {
        return intVal(K_RAG_GRAPH_ENTITIES, props.getDefaults().getRag().getGraphEntities());
    }

    public int graphChunks() {
        return intVal(K_RAG_GRAPH_CHUNKS, props.getDefaults().getRag().getGraphChunks());
    }

    public int historyLimit() {
        return intVal(K_RAG_HISTORY_LIMIT, props.getDefaults().getRag().getHistoryLimit());
    }

    public int graphExtractBatch() {
        return intVal(K_GRAPH_EXTRACT_BATCH, props.getDefaults().getGraph().getExtractBatch());
    }

    public boolean graphExtractOnIndex() {
        return boolVal(K_GRAPH_EXTRACT_ON_INDEX, props.getDefaults().getGraph().isExtractOnIndex());
    }

    public double entityMergeThreshold() {
        return doubleVal(K_GRAPH_MERGE_THRESHOLD, props.getDefaults().getGraph().getEntityMergeThreshold());
    }
}
