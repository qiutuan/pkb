package com.pkb.config;

import com.pkb.util.TextUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * 启动时初始化 SQLite 表结构，并准备本地加密密钥。
 */
@Component
@RequiredArgsConstructor
public class DatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final JdbcTemplate jdbc;
    private final PkbProperties props;

    private static final String[] DDL = {
            """
            CREATE TABLE IF NOT EXISTS model_provider (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                provider_type TEXT NOT NULL,
                base_url TEXT,
                api_key_enc TEXT,
                chat_model TEXT,
                embedding_model TEXT,
                temperature REAL DEFAULT 0.7,
                max_tokens INTEGER DEFAULT 2048,
                default_chat INTEGER DEFAULT 0,
                default_embedding INTEGER DEFAULT 0,
                enabled INTEGER DEFAULT 1,
                capabilities TEXT DEFAULT 'text',
                created_at TEXT,
                updated_at TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS category (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                parent_id INTEGER DEFAULT 0,
                name TEXT NOT NULL,
                sort_order INTEGER DEFAULT 0,
                created_at TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS knowledge_base (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category_id INTEGER DEFAULT 0,
                name TEXT NOT NULL,
                description TEXT DEFAULT '',
                embedding_provider_id INTEGER,
                chunk_strategy TEXT DEFAULT 'fixed',
                chunk_size INTEGER DEFAULT 600,
                chunk_overlap INTEGER DEFAULT 100,
                multimodal INTEGER DEFAULT 0,
                graph_enabled INTEGER DEFAULT 1,
                created_at TEXT,
                updated_at TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS document (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kb_id INTEGER NOT NULL,
                file_name TEXT NOT NULL,
                file_type TEXT,
                file_size INTEGER,
                file_path TEXT,
                status TEXT DEFAULT 'PENDING',
                progress REAL DEFAULT 0,
                error_message TEXT,
                chunk_count INTEGER DEFAULT 0,
                created_at TEXT,
                updated_at TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS chunk (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kb_id INTEGER NOT NULL,
                doc_id INTEGER NOT NULL,
                position INTEGER DEFAULT 0,
                content TEXT NOT NULL,
                meta TEXT DEFAULT '{}',
                created_at TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_chunk_kb ON chunk(kb_id)",
            "CREATE INDEX IF NOT EXISTS idx_chunk_doc ON chunk(doc_id)",
            """
            CREATE TABLE IF NOT EXISTS chat_session (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT DEFAULT '新对话',
                model_provider_id INTEGER,
                created_at TEXT,
                updated_at TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS chat_message (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT,
                sources TEXT DEFAULT '[]',
                created_at TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_msg_session ON chat_message(session_id)",
            """
            CREATE TABLE IF NOT EXISTS graph_entity (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kb_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                entity_type TEXT DEFAULT '',
                description TEXT DEFAULT '',
                embedding BLOB,
                created_at TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_entity_kb ON graph_entity(kb_id)",
            "CREATE INDEX IF NOT EXISTS idx_entity_name ON graph_entity(kb_id, name)",
            """
            CREATE TABLE IF NOT EXISTS graph_relation (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kb_id INTEGER NOT NULL,
                source_id INTEGER NOT NULL,
                target_id INTEGER NOT NULL,
                relation_type TEXT DEFAULT '',
                description TEXT DEFAULT '',
                created_at TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_rel_kb ON graph_relation(kb_id)",
            "CREATE INDEX IF NOT EXISTS idx_rel_src ON graph_relation(kb_id, source_id)",
            """
            CREATE TABLE IF NOT EXISTS entity_chunk (
                entity_id INTEGER NOT NULL,
                chunk_id INTEGER NOT NULL,
                PRIMARY KEY (entity_id, chunk_id)
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_ec_chunk ON entity_chunk(chunk_id)",
            """
            CREATE TABLE IF NOT EXISTS vectors (
                chunk_id INTEGER PRIMARY KEY,
                kb_id INTEGER NOT NULL,
                dim INTEGER NOT NULL,
                vec BLOB NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_vectors_kb ON vectors(kb_id)",
            "CREATE TABLE IF NOT EXISTS settings (k TEXT PRIMARY KEY, v TEXT NOT NULL)"
    };

    @PostConstruct
    public void init() {
        for (String sql : DDL) {
            jdbc.execute(sql);
        }
        migrate();
        Path dataDir = Paths.get(props.getDataDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dataDir);
        } catch (Exception e) {
            throw new IllegalStateException("无法创建数据目录 " + dataDir, e);
        }
        log.info("数据库初始化完成，数据目录: {}", dataDir);
    }

    /** 旧库升级：为已有表补充新增列（SQLite 无 IF NOT EXISTS 加列，需探测） */
    private void migrate() {
        addColumnIfMissing("model_provider", "capabilities", "TEXT DEFAULT 'text'");
        addColumnIfMissing("chat_session", "model_provider_id", "INTEGER");
        addColumnIfMissing("category", "sort_order", "INTEGER DEFAULT 0");
    }

    private void addColumnIfMissing(String table, String column, String ddl) {
        boolean exists = false;
        try {
            List<java.util.Map<String, Object>> cols = jdbc.queryForList("PRAGMA table_info(" + table + ")");            for (java.util.Map<String, Object> row : cols) {
                if (column.equals(String.valueOf(row.get("name")))) {
                    exists = true;
                    break;
                }
            }
        } catch (Exception e) {
            // 表不存在则跳过
            return;
        }
        if (!exists) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddl);
            log.info("已为表 {} 补充列 {}", table, column);
        }
    }
}
