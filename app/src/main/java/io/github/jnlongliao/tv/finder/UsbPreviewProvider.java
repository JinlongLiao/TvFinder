package io.github.jnlongliao.tv.finder;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 将已授权的 USB FAT/exFAT 文件按需暴露为可 seek 的只读 content URI。
 * 卷由 USB 页面挂载和卸载；URI 只在当前挂载期间有效，外部程序获得的只有单文件临时读权限。
 *
 * @see <a href="https://developer.android.com/reference/android/os/storage/StorageManager#openProxyFileDescriptor(int,%20android.os.ProxyFileDescriptorCallback,%20android.os.Handler)">Android ProxyFileDescriptor</a>
 */
public final class UsbPreviewProvider extends ContentProvider {
    /** 每次挂载独立的随机令牌，不暴露卷内路径。 */
    private static final Map<String, String> FILE_PATHS = new HashMap<>();
    /** 卷和令牌仅由本进程管理；磁盘实际读写由 FatFsVolume 同步。 */
    private static FatFsVolume fatFsVolume;
    /** 回调线程避免在 Binder 或界面线程等待 USB I/O。 */
    private static HandlerThread callbackThread;
    /** 正在查看此卷文件的页面数量。 */
    private static int previewCount;
    /** USB 页面已销毁，但仍有预览页需要继续读取。 */
    private static boolean ownerDetached;
    /** Android 日志标签。 */
    private static final String LOG_TAG = "TvFinderUsbPreview";

    /**
     * 在 USB 页面挂载成功后登记当前卷；再次挂载会使旧 URI 失效。
     * @param volume 已挂载的卷
     */
    public static synchronized void attachUsbVolume(FatFsVolume volume) {
        FILE_PATHS.clear();
        fatFsVolume = Objects.requireNonNull(volume);
        previewCount = 0;
        ownerDetached = false;
        if (Objects.isNull(callbackThread)) {
            callbackThread = new HandlerThread("tv-usb-preview-read");
            callbackThread.start();
        }
    }

    /**
     * USB 页面退出时撤销 URI；已有读请求在 FatFsVolume 的同步边界内结束。
     */
    public static synchronized void detachUsbVolume() {
        ownerDetached = true;
        if (previewCount == 0) {
            closeDetachedUsbVolume(false);
        }
    }

    /**
     * 预览页持有当前挂载租约，避免 USB 页面重建期间提前关闭正在读取的文件。
     * @throws IOException 卷已经退出
     */
    public static synchronized void retainUsbPreview() throws IOException {
        if (Objects.isNull(fatFsVolume)) {
            throw new IOException("USB 文件系统已关闭");
        }
        previewCount++;
    }

    /**
     * 预览页结束后释放租约；若 USB 页面已退出，则在回调线程完成卸载。
     */
    public static synchronized void releaseUsbPreview() {
        if (previewCount > 0) {
            previewCount--;
        }
        if (ownerDetached && previewCount == 0) {
            closeDetachedUsbVolume(true);
        }
    }

    /** 已无页面持有卷时关闭连接和回调线程。 */
    private static void closeDetachedUsbVolume(boolean asynchronous) {
        FatFsVolume closingVolume = fatFsVolume;
        HandlerThread closingThread = callbackThread;
        FILE_PATHS.clear();
        fatFsVolume = null;
        callbackThread = null;
        if (asynchronous && Objects.nonNull(closingThread)) {
            new Handler(closingThread.getLooper()).post(() -> {
                if (Objects.nonNull(closingVolume)) {
                    closingVolume.close();
                }
                closingThread.quitSafely();
            });
        } else {
            if (Objects.nonNull(closingVolume)) {
                closingVolume.close();
            }
            if (Objects.nonNull(closingThread)) {
                closingThread.quitSafely();
            }
        }
    }

    /**
     * 只为当前卷内已存在的普通文件生成 URI，调用方保留页面直至预览结束。
     * @param context 应用上下文
     * @param path 已验证的卷内绝对路径
     * @return 当前挂载期间有效的只读 URI
     * @throws IOException 卷已卸载或文件不可读
     */
    public static synchronized Uri registerUsbFile(Context context, String path) throws IOException {
        if (Objects.isNull(fatFsVolume)) {
            throw new IOException("USB 文件系统未挂载");
        }
        fatFsVolume.getFileSize(path);
        String token = UUID.randomUUID().toString();
        FILE_PATHS.put(token, path);
        return new Uri.Builder().scheme("content")
            .authority(context.getPackageName() + ".usb-preview").appendPath(token).build();
    }

    /**
     * WebView 资源仅允许访问主文件所在目录及其子目录，拒绝点段和跨目录请求。
     * @param context 应用上下文
     * @param mainUri 已登记主文件的 URI
     * @param relativePath URI 解码后的相对资源路径
     * @return 同一卷内资源 URI
     * @throws IOException 非法路径或资源不存在
     */
    public static synchronized Uri registerAdjacentUsbFile(Context context, Uri mainUri,
            String relativePath) throws IOException {
        String mainPath = resolveRegisteredPath(mainUri);
        if (Objects.isNull(mainPath) || Objects.isNull(relativePath) || relativePath.isEmpty()
            || relativePath.startsWith("/") || relativePath.indexOf('\\') >= 0) {
            throw new IOException("无效的 USB HTML 资源路径");
        }
        for (String part : relativePath.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IOException("HTML 资源超出当前目录");
            }
        }
        String parent = mainPath.substring(0, mainPath.lastIndexOf('/') + 1);
        return registerUsbFile(context, parent + relativePath);
    }

    /** {@inheritDoc} */
    @Override
    public boolean onCreate() {
        return true;
    }

    /** {@inheritDoc} 查询仅提供文件名和字节数，便于外部播放器读取元数据。 */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        String path = resolveRegisteredPath(uri);
        if (Objects.isNull(path) || Objects.isNull(fatFsVolume)) {
            return null;
        }
        String[] columns = Objects.isNull(projection)
            ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(path.substring(path.lastIndexOf('/') + 1));
            } else if (OpenableColumns.SIZE.equals(column)) {
                try {
                    row.add(fatFsVolume.getFileSize(path));
                } catch (IOException exception) {
                    Log.e(LOG_TAG, "查询 USB 文件大小失败 path=" + path + ", stage=query, type="
                        + exception.getClass().getName() + ", message=" + exception.getMessage(), exception);
                    row.add(null);
                }
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    /** {@inheritDoc} MIME 仅供外部应用路由，实际格式仍由解码器校验。 */
    @Override
    public String getType(Uri uri) {
        String path = resolveRegisteredPath(uri);
        if (Objects.isNull(path)) {
            return "application/octet-stream";
        }
        int dot = path.lastIndexOf('.');
        String mime = dot < 0 ? null : MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(path.substring(dot + 1).toLowerCase(Locale.ROOT));
        return Objects.isNull(mime) ? "application/octet-stream" : mime;
    }

    /**
     * 将外部应用 seek/read 请求转为 FatFs 按偏移读取；写入一律拒绝。
     * @throws FileNotFoundException URI 过期、模式不是只读或设备不可用
     */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String path = resolveRegisteredPath(uri);
        FatFsVolume volume = fatFsVolume;
        HandlerThread thread = callbackThread;
        if (!"r".equals(mode) || Objects.isNull(path) || Objects.isNull(volume)
            || Objects.isNull(thread)) {
            throw new FileNotFoundException("USB 文件 URI 不可用: " + uri);
        }
        try {
            long length = volume.getFileSize(path);
            StorageManager storageManager = Objects.requireNonNull(getContext())
                .getSystemService(StorageManager.class);
            if (Objects.isNull(storageManager)) {
                throw new FileNotFoundException("系统不支持随机读取文件描述符");
            }
            return storageManager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                new UsbPreviewReadCallback(volume, path, length), new Handler(thread.getLooper()));
        } catch (IOException exception) {
            FileNotFoundException failure = new FileNotFoundException("USB 文件打开失败: " + path);
            failure.initCause(exception);
            Log.e(LOG_TAG, "打开 USB 文件失败 path=" + path + ", stage=open, type="
                + exception.getClass().getName() + ", message=" + exception.getMessage(), exception);
            throw failure;
        }
    }

    /** {@inheritDoc} 预览 URI 永不接受插入。 */
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("USB 预览只读");
    }

    /** {@inheritDoc} 预览 URI 永不接受删除。 */
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("USB 预览只读");
    }

    /** {@inheritDoc} 预览 URI 永不接受更新。 */
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("USB 预览只读");
    }

    /** 从随机令牌取卷内路径，缺失或附加路径段均拒绝。 */
    private static synchronized String resolveRegisteredPath(Uri uri) {
        List<String> segments = uri.getPathSegments();
        return segments.size() == 1 ? FILE_PATHS.get(segments.get(0)) : null;
    }
}
