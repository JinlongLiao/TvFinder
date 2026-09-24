package io.github.jnlongliao.tv.finder;

import java.util.Locale;
import java.util.Objects;

/**
 * 应用内可直接预览的文件格式。扩展名仅用于选择预览器，实际解码仍由系统组件校验。
 */
public enum FilePreviewType {
    /** 尝试交给系统解码器的音频容器，不保证当前电视都可播放。 */
    AUDIO,
    /** 尝试交给系统解码器的视频容器，不保证当前电视都可播放。 */
    VIDEO,
    /** Android PdfRenderer 支持的 PDF。 */
    PDF,
    /** UTF-8 文本。 */
    TEXT,
    /** 按 CommonMark 排版的 UTF-8 Markdown。 */
    MARKDOWN,
    /** 可执行脚本并加载资源的本地 HTML。 */
    HTML,
    /** 不由应用内预览器处理的格式。 */
    UNSUPPORTED;

    /**
     * 按文件名选择预览器，不把文件类别图标误当作可解码能力。
     *
     * @param fileName 文件名，可以为 null
     * @return 预览类型；未知格式返回 UNSUPPORTED
     */
    public static FilePreviewType resolveFilePreviewType(String fileName) {
        if (Objects.isNull(fileName)) {
            return UNSUPPORTED;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return UNSUPPORTED;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (extension) {
            case "mp3": case "aac": case "m4a": case "wav": case "wave": case "ogg":
            case "oga": case "opus": case "flac": case "amr": case "wma": case "ape":
            case "aiff": case "aif": case "alac": case "mid": case "midi": case "mka":
            case "dsd": case "dsf": case "dff":
                return AUDIO;
            case "mp4": case "m4v": case "mkv": case "webm": case "3gp": case "3g2":
            case "ts": case "mts": case "m2ts": case "mov": case "avi": case "wmv":
            case "mpg": case "mpeg": case "flv": case "vob": case "ogv": case "asf":
            case "rm": case "rmvb":
                return VIDEO;
            case "pdf":
                return PDF;
            case "txt": case "text": case "log": case "nfo": case "csv": case "tsv":
            case "json": case "xml": case "yaml": case "yml": case "toml": case "ini":
            case "conf": case "cfg": case "properties": case "java": case "kt": case "kts":
            case "groovy": case "py": case "js": case "jsx": case "tsx":
            case "css": case "scss": case "c": case "cpp": case "h": case "cs":
            case "go": case "rs": case "sh": case "bat": case "ps1": case "sql":
            case "srt": case "ass": case "ssa": case "vtt": case "lrc":
                return TEXT;
            case "md": case "markdown":
                return MARKDOWN;
            case "html": case "htm": case "xhtml":
                return HTML;
            default:
                return UNSUPPORTED;
        }
    }
}
