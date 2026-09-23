package io.github.jnlongliao.tv.finder;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;

/**
 * 通过 Android USB Host 直接访问 Bulk-Only SCSI 磁盘，不依赖电视固件挂载 exFAT。
 * 每条命令按 CBW、数据、CSW 顺序执行，所有传输串行；断开或短传输立即失败，避免在未知状态下继续写入。
 * 当前仅支持 READ/WRITE(10) 所覆盖的磁盘和 512/4096 字节逻辑扇区。
 *
 * @see <a href="https://developer.android.com/develop/connectivity/usb/host">Android USB host</a>
 * @see <a href="https://www.usb.org/document-library/mass-storage-bulk-only-10">USB Mass Storage Bulk-Only Transport</a>
 */
public final class UsbScsiBlockDevice implements Closeable {
    /**
     * 单次 USB 传输超时，单位毫秒。
     */
    private static final int TRANSFER_TIMEOUT_MILLIS = 10000;
    /**
     * USB Mass Storage Bulk-Only 协议编号。
     */
    private static final int BULK_ONLY_PROTOCOL = 80;
    /**
     * SCSI transparent command set 子类编号。
     */
    private static final int SCSI_SUBCLASS = 6;
    /**
     * SCSI READ/WRITE(10) 最多传输的字节数，避免大数组及设备缓冲区压力。
     */
    private static final int MAX_TRANSFER_BYTES = 16 * 1024;
    /**
     * Android USB 连接，关闭后任何磁盘访问均失败。
     */
    private final UsbDeviceConnection usbDeviceConnection;
    /**
     * 已声明的 USB 存储接口。
     */
    private final UsbInterface usbInterface;
    /**
     * USB 批量输入端点。
     */
    private final UsbEndpoint inputEndpoint;
    /**
     * USB 批量输出端点。
     */
    private final UsbEndpoint outputEndpoint;
    /**
     * 可访问的逻辑扇区数。
     */
    private final long sectorCount;
    /**
     * 单个逻辑扇区的字节数。
     */
    private final int sectorSize;
    /**
     * CBW 标记在当前连接内递增，用于核对命令状态。
     */
    private int nextCommandTag = 1;
    /**
     * 关闭标记，访问仅在对象锁内进行。
     */
    private boolean closed;

    /**
     * 识别可由本驱动处理的 USB Mass Storage 接口。
     *
     * @param usbDevice 已枚举的 USB 设备
     * @return SCSI Bulk-Only 接口；不兼容时为 null
     */
    public static UsbInterface findScsiStorageInterface(UsbDevice usbDevice) {
        for (int index = 0; index < usbDevice.getInterfaceCount(); index++) {
            UsbInterface candidate = usbDevice.getInterface(index);
            if (candidate.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE
                && candidate.getInterfaceSubclass() == SCSI_SUBCLASS
                && candidate.getInterfaceProtocol() == BULK_ONLY_PROTOCOL) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 组合设备真实报告的制造商和产品名，用于界面展示；名称为空时由调用方显示通用 USB 标签。
     * @param usbDevice Android 枚举到的 USB 设备
     * @return 可读设备名；设备未报告名称时为空字符串
     */
    public static String resolveUsbDeviceDisplayName(UsbDevice usbDevice) {
        String manufacturer = usbDevice.getManufacturerName();
        String product = usbDevice.getProductName();
        String first = Objects.isNull(manufacturer) ? "" : manufacturer.trim();
        String second = Objects.isNull(product) ? "" : product.trim();
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty() || second.equalsIgnoreCase(first)) {
            return first;
        }
        return first + " " + second;
    }

    /**
     * 在应用获得 UsbManager 授权后声明接口并读取容量。
     * 若初始化失败，释放连接及接口；不会修改磁盘数据。
     *
     * @param usbManager Android USB 服务
     * @param usbDevice  已获授权的设备
     * @throws IOException 设备不兼容、被占用、断开或容量无效
     */
    public UsbScsiBlockDevice(UsbManager usbManager, UsbDevice usbDevice) throws IOException {
        UsbInterface candidateInterface = findScsiStorageInterface(usbDevice);
        if (Objects.isNull(candidateInterface) || !usbManager.hasPermission(usbDevice)) {
            throw new IOException("USB 存储设备不兼容或未授权: " + usbDevice.getDeviceName());
        }
        UsbEndpoint candidateInput = null;
        UsbEndpoint candidateOutput = null;
        for (int index = 0; index < candidateInterface.getEndpointCount(); index++) {
            UsbEndpoint endpoint = candidateInterface.getEndpoint(index);
            if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                    candidateInput = endpoint;
                } else if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                    candidateOutput = endpoint;
                }
            }
        }
        if (Objects.isNull(candidateInput) || Objects.isNull(candidateOutput)) {
            throw new IOException("USB 存储设备缺少 Bulk IN/OUT 端点: " + usbDevice.getDeviceName());
        }
        UsbDeviceConnection connection = usbManager.openDevice(usbDevice);
        if (Objects.isNull(connection)) {
            throw new IOException("无法打开 USB 存储设备: " + usbDevice.getDeviceName());
        }
        if (!connection.claimInterface(candidateInterface, true)) {
            connection.close();
            throw new IOException("无法声明 USB 存储接口: " + usbDevice.getDeviceName());
        }
        usbDeviceConnection = connection;
        usbInterface = candidateInterface;
        inputEndpoint = candidateInput;
        outputEndpoint = candidateOutput;
        try {
            byte[] capacityCommand = new byte[10];
            capacityCommand[0] = 0x25;
            byte[] capacity = executeScsiCommand(capacityCommand, new byte[8], false);
            long lastSector = ((capacity[0] & 0xffL) << 24) | ((capacity[1] & 0xffL) << 16)
                | ((capacity[2] & 0xffL) << 8) | (capacity[3] & 0xffL);
            int blockSize = ((capacity[4] & 0xff) << 24) | ((capacity[5] & 0xff) << 16)
                | ((capacity[6] & 0xff) << 8) | (capacity[7] & 0xff);
            if (lastSector == 0xffffffffL || (blockSize != 512 && blockSize != 4096)) {
                throw new IOException("不支持的 USB 磁盘容量或逻辑扇区大小: lastSector="
                    + lastSector + ", blockSize=" + blockSize);
            }
            sectorCount = lastSector + 1;
            sectorSize = blockSize;
        } catch (IOException exception) {
            close();
            throw exception;
        }
    }

    /**
     * @return 单个逻辑扇区的字节数。
     */
    public int getSectorSize() {
        return sectorSize;
    }

    /**
     * @return 可访问的逻辑扇区数。
     */
    public long getSectorCount() {
        return sectorCount;
    }

    /**
     * 读取连续完整扇区，供 FatFs 的 disk_read 回调使用。
     *
     * @param firstSector 首扇区 LBA
     * @param data        长度为扇区大小整数倍的目标数组
     * @throws IOException 读越界、设备断开或 SCSI 返回失败
     */
    public synchronized void readSectors(long firstSector, byte[] data) throws IOException {
        transferSectors(firstSector, data, false);
    }

    /**
     * 写入连续完整扇区，供 FatFs 的 disk_write 回调使用。
     * 上层必须确保没有其他进程同时写入该分区，且写入前已取得 USB 授权。
     *
     * @param firstSector 首扇区 LBA
     * @param data        长度为扇区大小整数倍的源数组
     * @throws IOException 写越界、设备断开或 SCSI 返回失败
     */
    public synchronized void writeSectors(long firstSector, byte[] data) throws IOException {
        transferSectors(firstSector, data, true);
    }

    /**
     * 请求设备把写缓存落盘；FatFs 的 CTRL_SYNC 在安全弹出前也会调用。
     * @throws IOException 设备断开或 SCSI 缓存同步失败
     */
    public synchronized void syncDevice() throws IOException {
        if (closed) {
            throw new IOException("USB 磁盘连接已关闭");
        }
        byte[] command = new byte[10];
        command[0] = 0x35;
        try {
            executeScsiCommand(command, new byte[0], false);
        } catch (IOException syncException) {
            byte[] senseCommand = new byte[6];
            senseCommand[0] = 0x03;
            senseCommand[4] = 18;
            byte[] sense;
            try {
                sense = executeScsiCommand(senseCommand, new byte[18], false);
            } catch (IOException senseException) {
                syncException.addSuppressed(senseException);
                throw syncException;
            }
            int senseKey = sense[2] & 0x0f;
            int additionalCode = sense[12] & 0xff;
            if (senseKey == 5 && (additionalCode == 0x20 || additionalCode == 0x24)) {
                // 设备明确报告不支持该可选命令；WRITE(10) 的成功 CSW 已确认数据被设备接受。
                return;
            }
            throw new IOException("USB 缓存同步失败: senseKey=" + senseKey
                    + ", asc=0x" + Integer.toHexString(additionalCode), syncException);
        }
    }

    /**
     * 在 SCSI READ/WRITE(10) 限制内分块传输；任何一块失败后立即停止。
     */
    private void transferSectors(long firstSector, byte[] data, boolean write) throws IOException {
        if (closed) {
            throw new IOException("USB 磁盘连接已关闭");
        }
        if (data.length == 0 || data.length % sectorSize != 0) {
            throw new IOException("USB 扇区传输长度无效: " + data.length);
        }
        long totalSectors = data.length / sectorSize;
        if (firstSector < 0 || firstSector + totalSectors > sectorCount
            || firstSector + totalSectors > 0x100000000L) {
            throw new IOException("USB 扇区访问越界: LBA=" + firstSector + ", count=" + totalSectors);
        }
        int offset = 0;
        while (offset < data.length) {
            int length = Math.min(MAX_TRANSFER_BYTES / sectorSize * sectorSize, data.length - offset);
            int blocks = length / sectorSize;
            byte[] command = new byte[10];
            command[0] = (byte) (write ? 0x2a : 0x28);
            long lba = firstSector + offset / sectorSize;
            command[2] = (byte) (lba >>> 24);
            command[3] = (byte) (lba >>> 16);
            command[4] = (byte) (lba >>> 8);
            command[5] = (byte) lba;
            command[7] = (byte) (blocks >>> 8);
            command[8] = (byte) blocks;
            byte[] block = new byte[length];
            if (write) {
                System.arraycopy(data, offset, block, 0, length);
            }
            executeScsiCommand(command, block, write);
            if (!write) {
                System.arraycopy(block, 0, data, offset, length);
            }
            offset += length;
        }
    }

    /**
     * 执行单条 Bulk-Only 命令并核对完整 CSW、标记、残留及命令状态。
     *
     * @param command SCSI CDB，长度最多 16 字节
     * @param data    数据阶段缓冲区；零长度表示无数据阶段
     * @param write   数据阶段方向是否为主机写往设备
     * @return 原数据缓冲区
     * @throws IOException 任一阶段发生短传输、设备错误或命令失败
     */
    private byte[] executeScsiCommand(byte[] command, byte[] data, boolean write) throws IOException {
        byte[] wrapper = new byte[31];
        wrapper[0] = 0x55;
        wrapper[1] = 0x53;
        wrapper[2] = 0x42;
        wrapper[3] = 0x43;
        int tag = nextCommandTag++;
        for (int index = 0; index < 4; index++) {
            wrapper[4 + index] = (byte) (tag >>> (index * 8));
            wrapper[8 + index] = (byte) (data.length >>> (index * 8));
        }
        wrapper[12] = (byte) (write ? 0 : 0x80);
        wrapper[14] = (byte) command.length;
        System.arraycopy(command, 0, wrapper, 15, command.length);
        transferExact(outputEndpoint, wrapper, "CBW");
        if (data.length > 0) {
            transferExact(write ? outputEndpoint : inputEndpoint, data, "SCSI 数据");
        }
        byte[] status = new byte[13];
        transferExact(inputEndpoint, status, "CSW");
        int returnedTag = (status[4] & 0xff) | ((status[5] & 0xff) << 8)
            | ((status[6] & 0xff) << 16) | ((status[7] & 0xff) << 24);
        int residue = (status[8] & 0xff) | ((status[9] & 0xff) << 8)
            | ((status[10] & 0xff) << 16) | ((status[11] & 0xff) << 24);
        if (status[0] != 0x55 || status[1] != 0x53 || status[2] != 0x42 || status[3] != 0x53
            || returnedTag != tag || residue != 0 || status[12] != 0) {
            throw new IOException("SCSI 命令失败: opcode=0x" + Integer.toHexString(command[0] & 0xff)
                + ", tag=" + tag + ", returnedTag=" + returnedTag + ", residue=" + residue
                + ", status=" + (status[12] & 0xff));
        }
        return data;
    }

    /**
     * Android bulkTransfer 可能发生短传输，必须按偏移继续直至完成。
     */
    private void transferExact(UsbEndpoint endpoint, byte[] bytes, String stage) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            int transferred = usbDeviceConnection.bulkTransfer(endpoint, bytes, offset,
                bytes.length - offset, TRANSFER_TIMEOUT_MILLIS);
            if (transferred <= 0) {
                throw new IOException("USB " + stage + " 传输失败: offset=" + offset
                    + ", expected=" + bytes.length + ", transferred=" + transferred);
            }
            offset += transferred;
        }
    }

    /**
     * 关闭 USB 接口及连接。重复关闭无副作用；调用方应先卸载文件系统。
     */
    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            usbDeviceConnection.releaseInterface(usbInterface);
            usbDeviceConnection.close();
        }
    }
}
