package com.pkb.knowledge.parser;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Word 解析器：docx（POI XWPF）与 doc（POI HWPF）。
 */
public class DocxParser implements DocumentParser {

    @Override
    public String parse(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".doc")) {
            return parseDoc(file);
        }
        return parseDocx(file);
    }

    private String parseDocx(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = Files.newInputStream(file);
             XWPFDocument doc = new XWPFDocument(in)) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) {
                    sb.append(t).append('\n');
                }
            }
            for (XWPFTable table : doc.getTables()) {
                table.getRows().forEach(row -> {
                    row.getTableCells().forEach(cell -> {
                        String t = cell.getText();
                        if (t != null && !t.isBlank()) {
                            sb.append(t).append('\t');
                        }
                    });
                    sb.append('\n');
                });
            }
        }
        return sb.toString();
    }

    private String parseDoc(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file);
             HWPFDocument doc = new HWPFDocument(in);
             WordExtractor extractor = new WordExtractor(doc)) {
            return extractor.getText();
        }
    }
}
