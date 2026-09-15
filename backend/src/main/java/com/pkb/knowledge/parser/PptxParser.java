package com.pkb.knowledge.parser;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** PPTX：逐页抽取文本框内容转文本 */
public class PptxParser implements DocumentParser {

    @Override
    public String parse(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file); XMLSlideShow ppt = new XMLSlideShow(in)) {
            StringBuilder sb = new StringBuilder();
            int idx = 1;
            for (XSLFSlide slide : ppt.getSlides()) {
                sb.append("\n## 第 ").append(idx).append(" 页\n");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape ts) {
                        String t = ts.getText();
                        if (t != null && !t.isBlank()) {
                            sb.append(t).append("\n");
                        }
                    }
                }
                idx++;
            }
            return sb.toString();
        } catch (Exception e) {
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("PPT 解析失败：" + e.getMessage(), e);
        }
    }
}
