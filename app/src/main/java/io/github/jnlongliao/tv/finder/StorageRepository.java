package io.github.jnlongliao.tv.finder;

import android.content.Context;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 通过 Android 11+ 系统卷 API 发现存储，同时补充 /storage 中厂商公开的可读挂载目录。
 * 只发现路径，不绕过文件系统权限；厂商未公开的 USB 挂载可能无法识别。
 * @see <a href="https://developer.android.com/reference/android/os/storage/StorageVolume">StorageVolume</a>
 */
public final class StorageRepository {
    /** 禁止实例化，无共享可变状态。 */
    private StorageRepository() {
    }

    /**
     * 返回内部共享存储及当前系统公开的已挂载存储卷。
     * @param context 用于查询系统存储服务的上下文
     * @return 去除重复路径的卷列表，不包含应用私有数据目录
     */
    public static List<StorageLocation> findStorageLocations(Context context) {
        List<StorageLocation> locations = new ArrayList<>();
        addStorageLocation(locations, context.getString(R.string.internal_storage), Environment.getExternalStorageDirectory());
        StorageManager storageManager = context.getSystemService(StorageManager.class);
        if (Objects.nonNull(storageManager)) {
            for (StorageVolume volume : storageManager.getStorageVolumes()) {
                File directory = volume.getDirectory();
                if (Objects.nonNull(directory) && !volume.isPrimary()) {
                    addStorageLocation(locations, volume.getDescription(context), directory);
                }
            }
        }
        File[] mounted = new File("/storage").listFiles();
        if (Objects.nonNull(mounted)) {
            assert mounted != null;
            for (File directory : mounted) {
                String name = directory.getName();
                if (directory.isDirectory() && directory.canRead()
                        && !"emulated".equals(name) && !"self".equals(name)) {
                    addStorageLocation(locations, "USB / " + name, directory);
                }
            }
        }
        return locations;
    }

    /** 同一路径只出现一次，系统给出的卷描述优先于目录回退名称。 */
    private static void addStorageLocation(List<StorageLocation> locations, String name, File directory) {
        for (StorageLocation location : locations) {
            if (location.directory.equals(directory)) {
                return;
            }
        }
        locations.add(new StorageLocation(name, directory));
    }
}
