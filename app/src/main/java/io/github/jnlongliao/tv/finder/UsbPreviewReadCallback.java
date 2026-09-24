package io.github.jnlongliao.tv.finder;

import android.os.ProxyFileDescriptorCallback;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import java.io.IOException;

/**
 * 单个 USB 文件的只读随机访问回调；每次读请求在 FatFsVolume 锁下完成，不缓存整个媒体文件。
 */
public final class UsbPreviewReadCallback extends ProxyFileDescriptorCallback {
    /** 回调所属卷，由 USB 页面保持挂载。 */
    private final FatFsVolume fatFsVolume;
    /** 卷内文件路径。 */
    private final String path;
    /** 打开时的文件长度，用于 seek 与 EOF。 */
    private final long length;

    /**
     * 固定只读文件的来源及大小。
     * @param fatFsVolume 已挂载卷
     * @param path 卷内文件路径
     * @param length 文件字节数
     */
    public UsbPreviewReadCallback(FatFsVolume fatFsVolume, String path, long length) {
        this.fatFsVolume = fatFsVolume;
        this.path = path;
        this.length = length;
    }

    /** {@inheritDoc} */
    @Override
    public long onGetSize() {
        return length;
    }

    /**
     * {@inheritDoc} 设备断开和 FatFs 失败转成 EIO，保留完整日志供定位。
     */
    @Override
    public int onRead(long offset, int size, byte[] data) throws ErrnoException {
        if (offset < 0 || size < 0 || size > data.length) {
            throw new ErrnoException("USB preview read", OsConstants.EINVAL);
        }
        if (offset >= length) {
            return 0;
        }
        int requested = (int) Math.min(size, length - offset);
        int total = 0;
        try {
            while (total < requested) {
                byte[] chunk = new byte[requested - total];
                int count = fatFsVolume.readFileChunk(path, offset + total, chunk);
                if (count <= 0) {
                    throw new IOException("文件在读取期间提前结束: expected=" + length + ", offset="
                        + (offset + total));
                }
                System.arraycopy(chunk, 0, data, total, count);
                total += count;
            }
            return total;
        } catch (IOException exception) {
            Log.e("TvFinderUsbPreview", "USB 预览读取失败 path=" + path + ", offset=" + offset
                + ", size=" + size + ", stage=read, type=" + exception.getClass().getName()
                + ", message=" + exception.getMessage(), exception);
            throw new ErrnoException("USB preview read", OsConstants.EIO, exception);
        }
    }

    /** {@inheritDoc} 回调不持有卷所有权。 */
    @Override
    public void onRelease() {
    }
}
