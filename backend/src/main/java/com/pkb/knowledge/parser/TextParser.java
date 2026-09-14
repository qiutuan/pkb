package com.pkb.knowledge.parser;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 纯文本解析器（txt / md），支持 UTF-8 与 GBK 回退。
 */
public class TextParser implements DocumentParser {

    @Override
    public String parse(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        // 优先 UTF-8，若解码出现过多替换符则回退 GBK
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (garbageRatio(utf8) < 0.02) {
            return utf8;
        }
        try {
            return new String(bytes, Charset.forName("GBK"));
        } catch (Exception e) {
            return utf8;
        }
    }

    private static double garbageRatio(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        int bad = 0;
        int n = Math.min(s.length(), 4000);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\uFFFD' || (c < 0x20 && c != '\n' && c != '\r' && c != '\t')) {
                bad++;
            }
        }
        return (double) bad / n;
    }
}
