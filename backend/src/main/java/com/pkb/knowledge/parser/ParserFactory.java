package com.pkb.knowledge.parser;

import com.pkb.util.TextUtil;

import java.nio.file.Path;

/**
 * 按扩展名选择解析器。
 */
public final class ParserFactory {

    private ParserFactory() {
    }

    public static DocumentParser forFile(String fileName) {
        String ext = TextUtil.extOf(fileName);
        return switch (ext) {
            case "txt", "md", "markdown" -> new TextParser();
            case "pdf" -> new PdfParser();
            case "docx", "doc" -> new DocxParser();
            default -> null;
        };
    }

    public static boolean supported(String fileName) {
        return forFile(fileName) != null;
    }
}
