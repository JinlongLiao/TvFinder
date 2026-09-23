package io.github.jnlongliao.tv.finder;

/**
 * 文件引擎的本地化消息接口；生产环境使用 Android 资源，单元测试使用独立实现。
 */
public interface FileOperationMessages {
    /**
     * 格式化当前任务语言的错误消息，无文件写入副作用。
     *
     * @param resourceId 字符串资源 ID
     * @param arguments  与资源位置占位符匹配的路径或名称
     * @return 可向用户展示的错误说明
     */
    String formatFileMessage(int resourceId, Object... arguments);
}
