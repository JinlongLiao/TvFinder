package io.github.jnlongliao.tv.finder;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 按文件扩展名分类，忽略大小写；不读取文件正文，不把扩展名作为内容安全证明。
 */
public final class FileTypeResolver {
    /**
     * 类初始化时构造的只读映射，可安全供 UI 与工作线程并发查询。
     */
    private static final Map<String, FileCategory> EXTENSION_CATEGORIES = createExtensionCategories();

    /**
     * 禁止实例化，无可变的运行期状态。
     */
    private FileTypeResolver() {
    }

    /**
     * 文件夹优先于扩展名；复合归档后缀由末段扩展名归类，未知类型回退普通文件。
     *
     * @param fileName  文件名，不含父目录；null 和空字符串视为未知类型
     * @param directory true 表示目录，目录名即使以 .mp4 结尾也显示文件夹
     * @return 非空文件类别
     */
    public static FileCategory resolveFileCategory(String fileName, boolean directory) {
        if (directory) {
            return FileCategory.FOLDER;
        }
        if (Objects.isNull(fileName)) {
            return FileCategory.OTHER;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return FileCategory.OTHER;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        FileCategory category = EXTENSION_CATEGORIES.get(extension);
        return Objects.nonNull(category) ? category : FileCategory.OTHER;
    }

    /**
     * 为已知约 140 种扩展名预分配容量，所有类别登记完后冻结映射。
     */
    private static Map<String, FileCategory> createExtensionCategories() {
        Map<String, FileCategory> categories = new HashMap<>(256);
        registerFileExtensions(categories, FileCategory.PACKAGE,
            "apk aab apks xapk exe msi msix appx rpm deb dmg pkg appimage bin run");
        registerFileExtensions(categories, FileCategory.DOCUMENT, "doc docx docm dot dotx odt rtf wps pages");
        registerFileExtensions(categories, FileCategory.SPREADSHEET, "xls xlsx xlsm xlsb xlt xltx ods csv tsv et numbers");
        registerFileExtensions(categories, FileCategory.PRESENTATION, "ppt pptx pptm pps ppsx pot potx odp dps key");
        registerFileExtensions(categories, FileCategory.PDF, "pdf");
        registerFileExtensions(categories, FileCategory.TEXT, "txt text log md markdown readme nfo");
        registerFileExtensions(categories, FileCategory.AUDIO,
            "mp3 flac wav wave aac m4a ogg oga opus wma ape aiff aif alac amr mid midi mka dsd dsf dff");
        registerFileExtensions(categories, FileCategory.VIDEO,
            "mp4 mkv avi mov m4v ts mts m2ts webm flv wmv mpg mpeg vob 3gp rm rmvb asf ogv");
        registerFileExtensions(categories, FileCategory.IMAGE,
            "jpg jpeg png gif bmp webp heic heif avif tif tiff svg ico raw dng psd");
        registerFileExtensions(categories, FileCategory.ARCHIVE, "zip rar 7z tar gz gzip bz2 xz tgz tbz2 zst lz cab jar");
        registerFileExtensions(categories, FileCategory.CODE,
            "json xml yaml yml toml ini conf cfg properties java kt kts groovy py js tsx jsx html htm css scss c cpp h cs go rs sh bat ps1 sql");
        registerFileExtensions(categories, FileCategory.SUBTITLE, "srt ass ssa vtt sub idx lrc");
        registerFileExtensions(categories, FileCategory.BOOK, "epub mobi azw azw3 fb2 cbz cbr");
        registerFileExtensions(categories, FileCategory.DISK_IMAGE, "iso img nrg vhd vhdx vmdk qcow2");
        return Collections.unmodifiableMap(categories);
    }

    /**
     * 仅类初始化时调用，将固定的空格分隔扩展名登记到给定类别。
     */
    private static void registerFileExtensions(Map<String, FileCategory> categories, FileCategory category,
                                               String extensions) {
        for (String extension : extensions.split(" ")) {
            categories.put(extension, category);
        }
    }
}
