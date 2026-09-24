package io.github.jnlongliao.tv.finder;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 不依赖电视外部应用的只读预览页。图片、PDF 和文字在工作线程解码，媒体交给系统内置解码器；
 * 文件格式或编解码不受支持时在页内报告失败，返回键始终可回到文件列表。
 *
 * @see <a href="https://developer.android.com/reference/android/graphics/pdf/PdfRenderer">PdfRenderer</a>
 * @see <a href="https://developer.android.com/reference/android/widget/VideoView">VideoView</a>
 */
public final class FilePreviewActivity extends Activity {
    /** 仅本应用内部使用的源文件路径。 */
    private static final String EXTRA_FILE_PATH = "preview_file_path";
    /** 原始文件名，USB 缓存文件使用它识别格式并显示标题。 */
    private static final String EXTRA_FILE_NAME = "preview_file_name";
    /** 文件列表菜单进入预览页后自动显示一次外部应用选择器。 */
    private static final String EXTRA_OPEN_EXTERNAL = "preview_open_external";
    /** 内置存储打开文件时所浏览的目录；缺失时不枚举应用缓存。 */
    private static final String EXTRA_INTERNAL_DIRECTORY = "preview_internal_directory";
    /** 同进程 USB 预览会话 ID，进程重启后允许只看首次缓存副本。 */
    private static final String EXTRA_USB_SESSION = "preview_usb_session";
    /** 日志标签，预览异常保留文件和执行阶段。 */
    private static final String LOG_TAG = "TvFinderPreview";
    /** 外部阅读器返回时撤销显式授权所用的请求码。 */
    private static final int EXTERNAL_OPEN_REQUEST_CODE = 1;
    /** 文本预览最多读取的字节数，避免大日志耗尽电视内存。 */
    private static final int MAX_TEXT_BYTES = 1024 * 1024;
    /** 单张图片解码的最大长边像素数。 */
    private static final int MAX_IMAGE_SIDE = 2048;
    /** 遥控器单次快进或快退的毫秒数。 */
    private static final int MEDIA_SEEK_MILLIS = 10_000;
    /** 顺序执行解码，页面销毁后不再提交 UI 更新。 */
    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor();
    /** 目录枚举独立于文档解码，首次快照不会排在耗时的 Office 读取后。 */
    private final ExecutorService directoryExecutor = Executors.newSingleThreadExecutor();
    /** 用户选中的原始或 USB 临时文件。 */
    private File previewFile;
    /** 预览文件显示名。 */
    private String previewName;
    /** 顶栏文件名，切换同目录文件时原位更新。 */
    private TextView previewTitle;
    /** 仅内部存储预览设置的目录边界。 */
    private File internalDirectory;
    /** 内部存储同级可预览文件的排序快照，仅在主线程读取和替换。 */
    private List<File> internalPreviewFiles;
    /** 当前文件在目录快照中的位置；目标失效时由后台重新定位。 */
    private int internalPreviewIndex = -1;
    /** 避免首次枚举期间或失败后重复提交相同目录扫描。 */
    private boolean internalSnapshotLoading;
    /** 首次快照尚未就绪时保留一次遥控器方向操作。 */
    private int pendingDirectoryDirection;
    /** 由 USB 列表页持有的临时预览会话。 */
    private UsbPreviewSession usbPreviewSession;
    /** 切换期间拒绝重复上下键请求。 */
    private boolean switchingPreview;
    /** 每次切换递增，后台旧文件结果不得覆盖当前页面。 */
    private int previewGeneration;
    /** 页面纵向骨架，仅容纳顶栏、内容舞台及 PDF 底栏。 */
    private LinearLayout contentLayout;
    /** 内容舞台；翻页时只替换舞台内容，不依赖外层子视图下标。 */
    private FrameLayout previewStage;
    /** PDF 页码提示，随成功渲染的页面更新。 */
    private TextView pdfPageCounter;
    /** 媒体控件仅在当前页面存活，返回列表时主动停止播放。 */
    private VideoView mediaView;
    /** 文档阅读区，按当前可见高度计算“上一屏”和“下一屏”。 */
    private ScrollView documentScrollView;
    /** 当前为文档或文本预览时，左右键在任意焦点位置翻动可视屏。 */
    private boolean documentPreviewActive;
    /** 文档当前屏和总屏数，不代表原 Office 文档页数。 */
    private TextView documentPageCounter;
    /** 媒体准备完成后才允许遥控器快进快退。 */
    private boolean mediaPrepared;
    /** 当前为媒体预览时，即使电视系统转移焦点，左右键仍能调整播放时间。 */
    private boolean mediaPreviewActive;
    /** 视频自然结束后保留完成提示，直到用户重新播放或调整时间。 */
    private boolean mediaCompleted;
    /** 媒体播放与暂停按钮，文案随播放状态变化。 */
    private Button mediaPlaybackButton;
    /** 媒体后退按钮，准备完成前禁用。 */
    private Button mediaRewindButton;
    /** 媒体前进按钮，准备完成前禁用。 */
    private Button mediaForwardButton;
    /** 媒体播放位置；用户操作时跳到对应时间。 */
    private SeekBar mediaSeekBar;
    /** 媒体已播和总时长；未知时长以占位符显示。 */
    private TextView mediaTimeTextView;
    /** 每秒刷新当前媒体播放位置，仅在预览页存活时运行。 */
    private final Runnable mediaProgressUpdater = this::updateMediaProgress;
    /** PDF 当前页，从零开始。 */
    private int pdfPageIndex;
    /** 当前是否为 PDF 预览，用于在任意焦点位置响应遥控器左右翻页。 */
    private boolean pdfPreviewActive;
    /** PDF 实际页数，在首张成功渲染后用于限制遥控器翻页边界。 */
    private int pdfPageCount;
    /** 当前可见的位图；翻页或销毁页面时释放旧像素内存。 */
    private Bitmap previewBitmap;
    /** 当前外部打开操作授予只读权限的单个文件 URI。 */
    private Uri externallySharedUri;
    /** 当前外部打开操作获得显式只读授权的应用包名。 */
    private final Set<String> externallyGrantedPackages = new HashSet<>();

    /**
     * 创建应用内只读预览请求；调用方必须先确认文件来自当前授权的存储范围。
     *
     * @param context 发起页面的上下文
     * @param file 可读取的真实文件或 USB 临时副本
     * @param displayName 原始文件名
     * @return 限定到本应用未导出 Activity 的 Intent
     */
    public static Intent createPreviewIntent(Context context, File file, String displayName) {
        return new Intent(context, FilePreviewActivity.class)
            .putExtra(EXTRA_FILE_PATH, file.getAbsolutePath())
            .putExtra(EXTRA_FILE_NAME, displayName);
    }

    /**
     * 从文件列表的“使用其他应用打开”进入本页并立即显示选择器。
     * 选择器返回后保留预览页，用户仍能继续阅读或返回文件列表。
     *
     * @param context 发起页面的上下文
     * @param file 可读取的真实文件或 USB 临时副本
     * @param displayName 原始文件名
     * @return 内部预览 Intent，附带一次性的外部打开标记
     */
    public static Intent createExternalOpenIntent(Context context, File file, String displayName) {
        return createPreviewIntent(context, file, displayName).putExtra(EXTRA_OPEN_EXTERNAL, true);
    }

    /**
     * 内置存储列表的预览入口，传入浏览目录以限定上下切换范围。
     *
     * @param context 发起页面的上下文
     * @param file 当前普通文件
     * @param displayName 文件显示名
     * @param directory 列表当前目录
     * @param openExternally 是否进入后立即显示外部应用选择器
     * @return 同目录可切换的内部预览 Intent
     */
    public static Intent createDirectoryPreviewIntent(Context context, File file, String displayName,
        File directory, boolean openExternally) {
        return createPreviewIntent(context, file, displayName)
            .putExtra(EXTRA_INTERNAL_DIRECTORY, directory.getAbsolutePath())
            .putExtra(EXTRA_OPEN_EXTERNAL, openExternally);
    }

    /**
     * USB 列表的预览入口，通过进程内会话 ID 复用当前已挂载卷。
     *
     * @param context 发起页面的上下文
     * @param file 已复制完成的首次缓存副本
     * @param displayName USB 卷中的原始文件名
     * @param sessionId USB 列表页创建的会话 ID
     * @param openExternally 是否进入后立即显示外部应用选择器
     * @return 可请求 USB 相邻文件的内部预览 Intent
     */
    public static Intent createUsbPreviewIntent(Context context, File file, String displayName,
        String sessionId, boolean openExternally) {
        return createPreviewIntent(context, file, displayName)
            .putExtra(EXTRA_USB_SESSION, sessionId)
            .putExtra(EXTRA_OPEN_EXTERNAL, openExternally);
    }

    /** {@inheritDoc} 与文件列表沿用用户选择的语言和明暗主题。 */
    @Override
    protected void attachBaseContext(Context context) {
        super.attachBaseContext(AppAppearance.applyThemeContext(AppLanguage.localizeAppContext(context)));
    }

    /** {@inheritDoc} 按扩展名选择内建预览器，不执行文件内容。 */
    @Override
    protected void onCreate(Bundle state) {
        setTheme(R.style.AppTheme);
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);
        previewName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        if (Objects.isNull(path) || Objects.isNull(previewName)) {
            finish();
            return;
        }
        previewFile = new File(path);
        String directoryPath = getIntent().getStringExtra(EXTRA_INTERNAL_DIRECTORY);
        if (Objects.nonNull(directoryPath)) {
            internalDirectory = new File(directoryPath);
        }
        usbPreviewSession = UsbPreviewSession.find(getIntent().getStringExtra(EXTRA_USB_SESSION));
        contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(dp(16), dp(8), dp(16), dp(8));
        contentLayout.setBackgroundColor(getColor(R.color.preview_background));
        setContentView(contentLayout);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        contentLayout.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));
        previewTitle = createText(previewName, 22);
        previewTitle.setTypeface(null, Typeface.BOLD);
        previewTitle.setSingleLine(true);
        previewTitle.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(previewTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView shortcutHint = createText(getString(R.string.preview_remote_hint), 15);
        shortcutHint.setTextColor(getColor(R.color.preview_secondary));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-2, -2);
        hintParams.leftMargin = dp(16);
        header.addView(shortcutHint, hintParams);
        previewStage = new FrameLayout(this);
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(-1, 0, 1);
        stageParams.topMargin = dp(4);
        contentLayout.addView(previewStage, stageParams);
        displayPreviewFile(previewFile, previewName);
        if (Objects.nonNull(internalDirectory)) {
            loadInternalPreviewFiles(0);
        }
        if (getIntent().getBooleanExtra(EXTRA_OPEN_EXTERNAL, false)) {
            getIntent().removeExtra(EXTRA_OPEN_EXTERNAL);
            previewStage.post(this::openFileWithExternalApplication);
        }
    }

    /** 替换同一个 Activity 内的预览内容，先释放旧媒体与位图再启动新文件解码。 */
    private void displayPreviewFile(File file, String displayName) {
        previewGeneration++;
        releaseCurrentPreviewContent();
        previewFile = file;
        previewName = displayName;
        previewTitle.setText(displayName);
        if (!file.isFile() || !file.canRead()) {
            showPreviewFailure(getString(R.string.preview_unreadable));
            return;
        }
        String extension = extensionOf(previewName);
        if (isOneOf(extension, "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif")) {
            showImagePreview();
        } else if ("pdf".equals(extension)) {
            showPdfPreview();
        } else if (isOneOf(extension, "mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi", "ts",
            "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus")) {
            showMediaPreview();
        } else if (isOneOf(extension, "docx", "docm", "xlsx", "xlsm", "pptx", "pptm", "ppsx")) {
            showOfficeTextPreview(extension);
        } else if (isOneOf(extension, "txt", "text", "log", "md", "csv", "tsv", "json", "xml",
            "yaml", "yml", "ini", "conf", "cfg", "properties", "java", "kt", "py", "js",
            "html", "htm", "css", "srt", "vtt", "lrc")) {
            showTextPreview();
        } else {
            showPreviewFailure(getString(R.string.preview_unsupported, extension.isEmpty() ? previewName : extension));
        }
    }

    /** 旧预览结果只通过代次丢弃，UI 资源则在切换当下主动释放。 */
    private void releaseCurrentPreviewContent() {
        pdfPreviewActive = false;
        mediaPreviewActive = false;
        documentPreviewActive = false;
        mediaPrepared = false;
        mediaCompleted = false;
        pdfPageIndex = 0;
        pdfPageCount = 0;
        if (Objects.nonNull(mediaTimeTextView)) {
            mediaTimeTextView.removeCallbacks(mediaProgressUpdater);
        }
        if (Objects.nonNull(mediaView)) {
            mediaView.setOnPreparedListener(null);
            mediaView.setOnCompletionListener(null);
            mediaView.setOnErrorListener(null);
            mediaView.stopPlayback();
            mediaView = null;
        }
        previewStage.removeAllViews();
        if (Objects.nonNull(previewBitmap)) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
        while (contentLayout.getChildCount() > 2) {
            contentLayout.removeViewAt(2);
        }
        pdfPageCounter = null;
        documentScrollView = null;
        documentPageCounter = null;
        mediaSeekBar = null;
        mediaTimeTextView = null;
        mediaPlaybackButton = null;
        mediaRewindButton = null;
        mediaForwardButton = null;
    }

    /** {@inheritDoc} 停止媒体与未完成解码，并撤销本页尚未回收的外部文件只读授权。 */
    @Override
    protected void onDestroy() {
        previewExecutor.shutdownNow();
        directoryExecutor.shutdownNow();
        revokeExternalFileAccess();
        if (Objects.nonNull(mediaView)) {
            mediaView.stopPlayback();
        }
        if (Objects.nonNull(mediaTimeTextView)) {
            mediaTimeTextView.removeCallbacks(mediaProgressUpdater);
        }
        if (Objects.nonNull(previewStage)) {
            previewStage.removeAllViews();
        }
        if (Objects.nonNull(previewBitmap)) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
        super.onDestroy();
    }

    /**
     * {@inheritDoc} 上下键切换同目录文件，菜单键打开外部选择器；左右键按当前类型翻页或调整时间。
     * 媒体确定键只在播放舞台或进度条获焦时拦截，以保留按钮本身的点击动作。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
            || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                requestAdjacentPreview(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                openFileWithExternalApplication();
            }
            return true;
        }
        if (pdfPreviewActive && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
            || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                int nextPage = pdfPageIndex + (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                if (nextPage >= 0 && (pdfPageCount == 0 || nextPage < pdfPageCount)) {
                    renderPdfPage(nextPage);
                }
            }
            return true;
        }
        if (documentPreviewActive && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
            || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                scrollDocumentScreen(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            }
            return true;
        }
        if (mediaPreviewActive && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
            || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                seekMediaBy(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                    ? -MEDIA_SEEK_MILLIS : MEDIA_SEEK_MILLIS);
            }
            return true;
        }
        if (mediaPreviewActive && (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            || ((event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                || event.getKeyCode() == KeyEvent.KEYCODE_ENTER)
                && ((Objects.nonNull(mediaView) && mediaView.hasFocus())
                    || (Objects.nonNull(mediaSeekBar) && mediaSeekBar.hasFocus()))))) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                toggleMediaPlayback();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * 上下键在所有格式中保持同一个含义。USB 请求缓存副本，内部存储后台枚举同级普通文件；
     * 两条路径均在新文件可读后才替换页面，失败保留旧文件。
     */
    private void requestAdjacentPreview(int direction) {
        if (switchingPreview) {
            return;
        }
        if (Objects.nonNull(usbPreviewSession)) {
            int requestedGeneration = previewGeneration;
            switchingPreview = usbPreviewSession.requestAdjacentFile(direction, new UsbPreviewSession.Listener() {
                /** {@inheritDoc} 仅活动页面接收完整 USB 副本。 */
                @Override
                public void onFileReady(File file, String displayName, int position, int count) {
                    switchingPreview = false;
                    if (!isDestroyed() && requestedGeneration == previewGeneration) {
                        displayPreviewFile(file, displayName);
                    }
                }

                /** {@inheritDoc} 保留当前预览，并显示 USB 复制失败原因。 */
                @Override
                public void onFailure(Exception exception) {
                    switchingPreview = false;
                    if (!isDestroyed()) {
                        showSwitchMessage(getString(R.string.preview_switch_failed, exception.getMessage()));
                    }
                }
            });
            if (!switchingPreview) {
                showSwitchMessage(getString(R.string.preview_switch_boundary));
            }
        } else if (Objects.nonNull(internalDirectory)) {
            requestInternalAdjacentFile(direction);
        } else {
            showSwitchMessage(getString(R.string.preview_switch_unavailable));
        }
    }

    /**
     * 已有目录快照时按索引直接定位；首次快照未就绪只保存最近一次方向键。
     * 目标仍在后台核实真实目录及可读性，防止列表打开后文件被外部替换。
     */
    private void requestInternalAdjacentFile(int direction) {
        if (Objects.isNull(internalPreviewFiles)) {
            pendingDirectoryDirection = direction;
            if (!internalSnapshotLoading) {
                loadInternalPreviewFiles(direction);
            }
            return;
        }
        int targetIndex = internalPreviewIndex + direction;
        if (internalPreviewIndex < 0 || targetIndex < 0 || targetIndex >= internalPreviewFiles.size()) {
            showSwitchMessage(getString(R.string.preview_switch_boundary));
            return;
        }
        switchingPreview = true;
        File adjacent = internalPreviewFiles.get(targetIndex);
        int requestedGeneration = previewGeneration;
        directoryExecutor.execute(() -> {
            try {
                File canonicalDirectory = internalDirectory.getCanonicalFile();
                if (!adjacent.isFile() || !adjacent.canRead()
                    || !canonicalDirectory.equals(adjacent.getCanonicalFile().getParentFile())) {
                    runOnUiThread(() -> {
                        if (!isDestroyed() && requestedGeneration == previewGeneration) {
                            internalPreviewFiles = null;
                            pendingDirectoryDirection = direction;
                            loadInternalPreviewFiles(direction);
                        }
                    });
                    return;
                }
                runOnUiThread(() -> {
                    switchingPreview = false;
                    if (!isDestroyed() && requestedGeneration == previewGeneration) {
                        internalPreviewIndex = targetIndex;
                        displayPreviewFile(adjacent, adjacent.getName());
                    }
                });
            } catch (Exception exception) {
                reportInternalSwitchFailure(exception, adjacent, requestedGeneration);
            }
        });
    }

    /** 首次或目标失效时扫描一次目录，按列表原有名称顺序建立可预览文件快照。 */
    private void loadInternalPreviewFiles(int retryDirection) {
        internalSnapshotLoading = true;
        File current = previewFile;
        int requestedGeneration = previewGeneration;
        directoryExecutor.execute(() -> {
            try {
                File canonicalDirectory = internalDirectory.getCanonicalFile();
                if (!canonicalDirectory.equals(current.getCanonicalFile().getParentFile())) {
                    throw new IOException("当前文件已离开预览目录: " + current);
                }
                File[] files = internalDirectory.listFiles();
                if (Objects.isNull(files)) {
                    throw new IOException("无法读取预览目录: " + internalDirectory);
                }
                Arrays.sort(files, Comparator.comparing(File::isDirectory).reversed()
                    .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                List<File> candidates = new ArrayList<>(files.length);
                for (File candidate : files) {
                    if (candidate.isFile() && candidate.canRead()
                        && (candidate.equals(current) || isSupportedPreviewName(candidate.getName()))
                        && canonicalDirectory.equals(candidate.getCanonicalFile().getParentFile())) {
                        candidates.add(candidate);
                    }
                }
                runOnUiThread(() -> {
                    switchingPreview = false;
                    internalSnapshotLoading = false;
                    if (isDestroyed() || requestedGeneration != previewGeneration) {
                        return;
                    }
                    internalPreviewFiles = candidates;
                    internalPreviewIndex = candidates.indexOf(current);
                    int direction = pendingDirectoryDirection;
                    pendingDirectoryDirection = 0;
                    if (direction != 0) {
                        requestInternalAdjacentFile(direction);
                    } else if (retryDirection != 0 && internalPreviewIndex < 0) {
                        showSwitchMessage(getString(R.string.preview_switch_unavailable));
                    }
                });
            } catch (Exception exception) {
                reportInternalSwitchFailure(exception, current, requestedGeneration);
            }
        });
    }

    /** 保留异常堆栈以定位外部文件变更，失败后仍显示原预览。 */
    private void reportInternalSwitchFailure(Exception exception, File file, int generation) {
        Log.e(LOG_TAG, "同目录文件切换失败 directory=" + internalDirectory + ", file=" + file
            + ", type=" + exception.getClass().getName() + ", message=" + exception.getMessage(), exception);
        runOnUiThread(() -> {
            switchingPreview = false;
            internalSnapshotLoading = false;
            if (!isDestroyed() && generation == previewGeneration) {
                showSwitchMessage(getString(R.string.preview_switch_failed, exception.getMessage()));
            }
        });
    }

    /** 在预览画面上短暂显示边界或复制结果，不遮挡正文与播放进度。 */
    private void showSwitchMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** 按文件名最后一个点识别扩展名，不将类别图标当作实际解码能力。 */
    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 判断扩展名是否属于此预览器的有限白名单。 */
    private static boolean isOneOf(String extension, String... supported) {
        for (String candidate : supported) {
            if (candidate.equals(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 同目录切换仅包含应用能够尝试内建预览的常见普通文件。
     * 扩展名表示预览入口可用，实际编解码仍由电视系统决定。
     *
     * @param name 原始文件名
     * @return 是否进入相邻文件候选列表
     */
    public static boolean isSupportedPreviewName(String name) {
        String extension = extensionOf(name);
        return isOneOf(extension, "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "pdf",
            "mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi", "ts", "mp3", "m4a", "aac", "wav",
            "ogg", "flac", "opus", "docx", "docm", "xlsx", "xlsm", "pptx", "pptm", "ppsx", "txt",
            "text", "log", "md", "csv", "tsv", "json", "xml", "yaml", "yml", "ini", "conf", "cfg",
            "properties", "java", "kt", "py", "js", "html", "htm", "css", "srt", "vtt", "lrc");
    }

    /** 在后台按屏幕尺寸采样图片，解码失败时保留可见反馈。 */
    private void showImagePreview() {
        previewStage.setBackgroundResource(R.drawable.preview_stage_surface);
        TextView status = createStatus();
        File source = previewFile;
        int generation = previewGeneration;
        previewExecutor.execute(() -> {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw new IOException("无法解码图片");
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > MAX_IMAGE_SIDE) {
                    options.inSampleSize *= 2;
                }
                Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
                if (Objects.isNull(bitmap)) {
                    throw new IOException("无法解码图片");
                }
                runOnUiThread(() -> {
                    if (isDestroyed() || generation != previewGeneration) {
                        bitmap.recycle();
                        return;
                    }
                    previewStage.removeView(status);
                    ImageView image = new ImageView(this);
                    previewBitmap = bitmap;
                    image.setImageBitmap(bitmap);
                    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    previewStage.addView(image, new FrameLayout.LayoutParams(-1, -1));
                });
            } catch (Exception exception) {
                reportPreviewFailure("image", exception, source, generation);
            }
        });
    }

    /** 使用系统媒体解码器播放，并提供始终可见的遥控器播放和时间控制。 */
    private void showMediaPreview() {
        mediaPreviewActive = true;
        boolean audioOnly = isOneOf(extensionOf(previewName), "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus");
        previewStage.setBackgroundResource(audioOnly
            ? R.drawable.preview_document_surface : R.drawable.preview_stage_surface);
        VideoView videoView = new VideoView(this);
        int generation = previewGeneration;
        mediaView = videoView;
        previewStage.addView(videoView, new FrameLayout.LayoutParams(-1, -1));
        if (audioOnly) {
            showAudioPlaceholder();
        }
        videoView.setFocusable(true);
        videoView.setFocusableInTouchMode(true);
        videoView.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                toggleMediaPlayback();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                seekMediaBy(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -MEDIA_SEEK_MILLIS : MEDIA_SEEK_MILLIS);
                return true;
            }
            return false;
        });
        buildMediaControls();
        videoView.setOnPreparedListener(player -> {
            if (generation != previewGeneration || mediaView != videoView) {
                return;
            }
            mediaPrepared = true;
            centerVideoContent(player.getVideoWidth(), player.getVideoHeight());
            int duration = Math.max(0, videoView.getDuration());
            mediaSeekBar.setMax(duration);
            mediaSeekBar.setEnabled(duration > 0);
            mediaRewindButton.setEnabled(duration > 0);
            mediaForwardButton.setEnabled(duration > 0);
            mediaPlaybackButton.setEnabled(true);
            updateMediaProgress();
        });
        videoView.setOnCompletionListener(player -> {
            if (generation != previewGeneration || mediaView != videoView) {
                return;
            }
            mediaCompleted = true;
            mediaPlaybackButton.setText(R.string.preview_play);
            updateMediaTimeText(videoView.getCurrentPosition());
        });
        videoView.setOnErrorListener((player, what, extra) -> {
            if (generation != previewGeneration || mediaView != videoView) {
                return true;
            }
            Log.e(LOG_TAG, "媒体预览失败 file=" + previewFile + ", what=" + what + ", extra=" + extra);
            mediaPrepared = false;
            mediaSeekBar.setEnabled(false);
            mediaRewindButton.setEnabled(false);
            mediaForwardButton.setEnabled(false);
            mediaPlaybackButton.setEnabled(false);
            mediaTimeTextView.removeCallbacks(mediaProgressUpdater);
            showPreviewFailure(getString(R.string.preview_codec_unsupported));
            return true;
        });
        videoView.setVideoURI(Uri.fromFile(previewFile));
        videoView.start();
        videoView.post(videoView::requestFocus);
    }

    /** 音频没有视频帧，在主题色阅读面板上显示文件名和类型标识，避免整屏纯黑。 */
    private void showAudioPlaceholder() {
        LinearLayout placeholder = new LinearLayout(this);
        placeholder.setOrientation(LinearLayout.VERTICAL);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setPadding(dp(40), dp(24), dp(40), dp(24));
        placeholder.setBackgroundResource(R.drawable.preview_document_surface);
        placeholder.setFocusable(false);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.audio_icon);
        placeholder.addView(icon, new LinearLayout.LayoutParams(dp(96), dp(96)));
        TextView name = createText(previewName, 26);
        name.setTypeface(null, Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2);
        nameParams.topMargin = dp(20);
        placeholder.addView(name, nameParams);
        TextView type = createText(getString(R.string.type_audio), 17);
        type.setTextColor(getColor(R.color.preview_secondary));
        type.setGravity(Gravity.CENTER);
        placeholder.addView(type);
        previewStage.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
    }

    /** 视频按原始宽高比居中铺到舞台允许的最大尺寸；音频没有画面尺寸，保持完整舞台。 */
    private void centerVideoContent(int videoWidth, int videoHeight) {
        int stageWidth = previewStage.getWidth();
        int stageHeight = previewStage.getHeight();
        if (videoWidth <= 0 || videoHeight <= 0 || stageWidth <= 0 || stageHeight <= 0) {
            return;
        }
        float scale = Math.min((float) stageWidth / videoWidth, (float) stageHeight / videoHeight);
        int fittedWidth = Math.max(1, Math.round(videoWidth * scale));
        int fittedHeight = Math.max(1, Math.round(videoHeight * scale));
        mediaView.setLayoutParams(new FrameLayout.LayoutParams(fittedWidth, fittedHeight, Gravity.CENTER));
    }

    /** 把媒体控制固定在舞台下方，遥控器无需唤出或等待系统浮层。 */
    private void buildMediaControls() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setBackgroundResource(R.drawable.preview_toolbar_surface);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, -2);
        footerParams.topMargin = dp(6);
        contentLayout.addView(footer, footerParams);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        footer.addView(buttons, new LinearLayout.LayoutParams(-1, dp(46)));
        mediaRewindButton = createPreviewActionButton(R.string.preview_rewind_10_seconds);
        mediaRewindButton.setEnabled(false);
        mediaRewindButton.setOnClickListener(view -> seekMediaBy(-MEDIA_SEEK_MILLIS));
        buttons.addView(mediaRewindButton, new LinearLayout.LayoutParams(-2, dp(40)));
        mediaPlaybackButton = createPreviewActionButton(R.string.preview_media_preparing);
        mediaPlaybackButton.setOnClickListener(view -> toggleMediaPlayback());
        LinearLayout.LayoutParams playbackParams = new LinearLayout.LayoutParams(-2, dp(40));
        playbackParams.leftMargin = dp(10);
        playbackParams.rightMargin = dp(10);
        buttons.addView(mediaPlaybackButton, playbackParams);
        mediaForwardButton = createPreviewActionButton(R.string.preview_forward_10_seconds);
        mediaForwardButton.setEnabled(false);
        mediaForwardButton.setOnClickListener(view -> seekMediaBy(MEDIA_SEEK_MILLIS));
        buttons.addView(mediaForwardButton, new LinearLayout.LayoutParams(-2, dp(40)));
        LinearLayout progress = new LinearLayout(this);
        progress.setGravity(Gravity.CENTER_VERTICAL);
        progress.setPadding(dp(18), 0, dp(18), 0);
        footer.addView(progress, new LinearLayout.LayoutParams(-1, dp(36)));
        mediaSeekBar = new SeekBar(this);
        mediaSeekBar.setEnabled(false);
        mediaSeekBar.setKeyProgressIncrement(MEDIA_SEEK_MILLIS);
        mediaSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            /** {@inheritDoc} 只响应用户拖动或遥控器按键，避免刷新进度时反复 seek。 */
            @Override
            public void onProgressChanged(SeekBar seekBar, int position, boolean fromUser) {
                if (fromUser && mediaPrepared) {
                    mediaCompleted = false;
                    mediaView.seekTo(position);
                    updateMediaTimeText(position);
                }
            }

            /** {@inheritDoc} 时间调整无需额外准备动作。 */
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            /** {@inheritDoc} 时间调整已在进度改变时执行。 */
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        progress.addView(mediaSeekBar, new LinearLayout.LayoutParams(0, -2, 1));
        mediaTimeTextView = createText(getString(R.string.preview_media_preparing), 17);
        mediaTimeTextView.setGravity(Gravity.CENTER_VERTICAL);
        mediaTimeTextView.setTextColor(getColor(R.color.preview_secondary));
        progress.addView(mediaTimeTextView);
    }

    /** 播放器尚未准备好时忽略确定键，避免 UI 承诺未执行的动作。 */
    private void toggleMediaPlayback() {
        if (!mediaPrepared || Objects.isNull(mediaView)) {
            return;
        }
        if (mediaView.isPlaying()) {
            mediaView.pause();
        } else {
            mediaCompleted = false;
            mediaView.start();
        }
        mediaPlaybackButton.setText(mediaView.isPlaying() ? R.string.preview_pause : R.string.preview_play);
        updateMediaProgress();
    }

    /** 快进快退始终夹在媒体文件可播放的时间范围内。 */
    private void seekMediaBy(int milliseconds) {
        if (!mediaPrepared || Objects.isNull(mediaView)) {
            return;
        }
        int duration = mediaView.getDuration();
        if (duration <= 0) {
            return;
        }
        int position = Math.max(0, Math.min(duration, mediaView.getCurrentPosition() + milliseconds));
        mediaCompleted = false;
        mediaView.seekTo(position);
        mediaSeekBar.setProgress(position);
        updateMediaTimeText(position);
    }

    /** 更新播放位置与按钮状态；页面销毁或错误后停止下一次刷新。 */
    private void updateMediaProgress() {
        if (isDestroyed() || !mediaPrepared || Objects.isNull(mediaView)) {
            return;
        }
        int position = Math.max(0, mediaView.getCurrentPosition());
        mediaSeekBar.setProgress(position);
        mediaPlaybackButton.setText(mediaView.isPlaying() ? R.string.preview_pause : R.string.preview_play);
        updateMediaTimeText(position);
        mediaTimeTextView.removeCallbacks(mediaProgressUpdater);
        mediaTimeTextView.postDelayed(mediaProgressUpdater, 1000);
    }

    /** 时长未知时仍显示明确占位，不把负数格式化成错误时间。 */
    private void updateMediaTimeText(int position) {
        int duration = mediaView.getDuration();
        String time = getString(R.string.preview_media_time, formatMediaTime(position),
            duration > 0 ? formatMediaTime(duration) : getString(R.string.preview_media_time_unknown));
        mediaTimeTextView.setText(mediaCompleted ? getString(R.string.preview_media_finished) + " · " + time : time);
    }

    /** 把毫秒时间转换为电视上可读的时分秒。 */
    private String formatMediaTime(int milliseconds) {
        int seconds = Math.max(0, milliseconds) / 1000;
        if (seconds >= 3600) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    /** PDF 页面在后台按有限尺寸渲染，每次只打开一页；透明页先铺白底保证黑字可读。 */
    private void showPdfPreview() {
        pdfPreviewActive = true;
        previewStage.setBackgroundResource(R.drawable.preview_stage_surface);
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setBackgroundResource(R.drawable.preview_toolbar_surface);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, dp(46));
        controlsParams.topMargin = dp(6);
        contentLayout.addView(controls, controlsParams);
        controls.setPadding(dp(16), 0, dp(16), 0);
        TextView remoteHint = createText(getString(R.string.preview_pdf_remote_hint), 16);
        remoteHint.setTextColor(getColor(R.color.preview_secondary));
        controls.addView(remoteHint, new LinearLayout.LayoutParams(0, -2, 1));
        pdfPageCounter = createText(getString(R.string.preview_loading), 18);
        pdfPageCounter.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        pdfPageCounter.setTextColor(getColor(R.color.preview_secondary));
        controls.addView(pdfPageCounter);
        renderPdfPage(0);
    }

    /** 保持 PDF 文件描述符与渲染器在同一工作任务内关闭，页码只在成功后更新。 */
    private void renderPdfPage(int requestedPage) {
        File source = previewFile;
        int generation = previewGeneration;
        previewExecutor.execute(() -> {
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(source,
                ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer renderer = new PdfRenderer(descriptor)) {
                if (requestedPage >= renderer.getPageCount()) {
                    return;
                }
                int pageCount = renderer.getPageCount();
                try (PdfRenderer.Page page = renderer.openPage(requestedPage)) {
                    float scale = Math.min(1f, (float) MAX_IMAGE_SIDE / Math.max(page.getWidth(), page.getHeight()));
                    Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * scale)),
                        Math.max(1, Math.round(page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    runOnUiThread(() -> {
                        if (isDestroyed() || generation != previewGeneration) {
                            bitmap.recycle();
                            return;
                        }
                        pdfPageIndex = requestedPage;
                        showPageBitmap(bitmap, pageCount);
                    });
                }
            } catch (Exception exception) {
                reportPreviewFailure("pdf", exception, source, generation);
            }
        });
    }

    /** 更新当前 PDF 图像与页码；新页替换旧页，避免累计持有位图。 */
    private void showPageBitmap(Bitmap bitmap, int pageCount) {
        previewStage.removeAllViews();
        if (Objects.nonNull(previewBitmap)) {
            previewBitmap.recycle();
        }
        previewBitmap = bitmap;
        pdfPageCount = pageCount;
        pdfPageCounter.setText(getString(R.string.preview_page, pdfPageIndex + 1, pageCount));
        ImageView image = new ImageView(this);
        image.setImageBitmap(bitmap);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setFocusable(true);
        previewStage.addView(image, new FrameLayout.LayoutParams(-1, -1));
        image.post(image::requestFocus);
    }

    /** 按上限读取普通 UTF-8 文本；超长文件仅展示前段并给出截断提示。 */
    private void showTextPreview() {
        documentPreviewActive = true;
        previewStage.setBackgroundColor(getColor(R.color.preview_background));
        TextView status = createStatus();
        File source = previewFile;
        int generation = previewGeneration;
        previewExecutor.execute(() -> {
            try (FileInputStream input = new FileInputStream(source)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                while (output.size() < MAX_TEXT_BYTES + 1) {
                    int length = input.read(buffer, 0,
                        Math.min(buffer.length, MAX_TEXT_BYTES + 1 - output.size()));
                    if (length < 0) {
                        break;
                    }
                    output.write(buffer, 0, length);
                }
                byte[] bytes = output.toByteArray();
                boolean truncated = bytes.length > MAX_TEXT_BYTES;
                String content = new String(bytes, 0, Math.min(bytes.length, MAX_TEXT_BYTES), StandardCharsets.UTF_8);
                if (truncated) {
                    content += "\n\n" + getString(R.string.preview_text_truncated);
                }
                String text = content;
                runOnUiThread(() -> {
                    if (generation == previewGeneration) {
                        showPreviewText(status, text);
                    }
                });
            } catch (Exception exception) {
                reportPreviewFailure("text", exception, source, generation);
            }
        });
    }

    /** Office 新格式展示有限纯文字，不承诺还原排版、公式或嵌入对象。 */
    private void showOfficeTextPreview(String extension) {
        documentPreviewActive = true;
        previewStage.setBackgroundColor(getColor(R.color.preview_background));
        TextView status = createStatus();
        File source = previewFile;
        int generation = previewGeneration;
        previewExecutor.execute(() -> {
            try {
                String text = OfficeTextPreview.readOfficeText(source, extension, MAX_TEXT_BYTES);
                runOnUiThread(() -> {
                    if (generation == previewGeneration) {
                        showPreviewText(status, getString(R.string.preview_office_text_only) + "\n\n" + text);
                    }
                });
            } catch (Exception exception) {
                reportPreviewFailure("office", exception, source, generation);
            }
        });
    }

    /** 把后台结果放入可滚动文字区，按实际可见高度提供可见的上一屏与下一屏。 */
    private void showPreviewText(TextView status, String content) {
        if (isDestroyed()) {
            return;
        }
        int generation = previewGeneration;
        previewStage.removeView(status);
        TextView text = createText(content, 22);
        text.setBackgroundResource(R.drawable.preview_document_surface);
        text.setTextColor(getColor(R.color.preview_ink));
        text.setPadding(dp(40), dp(26), dp(40), dp(30));
        text.setLineSpacing(dp(4), 1.15f);
        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        scroll.setFillViewport(true);
        documentScrollView = scroll;
        scroll.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
                scrollDocumentScreen(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
                scrollDocumentScreen(1);
                return true;
            }
            return false;
        });
        scroll.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) ->
            updateDocumentScreenControls());
        LinearLayout readingArea = new LinearLayout(this);
        readingArea.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        readingArea.setPadding(dp(8), dp(8), dp(8), dp(8));
        readingArea.addView(text, new LinearLayout.LayoutParams(
            Math.min(dp(1500), getResources().getDisplayMetrics().widthPixels - dp(64)), -2));
        scroll.addView(readingArea, new FrameLayout.LayoutParams(-1, -2));
        previewStage.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        buildDocumentControls();
        scroll.post(() -> {
            if (isDestroyed() || generation != previewGeneration) {
                return;
            }
            // 短文件的阅读纸张也铺满舞台；长文件仍由正文自然撑高供遥控器翻屏。
            text.setMinHeight(Math.max(0, scroll.getHeight() - dp(16)));
            text.post(this::updateDocumentScreenControls);
            scroll.requestFocus();
        });
    }

    /** 底栏仅提示左右键和当前可视屏数，不误称原 Office 文档页数。 */
    private void buildDocumentControls() {
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setBackgroundResource(R.drawable.preview_toolbar_surface);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, dp(46));
        controlsParams.topMargin = dp(6);
        contentLayout.addView(controls, controlsParams);
        controls.setPadding(dp(16), 0, dp(16), 0);
        TextView remoteHint = createText(getString(R.string.preview_document_remote_hint), 16);
        remoteHint.setTextColor(getColor(R.color.preview_secondary));
        controls.addView(remoteHint, new LinearLayout.LayoutParams(0, -2, 1));
        documentPageCounter = createText(getString(R.string.preview_reading_text), 18);
        documentPageCounter.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        documentPageCounter.setTextColor(getColor(R.color.preview_secondary));
        controls.addView(documentPageCounter);
    }

    /** 一次移动一个可视高度并留两行交叠，方便遥控器阅读时接续上下文。 */
    private void scrollDocumentScreen(int direction) {
        if (Objects.isNull(documentScrollView)) {
            return;
        }
        int step = Math.max(1, documentScrollView.getHeight() - dp(64));
        int target = Math.max(0, Math.min(documentScrollRange(), documentScrollView.getScrollY() + direction * step));
        documentScrollView.smoothScrollTo(0, target);
    }

    /** 阅读区布局完成或滚动后更新屏号。 */
    private void updateDocumentScreenControls() {
        if (Objects.isNull(documentScrollView) || Objects.isNull(documentPageCounter)) {
            return;
        }
        int range = documentScrollRange();
        int step = Math.max(1, documentScrollView.getHeight() - dp(64));
        int total = (range + step - 1) / step + 1;
        int current = Math.min(total, Math.round((float) documentScrollView.getScrollY() / step) + 1);
        documentPageCounter.setText(getString(R.string.preview_screen, current, total));
    }

    /** 文本内容高度可能短于屏幕，滚动范围至少为零。 */
    private int documentScrollRange() {
        if (Objects.isNull(documentScrollView) || documentScrollView.getChildCount() == 0) {
            return 0;
        }
        return Math.max(0, documentScrollView.getChildAt(0).getHeight() - documentScrollView.getHeight());
    }

    /** 创建可见加载状态。 */
    private TextView createStatus() {
        TextView status = createText(getString(R.string.preview_loading), 17);
        previewStage.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return status;
    }

    /** 统一生成遥控器页面文字。 */
    private TextView createText(String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(getColor(R.color.preview_foreground));
        text.setGravity(Gravity.START);
        text.setPadding(0, dp(8), 0, dp(8));
        return text;
    }

    /** 统一预览页操作按钮的遥控器焦点、字号和尺寸，避免系统默认大灰按钮压缩内容区。 */
    private Button createPreviewActionButton(int textResource) {
        Button button = new Button(this);
        button.setText(textResource);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setTextColor(getColorStateList(R.color.preview_action_text));
        button.setBackgroundResource(R.drawable.preview_action_surface);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setMinimumHeight(0);
        button.setMinimumWidth(0);
        button.setStateListAnimator(null);
        return button;
    }

    /** 记录完整失败堆栈并在当前页显示可理解的结果。 */
    private void reportPreviewFailure(String stage, Exception exception) {
        reportPreviewFailure(stage, exception, previewFile, previewGeneration);
    }

    /** 后台任务携带源文件和页面代次，旧文件失败不得覆盖已经切换后的页面。 */
    private void reportPreviewFailure(String stage, Exception exception, File source, int generation) {
        Log.e(LOG_TAG, "文件预览失败 stage=" + stage + ", file=" + source + ", generation=" + generation + ", type="
            + exception.getClass().getName() + ", message=" + exception.getMessage(), exception);
        runOnUiThread(() -> {
            if (!isDestroyed() && generation == previewGeneration) {
                showPreviewFailure(getString(R.string.preview_failed, exception.getMessage()));
            }
        });
    }

    /** 显示失败原因，避免外部应用缺失时出现空白页面。 */
    private void showPreviewFailure(String message) {
        previewStage.removeAllViews();
        TextView failure = createText(message, 20);
        failure.setPadding(dp(24), dp(24), dp(24), dp(24));
        previewStage.addView(failure, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
    }

    /**
     * 在所有预览页保留外部应用打开能力，供用户选择其他播放器、阅读器或安装器。
     * 将当前文件同时放入目标 Intent 与选择器的 ClipData，并向可处理文件的应用显式授予单文件
     * 只读权限，以兼容未转发临时授权的电视系统选择器。外部应用返回时撤销显式授权；
     * 电视没有处理应用时在本页显示具体原因。
     *
     * @see <a href="https://developer.android.com/reference/android/content/Intent#createChooser(android.content.Intent,%20java.lang.CharSequence)">Intent.createChooser</a>
     * @see <a href="https://developer.android.com/reference/android/content/Context#grantUriPermission(java.lang.String,%20android.net.Uri,%20int)">Context.grantUriPermission</a>
     */
    private void openFileWithExternalApplication() {
        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionOf(previewName));
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", previewFile);
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                Objects.isNull(mimeType) ? "application/octet-stream" : mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ClipData clipData = ClipData.newRawUri("", uri);
            intent.setClipData(clipData);
            Intent chooser = Intent.createChooser(intent, getString(R.string.preview_external_open));
            chooser.setClipData(clipData);
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            grantReadAccessToFileHandlers(intent, uri);
            startActivityForResult(chooser, EXTERNAL_OPEN_REQUEST_CODE);
        } catch (ActivityNotFoundException | SecurityException | IllegalArgumentException exception) {
            revokeExternalFileAccess();
            reportPreviewFailure("external application", exception);
        }
    }

    /**
     * 仅向系统报告能处理此 ACTION_VIEW 的应用授予当前 URI 的只读权限。
     * 厂商播放器从未携带授权的中间应用启动时，显式授权仍覆盖最终播放器自身的包名。
     *
     * @param viewIntent 与选择器目标相同的文件查看 Intent
     * @param uri 当前用户明确选择的单个文件 URI
     * @see <a href="https://developer.android.com/training/package-visibility/declaring">Android package visibility</a>
     */
    private void grantReadAccessToFileHandlers(Intent viewIntent, Uri uri) {
        revokeExternalFileAccess();
        List<ResolveInfo> handlers = getPackageManager().queryIntentActivities(viewIntent,
            PackageManager.MATCH_DEFAULT_ONLY);
        externallySharedUri = uri;
        for (ResolveInfo handler : handlers) {
            if (Objects.isNull(handler.activityInfo)) {
                continue;
            }
            String packageName = handler.activityInfo.packageName;
            if (externallyGrantedPackages.add(packageName)) {
                try {
                    grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException exception) {
                    externallyGrantedPackages.remove(packageName);
                    Log.w(LOG_TAG, "向候选应用授予文件只读权限失败 package=" + packageName
                        + ", uri=" + uri + ", type=" + exception.getClass().getName()
                        + ", message=" + exception.getMessage(), exception);
                }
            }
        }
        Log.d(LOG_TAG, "外部文件只读授权候选=" + externallyGrantedPackages
            + ", mimeType=" + viewIntent.getType());
    }

    /**
     * 结束一次外部打开后，仅撤销本页向候选应用显式授予的 URI 权限。
     * 未取消 Intent 自身的生命周期授权，以免影响系统选择器的正常行为。
     */
    private void revokeExternalFileAccess() {
        if (Objects.nonNull(externallySharedUri)) {
            for (String packageName : externallyGrantedPackages) {
                revokeUriPermission(packageName, externallySharedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        }
        externallyGrantedPackages.clear();
        externallySharedUri = null;
    }

    /** {@inheritDoc} 外部阅读器返回后收回本页额外授予的单文件只读权限。 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EXTERNAL_OPEN_REQUEST_CODE) {
            revokeExternalFileAccess();
        }
    }

    /** 逻辑尺寸转换为物理像素。 */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
