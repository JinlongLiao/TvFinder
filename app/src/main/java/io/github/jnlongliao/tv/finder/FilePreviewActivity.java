package io.github.jnlongliao.tv.finder;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 遥控器可操作的应用内文件预览：音视频按需播放、PDF 逐页渲染、文本与网页查看。
 * USB 文件通过 seekable content URI 读取；外部打开时才复制到应用临时缓存，兼容仅接受普通文件的应用。
 * 页面销毁时释放播放器、WebView 和后台工作线程。
 *
 * @see <a href="https://developer.android.com/reference/android/graphics/pdf/PdfRenderer">Android PdfRenderer</a>
 * @see <a href="https://developer.android.com/reference/android/media/MediaPlayer">Android MediaPlayer</a>
 * @see <a href="https://github.com/commonmark/commonmark-java">commonmark-java</a>
 */
public final class FilePreviewActivity extends Activity {
    /** 预览文件 URI，仅接收本应用显式启动。 */
    private static final String EXTRA_URI = "preview_uri";
    /** 用户可见文件名。 */
    private static final String EXTRA_NAME = "preview_name";
    /** 本地 HTML 文件的父目录；USB 使用受限卷内路径。 */
    private static final String EXTRA_PARENT = "preview_parent";
    /** 文件是否由 USB FAT/exFAT provider 提供。 */
    private static final String EXTRA_USB = "preview_usb";
    /** 文本最多读取 2 MiB，避免超大文件挤占电视内存。 */
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    /** 外部打开缓存保留上限为一天，防止使用中的外部应用读到提前删除的文件。 */
    private static final long CACHE_RETENTION_MILLIS = 24L * 60 * 60 * 1000;
    /** 统一的预览后台工作线程。 */
    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor(
        runnable -> new Thread(runnable, "tv-file-preview"));
    /** 主线程上的媒体进度更新。 */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    /** 正在预览的 URI。 */
    private Uri fileUri;
    /** 文件显示名称。 */
    private String fileName;
    /** 本地 HTML 资源根目录。 */
    private String parentPath;
    /** USB 来源标记。 */
    private boolean usbSource;
    /** USB 页面销毁后继续持有的卷租约。 */
    private boolean usbPreviewRetained;
    /** 防止快速连按“其他应用”产生多个大文件副本。 */
    private boolean copyingForExternal;
    /** 当前预览类型。 */
    private FilePreviewType previewType;
    /** 内容区域，PDF 页面和错误提示复用。 */
    private FrameLayout contentFrame;
    /** 用户可见状态。 */
    private TextView statusTextView;
    /** 媒体播放器，页面销毁后不得继续播放。 */
    private MediaPlayer mediaPlayer;
    /** 媒体完成异步准备后才能读进度或暂停。 */
    private boolean mediaPrepared;
    /** 首次获得窗口焦点后自动开始，返回外部应用时不会擅自恢复播放。 */
    private boolean autoPlayPending = true;
    /** Surface 可能早于或晚于 MediaPlayer 初始化。 */
    private SurfaceHolder videoSurfaceHolder;
    /** 媒体播放进度控件。 */
    private SeekBar mediaSeekBar;
    /** 媒体播放/暂停按钮。 */
    private Button playbackButton;
    /** 网页渲染器，销毁时释放。 */
    private WebView webView;
    /** 已渲染的 PDF 页号。 */
    private int pdfPageIndex;
    /** PDF 页数，首轮渲染完成后更新。 */
    private int pdfPageCount;
    /** 当前显示的 PDF 位图，翻页和退出时释放。 */
    private Bitmap pdfBitmap;
    /** PDF 上一页按钮。 */
    private Button previousPageButton;
    /** PDF 下一页按钮。 */
    private Button nextPageButton;

    /**
     * 生成应用内显式预览 Intent；路径仅用于约束 HTML 同目录资源。
     * @param context 发起预览的页面
     * @param uri FileProvider 或 USB provider 的只读 URI
     * @param name 原始文件名
     * @param parent 本地文件已校验的父目录；USB 为 null
     * @param usb 是否来自 USB 直连卷
     * @return 仅指向本应用的预览 Intent
     */
    public static Intent createPreviewIntent(Context context, Uri uri, String name, String parent,
            boolean usb) {
        return new Intent(context, FilePreviewActivity.class).putExtra(EXTRA_URI, uri)
            .putExtra(EXTRA_NAME, name).putExtra(EXTRA_PARENT, parent).putExtra(EXTRA_USB, usb);
    }

    /**
     * 为已挂载的本地文件调用系统选择器；无可用应用时给出应用内提示。
     * @param activity 当前页面
     * @param uri 已授权只读 URI
     * @param name 原始文件名，用于 MIME 路由
     */
    public static void openFileWithOtherApplications(Activity activity, Uri uri, String name) {
        String mime = resolveFileMimeType(name);
        Intent viewIntent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(Intent.createChooser(viewIntent,
                activity.getString(R.string.preview_other_apps)));
        } catch (ActivityNotFoundException exception) {
            Log.e("TvFinderPreview", "外部应用打开失败 name=" + name + ", uri=" + uri
                + ", stage=chooser, type=" + exception.getClass().getName()
                + ", message=" + exception.getMessage(), exception);
            new android.app.AlertDialog.Builder(activity).setTitle(R.string.open_failed)
                .setMessage(R.string.preview_no_external_app).setPositiveButton(R.string.okay, null).show();
        }
    }

    /** {@inheritDoc} 语言与主题与文件管理页保持一致。 */
    @Override
    protected void attachBaseContext(Context context) {
        super.attachBaseContext(AppAppearance.applyThemeContext(AppLanguage.localizeAppContext(context)));
    }

    /** {@inheritDoc} 按扩展名选择预览器，所有大文件 I/O 均交给工作线程或系统解码器。 */
    @Override
    protected void onCreate(Bundle state) {
        setTheme(R.style.AppTheme);
        super.onCreate(state);
        fileUri = getIntent().getParcelableExtra(EXTRA_URI);
        fileName = getIntent().getStringExtra(EXTRA_NAME);
        parentPath = getIntent().getStringExtra(EXTRA_PARENT);
        usbSource = getIntent().getBooleanExtra(EXTRA_USB, false);
        if (Objects.isNull(fileUri) || Objects.isNull(fileName)) {
            finish();
            return;
        }
        previewType = FilePreviewType.resolveFilePreviewType(fileName);
        buildPreviewPage();
        if (usbSource) {
            try {
                UsbPreviewProvider.retainUsbPreview();
                usbPreviewRetained = true;
            } catch (IOException exception) {
                reportPreviewFailure("usb retain", exception);
                return;
            }
        }
        previewExecutor.execute(this::cleanExpiredExternalPreviews);
        switch (previewType) {
            case AUDIO:
            case VIDEO:
                showMediaPlayer();
                break;
            case PDF:
                showPdfPage(0);
                break;
            case TEXT:
            case MARKDOWN:
            case HTML:
                showTextPreview();
                break;
            default:
                showPreviewStatus(getString(R.string.preview_unsupported));
                openOtherApplication();
                break;
        }
    }

    /** 构造大字号焦点按钮与预览区域，适配电视遥控器。 */
    private void buildPreviewPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(16), dp(24), dp(16));
        page.setBackgroundColor(getColor(R.color.page_background));
        TextView title = new TextView(this);
        title.setText(fileName);
        title.setTextSize(22);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setTextColor(getColor(R.color.text_primary));
        page.addView(title);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        addPreviewButton(actions, R.string.preview_other_apps, this::openOtherApplication);
        addPreviewButton(actions, R.string.close, this::finish);
        page.addView(actions);
        statusTextView = new TextView(this);
        statusTextView.setTextSize(14);
        statusTextView.setTextColor(getColor(R.color.text_secondary));
        page.addView(statusTextView);
        contentFrame = new FrameLayout(this);
        page.addView(contentFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
    }

    /** 为按钮设置统一的电视焦点背景。 */
    private Button addPreviewButton(LinearLayout parent, int label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setFocusable(true);
        button.setBackgroundResource(R.drawable.focus_surface);
        button.setTextColor(getColor(R.color.text_primary));
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
        return button;
    }

    /** 媒体使用系统 MediaPlayer 和 SurfaceView，播放器自行按偏移读取 URI。 */
    private void showMediaPlayer() {
        LinearLayout mediaPage = new LinearLayout(this);
        mediaPage.setOrientation(LinearLayout.VERTICAL);
        if (previewType == FilePreviewType.VIDEO) {
            SurfaceView surfaceView = new SurfaceView(this);
            mediaPage.addView(surfaceView, new LinearLayout.LayoutParams(-1, 0, 1));
            surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                /** {@inheritDoc} */
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    videoSurfaceHolder = holder;
                    if (Objects.nonNull(mediaPlayer)) {
                        mediaPlayer.setDisplay(holder);
                    }
                }
                /** {@inheritDoc} */
                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                }
                /** {@inheritDoc} */
                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    videoSurfaceHolder = null;
                    if (Objects.nonNull(mediaPlayer)) {
                        mediaPlayer.setDisplay(null);
                    }
                }
            });
        } else {
            TextView audioLabel = new TextView(this);
            audioLabel.setText(R.string.preview_audio);
            audioLabel.setTextSize(28);
            audioLabel.setGravity(Gravity.CENTER);
            audioLabel.setTextColor(getColor(R.color.text_primary));
            mediaPage.addView(audioLabel, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        mediaSeekBar = new SeekBar(this);
        mediaSeekBar.setEnabled(false);
        mediaSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            /** {@inheritDoc} */
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && Objects.nonNull(mediaPlayer)) {
                    mediaPlayer.seekTo(progress);
                }
            }
            /** {@inheritDoc} */
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            /** {@inheritDoc} */
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        mediaPage.addView(mediaSeekBar);
        LinearLayout controls = new LinearLayout(this);
        playbackButton = addPreviewButton(controls, R.string.preview_play, this::toggleMediaPlayback);
        playbackButton.setEnabled(false);
        mediaPage.addView(controls);
        contentFrame.addView(mediaPage);
        try {
            mediaPlayer = new MediaPlayer();
            if (Objects.nonNull(videoSurfaceHolder)) {
                mediaPlayer.setDisplay(videoSurfaceHolder);
            }
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(previewType == FilePreviewType.AUDIO
                    ? AudioAttributes.CONTENT_TYPE_MUSIC : AudioAttributes.CONTENT_TYPE_MOVIE).build());
            mediaPlayer.setDataSource(this, fileUri);
            mediaPlayer.setOnPreparedListener(player -> {
                mediaPrepared = true;
                mediaSeekBar.setMax(Math.max(player.getDuration(), 1));
                mediaSeekBar.setEnabled(true);
                playbackButton.setEnabled(true);
                startInitialMediaPlayback();
            });
            mediaPlayer.setOnCompletionListener(player -> playbackButton.setText(R.string.preview_play));
            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                mediaPrepared = false;
                Log.e("TvFinderPreview", "媒体解码失败 name=" + fileName + ", uri=" + fileUri
                    + ", stage=decode, what=" + what + ", extra=" + extra);
                showPreviewStatus(getString(R.string.preview_decode_failed));
                playbackButton.setEnabled(false);
                return true;
            });
            mediaPlayer.prepareAsync();
            showPreviewStatus(getString(R.string.files_loading));
        } catch (IOException | RuntimeException exception) {
            reportPreviewFailure("media prepare", exception);
        }
    }

    /** 播放和暂停由遥控器按钮控制。 */
    private void toggleMediaPlayback() {
        if (Objects.isNull(mediaPlayer) || !mediaPrepared) {
            return;
        }
        autoPlayPending = false;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            playbackButton.setText(R.string.preview_play);
        } else {
            mediaPlayer.start();
            playbackButton.setText(R.string.preview_pause);
            updateMediaProgress();
        }
    }

    /** 异步准备与窗口焦点到达顺序不固定，只在首次播放时自动启动。 */
    private void startInitialMediaPlayback() {
        if (autoPlayPending && hasWindowFocus() && mediaPrepared && Objects.nonNull(mediaPlayer)) {
            autoPlayPending = false;
            mediaPlayer.start();
            playbackButton.setText(R.string.preview_pause);
            updateMediaProgress();
        }
    }

    /** {@inheritDoc} 若播放器先于窗口获得焦点完成准备，此处补启动首次播放。 */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            startInitialMediaPlayback();
        }
    }

    /** 每秒更新一次进度，不在后台保留轮询任务。 */
    private void updateMediaProgress() {
        if (mediaPrepared && Objects.nonNull(mediaPlayer) && mediaPlayer.isPlaying()) {
            mediaSeekBar.setProgress(mediaPlayer.getCurrentPosition());
            uiHandler.postDelayed(this::updateMediaProgress, 1000);
        }
    }

    /** PDF 每次仅在工作线程渲染当前页，避免整本文件进入内存。 */
    private void showPdfPage(int index) {
        showPreviewStatus(getString(R.string.files_loading));
        previewExecutor.execute(() -> {
            try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(fileUri, "r")) {
                if (Objects.isNull(descriptor)) {
                    throw new IOException("无法打开 PDF 文件描述符");
                }
                try (PdfRenderer renderer = new PdfRenderer(descriptor)) {
                    int count = renderer.getPageCount();
                    if (count == 0) {
                        throw new IOException("PDF 没有可显示的页面");
                    }
                    if (index < 0 || index >= count) {
                        throw new IOException("PDF 页号超出范围: " + index);
                    }
                    try (PdfRenderer.Page page = renderer.openPage(index)) {
                        float scale = Math.min(1600f / page.getWidth(), 900f / page.getHeight());
                        int width = Math.max(1, Math.round(page.getWidth() * scale));
                        int height = Math.max(1, Math.round(page.getHeight() * scale));
                        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        runOnUiThread(() -> renderPdfBitmap(bitmap, index, count));
                    }
                }
            } catch (IOException | RuntimeException exception) {
                runOnUiThread(() -> reportPreviewFailure("pdf render", exception));
            }
        });
    }

    /** 主线程替换 PDF 位图与翻页按钮。 */
    private void renderPdfBitmap(Bitmap bitmap, int index, int count) {
        if (isFinishing() || isDestroyed()) {
            bitmap.recycle();
            return;
        }
        contentFrame.removeAllViews();
        if (Objects.nonNull(pdfBitmap)) {
            pdfBitmap.recycle();
        }
        pdfBitmap = bitmap;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        ImageView image = new ImageView(this);
        image.setImageBitmap(bitmap);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        page.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = new LinearLayout(this);
        previousPageButton = addPreviewButton(controls, R.string.preview_previous_page,
            () -> showPdfPage(pdfPageIndex - 1));
        nextPageButton = addPreviewButton(controls, R.string.preview_next_page,
            () -> showPdfPage(pdfPageIndex + 1));
        pdfPageIndex = index;
        pdfPageCount = count;
        previousPageButton.setEnabled(index > 0);
        nextPageButton.setEnabled(index + 1 < count);
        page.addView(controls);
        contentFrame.addView(page);
        showPreviewStatus(getString(R.string.preview_page_number, index + 1, count));
    }

    /** 文本有界读取后按 TXT、Markdown 或 HTML 分别展示。 */
    private void showTextPreview() {
        showPreviewStatus(getString(R.string.files_loading));
        previewExecutor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(fileUri)) {
                if (Objects.isNull(input)) {
                    throw new IOException("无法读取文件内容");
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while (output.size() <= MAX_TEXT_BYTES && (count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                boolean truncated = output.size() > MAX_TEXT_BYTES;
                if (truncated && previewType != FilePreviewType.TEXT) {
                    throw new IOException("排版文件超过 2 MiB 预览上限");
                }
                String content = new String(output.toByteArray(), 0,
                    Math.min(output.size(), MAX_TEXT_BYTES), StandardCharsets.UTF_8);
                runOnUiThread(() -> renderTextContent(content, truncated));
            } catch (IOException | RuntimeException exception) {
                runOnUiThread(() -> reportPreviewFailure("text read", exception));
            }
        });
    }

    /** Markdown 使用 CommonMark 解析，纯文本转义，HTML 保留原正文。 */
    private void renderTextContent(String content, boolean truncated) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(previewType == FilePreviewType.HTML);
        settings.setDomStorageEnabled(previewType == FilePreviewType.HTML);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(previewType == FilePreviewType.HTML
            ? WebSettings.MIXED_CONTENT_ALWAYS_ALLOW : WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setWebViewClient(new PreviewWebViewClient(this, fileUri, parentPath, usbSource));
        contentFrame.addView(webView);
        String html;
        if (previewType == FilePreviewType.MARKDOWN) {
            html = HtmlRenderer.builder().escapeHtml(true).build()
                .render(Parser.builder().build().parse(content));
        } else if (previewType == FilePreviewType.TEXT) {
            html = "<pre style='white-space:pre-wrap'>" + android.text.TextUtils.htmlEncode(content) + "</pre>";
        } else {
            html = content;
        }
        String document = previewType == FilePreviewType.HTML ? html
            : "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>body{font:20px sans-serif;padding:20px;color:#222;background:#fff}"
            + "pre,code{white-space:pre-wrap;overflow-wrap:anywhere}</style>" + html;
        webView.loadDataWithBaseURL("https://tvfinder.local/preview/", document,
            "text/html", "UTF-8", null);
        showPreviewStatus(truncated ? getString(R.string.preview_text_truncated) : "");
    }

    /** 支持的类型也可显式选择其他应用；USB 先复制临时文件以兼容普通文件消费者。 */
    private void openOtherApplication() {
        if (!usbSource) {
            openFileWithOtherApplications(this, fileUri, fileName);
            return;
        }
        if (copyingForExternal) {
            return;
        }
        copyingForExternal = true;
        showPreviewStatus(getString(R.string.preview_copying_for_external));
        previewExecutor.execute(() -> {
            File target = null;
            try {
                File directory = new File(getCacheDir(), "external-preview/" + UUID.randomUUID());
                if (!directory.mkdirs()) {
                    throw new IOException("无法创建外部打开缓存目录: " + directory);
                }
                target = new File(directory, new File(fileName).getName());
                long size = queryFileSize();
                StorageManager storageManager = Objects.requireNonNull(
                    getSystemService(StorageManager.class));
                long allocatable = storageManager.getAllocatableBytes(
                    storageManager.getUuidForPath(getCacheDir()));
                if (size > allocatable) {
                    throw new IOException("电视剩余空间不足以交给外部应用打开");
                }
                try (InputStream input = getContentResolver().openInputStream(fileUri);
                     FileOutputStream output = new FileOutputStream(target)) {
                    if (Objects.isNull(input)) {
                        throw new IOException("无法读取 USB 文件");
                    }
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IOException("外部打开已取消");
                        }
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                File completed = target;
                runOnUiThread(() -> {
                    copyingForExternal = false;
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", completed);
                    openFileWithOtherApplications(this, uri, fileName);
                    showPreviewStatus("");
                });
            } catch (IOException | RuntimeException exception) {
                if (Objects.nonNull(target) && target.exists() && !target.delete()) {
                    Log.w("TvFinderPreview", "临时副本清理失败 path=" + target);
                }
                runOnUiThread(() -> {
                    copyingForExternal = false;
                    reportPreviewFailure("external copy", exception);
                });
            }
        });
    }

    /** 读取 provider 报告的文件大小，用于预判临时缓存空间。 */
    private long queryFileSize() {
        try (Cursor cursor = getContentResolver().query(fileUri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (Objects.nonNull(cursor) && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        }
        return 0;
    }

    /** 清理一天前的外部打开临时副本，不触碰用户导出到 Downloads 的文件。 */
    private void cleanExpiredExternalPreviews() {
        File directory = new File(getCacheDir(), "external-preview");
        File[] entries = directory.listFiles();
        if (Objects.isNull(entries)) {
            return;
        }
        long deadline = System.currentTimeMillis() - CACHE_RETENTION_MILLIS;
        for (File entry : entries) {
            if (entry.lastModified() < deadline && entry.isDirectory()) {
                File[] children = entry.listFiles();
                if (Objects.nonNull(children)) {
                    for (File child : children) {
                        if (!child.delete()) {
                            Log.w("TvFinderPreview", "过期临时文件清理失败 path=" + child);
                        }
                    }
                }
                if (!entry.delete()) {
                    Log.w("TvFinderPreview", "过期临时目录清理失败 path=" + entry);
                }
            }
        }
    }

    /** MIME 路由失败时使用二进制类型，不对内容作格式保证。 */
    private static String resolveFileMimeType(String name) {
        int dot = name.lastIndexOf('.');
        String mime = dot < 0 ? null : MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return Objects.isNull(mime) ? "*/*" : mime;
    }

    /** 记录完整异常并给用户简洁失败信息，保留其他方式打开按钮。 */
    private void reportPreviewFailure(String stage, Exception exception) {
        Log.e("TvFinderPreview", "文件预览失败 name=" + fileName + ", uri=" + fileUri
            + ", stage=" + stage + ", type=" + exception.getClass().getName()
            + ", message=" + exception.getMessage(), exception);
        showPreviewStatus(getString(R.string.preview_failed, exception.getMessage()));
    }

    /** 主线程更新预览状态。 */
    private void showPreviewStatus(String status) {
        if (!isDestroyed()) {
            statusTextView.setText(status);
        }
    }

    /** 电视独立像素转设备像素。 */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** {@inheritDoc} 外部应用覆盖页面时暂停媒体，避免后台继续播放。 */
    @Override
    protected void onPause() {
        autoPlayPending = false;
        if (mediaPrepared && Objects.nonNull(mediaPlayer) && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            playbackButton.setText(R.string.preview_play);
        }
        super.onPause();
    }

    /** {@inheritDoc} 释放播放与网页资源，并终止未完成的预览工作。 */
    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        if (Objects.nonNull(mediaPlayer)) {
            mediaPlayer.release();
            mediaPlayer = null;
            mediaPrepared = false;
        }
        videoSurfaceHolder = null;
        if (Objects.nonNull(webView)) {
            webView.destroy();
            webView = null;
        }
        if (Objects.nonNull(pdfBitmap)) {
            pdfBitmap.recycle();
            pdfBitmap = null;
        }
        previewExecutor.shutdownNow();
        if (usbPreviewRetained) {
            UsbPreviewProvider.releaseUsbPreview();
            usbPreviewRetained = false;
        }
        super.onDestroy();
    }
}
