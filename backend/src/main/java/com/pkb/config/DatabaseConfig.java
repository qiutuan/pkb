package com.pkb.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 嵌入式元数据存储：SQLite（WAL 模式）。
 * 无论向量模式是 embedded 还是 pgvector，业务元数据（知识库/文档/片段/会话/图谱）都存储在本机 SQLite，保证数据本地优先。
 */
@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    @Bean
    public DataSource dataSource(PkbProperties props) throws IOException {
        Path dataDir = Paths.get(props.getDataDir()).toAbsolutePath().normalize();
        Files.createDirectories(dataDir);
        Files.createDirectories(dataDir.resolve("files"));

        String dbPath = dataDir.resolve("pkb.db").toString();
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(15_000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setCacheSize(64 * 1024);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + dbPath);

        log.info("元数据存储: {} (SQLite, WAL)", dbPath);
        return ds;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
