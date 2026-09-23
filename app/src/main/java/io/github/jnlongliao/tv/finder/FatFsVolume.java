package io.github.jnlongliao.tv.finder;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将 USB 扇区设备交给 FatFs 管理的单卷文件系统入口。
 * 所有公开操作须由同一工作线程串行调用；卸载后不再触碰已释放的 USB 连接。
 *
 * @see <a href="https://elm-chan.org/fsw/ff/">FatFs R0.16</a>
 * @see <a href="https://elm-chan.org/fsw/ff/patches.html">FatFs 官方补丁</a>
 */
public final class FatFsVolume implements Closeable {
    /** FatFs JNI 库由 Gradle NDK/CMake 构建并打进 APK。 */
    static {
        System.loadLibrary("tvfinder_fatfs");
    }

    /**
     * 独占的 USB 块设备，先卸载 FatFs 再关闭设备。
     */
    private final UsbScsiBlockDevice usbScsiBlockDevice;
    /**
     * 是否已卸载，避免重复释放 JNI 全局引用。
     */
    private boolean closed;

    /**
     * 挂载已授权的 USB 块设备。仅允许同时存在一个 FatFsVolume。
     *
     * @param usbScsiBlockDevice 已打开的 USB SCSI 磁盘
     * @throws IOException 分区不是受支持的 FAT/exFAT，或设备读取失败
     */
    public FatFsVolume(UsbScsiBlockDevice usbScsiBlockDevice) throws IOException {
        this.usbScsiBlockDevice = Objects.requireNonNull(usbScsiBlockDevice);
        int result = nativeMount(usbScsiBlockDevice);
        if (result != 0) {
            usbScsiBlockDevice.close();
            throw new IOException("挂载 USB FAT/exFAT 失败，FatFs 错误码=" + result);
        }
    }

    /**
     * 列出指定目录的直接子项；目录名由设备提供，不作为本机路径使用。
     *
     * @param directory 以 / 开头的卷内目录路径
     * @return 目录子项
     * @throws IOException 路径不合法、卷已卸载或目录读取失败
     */
    public synchronized List<DirectoryEntry> listDirectoryEntries(String directory) throws IOException {
        ensureMounted();
        String[] encoded = nativeList(toFatFsPath(directory));
        if (Objects.isNull(encoded)) {
            throw new IOException("无法读取 USB 目录: " + directory);
        }
        List<DirectoryEntry> entries = new ArrayList<>(encoded.length);
        for (String value : encoded) {
            if (value.length() > 1) {
                entries.add(new DirectoryEntry(value.substring(1), value.charAt(0) == 'D'));
            }
        }
        return entries;
    }

    /**
     * 在卷内创建目录；目标必须位于根目录之下。
     *
     * @param path 卷内目标路径
     * @throws IOException 目标已存在、父目录不存在或设备写入失败
     */
    public synchronized void createDirectory(String path) throws IOException {
        ensureMounted();
        checkResult(nativeMakeDirectory(toMutableFatFsPath(path)), "创建目录", path);
    }

    /**
     * 删除卷内文件或空目录；非空目录由调用方先递归确认并逐项删除。
     *
     * @param path 卷内目标路径
     * @throws IOException 路径为根、目标不存在或写入失败
     */
    public synchronized void deleteEntry(String path) throws IOException {
        ensureMounted();
        checkResult(nativeDelete(toMutableFatFsPath(path)), "删除", path);
    }

    /**
     * 在同一卷内重命名或移动单个文件/目录；目标存在时由 FatFs 拒绝覆盖。
     *
     * @param source      卷内源路径
     * @param destination 卷内目标路径
     * @throws IOException 任一路径不合法、目标已存在或写入失败
     */
    public synchronized void renameEntry(String source, String destination) throws IOException {
        ensureMounted();
        checkResult(nativeRename(toMutableFatFsPath(source), toMutableFatFsPath(destination)), "重命名", source);
    }

    /**
     * 在同一 USB 卷内复制普通文件。目标必须不存在；失败的部分目标由 C 层清理。
     * @param source 卷内源文件
     * @param destination 卷内新文件
     * @throws IOException 源不是文件、目标已存在或传输失败
     */
    public synchronized void copyFile(String source, String destination) throws IOException {
        ensureMounted();
        checkResult(nativeCopyFile(toMutableFatFsPath(source), toMutableFatFsPath(destination)), "复制", source);
    }

    /**
     * 获取普通文件的大小，供有界分块读取及进度显示使用。
     * @param path 卷内文件路径
     * @return 文件字节数
     * @throws IOException 文件不存在、已卸载或读取失败
     */
    public synchronized long getFileSize(String path) throws IOException {
        ensureMounted();
        long size = nativeFileSize(toFatFsPath(path));
        if (size < 0) {
            throw new IOException("获取 USB 文件大小失败: path=" + path + ", FatFs 错误码=" + -size);
        }
        return size;
    }

    /**
     * 从指定偏移读取不超过目标数组长度的字节；文件末尾返回 0。
     * @param path 卷内源文件
     * @param offset 字节偏移
     * @param destination 有界目标数组
     * @return 实际读取字节数
     * @throws IOException 偏移无效、设备错误或文件不存在
     */
    public synchronized int readFileChunk(String path, long offset, byte[] destination) throws IOException {
        ensureMounted();
        if (offset < 0 || destination.length == 0) {
            throw new IOException("读取 USB 文件的偏移或长度无效: " + path);
        }
        int count = nativeReadChunk(toFatFsPath(path), offset, destination);
        if (count < 0) {
            throw new IOException("读取 USB 文件失败: path=" + path + ", offset=" + offset
                    + ", FatFs 错误码=" + -count);
        }
        return count;
    }

    /**
     * 写入文件的一块数据。首块 create=true 时仅创建新文件，目标存在则失败；后续块禁止重建。
     * @param path 卷内新文件路径
     * @param offset 字节偏移
     * @param source 源数组
     * @param length 有效源字节数，可为零以创建空文件
     * @param create 是否首次创建新文件
     * @throws IOException 目标存在、设备错误或短写
     */
    public synchronized void writeFileChunk(String path, long offset, byte[] source, int length,
            boolean create) throws IOException {
        ensureMounted();
        String fatFsPath = create ? toMutableFatFsPath(path) : toFatFsPath(path);
        if (offset < 0 || length < 0 || length > source.length) {
            throw new IOException("写入 USB 文件的偏移或长度无效: " + path);
        }
        int count = nativeWriteChunk(fatFsPath, offset, source, length, create);
        if (count != length) {
            throw new IOException("写入 USB 文件失败: path=" + path + ", offset=" + offset
                    + ", expected=" + length + ", result=" + count);
        }
    }

    /**
     * FatFs 路径限定在当前卷内，禁止空段、点段、NUL 和反斜杠混入。
     */
    private String toFatFsPath(String path) throws IOException {
        if (Objects.isNull(path) || !path.startsWith("/") || path.indexOf('\\') >= 0
            || path.indexOf('\0') >= 0
            || path.contains("//") || path.equals("/.") || path.equals("/..")
            || path.contains("/./") || path.contains("/../") || path.endsWith("/.")
            || path.endsWith("/..")) {
            throw new IOException("无效的 USB 卷内路径: " + path);
        }
        if (path.equals("/")) {
            return "0:/";
        }
        return "0:" + path;
    }

    /**
     * 对会新建、删除或移动卷内条目的操作额外拒绝根路径；根目录只允许读取。
     *
     * @param path 待修改的卷内路径
     * @return 已验证的 FatFs 路径
     * @throws IOException 路径无效或指向卷根
     */
    private String toMutableFatFsPath(String path) throws IOException {
        String fatFsPath = toFatFsPath(path);
        if ("0:/".equals(fatFsPath)) {
            throw new IOException("不能修改 USB 卷根目录");
        }
        return fatFsPath;
    }

    /**
     * 检查卷仍处于当前进程的挂载状态。
     */
    private void ensureMounted() throws IOException {
        if (closed) {
            throw new IOException("USB 文件系统已卸载");
        }
    }

    /**
     * 将 FatFs 的稳定数字错误码连同业务动作和路径返回给调用方。
     */
    private void checkResult(int result, String action, String path) throws IOException {
        if (result != 0) {
            throw new IOException(action + " USB 条目失败: path=" + path + ", FatFs 错误码=" + result);
        }
    }

    /**
     * {@inheritDoc} 先卸载文件系统，再释放 USB 接口；重复调用无副作用。
     */
    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            nativeUnmount();
            usbScsiBlockDevice.close();
        }
    }

    /**
     * C 层调用 FatFs 挂载逻辑。
     */
    private static native int nativeMount(UsbScsiBlockDevice device);

    /**
     * C 层按 UTF-8 名称枚举目录。
     */
    private static native String[] nativeList(String path);

    /**
     * C 层创建目录。
     */
    private static native int nativeMakeDirectory(String path);

    /**
     * C 层删除文件或空目录。
     */
    private static native int nativeDelete(String path);

    /**
     * C 层重命名卷内条目。
     */
    private static native int nativeRename(String source, String destination);
    /** C 层以固定缓冲区复制卷内普通文件。 */
    private static native int nativeCopyFile(String source, String destination);
    /** C 层读取文件元信息。 */
    private static native long nativeFileSize(String path);
    /** C 层从指定偏移读取文件块。 */
    private static native int nativeReadChunk(String path, long offset, byte[] destination);
    /** C 层在指定偏移写入文件块。 */
    private static native int nativeWriteChunk(String path, long offset, byte[] source, int length,
            boolean create);

    /**
     * C 层解除 FatFs 挂载。
     */
    private static native void nativeUnmount();

    /**
     * 单个 FAT/exFAT 目录条目的可展示信息。
     */
    public static final class DirectoryEntry {
        /**
         * USB 卷内的文件名，不包含父目录。
         */
        public final String name;
        /**
         * 目录为 true，普通文件为 false。
         */
        public final boolean directory;

        /**
         * 建立不可变目录项快照。
         *
         * @param name      文件名
         * @param directory 是否目录
         */
        public DirectoryEntry(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }
    }
}
