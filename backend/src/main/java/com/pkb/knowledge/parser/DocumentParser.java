package com.pkb.knowledge.parser;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 文档解析器：把文档内容抽取为纯文本。
 */
public interface DocumentParser {

    String parse(Path file) throws IOException;
}
