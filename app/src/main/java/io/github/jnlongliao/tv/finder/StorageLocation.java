package io.github.jnlongliao.tv.finder;

import java.io.File;

/** 系统发现的存储卷快照；容量在渲染时刷新，路径不表示已获得访问权限。 */
public final class StorageLocation {
    /** 面向用户的卷名称，来自系统描述或明确的内部存储标识。 */
    public final String displayName;
    /** 存储根目录，用于限制向上导航与禁止删除整个存储卷。 */
    public final File directory;

    /**
     * 创建不可变卷描述。
     * @param displayName 用户可读名称
     * @param directory 系统提供或已发现的存储根目录
     */
    public StorageLocation(String displayName, File directory) {
        this.displayName = displayName;
        this.directory = directory;
    }
}
