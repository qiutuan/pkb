package com.pkb.settings;

import com.pkb.config.PkbProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SettingsServiceTest {

    @TempDir
    Path tmp;

    private PkbProperties props(Path dir) {
        PkbProperties p = new PkbProperties();
        p.setDataDir(dir.toString());
        return p;
    }

    @Test
    void defaultsReturnedWithoutFile() {
        SettingsService s = new SettingsService(props(tmp));
        Map<String, Object> all = s.all();
        assertNotNull(all.get(SettingsService.K_SYSTEM_PROMPT));
        assertNotNull(all.get(SettingsService.K_CHUNK_SIZE));
    }

    @Test
    void putAllThenLoadRoundTrip() throws Exception {
        SettingsService s = new SettingsService(props(tmp));
        Map<String, Object> overrides = new HashMap<>();
        overrides.put(SettingsService.K_CHUNK_SIZE, 800);
        overrides.put(SettingsService.K_RAG_TOP_K, 7);
        s.putAll(overrides);

        Path file = tmp.resolve("settings.yml");
        assertTrue(Files.exists(file));

        SettingsService s2 = new SettingsService(props(tmp));
        assertEquals(800, ((Number) s2.all().get(SettingsService.K_CHUNK_SIZE)).intValue());
        assertEquals(7, ((Number) s2.all().get(SettingsService.K_RAG_TOP_K)).intValue());
    }

    @Test
    void resetKeyRemovesOverride() {
        SettingsService s = new SettingsService(props(tmp));
        s.putAll(Map.of(SettingsService.K_CHUNK_SIZE, 800));
        s.resetKey(SettingsService.K_CHUNK_SIZE); // 恢复默认 = 删除覆盖项
        int after = ((Number) s.all().get(SettingsService.K_CHUNK_SIZE)).intValue();
        assertEquals(600, after); // 默认分块大小
    }

    @Test
    void corruptFileIgnoredAndDefaultsUsed() throws Exception {
        Files.writeString(tmp.resolve("settings.yml"), ":::not:yaml:[");
        SettingsService s = new SettingsService(props(tmp));
        assertNotNull(s.all().get(SettingsService.K_SYSTEM_PROMPT));
    }
}
