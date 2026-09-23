package io.github.jnlongliao.tv.finder;

/**
 * 文件操作工作线程的进度回调；实现者必须自行切换到 UI 线程。
 */
public interface FileProgressListener {
    /**
     * 报告当前文件及累计已复制字节；删除时字节数为零。
     *
     * @param fileName    当前处理文件名，不含文件正文
     * @param copiedBytes 当前任务累计复制字节，非负
     */
    void reportFileProgress(String fileName, long copiedBytes);
}
