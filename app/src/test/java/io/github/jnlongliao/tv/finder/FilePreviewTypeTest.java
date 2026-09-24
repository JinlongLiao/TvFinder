package io.github.jnlongliao.tv.finder;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 验证应用内预览类型只由明确支持的扩展名选择。 */
public final class FilePreviewTypeTest {
    /** 支持类型与未知类型分别进入预览和外部打开分支。 */
    @Test
    public void resolvesSupportedAndUnsupportedFiles() {
        assertEquals(FilePreviewType.AUDIO, FilePreviewType.resolveFilePreviewType("song.MP3"));
        assertEquals(FilePreviewType.VIDEO, FilePreviewType.resolveFilePreviewType("movie.mp4"));
        assertEquals(FilePreviewType.PDF, FilePreviewType.resolveFilePreviewType("manual.pdf"));
        assertEquals(FilePreviewType.TEXT, FilePreviewType.resolveFilePreviewType("notes.txt"));
        assertEquals(FilePreviewType.MARKDOWN, FilePreviewType.resolveFilePreviewType("README.md"));
        assertEquals(FilePreviewType.HTML, FilePreviewType.resolveFilePreviewType("index.HTML"));
        assertEquals(FilePreviewType.UNSUPPORTED, FilePreviewType.resolveFilePreviewType("archive.zip"));
        assertEquals(FilePreviewType.UNSUPPORTED, FilePreviewType.resolveFilePreviewType(null));
    }

    /** 更多常见音视频容器和可读文本不应错误落入未知类型。 */
    @Test
    public void recognizesMainstreamMediaAndTextFormats() {
        assertEquals(FilePreviewType.AUDIO, FilePreviewType.resolveFilePreviewType("music.opus"));
        assertEquals(FilePreviewType.AUDIO, FilePreviewType.resolveFilePreviewType("music.wma"));
        assertEquals(FilePreviewType.VIDEO, FilePreviewType.resolveFilePreviewType("movie.avi"));
        assertEquals(FilePreviewType.VIDEO, FilePreviewType.resolveFilePreviewType("movie.m2ts"));
        assertEquals(FilePreviewType.TEXT, FilePreviewType.resolveFilePreviewType("data.json"));
        assertEquals(FilePreviewType.TEXT, FilePreviewType.resolveFilePreviewType("subtitle.srt"));
        assertEquals(FilePreviewType.HTML, FilePreviewType.resolveFilePreviewType("page.xhtml"));
    }
}
