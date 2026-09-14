package com.pkb.knowledge.splitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定长度分块：按字符数切分，支持重叠；切分点优先落在句子边界（中文句号/英文句点/换行）。
 */
public class FixedSizeSplitter implements ChunkSplitter {

    private static final String BOUNDARY = "。！？；.!?;\n";
    private static final int LOOKBACK = 120;

    private final int size;
    private final int overlap;

    public FixedSizeSplitter(int size, int overlap) {
        this.size = Math.max(50, size);
        this.overlap = Math.min(Math.max(0, overlap), this.size / 2);
    }

    @Override
    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String clean = text.replace("\r\n", "\n").replace('\r', '\n');
        int start = 0;
        int n = clean.length();
        while (start < n) {
            int end = Math.min(start + size, n);
            if (end < n) {
                int cut = findBoundary(clean, start, end);
                if (cut > start) {
                    end = cut;
                }
            }
            String chunk = clean.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= n) {
                break;
            }
            int next = end - overlap;
            start = Math.max(next, start + 1);
        }
        return chunks;
    }

    private int findBoundary(String s, int start, int end) {
        int from = Math.max(start, end - LOOKBACK);
        for (int i = end - 1; i >= from; i--) {
            if (BOUNDARY.indexOf(s.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return end;
    }
}
