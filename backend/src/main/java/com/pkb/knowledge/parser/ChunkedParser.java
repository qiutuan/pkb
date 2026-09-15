package com.pkb.knowledge.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 支持按行/按单元产出多条内容的解析器（用于表格「逐行 JSON」「Sheet 摘要 + 明细」策略）。
 * 普通策略下 parse() 仍可用（表格转文本）。
 */
public interface ChunkedParser extends DocumentParser {

    List<String> parseChunks(Path file) throws IOException;
}
