package io.github.jnlongliao.tv.finder;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 复用 USB 列表页已经挂载的卷，在后台有界复制同目录的相邻文件。
 * 列表页负责保留和清理首次预览副本；本会话仅清理自己创建的后续副本。
 */
public final class UsbPreviewSession {
    /** 单文件最多复制 1 GiB，与列表页原有预览上限一致。 */
    private static final long MAX_PREVIEW_BYTES = 1024L * 1024 * 1024;
    /** 有界流式复制的块大小。 */
    private static final int BUFFER_BYTES = 64 * 1024;
    /** 活跃会话只在当前进程有效；进程重启后预览页回退到首次副本。 */
    private static final Map<String, UsbPreviewSession> SESSIONS = new HashMap<>(2);
    /** USB 预览复制日志标签。 */
    private static final String LOG_TAG = "TvFinderUsbPreview";
    /** 完成回调必须返回主线程更新预览 UI。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 单线程保证切换顺序与副本清理顺序一致。 */
    private final ExecutorService copyExecutor = Executors.newSingleThreadExecutor();
    /** 列表页持有并保持挂载的卷。 */
    private final FatFsVolume fatFsVolume;
    /** 卷内当前目录。 */
    private final String directory;
    /** 按列表顺序保留的普通可预览文件名。 */
    private final List<String> fileNames;
    /** 初次预览的副本，由列表页负责清理。 */
    private final File initialCopy;
    /** 后续副本所用缓存目录。 */
    private final File cacheDirectory;
    /** 供两个 Activity 关联会话的随机 ID。 */
    private final String id;
    /** 初次文件在列表中的位置。 */
    private final int initialIndex;
    /** 当前完成加载的文件位置。 */
    private int currentIndex;
    /** 当前由会话创建的副本，最多保留一个。 */
    private File activeCopy;
    /** 复制进行时拒绝重复方向键请求。 */
    private boolean busy;
    /** 关闭后任何后台结果均不得更新预览页。 */
    private volatile boolean closed;

    /**
     * 创建会话。目录条目须按 USB 列表既有顺序排序；目录与不可预览格式会被过滤。
     *
     * @param fatFsVolume 已挂载 USB 卷
     * @param directory 当前卷内目录
     * @param orderedEntries 当前目录的排序快照
     * @param selectedName 当前文件原始名称
     * @param initialCopy 列表页已复制的首次预览文件
     * @param cacheDirectory 缓存目录
     * @return 已注册会话，列表页应在预览结束时调用 close
     */
    public static UsbPreviewSession create(FatFsVolume fatFsVolume, String directory,
        List<FatFsVolume.DirectoryEntry> orderedEntries, String selectedName, File initialCopy,
        File cacheDirectory) {
        UsbPreviewSession session = new UsbPreviewSession(fatFsVolume, directory, orderedEntries,
            selectedName, initialCopy, cacheDirectory);
        synchronized (SESSIONS) {
            SESSIONS.put(session.id, session);
        }
        return session;
    }

    /** 通过 Intent 中的 ID 查找同进程会话，重启或关闭后返回 null。 */
    public static UsbPreviewSession find(String id) {
        synchronized (SESSIONS) {
            UsbPreviewSession session = SESSIONS.get(id);
            return Objects.nonNull(session) && !session.closed ? session : null;
        }
    }

    /** 构造器仅保存目录快照，不执行 USB I/O。 */
    private UsbPreviewSession(FatFsVolume fatFsVolume, String directory,
        List<FatFsVolume.DirectoryEntry> orderedEntries, String selectedName, File initialCopy,
        File cacheDirectory) {
        this.fatFsVolume = Objects.requireNonNull(fatFsVolume);
        this.directory = Objects.requireNonNull(directory);
        this.initialCopy = Objects.requireNonNull(initialCopy);
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory);
        this.id = UUID.randomUUID().toString();
        this.fileNames = new ArrayList<>(orderedEntries.size());
        for (FatFsVolume.DirectoryEntry entry : orderedEntries) {
            if (!entry.directory && (entry.name.equals(selectedName)
                || FilePreviewActivity.isSupportedPreviewName(entry.name))) {
                fileNames.add(entry.name);
            }
        }
        this.initialIndex = fileNames.indexOf(selectedName);
        if (initialIndex < 0) {
            throw new IllegalArgumentException("USB 预览文件不在当前目录: " + selectedName);
        }
        this.currentIndex = initialIndex;
    }

    /** 返回用于内部 Intent 的会话 ID。 */
    public String getId() {
        return id;
    }

    /** 返回当前从零开始的文件序号。 */
    public synchronized int getCurrentIndex() {
        return currentIndex;
    }

    /** 返回当前目录可预览文件总数。 */
    public int getFileCount() {
        return fileNames.size();
    }

    /**
     * 请求相邻文件；边界、关闭或正在复制时返回 false，不改变当前页。
     *
     * @param direction 前一文件为 -1，后一文件为 1
     * @param listener 在主线程收到完整副本或失败原因
     * @return 是否接受切换请求
     */
    public synchronized boolean requestAdjacentFile(int direction, Listener listener) {
        int target = currentIndex + direction;
        if (closed || busy || (direction != -1 && direction != 1)
            || target < 0 || target >= fileNames.size()) {
            return false;
        }
        busy = true;
        String name = fileNames.get(target);
        if (target == initialIndex) {
            mainHandler.post(() -> finishCopy(target, name, initialCopy, listener));
        } else {
            copyExecutor.execute(() -> copyAdjacentFile(target, name, listener));
        }
        return true;
    }

    /** 有界复制卷内单个文件，失败时先删除部分副本再通知页面。 */
    private void copyAdjacentFile(int target, String name, Listener listener) {
        String source = "/".equals(directory) ? "/" + name : directory + "/" + name;
        File temporary = null;
        try {
            long size = fatFsVolume.getFileSize(source);
            if (size < 0 || size > MAX_PREVIEW_BYTES) {
                throw new IOException("USB 文件超过 1 GiB 预览上限: " + source);
            }
            if (size + 16L * 1024 * 1024 > cacheDirectory.getUsableSpace()) {
                throw new IOException("电视缓存空间不足: " + source);
            }
            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "" : name.substring(dot + 1);
            String suffix = extension.matches("[A-Za-z0-9]{1,10}") ? "." + extension : ".tmp";
            temporary = File.createTempFile("usb-preview-", suffix, cacheDirectory);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                long offset = 0;
                int length;
                while (!closed && (length = fatFsVolume.readFileChunk(source, offset, buffer)) > 0) {
                    output.write(buffer, 0, length);
                    offset += length;
                    if (offset > MAX_PREVIEW_BYTES) {
                        throw new IOException("USB 文件在复制中超过 1 GiB: " + source);
                    }
                }
                if (closed || offset != size) {
                    throw new IOException("USB 文件已变化或预览中断: " + source);
                }
            }
            File ready = temporary;
            mainHandler.post(() -> finishCopy(target, name, ready, listener));
        } catch (Exception exception) {
            if (Objects.nonNull(temporary) && !temporary.delete()) {
                Log.w(LOG_TAG, "USB 部分预览副本清理失败 file=" + temporary, exception);
            }
            Log.e(LOG_TAG, "USB 相邻文件预览复制失败 source=" + source + ", name=" + name
                + ", type=" + exception.getClass().getName() + ", message=" + exception.getMessage(), exception);
            mainHandler.post(() -> {
                synchronized (this) {
                    busy = false;
                }
                if (!closed) {
                    listener.onFailure(exception);
                }
            });
        }
    }

    /** 复制提交后更新位置并释放上一份后续副本，首次副本始终由列表页管理。 */
    private void finishCopy(int target, String name, File ready, Listener listener) {
        File oldCopy;
        synchronized (this) {
            busy = false;
            if (closed) {
                if (!ready.equals(initialCopy) && !ready.delete()) {
                    Log.w(LOG_TAG, "关闭会话后副本清理失败 file=" + ready);
                }
                return;
            }
            oldCopy = activeCopy;
            activeCopy = ready.equals(initialCopy) ? null : ready;
            currentIndex = target;
        }
        listener.onFileReady(ready, name, target + 1, fileNames.size());
        if (Objects.nonNull(oldCopy) && !oldCopy.equals(ready) && !oldCopy.delete()) {
            Log.w(LOG_TAG, "旧 USB 预览副本清理失败 file=" + oldCopy);
        }
    }

    /** 预览页返回时终止复制并清理会话创建的副本；首次副本交由列表页清理。 */
    public void close() {
        File copy;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            copy = activeCopy;
            activeCopy = null;
            copyExecutor.shutdownNow();
        }
        synchronized (SESSIONS) {
            SESSIONS.remove(id);
        }
        if (Objects.nonNull(copy) && !copy.delete()) {
            Log.w(LOG_TAG, "USB 预览会话关闭时清理失败 file=" + copy);
        }
    }

    /** 切换结果，两个方法均在主线程调用。 */
    public interface Listener {
        /** 新文件副本已完整复制，可以替换页面内容。 */
        void onFileReady(File file, String displayName, int position, int count);

        /** 复制失败，原文件和位置保持不变。 */
        void onFailure(Exception exception);
    }
}
