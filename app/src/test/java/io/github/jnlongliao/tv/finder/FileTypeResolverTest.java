package io.github.jnlongliao.tv.finder;

import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.assertEquals;

/** 验证用户指定的扩展名、大小写与未知文件回退，不依赖 Android 运行时。 */
public final class FileTypeResolverTest {
    /** 常见安装包都应显示安装包图标，而非暗示电视能执行桌面程序。 */
    @Test
    public void recognizesInstallationPackages() {
        assertFileExtensions(FileCategory.PACKAGE, "exe apk rpm deb msi xapk appimage");
    }

    /** Office、PDF 与普通文本应使用独立类别。 */
    @Test
    public void separatesMainstreamDocumentTypes() {
        assertFileExtensions(FileCategory.DOCUMENT, "doc docx docm odt rtf");
        assertFileExtensions(FileCategory.SPREADSHEET, "xls xlsx csv ods");
        assertFileExtensions(FileCategory.PRESENTATION, "ppt pptx ppsx odp");
        assertFileExtensions(FileCategory.PDF, "pdf");
        assertFileExtensions(FileCategory.TEXT, "txt md log");
    }

    /** 音频与视频分开，避免把 MP4 归入音频。 */
    @Test
    public void separatesAudioVideoAndImages() {
        assertFileExtensions(FileCategory.AUDIO, "mp3 flac wav m4a aac ogg opus ape");
        assertFileExtensions(FileCategory.VIDEO, "mp4 mkv avi ts m2ts mov webm");
        assertFileExtensions(FileCategory.IMAGE, "jpg jpeg png heic webp svg");
    }

    /** 归档、字幕、代码、电子书和磁盘镜像具有明确回退范围。 */
    @Test
    public void recognizesOtherCommonTypes() {
        assertFileExtensions(FileCategory.ARCHIVE, "zip rar 7z tar.gz tar.xz");
        assertFileExtensions(FileCategory.SUBTITLE, "srt ass vtt");
        assertFileExtensions(FileCategory.CODE, "json yaml java py html");
        assertFileExtensions(FileCategory.BOOK, "epub mobi azw3");
        assertFileExtensions(FileCategory.DISK_IMAGE, "iso img vhdx");
    }

    /** 文件夹、隐藏文件、未知与无扩展名文件不应被误判。 */
    @Test
    public void handlesDirectoryAndUnknownExtensions() {
        assertEquals(FileCategory.FOLDER, FileTypeResolver.resolveFileCategory("movie.mp4", true));
        for (String name : new String[]{null, "", "README", ".profile", "file.", "report.unknown"}) {
            assertEquals(FileCategory.OTHER, FileTypeResolver.resolveFileCategory(name, false));
        }
    }

    /** 本机系统为土耳其语时大写 I 也必须按扩展名规则映射为 ASCII i。 */
    @Test
    public void classificationDoesNotDependOnSystemLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(FileCategory.VIDEO, FileTypeResolver.resolveFileCategory("VIDEO.AVI", false));
        } finally {
            Locale.setDefault(original);
        }
    }

    /** 同时验证小写、大写和含中文及多点名称的匹配结果。 */
    private void assertFileExtensions(FileCategory expected, String extensions) {
        for (String extension : extensions.split(" ")) {
            assertEquals(extension, expected, FileTypeResolver.resolveFileCategory("文件.v1." + extension, false));
            assertEquals(extension, expected, FileTypeResolver.resolveFileCategory("FILE."
                    + extension.toUpperCase(Locale.ROOT), false));
        }
    }
}
