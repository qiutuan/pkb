package com.pkb.knowledge.parser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** HTML/HTM：提取正文文本（去除脚本/样式/导航/页脚） */
public class HtmlParser implements DocumentParser {

    @Override
    public String parse(Path file) throws IOException {
        String html = Files.readString(file, StandardCharsets.UTF_8);
        if (!html.contains("charset") || html.indexOf('\uFFFD') >= 0) {
            // 尝试按文件内声明的编码读取
            html = readWithDetectedCharset(file, html);
        }
        Document doc = Jsoup.parse(html, file.toUri().toString());
        doc.select("script,style,noscript,iframe,nav,footer,header,aside").remove();
        Element body = doc.body();
        if (body == null) {
            return "";
        }
        String title = doc.title();
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append("# ").append(title.trim()).append("\n\n");
        }
        sb.append(extractText(body));
        return sb.toString().trim();
    }

    private String readWithDetectedCharset(Path file, String fallback) throws IOException {
        try {
            Document d = Jsoup.parse(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
            String cs = d.charset().name();
            return new String(Files.readAllBytes(file), cs);
        } catch (Exception e) {
            return fallback;
        }
    }

    private String extractText(Node node) {
        StringBuilder sb = new StringBuilder();
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode tn) {
                String t = tn.text();
                if (t != null && !t.isBlank()) {
                    sb.append(t.trim()).append("\n");
                }
            } else if (child instanceof Element el) {
                String tag = el.tagName();
                if ("p".equals(tag) || "div".equals(tag) || "li".equals(tag) || "tr".equals(tag) || "h1".equals(tag)
                        || "h2".equals(tag) || "h3".equals(tag) || "h4".equals(tag) || "br".equals(tag)
                        || "table".equals(tag) || "blockquote".equals(tag) || "pre".equals(tag) || "section".equals(tag)) {
                    sb.append("\n");
                }
                if ("h1".equals(tag)) {
                    sb.append("# ");
                } else if ("h2".equals(tag)) {
                    sb.append("## ");
                } else if ("h3".equals(tag)) {
                    sb.append("### ");
                } else if ("td".equals(tag) || "th".equals(tag)) {
                    sb.append("| ");
                } else if ("tr".equals(tag)) {
                    sb.append("\n");
                }
                sb.append(extractText(el));
                if ("td".equals(tag) || "th".equals(tag)) {
                    sb.append(" ");
                }
            }
        }
        return sb.toString();
    }
}
