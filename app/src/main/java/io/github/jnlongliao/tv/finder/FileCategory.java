package io.github.jnlongliao.tv.finder;

/**
 * 文件卡片的稳定展示类别，图标仅表达类型，不承诺 Android 能执行或打开该文件。
 */
public enum FileCategory {
    /**
     * 可进入浏览的文件夹。
     */
    FOLDER(R.drawable.folder_icon, R.string.folder_type),
    /**
     * APK、EXE、RPM 等安装包或程序文件。
     */
    PACKAGE(R.drawable.package_icon, R.string.type_package),
    /**
     * DOC、DOCX、ODT 等文字处理文档。
     */
    DOCUMENT(R.drawable.document_icon, R.string.type_document),
    /**
     * XLS、XLSX、CSV 等表格与表格数据。
     */
    SPREADSHEET(R.drawable.spreadsheet_icon, R.string.type_spreadsheet),
    /**
     * PPT、PPTX、ODP 等演示文稿。
     */
    PRESENTATION(R.drawable.presentation_icon, R.string.type_presentation),
    /**
     * PDF 文档。
     */
    PDF(R.drawable.pdf_icon, R.string.type_pdf),
    /**
     * TXT、LOG、MD 等普通文本。
     */
    TEXT(R.drawable.text_icon, R.string.type_text),
    /**
     * MP3、FLAC、WAV 等音频文件。
     */
    AUDIO(R.drawable.audio_icon, R.string.type_audio),
    /**
     * MP4、MKV、TS 等视频文件。
     */
    VIDEO(R.drawable.video_icon, R.string.type_video),
    /**
     * JPG、PNG、HEIC、SVG 等图片。
     */
    IMAGE(R.drawable.image_icon, R.string.type_image),
    /**
     * ZIP、RAR、7Z、TAR 等压缩归档。
     */
    ARCHIVE(R.drawable.archive_icon, R.string.type_archive),
    /**
     * JSON、XML、JAVA、PY 等配置和源代码。
     */
    CODE(R.drawable.code_icon, R.string.type_code),
    /**
     * SRT、ASS、VTT 等视频字幕。
     */
    SUBTITLE(R.drawable.subtitle_icon, R.string.type_subtitle),
    /**
     * EPUB、MOBI、AZW 等电子书。
     */
    BOOK(R.drawable.book_icon, R.string.type_book),
    /**
     * ISO、IMG、VHD 等磁盘镜像。
     */
    DISK_IMAGE(R.drawable.disk_icon, R.string.type_disk_image),
    /**
     * 未知扩展名或无扩展名文件。
     */
    OTHER(R.drawable.file_icon, R.string.type_other);

    /**
     * 自绘矢量图标资源，在不同电视密度下保持清晰。
     */
    public final int iconResource;
    /**
     * 可翻译的类别名称资源，用于卡片说明。
     */
    public final int labelResource;

    /**
     * 绑定类别的不可变展示资源。
     *
     * @param iconResource  矢量图标资源 ID
     * @param labelResource 字符串资源 ID
     */
    FileCategory(int iconResource, int labelResource) {
        this.iconResource = iconResource;
        this.labelResource = labelResource;
    }
}
