package io.github.jnlongliao.tv.finder;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地文件操作引擎，每个任务创建一个实例并在单一工作线程使用。
 * 禁止覆盖和符号链接。复制失败清理本任务新建的目标；移动先完整复制、核对源树再删除。
 * 删除及移动的源删除阶段不可回滚；失败会明确保留剩余文件，不宣称事务原子性。
 *
 * @see <a href="https://developer.android.com/reference/java/nio/file/Files">Android Files API</a>
 */
public final class FileOperations {
    /**
     * UI 可请求取消；只在复制及显式删除的安全检查点读取。
     */
    private final AtomicBoolean cancellationRequested;
    /**
     * 工作线程进度接收者，整个任务期间保持有效。
     */
    private final FileProgressListener fileProgressListener;
    /**
     * 已成功读取并写入的累计字节，仅工作线程修改。
     */
    private long copiedBytes;
    /**
     * 当前任务语言的格式化器；整个任务期间保持不变。
     */
    private final FileOperationMessages fileOperationMessages;

    /**
     * 创建不共享文件句柄的任务实例。
     *
     * @param cancellationRequested 线程安全的取消标志
     * @param fileProgressListener  非空进度接收者
     * @param fileOperationMessages 当前任务语言的消息格式化器
     */
    public FileOperations(AtomicBoolean cancellationRequested, FileProgressListener fileProgressListener,
                          FileOperationMessages fileOperationMessages) {
        this.cancellationRequested = Objects.requireNonNull(cancellationRequested);
        this.fileProgressListener = Objects.requireNonNull(fileProgressListener);
        this.fileOperationMessages = Objects.requireNonNull(fileOperationMessages);
    }

    /**
     * 复制文件或整个目录，不覆盖任何已存在目标，不支持符号链接。
     *
     * @param source               已存在的源路径
     * @param destinationDirectory 已存在的目标目录
     * @return 新建目标路径
     * @throws IOException 无权限、同名冲突、空间不足、源变化或取消；源文件不删除
     */
    public Path copyFileTree(Path source, Path destinationDirectory) throws IOException {
        Path target = validateFileDestination(source, destinationDirectory, source.getFileName().toString());
        copyFileEntry(source, target, 0);
        return target;
    }

    /**
     * 先复制全部数据，再逐文件比对源与目标内容，最后开始不可取消的源删除阶段。
     * 不支持外部程序同时修改源树；核对期间检测到变化会保留源及目标并报错。
     *
     * @param source               待移动文件或目录
     * @param destinationDirectory 已存在目标目录
     * @throws IOException 复制、校验或删除失败；删除阶段失败可能留下部分源和完整目标
     */
    public void moveFileTree(Path source, Path destinationDirectory) throws IOException {
        Path target = copyFileTree(source, destinationDirectory);
        try {
            verifyCopiedTree(source, target, 0);
            checkFileCancellation();
        } catch (IOException | RuntimeException exception) {
            throw new IOException(fileOperationMessages.formatFileMessage(R.string.move_verification_failed, target), exception);
        }
        // 此后不响应取消，避免把正常取消误报为可恢复的移动回滚。
        try {
            deleteFileEntry(source, false, 0);
        } catch (IOException | RuntimeException exception) {
            throw new IOException(fileOperationMessages.formatFileMessage(R.string.move_deletion_failed, target), exception);
        }
    }

    /**
     * 永久删除用户确认的文件树，取消或异常不会恢复已删除文件。
     *
     * @param source 用户确认的非磁盘根目录路径
     * @throws IOException 无权限、拔盘、取消或部分删除失败
     */
    public void deleteFileTree(Path source) throws IOException {
        deleteFileEntry(source, true, 0);
    }

    /**
     * 在相同父目录改名；禁止空名称、路径分隔符、控制字符和已有名称。
     *
     * @param source  原文件或目录
     * @param newName 不含路径的名称
     * @throws IOException 输入非法、目标存在或文件系统拒绝改名
     */
    public void renameFileEntry(Path source, String newName) throws IOException {
        validateFileName(newName);
        Path target = validateFileDestination(source, source.getParent(), newName);
        checkFileCancellation();
        Files.move(source, target);
    }

    /**
     * 验证文件名，保持合法名称原样，不偷偷裁剪用户输入。
     *
     * @param fileName 用户输入名称
     * @throws IOException 名称为空、路径穿越或包含非法字符
     */
    public void validateFileName(String fileName) throws IOException {
        if (Objects.isNull(fileName) || fileName.trim().isEmpty()
            || ".".equals(fileName) || "..".equals(fileName)
            || fileName.matches("(?s).*[\\\\/:*?\"<>|\\p{Cntrl}].*")) {
            throw new IOException(fileOperationMessages.formatFileMessage(R.string.invalid_file_name));
        }
    }

    /**
     * 检查真实路径以拒绝向自身或子目录复制，并在任何写入前检查冲突。
     */
    private Path validateFileDestination(Path source, Path directory, String fileName) throws IOException {
        rejectFileLink(source);
        Path realSource = source.toRealPath();
        Path realDirectory = directory.toRealPath();
        Path target = realDirectory.resolve(fileName);
        if (!Files.isDirectory(realDirectory) || target.startsWith(realSource)) {
            throw new IOException(fileOperationMessages.formatFileMessage(R.string.recursive_destination));
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(fileOperationMessages.formatFileMessage(R.string.target_exists, fileName));
        }
        return target;
    }

    /**
     * 有界深度递归复制；仅清理由本次成功创建的目标，失败根因保留为 cause。
     */
    private void copyFileEntry(Path source, Path target, int depth) throws IOException {
        checkFileCancellation();
        validateFileDepth(depth);
        rejectFileLink(source);
        BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class);
        if (!before.isDirectory() && !before.isRegularFile()) {
            throw new IOException(fileOperationMessages.formatFileMessage(R.string.file_type_unsupported, source));
        }
        boolean created = false;
        try {
            if (before.isDirectory()) {
                Files.createDirectory(target);
                created = true;
                try (DirectoryStream<Path> children = Files.newDirectoryStream(source)) {
                    for (Path child : children) {
                        copyFileEntry(child, target.resolve(child.getFileName()), depth + 1);
                    }
                }
            } else {
                Files.createFile(target);
                created = true;
                try (InputStream input = Files.newInputStream(source);
                     OutputStream output = Files.newOutputStream(target, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[128 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkFileCancellation();
                        output.write(buffer, 0, count);
                        copiedBytes += count;
                        fileProgressListener.reportFileProgress(source.getFileName().toString(), copiedBytes);
                    }
                }
            }
            checkFileCancellation();
            BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
                throw new IOException(fileOperationMessages.formatFileMessage(R.string.source_changed, source));
            }
        } catch (IOException | RuntimeException exception) {
            if (created) {
                try {
                    deleteFileEntry(target, false, 0);
                } catch (IOException | RuntimeException cleanupException) {
                    exception.addSuppressed(cleanupException);
                    throw new IOException(fileOperationMessages.formatFileMessage(R.string.copy_cleanup_failed, target), exception);
                }
            }
            throw exception;
        }
    }

    /**
     * 移动前逐字节核对全部文件并检查目录成员，不依赖低精度修改时间判断内容。
     */
    private void verifyCopiedTree(Path source, Path target, int depth) throws IOException {
        checkFileCancellation();
        validateFileDepth(depth);
        rejectFileLink(source);
        if (Files.isDirectory(source)) {
            Set<String> sourceNames = new HashSet<>();
            try (DirectoryStream<Path> children = Files.newDirectoryStream(source)) {
                for (Path child : children) {
                    sourceNames.add(child.getFileName().toString());
                    verifyCopiedTree(child, target.resolve(child.getFileName()), depth + 1);
                }
            }
            try (DirectoryStream<Path> children = Files.newDirectoryStream(target)) {
                for (Path child : children) {
                    if (!sourceNames.contains(child.getFileName().toString())) {
                        throw new IOException(fileOperationMessages.formatFileMessage(R.string.source_folder_changed, source));
                    }
                }
            }
        } else {
            if (Files.size(source) != Files.size(target)) {
                throw new IOException(fileOperationMessages.formatFileMessage(R.string.source_size_changed, source));
            }
            try (InputStream original = Files.newInputStream(source);
                 InputStream copied = Files.newInputStream(target)) {
                byte[] first = new byte[128 * 1024];
                byte[] second = new byte[128 * 1024];
                int count;
                while ((count = original.read(first)) != -1) {
                    checkFileCancellation();
                    int offset = 0;
                    while (offset < count) {
                        int read = copied.read(second, offset, count - offset);
                        if (read < 0) {
                            throw new IOException(fileOperationMessages.formatFileMessage(R.string.target_length_changed, target));
                        }
                        offset += read;
                    }
                    for (int index = 0; index < count; index++) {
                        if (first[index] != second[index]) {
                            throw new IOException(fileOperationMessages.formatFileMessage(R.string.source_content_changed, source));
                        }
                    }
                }
                if (copied.read() != -1) {
                    throw new IOException(fileOperationMessages.formatFileMessage(R.string.source_length_changed, source));
                }
            }
        }
    }

    /**
     * 不跟随链接删除；目录深度限制防止栈溢出，失败由调用者解释部分完成语义。
     */
    private void deleteFileEntry(Path source, boolean cancellable, int depth) throws IOException {
        validateFileDepth(depth);
        if (cancellable) {
            checkFileCancellation();
        }
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(source)) {
                for (Path child : children) {
                    deleteFileEntry(child, cancellable, depth + 1);
                }
            }
        }
        Files.delete(source);
        fileProgressListener.reportFileProgress(source.getFileName().toString(), copiedBytes);
    }

    /**
     * 复制与校验不跟随符号链接，防止意外访问另一个磁盘或系统目录。
     */
    private void rejectFileLink(Path source) throws IOException {
        if (Files.isSymbolicLink(source)) {
            throw new IOException(fileOperationMessages.formatFileMessage(R.string.file_link_unsupported, source));
        }
    }

    /**
     * 限制目录递归为 128 层；超限失败会走复制清理流程。
     */
    private void validateFileDepth(int depth) throws IOException {
        if (depth > 128) {
            throw new IOException(fileOperationMessages.formatFileMessage(R.string.directory_too_deep));
        }
    }

    /**
     * 取消请求与线程中断均转为可诊断的 I/O 取消结果。
     */
    private void checkFileCancellation() throws IOException {
        if (cancellationRequested.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException(fileOperationMessages.formatFileMessage(R.string.operation_cancelled));
        }
    }
}
