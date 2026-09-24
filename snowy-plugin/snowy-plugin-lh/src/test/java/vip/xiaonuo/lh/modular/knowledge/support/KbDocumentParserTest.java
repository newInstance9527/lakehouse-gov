package vip.xiaonuo.lh.modular.knowledge.support;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import vip.xiaonuo.common.exception.CommonException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbDocumentParserTest {

    @Test
    void extensionOf_normalizes() {
        assertEquals("pdf", KbDocumentParser.extensionOf("a.PDF"));
        assertEquals("docx", KbDocumentParser.extensionOf("报告.docx"));
        assertEquals(null, KbDocumentParser.extensionOf("noext"));
    }

    @Test
    void normalizeExtracted_collapsesBlankLines() {
        String out = KbDocumentParser.normalizeExtracted("a  \n\n\n  b\r\nc");
        assertEquals("a\n\nb\nc", out);
    }

    /**
     * 回归：Tika 2.9 编译期依赖 TarArchiveEntry getNextEntry()；
     * classpath 若落到 commons-compress 1.21 及以下会 NoSuchMethodError。
     */
    @Test
    void classpath_commonsCompress_getNextEntry_returnsTarArchiveEntry() throws Exception {
        Method m = TarArchiveInputStream.class.getMethod("getNextEntry");
        assertEquals(TarArchiveEntry.class, m.getReturnType(),
                "期望 commons-compress>=1.22（与 Tika 2.9 对齐），实际返回类型=" + m.getReturnType().getName());
    }

    @Test
    void parse_txt_extractsRealText() {
        byte[] bytes = "GMV 口径说明\n含税不含退货".getBytes(StandardCharsets.UTF_8);
        KbDocumentParser.ParseResult r = KbDocumentParser.parse(
                "gmv.txt", bytes.length, new ByteArrayInputStream(bytes));
        assertTrue(r.text().contains("GMV 口径说明"));
        assertTrue(r.text().contains("含税不含退货"));
        assertEquals("txt", r.ext());
        assertTrue(r.charCount() > 0);
    }

    @Test
    void parse_html_extractsRealText() {
        byte[] bytes = "<html><body><p>湖仓知识库上传</p></body></html>".getBytes(StandardCharsets.UTF_8);
        KbDocumentParser.ParseResult r = KbDocumentParser.parse(
                "kb.html", bytes.length, new ByteArrayInputStream(bytes));
        assertTrue(r.text().contains("湖仓知识库上传"));
        assertEquals("html", r.ext());
    }

    @Test
    void parse_docx_extractsRealText() throws Exception {
        byte[] bytes = minimalDocx("口径说明 DOCX");
        KbDocumentParser.ParseResult r = KbDocumentParser.parse(
                "口径.docx", bytes.length, new ByteArrayInputStream(bytes));
        assertTrue(r.text().contains("口径说明 DOCX"));
        assertEquals("docx", r.ext());
    }

    @Test
    void parse_rejectsUnsupportedExt() {
        byte[] bytes = "x".getBytes(StandardCharsets.UTF_8);
        CommonException ex = assertThrows(CommonException.class, () ->
                KbDocumentParser.parse("x.xlsx", bytes.length, new ByteArrayInputStream(bytes)));
        assertTrue(ex.getMessage().contains("不支持"));
    }

    @Test
    void parse_rejectsEmptyBody() {
        byte[] bytes = "   \n\t  ".getBytes(StandardCharsets.UTF_8);
        assertThrows(CommonException.class, () ->
                KbDocumentParser.parse("empty.txt", bytes.length, new ByteArrayInputStream(bytes)));
    }

    /** 最小可解析 OOXML docx（ZIP + word/document.xml） */
    private static byte[] minimalDocx(String bodyText) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zos.write(("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml"
                        ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("_rels/.rels"));
            zos.write(("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                        Target="word/document.xml"/>
                    </Relationships>
                    """).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("word/document.xml"));
            String doc = """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>%s</w:t></w:r></w:p></w:body>
                    </w:document>
                    """.formatted(bodyText);
            zos.write(doc.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }
}
