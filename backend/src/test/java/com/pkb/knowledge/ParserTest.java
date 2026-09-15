package com.pkb.knowledge;

import com.pkb.knowledge.parser.ChunkedParser;
import com.pkb.knowledge.parser.DocumentParser;
import com.pkb.knowledge.parser.ParserFactory;
import com.pkb.knowledge.parser.TableParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 表格与格式解析测试：CSV 逐行 JSON / Markdown 表头保留 / 大表分批 / HTML 正文提取 */
class ParserTest {

    private Path write(String name, String content) throws Exception {
        Path p = Files.createTempFile("pkb-", "-" + name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void csvJsonLines() throws Exception {
        Path f = write("grades.csv", "姓名,科目,成绩\n张三,数学,95\n李四,数学,88\n");
        DocumentParser p = ParserFactory.forFile("grades.csv");
        assertInstanceOf(TableParser.class, p);
        List<String> rows = ((ChunkedParser) p).parseChunks(f);
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).contains("\"姓名\":\"张三\""));
        assertTrue(rows.get(0).contains("\"成绩\":\"95\""));
    }

    @Test
    void csvMarkdownKeepsHeader() throws Exception {
        Path f = write("grades.csv", "姓名,科目,成绩\n张三,数学,95\n");
        String text = ParserFactory.forFile("grades.csv").parse(f);
        assertTrue(text.contains("| 姓名 | 科目 | 成绩 |"));
        assertTrue(text.contains("| --- |"));
        assertTrue(text.contains("| 张三 | 数学 | 95 |"));
    }

    @Test
    void csvLargeSheetBatched() throws Exception {
        StringBuilder sb = new StringBuilder("姓名,科目,成绩\n");
        for (int i = 0; i < TableParser.BATCH_ROWS + 50; i++) {
            sb.append("学生").append(i).append(",数学,").append(i).append("\n");
        }
        Path f = write("big.csv", sb.toString());
        String text = ParserFactory.forFile("big.csv").parse(f);
        // 超过 1000 行分两批，每批保留表头
        long headerCount = text.split("\\| 姓名 \\| 科目 \\| 成绩 \\|").length - 1;
        assertEquals(2, headerCount);
    }

    @Test
    void csvQuotedAndBom() throws Exception {
        Path f = write("q.csv", "\uFEFF\"姓,名\",备注\n\"张,三\",\"含\"\"引号\"\n");
        List<String> rows = ((ChunkedParser) ParserFactory.forFile("q.csv")).parseChunks(f);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).contains("张,三"));
    }

    @Test
    void htmlBodyExtract() throws Exception {
        Path f = write("a.html", "<html><head><title>标题T</title></head><body><h1>一级</h1><p>正文内容。</p><script>var x=1;</script></body></html>");
        String text = ParserFactory.forFile("a.html").parse(f);
        assertTrue(text.contains("标题T"));
        assertTrue(text.contains("正文内容"));
        assertFalse(text.contains("var x"));
    }

    @Test
    void unsupportedType() {
        assertNull(ParserFactory.forFile("a.xyz"));
        assertFalse(ParserFactory.supported("a.xyz"));
        assertTrue(ParserFactory.supported("a.xlsx"));
        assertTrue(ParserFactory.supported("a.csv"));
        assertTrue(ParserFactory.supported("a.pptx"));
        assertTrue(ParserFactory.supported("a.html"));
        assertTrue(ParserFactory.supported("a.epub"));
    }
}
