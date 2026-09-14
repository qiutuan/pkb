package com.pkb.knowledge.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;

/**
 * PDF 文本抽取（PDFBox）。
 */
public class PdfParser implements DocumentParser {

    @Override
    public String parse(Path file) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }
}
