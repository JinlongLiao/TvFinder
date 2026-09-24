package io.github.jnlongliao.tv.finder;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从常见 OOXML 文档、表格和演示文稿的内容部件提取有限纯文字，供电视快速预览。
 * 不执行宏、不展开外部实体，也不解析排版或公式；总输入和输出均受调用方上限约束。
 *
 * @see <a href="https://www.ecma-international.org/publications-and-standards/standards/ecma-376/">ECMA-376 Office Open XML</a>
 */
public final class OfficeTextPreview {
    /** 最多扫描的压缩包条目数量，防止异常压缩包拖慢预览。 */
    private static final int MAX_ENTRIES = 1000;
    /** 最多解析的内容部件数量，防止多页文档拖慢预览。 */
    private static final int MAX_PARTS = 200;

    /** 禁止实例化纯解析工具。 */
    private OfficeTextPreview() {
    }

    /**
     * 有界提取常见 Office 新格式的纯文字。超出限制时保留已提取内容并标记截断。
     *
     * @param file Office ZIP 文件
     * @param extension 已核对的 OOXML 文件后缀
     * @param limitBytes 允许读取的 XML 总字节数及最大输出字符数
     * @return 纯文字内容，空文档返回说明文字
     * @throws IOException 文件损坏、格式不符或 XML 无法解析
     */
    public static String readOfficeText(File file, String extension, int limitBytes) throws IOException {
        if (limitBytes <= 0) {
            throw new IllegalArgumentException("limitBytes must be positive");
        }
        StringBuilder output = new StringBuilder();
        int remaining = limitBytes;
        int parts = 0;
        int scanned = 0;
        try (ZipFile zipFile = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements() && scanned < MAX_ENTRIES && parts < MAX_PARTS && remaining > 0
                && output.length() < limitBytes) {
                ZipEntry entry = entries.nextElement();
                scanned++;
                if (!isContentPart(extension, entry.getName())) {
                    continue;
                }
                parts++;
                byte[] xml;
                try (InputStream input = zipFile.getInputStream(entry)) {
                    xml = readLimitedBytes(input, remaining + 1);
                }
                if (xml.length > remaining) {
                    remaining = 0;
                    break;
                }
                remaining -= xml.length;
                if (containsDoctype(xml)) {
                    throw new IOException("Office XML 包含不允许的文档类型声明");
                }
                appendPartText(xml, output, limitBytes);
            }
        }
        if (parts == 0) {
            throw new IOException("未找到 Office 正文部件");
        }
        if (output.length() == 0) {
            return remaining == 0 ? "（正文超出预览上限，内容已截断）" : "（文档没有可提取的纯文字）";
        }
        if (remaining == 0 || parts == MAX_PARTS || scanned == MAX_ENTRIES
            || output.length() >= limitBytes) {
            output.append("\n\n…内容已截断");
        }
        return output.toString();
    }

    /** 仅选择对应格式的正文、工作表和幻灯片，忽略关系文件、宏及嵌入对象。 */
    private static boolean isContentPart(String extension, String name) {
        if ("docx".equals(extension) || "docm".equals(extension)) {
            return "word/document.xml".equals(name);
        }
        if ("xlsx".equals(extension) || "xlsm".equals(extension)) {
            return "xl/sharedStrings.xml".equals(name)
                || (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"));
        }
        return ("pptx".equals(extension) || "pptm".equals(extension) || "ppsx".equals(extension))
            && name.startsWith("ppt/slides/slide")
            && name.endsWith(".xml");
    }

    /** 逐块读取，超过剩余预算时停止，压缩数据不会无限膨胀到内存。 */
    private static byte[] readLimitedBytes(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        while (output.size() < limit) {
            int count = input.read(buffer, 0, Math.min(buffer.length, limit - output.size()));
            if (count < 0) {
                break;
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    /** 拒绝 DTD，避免解析器处理文档内定义或外部实体。 */
    private static boolean containsDoctype(byte[] xml) {
        String text = new String(xml, StandardCharsets.UTF_8);
        return text.contains("<!DOCTYPE") || text.contains("<!ENTITY");
    }

    /** 只收集 OOXML 文本节点；表格数值节点也展示为纯文字。 */
    private static void appendPartText(byte[] xml, StringBuilder output, int limit)
        throws IOException {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new ByteArrayInputStream(xml), "UTF-8");
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT && output.length() < limit) {
                if (event != XmlPullParser.START_TAG) {
                    continue;
                }
                String name = parser.getName();
                if ("t".equals(name) || "v".equals(name)) {
                    String value = parser.nextText();
                    if (!value.isEmpty()) {
                        output.append(value, 0, Math.min(value.length(), limit - output.length()));
                        output.append(' ');
                    }
                } else if ("p".equals(name) || "row".equals(name)) {
                    output.append('\n');
                }
            }
        } catch (XmlPullParserException exception) {
            throw new IOException("Office XML 内容无法解析", exception);
        }
    }
}
