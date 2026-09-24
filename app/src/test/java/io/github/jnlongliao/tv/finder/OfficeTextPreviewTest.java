package io.github.jnlongliao.tv.finder;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 验证 Office 新格式的只读文字提取、格式边界和有界读取。 */
@RunWith(RobolectricTestRunner.class)
public final class OfficeTextPreviewTest {
    /** 测试压缩包均在独立临时目录创建。 */
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** DOCX 正文可读，关系文件中的文字不应混入预览。 */
    @Test
    public void extractsOnlyDocumentBody() throws Exception {
        File document = createOfficeFile("docx", "word/document.xml",
            "<w:document xmlns:w=\"urn:test\"><w:p><w:t>电视文档</w:t></w:p></w:document>");
        String text = OfficeTextPreview.readOfficeText(document, "docx", 4096);
        assertTrue(text.contains("电视文档"));
        assertFalse(text.contains("ignore me"));
    }

    /** XLSX 单元格文字和数值、PPTX 幻灯片文字都能展示。 */
    @Test
    public void extractsSpreadsheetAndSlideText() throws Exception {
        File spreadsheet = createOfficeFile("xlsx", "xl/worksheets/sheet1.xml",
            "<worksheet><row><c><v>42</v></c><c><t>收入</t></c></row></worksheet>");
        File slides = createOfficeFile("pptx", "ppt/slides/slide1.xml",
            "<p:sld xmlns:p=\"urn:p\" xmlns:a=\"urn:a\"><a:p><a:t>首页</a:t></a:p></p:sld>");
        assertTrue(OfficeTextPreview.readOfficeText(spreadsheet, "xlsx", 4096).contains("42"));
        assertTrue(OfficeTextPreview.readOfficeText(slides, "pptx", 4096).contains("首页"));
    }

    /** 超过输入预算时不解析残缺 XML，也不无限展开压缩内容。 */
    @Test
    public void stopsBeforeOversizedXmlPart() throws Exception {
        File document = createOfficeFile("docx", "word/document.xml",
            "<document><t>" + "A".repeat(8192) + "</t></document>");
        String text = OfficeTextPreview.readOfficeText(document, "docx", 256);
        assertTrue(text.contains("截断"));
    }

    /** 带文档类型声明的内容不能进入 XML 解析器。 */
    @Test
    public void rejectsDocumentTypeDeclarations() throws Exception {
        File document = createOfficeFile("docx", "word/document.xml",
            "<!DOCTYPE document [<!ENTITY x SYSTEM \"file:///private\">]><document><t>&x;</t></document>");
        assertThrows(java.io.IOException.class,
            () -> OfficeTextPreview.readOfficeText(document, "docx", 4096));
    }

    /** 为指定正文部件创建最小 OOXML 压缩包。 */
    private File createOfficeFile(String extension, String entryName, String xml) throws Exception {
        File file = temporaryFolder.newFile("sample." + extension);
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            output.putNextEntry(new ZipEntry("_rels/.rels"));
            output.write("ignore me".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry(entryName));
            output.write(xml.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return file;
    }
}
