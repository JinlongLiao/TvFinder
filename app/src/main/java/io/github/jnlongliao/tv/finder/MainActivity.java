package io.github.jnlongliao.tv.finder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.Formatter;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ImageView;
import android.content.res.ColorStateList;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 电视文件管理入口：方向键导航、确定打开、菜单或长按显示操作。
 * UI 状态仅主线程持有，磁盘扫描及写操作由单一有界工作线程串行执行。
 * 写操作期间禁止启动第二项任务或切换目录；离开页面请求取消，移动删除阶段除外。
 *
 * @see <a href="https://developer.android.com/training/tv/get-started/navigation">TV navigation</a>
 * @see <a href="https://developer.android.com/training/data-storage/manage-all-files">All files access</a>
 */
public final class MainActivity extends Activity {
    /** 应用内部导入模式：用户从电视共享存储选择一个普通文件后返回路径。 */
    public static final String EXTRA_PICK_INTERNAL_FILE = "io.github.jnlongliao.tv.finder.PICK_INTERNAL_FILE";
    /** 导入模式返回的已校验文件绝对路径。 */
    public static final String EXTRA_PICKED_FILE_PATH = "io.github.jnlongliao.tv.finder.PICKED_FILE_PATH";
    /**
     * Android 日志标签，失败时包含动作、路径、异常 message 和完整堆栈。
     */
    private static final String LOG_TAG = "TvFinder";
    /**
     * 用户显式选择“系统根目录”时采用的 Linux 文件系统浏览边界。
     */
    private static final File SYSTEM_ROOT_DIRECTORY = new File("/");
    /**
     * Android 固件可能允许访问具体路径，却禁止普通应用枚举根目录；这些标准顶层路径用于兼容展示。
     * 列表只提供入口，后续读取仍由系统文件权限决定，不会把不存在或不可识别为目录的路径显示出来。
     */
    private static final String[] SYSTEM_ROOT_FALLBACK_PATHS = {
        "/apex", "/bootstrap-apex", "/config", "/cust", "/data", "/debug_ramdisk", "/dev",
        "/linkerconfig", "/metadata", "/mi_ext", "/mnt", "/odm", "/odm_dlkm", "/oem",
        "/opconfig", "/opcust", "/postinstall", "/proc", "/product", "/sdcard", "/storage",
        "/sys", "/system", "/system_dlkm", "/system_ext", "/tmp", "/vendor", "/vendor_dlkm"
    };
    /**
     * 单条终端命令最长执行时间，防止交互式或阻塞命令永久占用文件工作线程。
     */
    private static final long SHELL_COMMAND_TIMEOUT_SECONDS = 15;
    /**
     * 单条命令最多展示的输出字节数，避免异常输出耗尽电视内存。
     */
    private static final int SHELL_OUTPUT_LIMIT_BYTES = 64 * 1024;
    /**
     * 单一 I/O 工作线程及最多一个候选任务，避免快速按键造成无限排队。
     */
    private final ThreadPoolExecutor fileExecutor = new ThreadPoolExecutor(1, 1, 0,
        TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> new Thread(runnable, "tv-file-io"));
    /**
     * 本次文件操作取消信号，在工作线程检查安全点时生效。
     */
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    /**
     * 页面纵向容器，每次目录切换重建子视图。
     */
    private LinearLayout pageLayout;
    /**
     * 当前目录列表，首页及加载中可以为空。
     */
    private GridView fileGridView;
    /**
     * 当前文件列表快照，与列表适配器同时更新。
     */
    private List<File> visibleFiles = new ArrayList<>();
    /**
     * 当前目录最后明确选中的文件；工具栏取得焦点后仍用于“操作”，切换页面即清空。
     */
    private File selectedFile;
    /**
     * 当前存储卷根；不允许通过导航进入根之外或对根执行破坏性操作。
     */
    private File storageRoot;
    /**
     * 当前目录；为空表示磁盘首页。
     */
    private File currentDirectory;
    /**
     * 用户选择复制或移动的源；粘贴成功后清空。
     */
    private File clipboardFile;
    /**
     * true 表示移动，false 表示复制，仅在剪贴板非空时有意义。
     */
    private boolean clipboardMove;
    /**
     * 主线程任务门闩，包含目录加载和写操作；防止重复触发。
     */
    private boolean busy;
    /**
     * 写操作进度文本；工作线程只能通过主线程回调更新。
     */
    private TextView operationProgress;
    /**
     * 当前写操作对话框；禁用隐式关闭以保持任务可见。
     */
    private AlertDialog operationDialog;
    /**
     * 进度节流的单调时钟毫秒，只由工作线程访问。
     */
    private long lastProgressTime;

    /**
     * 当前已绘制的夜间标志，仅主线程使用，避免重复重建。
     */
    private int appliedNightMode;
    /**
     * 系统主题变化等待文件任务结束后生效，不能为外观改变中断复制或移动。
     */
    private boolean themeChangePending;
    /**
     * 当前是否显示终端页面，仅用于侧栏选中状态与退出后的首页恢复。
     */
    private boolean terminalVisible;

    /**
     * {@inheritDoc} 语言和主题在创建界面之前生效，不更改电视系统设置。
     */
    @Override
    protected void attachBaseContext(Context context) {
        super.attachBaseContext(AppAppearance.applyThemeContext(AppLanguage.localizeAppContext(context)));
    }

    /**
     * {@inheritDoc} 建立电视布局后检查首次授权引导；恢复和重建页面不重复请求。
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
        appliedNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Objects.nonNull(savedInstanceState)) {
            storageRoot = readSavedFile(savedInstanceState, "storage_root");
            currentDirectory = readSavedFile(savedInstanceState, "current_directory");
            clipboardFile = readSavedFile(savedInstanceState, "clipboard_file");
            clipboardMove = savedInstanceState.getBoolean("clipboard_move");
        }
        if (Objects.nonNull(currentDirectory) && Objects.nonNull(storageRoot)) {
            loadDirectoryFiles(currentDirectory, savedInstanceState.getString("selected_name"));
        } else {
            showStorageHome();
        }
        showInitialStoragePermissionGuidance();
    }

    /**
     * {@inheritDoc} 保存浏览位置和待粘贴项，重建页面不会自动重放文件操作。
     * Bundle 仅保存路径与选择状态；进程中断的任务仍遵循文件操作的失败边界。
     */
    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("storage_root", Objects.isNull(storageRoot) ? null : storageRoot.getAbsolutePath());
        state.putString("current_directory", Objects.isNull(currentDirectory) ? null : currentDirectory.getAbsolutePath());
        state.putString("clipboard_file", Objects.isNull(clipboardFile) ? null : clipboardFile.getAbsolutePath());
        state.putBoolean("clipboard_move", clipboardMove);
        state.putString("selected_name", Objects.isNull(selectedFile) ? null : selectedFile.getName());
    }

    /**
     * 将本 Activity 保存的可空路径恢复为文件引用，不在主线程探测磁盘或执行操作。
     *
     * @param state 系统保存的页面状态
     * @param key   路径字段键
     * @return 文件引用；未保存路径时为 null
     */
    private File readSavedFile(Bundle state, String key) {
        String path = state.getString(key);
        return Objects.isNull(path) ? null : new File(path);
    }

    /**
     * {@inheritDoc} 跟随系统时更新外观；文件扫描或写操作期间延后重建。
     * 固定浅色和深色模式忽略系统明暗变化，防止重建导致取消正在执行的文件任务。
     *
     * @see <a href="https://developer.android.com/develop/ui/views/theming/darktheme">Configuration changes</a>
     */
    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        int requestedNightMode = AppAppearance.resolveNightMode(AppAppearance.readThemeMode(this), configuration.uiMode);
        themeChangePending = requestedNightMode != appliedNightMode;
        recreateForPendingTheme();
    }

    /**
     * 空闲时应用系统主题变化；主线程调用，任务进行中保留待处理标志。
     *
     * @return 是否已安排页面重建，调用方此时应停止绘制旧页面
     */
    private boolean recreateForPendingTheme() {
        if (themeChangePending && !busy && !isDestroyed()) {
            themeChangePending = false;
            recreate();
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc} 从授权页或播放器返回后刷新可用存储及当前目录。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (!busy) {
            refreshCurrentDirectory();
        }
    }

    /**
     * {@inheritDoc} 不强制中断移动删除阶段，避免错误地承诺操作回滚。
     */
    @Override
    protected void onDestroy() {
        cancellationRequested.set(true);
        fileExecutor.shutdown();
        super.onDestroy();
    }

    /**
     * 建立统一安全边距、标题和导航提示，所有尺寸按电视逻辑密度换算。
     */
    private void createPageLayout(String title, String subtitle) {
        fileGridView = null;
        selectedFile = null;
        LinearLayout shell = new LinearLayout(this);
        shell.setPadding(toDisplayPixels(32), toDisplayPixels(24), toDisplayPixels(32), toDisplayPixels(24));
        shell.setBackgroundColor(getColor(R.color.page_background));
        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.VERTICAL);
        navigation.setPadding(0, toDisplayPixels(8), toDisplayPixels(18), 0);
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView brandIcon = new ImageView(this);
        brandIcon.setImageResource(R.drawable.brand_mark);
        brand.addView(brandIcon, new LinearLayout.LayoutParams(toDisplayPixels(32), toDisplayPixels(32)));
        TextView brandName = createPageText("TV Finder", 21, getColor(R.color.text_primary));
        brandName.setPadding(toDisplayPixels(8), 0, 0, 0);
        brand.addView(brandName);
        navigation.addView(brand);
        TextView tagline = createPageText(getString(R.string.app_name), 14, getColor(R.color.text_secondary));
        tagline.setPadding(0, toDisplayPixels(4), 0, toDisplayPixels(32));
        navigation.addView(tagline);
        addNavigationButton(navigation, getString(R.string.storage_home), this::showStorageHome);
        for (StorageLocation location : StorageRepository.findStorageLocations(this)) {
            addNavigationButton(navigation, location.displayName, () -> {
                storageRoot = location.directory;
                loadDirectoryFiles(location.directory, null);
            });
        }
        addNavigationButton(navigation, resolveUsbNavigationLabel(),
                () -> startActivity(new Intent(this, ExfatUsbActivity.class)));
        addNavigationButton(navigation, getString(R.string.system_root), this::openSystemRootDirectory);
        addNavigationButton(navigation, getString(R.string.terminal), this::showTerminal);
        navigation.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        addNavigationButton(navigation, getString(R.string.access_permission), this::requestStorageAccess);
        addNavigationButton(navigation, getString(R.string.appearance), this::showAppearanceOptions);
        addNavigationButton(navigation, getString(R.string.device_info), this::showDeviceInformation);
        addNavigationButton(navigation, getString(R.string.about_app), this::showAboutApplication);
        shell.addView(navigation, new LinearLayout.LayoutParams(toDisplayPixels(188), -1));
        pageLayout = new LinearLayout(this);
        pageLayout.setOrientation(LinearLayout.VERTICAL);
        pageLayout.setPadding(toDisplayPixels(18), toDisplayPixels(6), 0, 0);
        pageLayout.addView(createPageText(title, 26, getColor(R.color.text_primary)));
        TextView description = createPageText(subtitle, 13, getColor(R.color.text_secondary));
        description.setMaxLines(2);
        description.setPadding(0, toDisplayPixels(6), 0, toDisplayPixels(18));
        pageLayout.addView(description);
        shell.addView(pageLayout, new LinearLayout.LayoutParams(0, -1, 1));
        setContentView(shell);
    }

    /**
     * 从侧栏显式进入 Linux 系统根目录；默认启动页及普通存储入口保持不变。
     */
    private void openSystemRootDirectory() {
        storageRoot = SYSTEM_ROOT_DIRECTORY;
        loadDirectoryFiles(SYSTEM_ROOT_DIRECTORY, null);
    }

    /** 从 USB 描述符展示设备名；未插入兼容设备时保留通用入口供后续刷新。 */
    private String resolveUsbNavigationLabel() {
        UsbManager usbManager = getSystemService(UsbManager.class);
        if (Objects.nonNull(usbManager)) {
            for (UsbDevice candidate : usbManager.getDeviceList().values()) {
                if (Objects.nonNull(UsbScsiBlockDevice.findScsiStorageInterface(candidate))) {
                    String name = UsbScsiBlockDevice.resolveUsbDeviceDisplayName(candidate);
                    if (!name.isEmpty()) {
                        return name;
                    }
                }
            }
        }
        return getString(R.string.usb_device);
    }

    /**
     * 显示以应用进程身份执行单条 Shell 命令的终端页面。
     */
    private void showTerminal() {
        currentDirectory = null;
        storageRoot = null;
        terminalVisible = true;
        createPageLayout(getString(R.string.terminal), getString(R.string.terminal_identity_hint));
        TextView commandOutput = createPageText(getString(R.string.terminal_welcome), 16,
            getColor(R.color.text_primary));
        commandOutput.setTypeface(Typeface.MONOSPACE);
        commandOutput.setTextIsSelectable(true);
        commandOutput.setPadding(toDisplayPixels(12), toDisplayPixels(12), toDisplayPixels(12),
            toDisplayPixels(12));
        ScrollView outputScroll = new ScrollView(this);
        outputScroll.setFocusable(true);
        outputScroll.addView(commandOutput);
        LinearLayout.LayoutParams outputParameters = new LinearLayout.LayoutParams(-1, 0, 1);
        outputParameters.setMargins(0, 0, 0, toDisplayPixels(8));
        pageLayout.addView(outputScroll, outputParameters);

        EditText commandInput = new EditText(this);
        commandInput.setSingleLine(true);
        commandInput.setTextSize(18);
        commandInput.setTextColor(getColor(R.color.text_primary));
        commandInput.setHintTextColor(getColor(R.color.text_secondary));
        commandInput.setHint(R.string.terminal_command_hint);
        commandInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pageLayout.addView(commandInput, new LinearLayout.LayoutParams(-1, toDisplayPixels(64)));

        LinearLayout toolbar = createButtonRow();
        addActionButton(toolbar, getString(R.string.terminal_execute),
            () -> executeTerminalCommand(commandInput, commandOutput, outputScroll));
        addActionButton(toolbar, getString(R.string.terminal_clear),
            () -> commandOutput.setText(getString(R.string.terminal_welcome)));
        Button exitButton = createActionButton(getString(R.string.terminal_exit), this::exitTerminal);
        // 退出属于页面导航，即使命令仍在后台收尾也必须立即可用，不能受通用 busy 门闩阻止。
        exitButton.setOnClickListener(view -> exitTerminal());
        LinearLayout.LayoutParams exitParameters = new LinearLayout.LayoutParams(-2, toDisplayPixels(48));
        exitParameters.setMargins(toDisplayPixels(10), 0, 0, 0);
        toolbar.addView(exitButton, exitParameters);
        commandInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        commandInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && !busy) {
                executeTerminalCommand(commandInput, commandOutput, outputScroll);
                return true;
            }
            return false;
        });
        commandInput.requestFocus();
    }

    /**
     * 立即离开终端页面；单条命令仍受十五秒上限约束并在后台完成资源清理。
     */
    private void exitTerminal() {
        terminalVisible = false;
        showStorageHome();
    }

    /**
     * 校验并在有界文件工作线程中执行命令；输出、退出码和超时状态统一返回主线程显示。
     *
     * @param commandInput  用户输入控件
     * @param commandOutput 只读输出控件
     * @param outputScroll  输出滚动容器
     */
    private void executeTerminalCommand(EditText commandInput, TextView commandOutput, ScrollView outputScroll) {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) {
            commandInput.setError(getString(R.string.terminal_command_required));
            return;
        }
        busy = true;
        String previousOutput = commandOutput.getText().toString();
        commandOutput.setText(getString(R.string.terminal_command_started, previousOutput, command));
        commandInput.setText("");
        outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
        fileExecutor.execute(() -> {
            try {
                String result = runApplicationShellCommand(command);
                runOnUiThread(() -> {
                    busy = false;
                    if (!isDestroyed()) {
                        commandOutput.append(result);
                        outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
                        commandInput.requestFocus();
                    }
                });
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                Log.e(LOG_TAG, "执行终端命令失败 command=" + command + " reason=" + exception.getMessage(), exception);
                runOnUiThread(() -> {
                    busy = false;
                    if (!isDestroyed()) {
                        commandOutput.append(getString(R.string.terminal_failed, exception.getMessage()));
                        commandInput.requestFocus();
                    }
                });
            }
        });
    }

    /**
     * 通过 Android Shell 执行单条命令，合并标准错误并将输出落入应用缓存，避免管道写满造成死锁。
     * 超时后强制终止进程；输出只读取固定上限，缓存文件无论成功失败均尝试删除。
     *
     * @param command 用户确认执行的完整命令
     * @return 包含退出状态及有界输出的显示文本
     * @throws IOException          进程启动、缓存或输出读取失败
     * @throws InterruptedException Activity 工作线程在等待命令时被中断
     */
    private String runApplicationShellCommand(String command) throws IOException, InterruptedException {
        File outputFile = File.createTempFile("terminal-", ".log", getCacheDir());
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .start();
            boolean completed = process.waitFor(SHELL_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor();
            }
            String output = readBoundedShellOutput(outputFile);
            if (!completed) {
                return getString(R.string.terminal_timeout, SHELL_COMMAND_TIMEOUT_SECONDS, output);
            }
            return getString(R.string.terminal_completed, process.exitValue(), output);
        } finally {
            if (Objects.nonNull(process) && process.isAlive()) {
                process.destroyForcibly();
            }
            if (!outputFile.delete()) {
                Log.w(LOG_TAG, "终端临时输出删除失败 path=" + outputFile);
            }
        }
    }

    /**
     * 读取固定上限的命令输出，超过上限时追加截断说明。
     *
     * @param outputFile Shell 合并输出文件
     * @return UTF-8 输出文本
     * @throws IOException 文件读取失败
     */
    private String readBoundedShellOutput(File outputFile) throws IOException {
        try (FileInputStream input = new FileInputStream(outputFile);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int remaining = SHELL_OUTPUT_LIMIT_BYTES;
            int count;
            while (remaining > 0 && (count = input.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
                output.write(buffer, 0, count);
                remaining -= count;
            }
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            if (input.read() != -1) {
                return text + getString(R.string.terminal_output_truncated, SHELL_OUTPUT_LIMIT_BYTES);
            }
            return text;
        }
    }

    /**
     * 显示磁盘容量及授权入口；容量未知时不以零容量误导用户。
     */
    private void showStorageHome() {
        terminalVisible = false;
        currentDirectory = null;
        storageRoot = null;
        createPageLayout(getString(R.string.storage_home), getString(R.string.storage_tagline));
        LinearLayout toolbar = createButtonRow();
        if (!hasStorageAccess()) {
            addActionButton(toolbar, getString(R.string.permission_request), this::requestStorageAccess);
        } else {
            TextView permissionStatus = createPageText(getString(R.string.permission_granted), 14,
                getColor(R.color.accent));
            permissionStatus.setPadding(0, 0, toDisplayPixels(20), 0);
            toolbar.addView(permissionStatus);
        }
        addActionButton(toolbar, getString(R.string.refresh_disks), this::showStorageHome);

        if (Objects.nonNull(clipboardFile)) {
            pageLayout.addView(createPageText(getString(R.string.clipboard_pending, getString(clipboardMove ? R.string.action_move
                : R.string.action_copy), clipboardFile.getName()), 16, getColor(R.color.accent)));
        }
        List<StorageLocation> locations = StorageRepository.findStorageLocations(this);
        ScrollView storageScroll = new ScrollView(this);
        LinearLayout storageCards = new LinearLayout(this);
        storageCards.setOrientation(LinearLayout.VERTICAL);
        storageScroll.addView(storageCards);
        pageLayout.addView(storageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        for (StorageLocation location : locations) {
            long total = location.directory.getTotalSpace();
            long free = location.directory.getUsableSpace();
            String capacity = total > 0 ? getString(R.string.capacity_summary, Formatter.formatFileSize(this, free),
                Formatter.formatFileSize(this, total), Formatter.formatFileSize(this, Math.max(0, total - free))) : getString(R.string.capacity_unknown);
            LinearLayout volumeCard = new LinearLayout(this);
            volumeCard.setOrientation(LinearLayout.VERTICAL);
            volumeCard.setPadding(toDisplayPixels(22), toDisplayPixels(16), toDisplayPixels(22), toDisplayPixels(16));
            volumeCard.setBackgroundResource(R.drawable.focus_surface);
            volumeCard.setFocusable(true);
            volumeCard.setOnClickListener(view -> {
                if (!busy) {
                    storageRoot = location.directory;
                    loadDirectoryFiles(location.directory, null);
                }
            });
            volumeCard.addView(createPageText(location.displayName, 21, getColor(R.color.text_primary)));
            TextView capacityText = createPageText(capacity, 16, getColor(R.color.text_secondary));
            capacityText.setPadding(0, toDisplayPixels(8), 0, toDisplayPixels(10));
            volumeCard.addView(capacityText);
            ProgressBar capacityBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            capacityBar.setMax(1000);
            capacityBar.setProgress(total > 0 ? (int) ((total - free) * 1000.0 / total) : 0);
            capacityBar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.accent)));
            volumeCard.addView(capacityBar, new LinearLayout.LayoutParams(-1, toDisplayPixels(6)));
            TextView volumePath = createPageText(location.directory.getAbsolutePath(), 12, getColor(R.color.text_secondary));
            volumePath.setPadding(0, toDisplayPixels(8), 0, 0);
            volumeCard.addView(volumePath);
            LinearLayout.LayoutParams cardParameters = new LinearLayout.LayoutParams(-1, -2);
            cardParameters.setMargins(0, toDisplayPixels(8), 0, toDisplayPixels(12));
            storageCards.addView(volumeCard, cardParameters);
            if (storageCards.getChildCount() == 1 && hasStorageAccess()) {
                volumeCard.requestFocus();
            }
        }
        UsbManager usbManager = getSystemService(UsbManager.class);
        if (Objects.nonNull(usbManager)) {
            for (UsbDevice candidate : usbManager.getDeviceList().values()) {
                if (Objects.isNull(UsbScsiBlockDevice.findScsiStorageInterface(candidate))) {
                    continue;
                }
                String deviceName = UsbScsiBlockDevice.resolveUsbDeviceDisplayName(candidate);
                if (deviceName.isEmpty()) {
                    deviceName = getString(R.string.usb_device);
                }
                LinearLayout usbCard = new LinearLayout(this);
                usbCard.setOrientation(LinearLayout.VERTICAL);
                usbCard.setPadding(toDisplayPixels(22), toDisplayPixels(16), toDisplayPixels(22), toDisplayPixels(16));
                usbCard.setBackgroundResource(R.drawable.focus_surface);
                usbCard.setFocusable(true);
                usbCard.setOnClickListener(view -> startActivity(new Intent(this, ExfatUsbActivity.class)));
                usbCard.addView(createPageText(deviceName, 21, getColor(R.color.text_primary)));
                TextView description = createPageText(getString(R.string.usb_direct_access), 16,
                    getColor(R.color.text_secondary));
                description.setPadding(0, toDisplayPixels(8), 0, 0);
                usbCard.addView(description);
                LinearLayout.LayoutParams usbParameters = new LinearLayout.LayoutParams(-1, -2);
                usbParameters.setMargins(0, toDisplayPixels(8), 0, toDisplayPixels(12));
                storageCards.addView(usbCard, usbParameters);
            }
        }
        pageLayout.addView(createPageText(getString(R.string.usb_hint), 14,
            getColor(R.color.text_secondary)));
    }

    /**
     * 后台加载当前一级目录；无法读取与空目录分别呈现，不进行递归扫描。
     */
    private void loadDirectoryFiles(File directory, String selectedName) {
        if (busy) {
            return;
        }
        terminalVisible = false;
        File previousDirectory = currentDirectory;
        busy = true;
        currentDirectory = directory;
        createPageLayout(directory.getName().isEmpty() ? getString(R.string.files_title) : directory.getName(), directory.getAbsolutePath());
        addDirectoryBreadcrumbs();
        pageLayout.addView(createPageText(getString(R.string.files_loading), 20, getColor(R.color.text_primary)));
        fileExecutor.execute(() -> {
            try {
                File[] files = listDirectoryEntries(directory);
                if (Objects.isNull(files)) {
                    throw new IOException(getString(R.string.directory_unreadable));
                }
                Arrays.sort(files, Comparator.comparing(File::isDirectory).reversed()
                    .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                runOnUiThread(() -> {
                    busy = false;
                    if (!isDestroyed() && !recreateForPendingTheme()) {
                        visibleFiles = Arrays.asList(files);
                        showDirectoryFiles(selectedName);
                    }
                });
            } catch (IOException | RuntimeException exception) {
                Log.e(LOG_TAG, "读取目录失败 directory=" + directory + " reason=" + exception.getMessage(), exception);
                runOnUiThread(() -> {
                    busy = false;
                    if (!isDestroyed()) {
                        if (Objects.nonNull(previousDirectory)) {
                            currentDirectory = previousDirectory;
                            showDirectoryFiles(directory.getName());
                        } else {
                            showStorageHome();
                        }
                        showUserMessage(getString(R.string.read_failed), exception.getMessage())
                            .setOnDismissListener(dialog -> recreateForPendingTheme());
                    }
                });
            }
        });
    }

    /**
     * 读取一级目录；厂商固件拒绝枚举 Linux 根目录时，返回当前设备实际存在的标准顶层路径。
     * 普通子目录不使用候选路径兜底，避免把权限失败误报为空目录。
     *
     * @param directory 需要读取的绝对目录
     * @return 目录内容；普通目录读取失败时返回 {@code null}
     */
    private File[] listDirectoryEntries(File directory) {
        File[] files = directory.listFiles();
        if (Objects.nonNull(files) || !directory.equals(SYSTEM_ROOT_DIRECTORY)) {
            return files;
        }
        List<File> fallbackEntries = new ArrayList<>();
        for (String path : SYSTEM_ROOT_FALLBACK_PATHS) {
            File candidate = new File(path);
            if (candidate.exists() && candidate.isDirectory()) {
                fallbackEntries.add(candidate);
            }
        }
        Log.w(LOG_TAG, "系统拒绝枚举根目录，使用已存在的标准顶层路径 entries=" + fallbackEntries.size());
        return fallbackEntries.toArray(new File[0]);
    }

    /**
     * 显示列表与明确的操作按钮，菜单键不是访问文件操作的唯一方式。
     */
    private void showDirectoryFiles(String selectedName) {
        createPageLayout(currentDirectory.equals(storageRoot) ? getString(storageRoot.equals(SYSTEM_ROOT_DIRECTORY)
                                                                          ? R.string.system_root : R.string.disk_files) : currentDirectory.getName(),
            getResources().getQuantityString(R.plurals.directory_summary, visibleFiles.size(),
                currentDirectory.getAbsolutePath(), visibleFiles.size()));
        addDirectoryBreadcrumbs();
        LinearLayout toolbar = createButtonRow();
        addActionButton(toolbar, getString(R.string.parent_folder), this::navigateParentDirectory);
        addActionButton(toolbar, getString(R.string.file_actions), this::showSelectedFileActions);
        addActionButton(toolbar, getString(R.string.action_new_folder), this::requestNewDirectory);
        addActionButton(toolbar, getString(R.string.refresh), this::refreshCurrentDirectory);
        if (Objects.nonNull(clipboardFile)) {
            LinearLayout clipboardToolbar = createButtonRow();
            addActionButton(clipboardToolbar, getString(clipboardMove ? R.string.paste_move : R.string.paste_copy), this::pasteClipboardFile);
            addActionButton(clipboardToolbar, getString(R.string.cancel_clipboard), () -> {
                clipboardFile = null;
                showDirectoryFiles(null);
            });
            clipboardToolbar.addView(createPageText(clipboardFile.getName(), 14, getColor(R.color.text_primary)),
                new LinearLayout.LayoutParams(0, -2, 1));
        }
        if (visibleFiles.isEmpty()) {
            pageLayout.addView(createPageText(getString(R.string.folder_empty), 22, getColor(R.color.text_secondary)));
            return;
        }
        fileGridView = new GridView(this);
        fileGridView.setAdapter(new FileEntryAdapter(this, visibleFiles));
        fileGridView.setSelector(R.drawable.focus_surface);
        fileGridView.setDrawSelectorOnTop(false);
        fileGridView.setNumColumns(4);
        fileGridView.setHorizontalSpacing(toDisplayPixels(12));
        fileGridView.setVerticalSpacing(toDisplayPixels(12));
        fileGridView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        fileGridView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            /** {@inheritDoc} 保存用户的网格选择，离开网格不重新指定其他文件。 */
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedFile = visibleFiles.get(position);
            }

            /** {@inheritDoc} 工具栏获得焦点时保留已选文件；页面重建时统一清空。 */
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 保留同一目录内的明确选择，使顶部“操作”按钮仍指向该文件。
            }
        });
        fileGridView.setOnItemClickListener((parent, view, position, id) -> openFileEntry(visibleFiles.get(position)));
        fileGridView.setOnItemLongClickListener((parent, view, position, id) -> {
            showFileActions(visibleFiles.get(position));
            return true;
        });
        pageLayout.addView(fileGridView, new LinearLayout.LayoutParams(-1, 0, 1));
        pageLayout.addView(createPageText(getString(R.string.navigation_hint), 14,
            getColor(R.color.text_secondary)));
        fileGridView.requestFocus();
        int selection = 0;
        if (Objects.nonNull(selectedName)) {
            for (int index = 0; index < visibleFiles.size(); index++) {
                if (selectedName.equals(visibleFiles.get(index).getName())) {
                    selection = index;
                    break;
                }
            }
        }
        fileGridView.setSelection(selection);
    }

    /**
     * 添加可聚焦的大号路径面包屑；点击祖先目录直接跳转，并在目标目录恢复原路径下一级的焦点。
     * 路径必须能够回溯到当前浏览边界，异常恢复状态不会借此越界。
     */
    private void addDirectoryBreadcrumbs() {
        List<File> directories = resolveBreadcrumbDirectories();
        if (directories.isEmpty()) {
            return;
        }
        HorizontalScrollView breadcrumbScroll = new HorizontalScrollView(this);
        breadcrumbScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout breadcrumbRow = new LinearLayout(this);
        breadcrumbRow.setGravity(Gravity.CENTER_VERTICAL);
        List<StorageLocation> storageLocations = StorageRepository.findStorageLocations(this);
        for (int index = 0; index < directories.size(); index++) {
            File directory = directories.get(index);
            String selectedName = index + 1 < directories.size() ? directories.get(index + 1).getName() : null;
            Button breadcrumb = createActionButton(resolveBreadcrumbLabel(directory, storageLocations),
                () -> loadDirectoryFiles(directory, selectedName));
            breadcrumb.setTextSize(18);
            LinearLayout.LayoutParams breadcrumbParameters = new LinearLayout.LayoutParams(-2, toDisplayPixels(52));
            breadcrumbParameters.setMargins(0, 0, toDisplayPixels(8), 0);
            breadcrumbRow.addView(breadcrumb, breadcrumbParameters);
            if (index + 1 < directories.size()) {
                TextView separator = createPageText("›", 22, getColor(R.color.text_secondary));
                separator.setPadding(0, 0, toDisplayPixels(8), 0);
                breadcrumbRow.addView(separator);
            }
        }
        breadcrumbScroll.addView(breadcrumbRow);
        LinearLayout.LayoutParams scrollParameters = new LinearLayout.LayoutParams(-1, toDisplayPixels(60));
        scrollParameters.setMargins(0, 0, 0, toDisplayPixels(8));
        pageLayout.addView(breadcrumbScroll, scrollParameters);
    }

    /**
     * 从当前目录向上构造浏览边界内的有序路径；无法到达边界时返回空列表。
     *
     * @return 从浏览边界到当前目录的路径，列表仅在主线程本次绘制中使用
     */
    private List<File> resolveBreadcrumbDirectories() {
        List<File> directories = new ArrayList<>();
        if (Objects.isNull(storageRoot) || Objects.isNull(currentDirectory)) {
            return directories;
        }
        File directory = currentDirectory;
        while (Objects.nonNull(directory)) {
            directories.add(0, directory);
            if (directory.equals(storageRoot)) {
                return directories;
            }
            directory = directory.getParentFile();
        }
        directories.clear();
        return directories;
    }

    /**
     * 优先使用存储卷名称作为边界标签，系统根显示斜杠，其余层级显示真实目录名。
     *
     * @param directory        面包屑对应目录
     * @param storageLocations 当前系统公开的存储卷快照
     * @return 适合按钮显示的本地化或真实目录名称
     */
    private String resolveBreadcrumbLabel(File directory, List<StorageLocation> storageLocations) {
        if (directory.equals(SYSTEM_ROOT_DIRECTORY)) {
            return "/";
        }
        for (StorageLocation storageLocation : storageLocations) {
            if (directory.equals(storageLocation.directory)) {
                return storageLocation.displayName;
            }
        }
        return directory.getName();
    }

    /**
     * 只打开当前存储根下的真实文件；内部导入模式仅接受本应用发起的有结果调用，
     * 防止外部应用设置 Intent extra 获取电视文件的绝对路径。
     */
    private void openFileEntry(File file) {
        try {
            validateStorageBoundary(file);
            if (file.isDirectory()) {
                loadDirectoryFiles(file, null);
                return;
            }
            if (getIntent().getBooleanExtra(EXTRA_PICK_INTERNAL_FILE, false)
                && getPackageName().equals(getCallingPackage())) {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_PICKED_FILE_PATH, file.getAbsolutePath()));
                finish();
                return;
            }
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            String mimeType = dot < 0 ? null : MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                    Objects.isNull(mimeType) ? "application/octet-stream" : mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (IOException | IllegalArgumentException | SecurityException |
                 ActivityNotFoundException exception) {
            Log.e(LOG_TAG, "打开文件失败 source=" + file + " reason=" + exception.getMessage(), exception);
            showUserMessage(getString(R.string.open_failed), getString(R.string.open_error_hint, exception.getMessage()));
        }
    }

    /**
     * 无焦点选择时明确提示，不擅自将第一个文件作为破坏性操作目标。
     */
    private void showSelectedFileActions() {
        if (Objects.nonNull(fileGridView) && fileGridView.getSelectedItemPosition() >= 0) {
            showFileActions(visibleFiles.get(fileGridView.getSelectedItemPosition()));
        } else if (Objects.nonNull(selectedFile) && visibleFiles.contains(selectedFile)) {
            showFileActions(selectedFile);
        } else {
            showUserMessage(getString(R.string.select_file), getString(R.string.select_file_hint));
        }
    }

    /**
     * 显示完整文件名，所有操作通过方向键与确定键可达。
     */
    private void showFileActions(File file) {
        if (busy) {
            return;
        }
        new AlertDialog.Builder(this).setTitle(file.getName())
            .setItems(new String[]{getString(R.string.action_open), getString(R.string.action_copy), getString(R.string.action_move), getString(R.string.action_rename), getString(R.string.action_delete), getString(R.string.file_details)}, (dialog, which) -> {
                switch (which) {
                    case 0:
                        openFileEntry(file);
                        break;
                    case 1:
                    case 2:
                        clipboardFile = file;
                        clipboardMove = which == 2;
                        showDirectoryFiles(file.getName());
                        Toast.makeText(this, getString(R.string.paste_hint), Toast.LENGTH_LONG).show();
                        break;
                    case 3:
                        requestFileRename(file);
                        break;
                    case 4:
                        confirmFileDeletion(file);
                        break;
                    default:
                        showUserMessage(getString(R.string.file_info), getString(R.string.file_detail_summary, file.getAbsolutePath(),
                            file.isDirectory() ? getString(R.string.folder_type) : Formatter.formatFileSize(this, file.length()),
                            getString(file.canWrite() ? R.string.file_writable : R.string.file_readonly)));
                        break;
                }
            }).setNegativeButton(getString(R.string.back), null).show();
    }

    /**
     * 永久删除再次确认且默认焦点放在取消，避免连续确定误删文件。
     */
    private void confirmFileDeletion(File file) {
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(getString(R.string.delete_title))
            .setMessage(getString(R.string.delete_warning, file.getAbsolutePath()))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.delete_permanent), (ignored, which) -> startFileOperation(FileAction.DELETE, file, null))
            .create();
        dialog.setOnShowListener(ignored -> {
            Button cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            cancelButton.setFocusableInTouchMode(true);
            cancelButton.requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.destructive));
        });
        dialog.show();
    }

    /**
     * 重命名保留完整原名，最终名称由操作引擎验证。
     */
    private void requestFileRename(File file) {
        EditText input = createNameInput(file.getName());
        new AlertDialog.Builder(this).setTitle(getString(R.string.action_rename)).setView(input)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.save), (dialog, which) -> startFileOperation(FileAction.RENAME, file, input.getText().toString()))
            .show();
    }

    /**
     * 为组织文件提供显式新建目录入口，不自动创建未知父目录。
     */
    private void requestNewDirectory() {
        EditText input = createNameInput("");
        new AlertDialog.Builder(this).setTitle(getString(R.string.action_new_folder)).setView(input)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.create), (dialog, which) -> startFileOperation(FileAction.CREATE_DIRECTORY, currentDirectory,
                input.getText().toString())).show();
    }

    /**
     * 输入通过系统电视输入法；限制为单行，不伪造英文键盘来替代中文输入。
     */
    private EditText createNameInput(String initialName) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setText(initialName);
        input.setSelectAllOnFocus(true);
        return input;
    }

    /**
     * 粘贴前明确显示目标；跨卷移动的源删除风险在确认页面可见。
     */
    private void pasteClipboardFile() {
        if (Objects.isNull(clipboardFile)) {
            return;
        }
        new AlertDialog.Builder(this).setTitle(clipboardMove ? getString(R.string.move_here) : getString(R.string.copy_here))
            .setMessage(getString(R.string.paste_confirmation, clipboardFile.getAbsolutePath(), currentDirectory.getAbsolutePath(),
                getString(clipboardMove ? R.string.move_warning : R.string.copy_warning)))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.start), (dialog, which) -> startFileOperation(clipboardMove ? FileAction.MOVE : FileAction.COPY,
                clipboardFile, null)).show();
    }

    /**
     * 串行执行用户已确认的写操作，执行期间保持屏幕唤醒并禁止关闭进度对话框。
     * 失败在 UI 展示根因，日志保留完整上下文；不会自动重试破坏性动作。
     */
    private void startFileOperation(FileAction action, File source, String newName) {
        if (busy) {
            return;
        }
        busy = true;
        cancellationRequested.set(false);
        lastProgressTime = 0;
        File destination = currentDirectory;
        File boundary = storageRoot;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        operationProgress = createPageText(getString(R.string.operation_running, getString(action.labelResource)), 19, getColor(R.color.text_primary));
        operationProgress.setPadding(32, 24, 32, 24);
        operationDialog = new AlertDialog.Builder(this).setTitle(getString(R.string.operation_title, getString(action.labelResource), source.getName()))
            .setView(operationProgress).setCancelable(false).setNegativeButton(getString(R.string.request_cancel), null).create();
        operationDialog.setOnShowListener(dialog -> operationDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            .setOnClickListener(view -> {
                cancellationRequested.set(true);
                operationProgress.setText(getString(R.string.cancel_pending));
                view.setEnabled(false);
            }));
        operationDialog.show();
        fileExecutor.execute(() -> {
            String failure = null;
            try {
                FileOperations fileOperations = new FileOperations(cancellationRequested, this::reportOperationProgress, this::getString);
                // 源可能位于另一个卷，逐一核对当前系统仍公开的存储根，禁止根目录写操作。
                validateOperationSource(source, action == FileAction.CREATE_DIRECTORY);
                switch (action) {
                    case COPY:
                    case MOVE:
                        if (!destination.getCanonicalFile().toPath().startsWith(boundary.getCanonicalFile().toPath())) {
                            throw new IOException(getString(R.string.destination_outside));
                        }
                        if (action == FileAction.MOVE) {
                            fileOperations.moveFileTree(source.toPath(), destination.toPath());
                        } else {
                            fileOperations.copyFileTree(source.toPath(), destination.toPath());
                        }
                        break;
                    case DELETE:
                        fileOperations.deleteFileTree(source.toPath());
                        break;
                    case RENAME:
                        fileOperations.renameFileEntry(source.toPath(), newName);
                        break;
                    default:
                        fileOperations.validateFileName(newName);
                        Files.createDirectory(source.toPath().resolve(newName));
                        break;
                }
            } catch (IOException | RuntimeException exception) {
                Log.e(LOG_TAG, "文件操作失败 action=" + action + " source=" + source + " destination=" + destination
                    + " newName=" + newName + " reason=" + exception.getMessage(), exception);
                failure = Objects.nonNull(exception.getMessage()) ? exception.getMessage() : getString(R.string.error_unknown);
            }
            String finalFailure = failure;
            runOnUiThread(() -> {
                busy = false;
                if (isDestroyed()) {
                    return;
                }
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                operationDialog.dismiss();
                if (Objects.isNull(finalFailure)) {
                    if (action == FileAction.COPY || action == FileAction.MOVE) {
                        clipboardFile = null;
                    }
                    Toast.makeText(this, getString(R.string.operation_success, getString(action.labelResource)), Toast.LENGTH_LONG).show();
                    refreshCurrentDirectory();
                } else {
                    // 先让用户读完失败原因，再刷新或应用等待中的主题，避免重建吞掉错误提示。
                    showUserMessage(getString(R.string.operation_failed, getString(action.labelResource)),
                        getString(R.string.operation_failure_details, finalFailure))
                        .setOnDismissListener(dialog -> refreshCurrentDirectory());
                }
            });
        });
    }

    /**
     * 进度每 200 毫秒最多投递一次，防止高速文件复制淹没主线程消息队列。
     */
    private void reportOperationProgress(String fileName, long copiedBytes) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastProgressTime < 200) {
            return;
        }
        lastProgressTime = now;
        runOnUiThread(() -> {
            if (!isDestroyed() && !cancellationRequested.get()) {
                operationProgress.setText(getString(R.string.operation_progress, fileName, Formatter.formatFileSize(this, copiedBytes)));
            }
        });
    }

    /**
     * 核对真实路径属于当前存储卷，拒绝符号链接跳转。
     */
    private void validateStorageBoundary(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath()) || Objects.isNull(storageRoot)
            || !file.getCanonicalFile().toPath().startsWith(storageRoot.getCanonicalFile().toPath())) {
            throw new IOException(getString(R.string.boundary_error));
        }
    }

    /**
     * 写操作允许用户已进入的任意绝对路径；系统根本身只能作为新建目录的父路径。
     */
    private void validateOperationSource(File file, boolean allowRoot) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) {
            throw new IOException(getString(R.string.link_unsupported));
        }
        File canonical = file.getCanonicalFile();
        if (!canonical.isAbsolute() || (!allowRoot && Objects.isNull(canonical.getParentFile()))) {
            throw new IOException(getString(R.string.source_unavailable));
        }
    }

    /**
     * 返回父目录时恢复原文件夹焦点；卷根返回首页。
     */
    private void navigateParentDirectory() {
        if (busy) {
            return;
        }
        if (Objects.isNull(currentDirectory) || currentDirectory.equals(storageRoot)) {
            showStorageHome();
        } else {
            loadDirectoryFiles(currentDirectory.getParentFile(), currentDirectory.getName());
        }
    }

    /**
     * 刷新当前位置，不更改剪贴板。
     */
    private void refreshCurrentDirectory() {
        if (busy || recreateForPendingTheme()) {
            return;
        }
        if (Objects.isNull(currentDirectory)) {
            showStorageHome();
        } else {
            loadDirectoryFiles(currentDirectory, null);
        }
    }

    /**
     * 查询 Android 11+ 的所有文件访问权限，不能通过版本或设置页跳转结果推断已经授权。
     */
    private boolean hasStorageAccess() {
        return Environment.isExternalStorageManager();
    }

    /**
     * 未授权的安装首次启动时显示用途说明，用户确认后才发起系统授权。
     * 主线程调用；显示前持久标记，取消、返回或重建均不重复弹出，仍可从首页手动请求。
     * 该标记只表示引导已展示，实际授权状态始终由系统查询。
     */
    private void showInitialStoragePermissionGuidance() {
        SharedPreferences permissionPreferences = getSharedPreferences("storage_permission", MODE_PRIVATE);
        if (hasStorageAccess() || permissionPreferences.getBoolean("guidance_shown", false)) {
            return;
        }
        permissionPreferences.edit().putBoolean("guidance_shown", true).apply();
        new AlertDialog.Builder(this).setTitle(R.string.permission_request)
            .setMessage(R.string.permission_initial_hint)
            .setPositiveButton(R.string.permission_request, (dialog, which) -> requestStorageAccess())
            .setNegativeButton(R.string.cancel, null).show();
    }

    /**
     * 依次尝试应用专属和通用所有文件访问页。
     * 两者均失败时保留明确的手动设置入口，不把页面成功打开视为已经授权。
     *
     * @see <a href="https://developer.android.com/training/data-storage/manage-all-files">所有文件访问权限</a>
     */
    private void requestStorageAccess() {
        if (hasStorageAccess()) {
            showStorageHome();
            return;
        }
        if (tryOpenStorageSettings(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:" + getPackageName())))
            || tryOpenStorageSettings(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))) {
            return;
        }
        new AlertDialog.Builder(this).setTitle(R.string.permission_page_missing)
            .setMessage(R.string.permission_page_hint)
            .setPositiveButton(R.string.open_app_settings, (dialog, which) -> openStoragePermissionSettings())
            .setNegativeButton(R.string.cancel, null).show();
    }

    /**
     * 用户确认后打开应用详情，缺失或受限时依次回退应用列表、系统设置。
     * 厂商可能不提供所需开关；全部失败只提示，不循环跳转或触发普通读写权限假授权。
     *
     * @see <a href="https://developer.android.com/reference/android/provider/Settings">系统设置 Intent 契约</a>
     */
    private void openStoragePermissionSettings() {
        if (tryOpenStorageSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + getPackageName())))
            || tryOpenStorageSettings(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
            || tryOpenStorageSettings(new Intent(Settings.ACTION_SETTINGS))) {
            return;
        }
        showUserMessage(getString(R.string.permission_page_missing), getString(R.string.permission_settings_missing_hint));
    }

    /**
     * 尝试一次设置页跳转；只处理系统缺少入口及访问拒绝，保留完整异常和设备上下文供真机诊断。
     *
     * @param intent 标准设置动作，应用专属入口须携带当前包名
     * @return 系统是否接受启动请求；不代表用户已授予文件权限
     */
    private boolean tryOpenStorageSettings(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException exception) {
            Log.w(LOG_TAG, "打开存储设置失败 action=" + intent.getAction() + " data=" + intent.getData()
                + " package=" + getPackageName() + " manufacturer=" + Build.MANUFACTURER
                + " model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT + " firmware=" + Build.DISPLAY
                + " exception=" + exception.getClass().getSimpleName() + " reason=" + exception.getMessage(), exception);
            return false;
        }
    }

    /**
     * 展示真实运行环境，解决电视设置页隐藏底层系统版本的问题，不读取设备唯一标识。
     */
    private void showDeviceInformation() {
        showUserMessage(getString(R.string.device_info), getString(R.string.device_summary,
            Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
            String.join(", ", Build.SUPPORTED_ABIS), Build.DISPLAY,
            getString(hasStorageAccess() ? R.string.enabled : R.string.disabled), BuildConfig.VERSION_NAME));
    }

    /**
     * 展示来自构建配置的版本和用户确认的作者、许可及项目地址，入口适配遥控器。
     */
    private void showAboutApplication() {
        new AlertDialog.Builder(this).setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_summary, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
            .setPositiveButton(getString(R.string.version_notes), (dialog, which) -> showBundledDocument(
                getString(R.string.version_notes), localizedDocumentPath("版本说明", "Release-notes")))
            .setNeutralButton(getString(R.string.open_source), (dialog, which) -> showLicenseMenu())
            .setNegativeButton(getString(R.string.language), (dialog, which) -> showLanguageOptions())
            .show();
    }

    /**
     * 外观入口集中提供主题与语言；保留关于页语言按钮便于已有用户找到设置。
     */
    private void showAppearanceOptions() {
        new AlertDialog.Builder(this).setTitle(R.string.appearance)
            .setItems(new String[]{getString(R.string.theme_mode), getString(R.string.language)}, (dialog, which) -> {
                if (which == 0) {
                    showThemeOptions();
                } else {
                    showLanguageOptions();
                }
            }).setNegativeButton(R.string.cancel, null).show();
    }

    /**
     * 三种主题选择持久化后重建页面；入口按钮在文件任务进行中不可执行。
     */
    private void showThemeOptions() {
        String[] modes = {AppAppearance.LIGHT, AppAppearance.DARK, AppAppearance.SYSTEM};
        String currentMode = AppAppearance.readThemeMode(this);
        new AlertDialog.Builder(this).setTitle(R.string.theme_mode)
            .setSingleChoiceItems(new String[]{getString(R.string.theme_light), getString(R.string.theme_dark),
                getString(R.string.follow_system)}, Arrays.asList(modes).indexOf(currentMode), (dialog, which) -> {
                dialog.dismiss();
                if (!currentMode.equals(modes[which]) && !busy) {
                    AppAppearance.saveThemeMode(this, modes[which]);
                    recreate();
                }
            }).setNegativeButton(R.string.cancel, null).show();
    }

    /**
     * 当前应用支持中文与英文，其他系统语言使用默认英文文档。
     */
    private String localizedDocumentPath(String chineseName, String englishName) {
        boolean chinese = "zh".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());
        return "docs/" + (chinese ? chineseName : englishName) + ".md";
    }

    /**
     * 项目许可证与第三方许可分开显示，第三方组件不被重新授权为本项目协议。
     */
    private void showLicenseMenu() {
        new AlertDialog.Builder(this).setTitle(getString(R.string.open_source))
            .setItems(new String[]{getString(R.string.project_license), getString(R.string.third_party_notices),
                getString(R.string.project_homepage)}, (dialog, which) -> {
                if (which == 0) {
                    showBundledDocument(getString(R.string.project_license), "LICENSE");
                } else if (which == 1) {
                    showBundledDocument(getString(R.string.third_party_notices),
                        localizedDocumentPath("第三方声明", "Third-party-notices"));
                } else {
                    openProjectHomepage();
                }
            }).setNegativeButton(getString(R.string.back), null).show();
    }

    /**
     * 语言偏好仅在无文件任务时变更；重建页面回到首页，不更改电视的系统语言。
     */
    private void showLanguageOptions() {
        String[] tags = {"", "zh-CN", "en"};
        String currentTag = AppLanguage.readLanguageTag(this);
        int selectedIndex = Arrays.asList(tags).indexOf(currentTag);
        new AlertDialog.Builder(this).setTitle(getString(R.string.language))
            .setSingleChoiceItems(new String[]{getString(R.string.follow_system), "简体中文", "English"},
                selectedIndex, (dialog, which) -> {
                    AppLanguage.saveLanguageTag(this, tags[which]);
                    dialog.dismiss();
                    recreate();
                }).setNegativeButton(getString(R.string.cancel), null).show();
    }

    /**
     * 显示随 APK 打包的短文档，离线可读并可用方向键滚动。
     * 文件来自构建时固定白名单，不接受用户路径；读取失败保留文件名与完整异常日志。
     */
    private void showBundledDocument(String title, String assetPath) {
        try (InputStream input = getAssets().open(assetPath);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            ScrollView scroll = new ScrollView(this);
            TextView document = createPageText(new String(output.toByteArray(), StandardCharsets.UTF_8), 17, getColor(R.color.text_primary));
            document.setPadding(toDisplayPixels(24), toDisplayPixels(16), toDisplayPixels(24), toDisplayPixels(16));
            scroll.addView(document);
            scroll.setFocusable(true);
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(scroll)
                .setPositiveButton(getString(R.string.close), null).create();
            dialog.setOnShowListener(ignored -> scroll.requestFocus());
            dialog.show();
        } catch (IOException exception) {
            Log.e(LOG_TAG, "读取内置文档失败 asset=" + assetPath + " reason=" + exception.getMessage(), exception);
            showUserMessage(getString(R.string.documents_failed), exception.getMessage());
        }
    }

    /**
     * 使用系统浏览器打开固定项目地址；未安装浏览器时显示地址供用户在其他设备访问。
     */
    private void openProjectHomepage() {
        String homepage = "https://github.com/JinlongLiao/TvFinder";
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(homepage)));
        } catch (ActivityNotFoundException | SecurityException exception) {
            Log.e(LOG_TAG, "打开项目主页失败 url=" + homepage + " reason=" + exception.getMessage(), exception);
            showUserMessage(getString(R.string.link_failed), homepage);
        }
    }

    /**
     * {@inheritDoc} 支持遥控器菜单键，无菜单键遥控器可长按确定。
     */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && Objects.nonNull(currentDirectory) && !busy) {
            showSelectedFileActions();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * {@inheritDoc} 目录内返回上级，首页返回退出，任务执行中不离开页面。
     */
    @Override
    public void onBackPressed() {
        if (busy) {
            return;
        }
        if (Objects.nonNull(currentDirectory)) {
            navigateParentDirectory();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * 将逻辑尺寸转换为物理像素，避免高分辨率电视上控件过小。
     */
    private int toDisplayPixels(int densityPixels) {
        return Math.round(densityPixels * getResources().getDisplayMetrics().density);
    }

    /**
     * 创建只读文字块，焦点仅留给可操作控件。
     */
    private TextView createPageText(String text, int size, int color) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextSize(size);
        textView.setTextColor(color);
        return textView;
    }

    /**
     * 工具栏横排承载最常用操作，放在文件列表之前便于方向键上移访问。
     */
    private LinearLayout createButtonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, toDisplayPixels(10));
        pageLayout.addView(row);
        return row;
    }

    /**
     * 为按钮设置可见的描边焦点状态，动作执行前统一检查任务门闩。
     */
    private Button createActionButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(getColor(R.color.text_primary));
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.focus_surface);
        button.setPadding(toDisplayPixels(14), toDisplayPixels(6), toDisplayPixels(14), toDisplayPixels(6));
        button.setOnClickListener(view -> {
            if (!busy) {
                action.run();
            }
        });
        return button;
    }

    /**
     * 侧栏导航保持统一高度和左对齐，焦点描边与文件区共享色彩。
     */
    private void addNavigationButton(LinearLayout navigation, String label, Runnable action) {
        Button button = createActionButton(label, action);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setTextSize(16);
        button.setBackgroundResource(R.drawable.navigation_surface);
        boolean active = !terminalVisible && Objects.isNull(currentDirectory)
            && label.equals(getString(R.string.storage_home));
        active |= terminalVisible && label.equals(getString(R.string.terminal));
        active |= Objects.nonNull(storageRoot) && storageRoot.equals(SYSTEM_ROOT_DIRECTORY)
            && label.equals(getString(R.string.system_root));
        for (StorageLocation location : StorageRepository.findStorageLocations(this)) {
            active |= Objects.nonNull(storageRoot) && storageRoot.equals(location.directory)
                && label.equals(location.displayName);
        }
        button.setSelected(active);
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(-1, toDisplayPixels(48));
        parameters.setMargins(0, 0, 0, toDisplayPixels(10));
        navigation.addView(button, parameters);
    }

    /**
     * 按内容宽度排列工具栏按钮，并保留方向键焦点之间的视觉间距。
     */
    private void addActionButton(LinearLayout row, String label, Runnable action) {
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(-2, toDisplayPixels(46));
        parameters.setMargins(0, 0, toDisplayPixels(10), 0);
        row.addView(createActionButton(label, action), parameters);
    }

    /**
     * 使用遥控器可关闭的原生对话框显示信息与可诊断失败。
     *
     * @param title   本地化标题
     * @param message 说明或错误根因
     * @return 已显示的对话框，调用方可在关闭后刷新目录或应用待处理的主题
     */
    private AlertDialog showUserMessage(String title, String message) {
        return new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton(getString(R.string.okay), null).show();
    }
}
