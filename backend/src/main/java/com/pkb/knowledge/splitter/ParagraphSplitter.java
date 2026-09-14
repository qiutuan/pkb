package com.pkb.knowledge.splitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 按段落分块：以空行/换行划分段落，小段落合并到大段落阈值，超长段落内部再按固定长度切分。
 */
public class ParagraphSplitter implements ChunkSplitter {

    private final int size;
    private final int overlap;

    public ParagraphSplitter(int size, int overlap) {
        this.size = Math.max(100, size);
        this.overlap = Math.min(Math.max(0, overlap), this.size / 2);
    }

    @Override
    public List<String> split(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String clean = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] paragraphs = clean.split("\\n\\s*\\n");
        StringBuilder buf = new StringBuilder();
        for (String raw : paragraphs) {
            String p = raw.trim();
            if (p.isEmpty()) {
                continue;
            }
            // 单段超过阈值：先落盘缓冲，再切分该段
            if (p.length() > size) {
                flush(buf, out);
                out.addAll(new FixedSizeSplitter(size, overlap).split(p));
                continue;
            }
            if (buf.length() + p.length() + 1 > size) {
                flush(buf, out);
            }
            if (buf.length() > 0) {
                buf.append('\n');
            }
            buf.append(p);
        }
        flush(buf, out);
        return out;
    }

    private void flush(StringBuilder buf, List<String> out) {
        String s = buf.toString().trim();
        if (!s.isEmpty()) {
            out.add(s);
        }
        buf.setLength(0);
    }
}
