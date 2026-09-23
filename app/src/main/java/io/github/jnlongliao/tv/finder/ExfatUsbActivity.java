package io.github.jnlongliao.tv.finder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 遥控器可操作的 USB FAT/exFAT 文件管理页，独立于电视固件的存储挂载能力。
 * USB 权限由系统逐设备授予；所有文件 I/O 在单个工作线程串行执行，页面只在主线程更新。
 *
 * @see <a href="https://developer.android.com/develop/connectivity/usb/host">Android USB host</a>
 */
public final class ExfatUsbActivity extends Activity {
    /**
     * USB 授权广播仅在本应用进程内接收。
     */
    private static final String USB_PERMISSION_ACTION = "io.github.jnlongliao.tv.finder.USB_FATFS_PERMISSION";
    /**
     * 应用内文件选择请求码，仅用于从电视导入单个文件。
     */
    private static final int IMPORT_REQUEST_CODE = 41;
    /**
     * 文件流读写缓冲区大小，单位字节。
     */
    private static final int FILE_BUFFER_BYTES = 16 * 1024;
    /**
     * USB 文件操作串行工作线程。
     */
    private final ExecutorService usbExecutor = Executors.newSingleThreadExecutor(
        runnable -> new Thread(runnable, "tv-usb-fatfs"));
    /**
     * Android USB 服务。
     */
    private UsbManager usbManager;
    /**
     * 本次选中的 USB Mass Storage 设备。
     */
    private UsbDevice usbDevice;
    /**
     * 后台工作线程独占的文件系统卷。
     */
    private FatFsVolume fatFsVolume;
    /**
     * 主线程当前展示的卷内目录。
     */
    private String currentDirectory = "/";
    /**
     * 目录标题视图。
     */
    private TextView directoryTextView;
    /** USB 设备报告的名称，同时显示在侧栏和内容区。 */
    private TextView deviceNameTextView;
    /** USB 侧栏入口，设备枚举后更新成实际名称。 */
    private Button usbNavigationButton;
    /**
     * 状态和错误视图。
     */
    private TextView statusTextView;
    /**
     * 可通过遥控器方向键聚焦的文件列表。
     */
    private GridView entryGridView;
    /**
     * 本页剪贴板源卷内路径。
     */
    private String clipboardPath;
    /**
     * 剪贴板条目是否为目录。
     */
    private boolean clipboardDirectory;
    /**
     * 剪贴板是否执行移动。
     */
    private boolean clipboardMove;
    /**
     * 主线程任务门闩，防止同一文件操作重复触发。
     */
    private boolean busy;
    /**
     * 是否已注册授权接收器。
     */
    private boolean receiverRegistered;

    /**
     * {@inheritDoc} 与主文件管理页共享语言和明暗主题选择。
     */
    @Override
    protected void attachBaseContext(Context context) {
        super.attachBaseContext(AppAppearance.applyThemeContext(AppLanguage.localizeAppContext(context)));
    }

    /**
     * {@inheritDoc} 构建遥控器界面后请求所选 USB 设备的临时授权。
     */
    @Override
    protected void onCreate(Bundle state) {
        setTheme(R.style.AppTheme);
        super.onCreate(state);
        buildUsbPage();
        IntentFilter permissionFilter = new IntentFilter(USB_PERMISSION_ACTION);
        ContextCompat.registerReceiver(this, permissionReceiver, permissionFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        usbManager = getSystemService(UsbManager.class);
        if (Objects.isNull(usbManager)) {
            showStatus(getString(R.string.usb_not_found));
            return;
        }
        for (UsbDevice candidate : usbManager.getDeviceList().values()) {
            if (Objects.nonNull(UsbScsiBlockDevice.findScsiStorageInterface(candidate))) {
                usbDevice = candidate;
                break;
            }
        }
        if (Objects.isNull(usbDevice)) {
            showStatus(getString(R.string.usb_not_found));
            return;
        }
        String displayName = UsbScsiBlockDevice.resolveUsbDeviceDisplayName(usbDevice);
        if (!displayName.isEmpty()) {
            deviceNameTextView.setText(displayName);
            usbNavigationButton.setText(displayName);
        }
        if (usbManager.hasPermission(usbDevice)) {
            mountUsbVolume();
        } else {
            showStatus(getString(R.string.usb_connecting));
            Intent permissionIntent = new Intent(USB_PERMISSION_ACTION).setPackage(getPackageName());
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, permissionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            usbManager.requestPermission(usbDevice, pendingIntent);
        }
    }

    /**
     * USB 权限结果只接受当前选定设备，避免其他设备广播触发错误挂载。
     */
    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        /** {@inheritDoc} */
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice grantedDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (!USB_PERMISSION_ACTION.equals(intent.getAction()) || Objects.isNull(usbDevice)
                || !usbDevice.equals(grantedDevice)) {
                return;
            }
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                mountUsbVolume();
            } else {
                showStatus(getString(R.string.usb_permission_denied));
            }
        }
    };

    /**
     * 复用普通文件页的左右布局、侧栏尺寸、四列文件卡片和焦点样式。
     */
    private void buildUsbPage() {
        LinearLayout shell = new LinearLayout(this);
        shell.setPadding(dp(32), dp(24), dp(32), dp(24));
        shell.setBackgroundColor(getColor(R.color.page_background));
        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.VERTICAL);
        navigation.setPadding(0, dp(8), dp(18), 0);
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView brandIcon = new ImageView(this);
        brandIcon.setImageResource(R.drawable.brand_mark);
        brand.addView(brandIcon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView brandName = new TextView(this);
        brandName.setText("TV Finder");
        brandName.setTextSize(21);
        brandName.setTextColor(getColor(R.color.text_primary));
        brandName.setPadding(dp(8), 0, 0, 0);
        brand.addView(brandName);
        navigation.addView(brand);
        TextView tagline = new TextView(this);
        tagline.setText(R.string.app_name);
        tagline.setTextSize(14);
        tagline.setTextColor(getColor(R.color.text_secondary));
        tagline.setPadding(0, dp(4), 0, dp(32));
        navigation.addView(tagline);
        addUsbNavigationButton(navigation, getString(R.string.storage_home), this::finish);
        addUsbNavigationButton(navigation, getString(R.string.internal_storage), this::finish);
        usbNavigationButton = addUsbNavigationButton(navigation, getString(R.string.usb_device),
            this::loadCurrentDirectory);
        navigation.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        addUsbNavigationButton(navigation, getString(R.string.back), this::finish);
        shell.addView(navigation, new LinearLayout.LayoutParams(dp(188), -1));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(6), 0, 0);
        deviceNameTextView = new TextView(this);
        deviceNameTextView.setText(R.string.usb_device);
        deviceNameTextView.setTextSize(26);
        deviceNameTextView.setTextColor(getColor(R.color.text_primary));
        page.addView(deviceNameTextView);
        directoryTextView = new TextView(this);
        directoryTextView.setText(currentDirectory);
        directoryTextView.setTextSize(13);
        directoryTextView.setTextColor(getColor(R.color.text_secondary));
        directoryTextView.setPadding(0, dp(6), 0, dp(18));
        page.addView(directoryTextView);
        LinearLayout actions = new LinearLayout(this);
        addActionButton(actions, getString(R.string.parent_folder), this::navigateToParent);
        addActionButton(actions, getString(R.string.action_new_folder), this::promptCreateFolder);
        addActionButton(actions, getString(R.string.paste_copy), this::pasteClipboardEntry);
        addActionButton(actions, getString(R.string.usb_import), this::startFileImport);
        addActionButton(actions, getString(R.string.refresh), this::loadCurrentDirectory);
        page.addView(actions);
        statusTextView = new TextView(this);
        statusTextView.setText(getString(R.string.usb_connecting));
        statusTextView.setTextSize(14);
        statusTextView.setTextColor(getColor(R.color.text_secondary));
        statusTextView.setPadding(0, dp(6), 0, dp(12));
        page.addView(statusTextView);
        entryGridView = new GridView(this);
        entryGridView.setSelector(R.drawable.focus_surface);
        entryGridView.setDrawSelectorOnTop(false);
        entryGridView.setNumColumns(4);
        entryGridView.setHorizontalSpacing(dp(12));
        entryGridView.setVerticalSpacing(dp(12));
        entryGridView.setOnItemClickListener((parent, view, position, id) -> {
            FatFsVolume.DirectoryEntry entry = (FatFsVolume.DirectoryEntry) parent.getItemAtPosition(position);
            if (entry.directory) {
                currentDirectory = childPath(currentDirectory, entry.name);
                loadCurrentDirectory();
            } else {
                showEntryActions(entry);
            }
        });
        entryGridView.setOnItemLongClickListener((parent, view, position, id) -> {
            showEntryActions((FatFsVolume.DirectoryEntry) parent.getItemAtPosition(position));
            return true;
        });
        entryGridView.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_MENU
                && entryGridView.getSelectedItemPosition() >= 0) {
                showEntryActions((FatFsVolume.DirectoryEntry) entryGridView.getSelectedItem());
                return true;
            }
            return false;
        });
        page.addView(entryGridView, new LinearLayout.LayoutParams(-1, 0, 1));
        shell.addView(page, new LinearLayout.LayoutParams(0, -1, 1));
        setContentView(shell);
    }

    /** 创建与主界面一致的侧栏按钮，设备名称可在枚举完成后更新。 */
    private Button addUsbNavigationButton(LinearLayout navigation, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setTextColor(getColor(R.color.text_primary));
        button.setBackgroundResource(R.drawable.navigation_surface);
        button.setOnClickListener(view -> action.run());
        navigation.addView(button, new LinearLayout.LayoutParams(-1, dp(48)));
        return button;
    }

    /**
     * 增加带焦点背景的电视操作按钮。
     */
    private void addActionButton(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setFocusable(true);
        button.setBackgroundResource(R.drawable.focus_surface);
        button.setTextColor(getColor(R.color.text_primary));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(0, dp(56), 1);
        parameters.setMargins(0, 0, dp(6), 0);
        parent.addView(button, parameters);
    }

    /**
     * 授权完成后只在工作线程读取磁盘容量并挂载文件系统。
     */
    private void mountUsbVolume() {
        if (busy) {
            return;
        }
        busy = true;
        showStatus(getString(R.string.usb_connecting));
        usbExecutor.execute(() -> {
            try {
                fatFsVolume = new FatFsVolume(new UsbScsiBlockDevice(usbManager, usbDevice));
                List<FatFsVolume.DirectoryEntry> entries = fatFsVolume.listDirectoryEntries("/");
                runOnUiThread(() -> {
                    busy = false;
                    renderDirectoryEntries(entries);
                });
            } catch (Exception exception) {
                reportUsbFailure("mount", "/", exception);
            }
        });
    }

    /**
     * 从当前卷内目录加载子项，任务完成前保持原列表可见。
     */
    private void loadCurrentDirectory() {
        if (busy || Objects.isNull(fatFsVolume)) {
            return;
        }
        busy = true;
        String directory = currentDirectory;
        showStatus(getString(R.string.files_loading));
        usbExecutor.execute(() -> {
            try {
                List<FatFsVolume.DirectoryEntry> entries = fatFsVolume.listDirectoryEntries(directory);
                runOnUiThread(() -> {
                    busy = false;
                    if (directory.equals(currentDirectory)) {
                        renderDirectoryEntries(entries);
                    }
                });
            } catch (Exception exception) {
                reportUsbFailure("list", directory, exception);
            }
        });
    }

    /**
     * 在主线程排序并绘制目录；长按或菜单键能打开目录条目的操作菜单。
     */
    private void renderDirectoryEntries(List<FatFsVolume.DirectoryEntry> entries) {
        directoryTextView.setText(currentDirectory);
        entries.sort(Comparator.comparing((FatFsVolume.DirectoryEntry entry) -> !entry.directory)
            .thenComparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER));
        entryGridView.setAdapter(new UsbEntryAdapter(this, entries));
        showStatus(entries.isEmpty() ? getString(R.string.folder_empty)
            : getString(R.string.usb_folder_hint));
    }

    /**
     * 显示单条目录项可执行的复制、移动、改名和删除等动作。
     */
    private void showEntryActions(FatFsVolume.DirectoryEntry entry) {
        if (busy) {
            return;
        }
        String path = childPath(currentDirectory, entry.name);
        String[] options = entry.directory
            ? new String[]{getString(R.string.action_open), getString(R.string.action_copy),
            getString(R.string.action_move), getString(R.string.action_rename),
            getString(R.string.action_delete)}
            : new String[]{getString(R.string.action_copy), getString(R.string.action_move),
            getString(R.string.action_rename), getString(R.string.action_delete),
            getString(R.string.usb_export)};
        new AlertDialog.Builder(this).setTitle(entry.name).setItems(options, (dialog, which) -> {
            if (entry.directory && which == 0) {
                currentDirectory = path;
                loadCurrentDirectory();
                return;
            }
            int action = entry.directory ? which - 1 : which;
            if (action == 0 || action == 1) {
                clipboardPath = path;
                clipboardDirectory = entry.directory;
                clipboardMove = action == 1;
                showStatus(getString(R.string.paste_hint));
            } else if (action == 2) {
                promptRenameEntry(path, entry.name);
            } else if (action == 3) {
                confirmDeleteEntry(path, entry.directory);
            } else if (!entry.directory && action == 4) {
                exportFileToTelevision(path, entry.name);
            }
        }).show();
    }

    /**
     * 新文件夹名称从电视输入法取得，拒绝路径分隔符和 FAT 保留字符。
     */
    private void promptCreateFolder() {
        if (busy || Objects.isNull(fatFsVolume)) {
            return;
        }
        promptForName(getString(R.string.action_new_folder), "", name ->
            runUsbMutation("mkdir", childPath(currentDirectory, name), () ->
                fatFsVolume.createDirectory(childPath(currentDirectory, name))));
    }

    /**
     * 重命名仅限当前目录，现有目标不会被覆盖。
     */
    private void promptRenameEntry(String path, String oldName) {
        promptForName(getString(R.string.action_rename), oldName, name -> {
            String destination = childPath(currentDirectory, name);
            runUsbMutation("rename", path, () -> fatFsVolume.renameEntry(path, destination));
        });
    }

    /**
     * 输入对话框统一验证 FAT 文件名，避免非法名称被解释成另一条路径。
     */
    private void promptForName(String title, String initial, java.util.function.Consumer<String> action) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(initial);
        new AlertDialog.Builder(this).setTitle(title).setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, (dialog, which) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty() || name.equals(".") || name.equals("..")
                    || name.matches(".*[\\\\/:*?\"<>|].*")) {
                    showStatus(getString(R.string.invalid_file_name));
                } else {
                    action.accept(name);
                }
            }).show();
    }

    /**
     * 粘贴前拒绝目录复制到自身或子目录；移动优先执行同卷原子重命名。
     * 剪贴板由主线程持有，工作线程只使用启动时的快照，成功后再回主线程清除。
     */
    private void pasteClipboardEntry() {
        if (busy || Objects.isNull(clipboardPath) || Objects.isNull(fatFsVolume)) {
            return;
        }
        String source = clipboardPath;
        boolean move = clipboardMove;
        boolean directory = clipboardDirectory;
        String target = childPath(currentDirectory, source.substring(source.lastIndexOf('/') + 1));
        if (source.equals(target) || (directory && currentDirectory.startsWith(source + "/"))) {
            showStatus(getString(R.string.recursive_destination));
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(move ? R.string.move_here : R.string.copy_here)
            .setMessage(source + "\n→ " + target + "\n" + getString(R.string.copy_warning))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.start, (dialog, which) ->
                runUsbMutation(move ? "move" : "copy", source, () -> {
                    if (move) {
                        fatFsVolume.renameEntry(source, target);
                    } else {
                        copyUsbEntry(source, target, directory);
                    }
                    runOnUiThread(() -> {
                        if (source.equals(clipboardPath)) {
                            clipboardPath = null;
                        }
                    });
                })).show();
    }

    /**
     * 递归复制目录；目标新建且任何子项失败时保留已复制内容供用户检查。
     */
    private void copyUsbEntry(String source, String target, boolean directory) throws IOException {
        if (!directory) {
            fatFsVolume.copyFile(source, target);
            return;
        }
        fatFsVolume.createDirectory(target);
        for (FatFsVolume.DirectoryEntry child : fatFsVolume.listDirectoryEntries(source)) {
            copyUsbEntry(childPath(source, child.name), childPath(target, child.name), child.directory);
        }
    }

    /**
     * 删除前需要遥控器确认；目录按子项到父目录的顺序删除。
     */
    private void confirmDeleteEntry(String path, boolean directory) {
        new AlertDialog.Builder(this).setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_warning, path))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_permanent, (dialog, which) ->
                runUsbMutation("delete", path, () -> deleteUsbEntry(path, directory))).show();
    }

    /**
     * 递归删除卷内目录，遇到第一处错误立即停止并保留其余项目。
     */
    private void deleteUsbEntry(String path, boolean directory) throws IOException {
        if (directory) {
            for (FatFsVolume.DirectoryEntry child : fatFsVolume.listDirectoryEntries(path)) {
                deleteUsbEntry(childPath(path, child.name), child.directory);
            }
        }
        fatFsVolume.deleteEntry(path);
    }

    /**
     * 使用本应用已有的电视文件浏览页选取文件，兼容缺少系统 DocumentsUI 的固件。
     */
    private void startFileImport() {
        if (busy || Objects.isNull(fatFsVolume)) {
            return;
        }
        Intent intent = new Intent(this, MainActivity.class)
            .putExtra(MainActivity.EXTRA_PICK_INTERNAL_FILE, true);
        try {
            startActivityForResult(intent, IMPORT_REQUEST_CODE);
        } catch (Exception exception) {
            reportUsbFailure("pick internal file", currentDirectory, exception);
        }
    }

    /**
     * {@inheritDoc} 应用内选择返回后再次核对文件并以新文件方式写入 USB。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMPORT_REQUEST_CODE || resultCode != RESULT_OK || Objects.isNull(data)) {
            return;
        }
        String sourcePath = data.getStringExtra(MainActivity.EXTRA_PICKED_FILE_PATH);
        if (Objects.isNull(sourcePath)) {
            return;
        }
        File source = new File(sourcePath);
        String name = source.getName();
        if (name.isEmpty() || name.matches(".*[\\\\/:*?\"<>|].*")) {
            showStatus(getString(R.string.invalid_file_name));
            return;
        }
        String target = childPath(currentDirectory, name);
        runUsbMutation("import", target, () -> importSelectedFile(source, target));
    }

    /**
     * 用户选中的电视普通文件按固定缓冲区写入 USB；失败不覆盖已有文件。
     */
    private void importSelectedFile(File source, String target) throws IOException {
        if (!source.isFile()) {
            throw new IOException("电视源文件不可用: " + source);
        }
        try (InputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[FILE_BUFFER_BYTES];
            long offset = 0;
            int length;
            boolean create = true;
            while ((length = input.read(buffer)) != -1) {
                if (length == 0) {
                    continue;
                }
                fatFsVolume.writeFileChunk(target, offset, buffer, length, create);
                offset += length;
                create = false;
            }
            if (create) {
                fatFsVolume.writeFileChunk(target, 0, buffer, 0, true);
            }
        }
    }

    /**
     * 将单个 USB 文件导出到电视公共 Downloads/TV Finder，不覆盖同名文件。
     */
    private void exportFileToTelevision(String source, String name) {
        runUsbMutation("export", source, () -> {
            File directory = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "TV Finder");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("无法创建电视下载目录: " + directory);
            }
            File target = new File(directory, name);
            if (!target.createNewFile()) {
                throw new IOException("电视下载目录已有同名文件: " + target);
            }
            boolean complete = false;
            try (FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[FILE_BUFFER_BYTES];
                long offset = 0;
                int length;
                while ((length = fatFsVolume.readFileChunk(source, offset, buffer)) > 0) {
                    output.write(buffer, 0, length);
                    offset += length;
                }
                output.getFD().sync();
                complete = true;
            } finally {
                if (!complete && !target.delete()) {
                    Log.w("TvFinderUsb", "导出失败且清理不完整: " + target);
                }
            }
        });
    }

    /**
     * 统一在工作线程执行卷内修改，失败时显示具体阶段并刷新当前目录。
     */
    private void runUsbMutation(String stage, String path, UsbOperation operation) {
        if (busy || Objects.isNull(fatFsVolume)) {
            return;
        }
        busy = true;
        showStatus(getString(R.string.operation_running, stage));
        usbExecutor.execute(() -> {
            try {
                operation.run();
                runOnUiThread(() -> {
                    busy = false;
                    showStatus(getString(R.string.operation_success, stage));
                    loadCurrentDirectory();
                });
            } catch (Exception exception) {
                reportUsbFailure(stage, path, exception);
            }
        });
    }

    /**
     * 统一记录操作阶段、卷内路径及完整堆栈，主线程保留可读错误信息。
     */
    private void reportUsbFailure(String stage, String path, Exception exception) {
        Log.e("TvFinderUsb", "USB 文件操作失败: stage=" + stage + ", path=" + path
                + ", device=" + (Objects.isNull(usbDevice) ? "none" : usbDevice.getDeviceName())
                + ", type=" + exception.getClass().getName() + ", message=" + exception.getMessage(),
            exception);
        runOnUiThread(() -> {
            busy = false;
            showStatus(getString(R.string.usb_incomplete) + " " + exception.getMessage());
        });
    }

    /**
     * 主线程更新状态文字。
     */
    private void showStatus(String status) {
        statusTextView.setText(status);
    }

    /**
     * 根据当前目录拼接卷内路径，不允许子项名称自行引入分隔符。
     */
    private String childPath(String parent, String name) {
        return parent.equals("/") ? "/" + name : parent + "/" + name;
    }

    /** 把电视独立像素转换为当前设备像素，避免高密度屏幕裁切按钮文字。 */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * 返回卷内上级目录；根目录时退出本页。
     */
    private void navigateToParent() {
        if (busy) {
            return;
        }
        if (currentDirectory.equals("/")) {
            finish();
        } else {
            int separator = currentDirectory.lastIndexOf('/');
            currentDirectory = separator == 0 ? "/" : currentDirectory.substring(0, separator);
            loadCurrentDirectory();
        }
    }

    /**
     * {@inheritDoc} 遥控器返回键按目录层级返回。
     */
    @Override
    public void onBackPressed() {
        navigateToParent();
    }

    /**
     * {@inheritDoc} 取消广播并在现有 I/O 后卸载文件系统，防止正在写入时强制关连接。
     */
    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(permissionReceiver);
            receiverRegistered = false;
        }
        usbExecutor.execute(() -> {
            if (Objects.nonNull(fatFsVolume)) {
                fatFsVolume.close();
                fatFsVolume = null;
            }
        });
        usbExecutor.shutdown();
        super.onDestroy();
    }

    /**
     * 工作线程上的单项 USB 文件操作，可抛出保留根因的 I/O 异常。
     */
    private interface UsbOperation {
        /**
         * @throws Exception 文件系统、设备或 Android 内容源访问失败。
         */
        void run() throws Exception;
    }
}
