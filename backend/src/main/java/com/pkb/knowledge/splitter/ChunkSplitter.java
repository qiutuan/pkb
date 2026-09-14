package com.pkb.knowledge.splitter;

import java.util.List;

/**
 * 分块策略接口。
 */
public interface ChunkSplitter {

    List<String> split(String text);
}
