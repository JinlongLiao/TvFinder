package io.github.jnlongliao.tv.finder;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Arrays;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 在独立临时目录验证文件操作的数据保全、冲突和取消边界，不触碰用户文件。 */
public final class FileOperationsTest {
    /** 每个测试独立创建并自动清理的文件树。 */
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /** 复制中文目录、嵌套文件及空目录后，源仍然存在且内容完全一致。 */
    @Test
    public void copyNestedDirectoryPreservesSourceAndContents() throws IOException {
        Path source = temporaryFolder.newFolder("影片").toPath();
        Files.createDirectories(source.resolve("空目录"));
        Files.write(source.resolve("字幕.srt"), "中文字幕".getBytes(StandardCharsets.UTF_8));
        Path destination = temporaryFolder.newFolder("usb").toPath();
        Path copied = createFileOperations().copyFileTree(source, destination);
        assertTrue(Files.isDirectory(copied.resolve("空目录")));
        assertArrayEquals(Files.readAllBytes(source.resolve("字幕.srt")), Files.readAllBytes(copied.resolve("字幕.srt")));
        assertTrue(Files.exists(source));
    }

    /** 已存在目标不能被覆盖，也不能被失败清理误删。 */
    @Test
    public void copyConflictPreservesBothFiles() throws IOException {
        Path source = temporaryFolder.newFile("a.txt").toPath();
        Files.write(source, new byte[]{1, 2});
        Path destination = temporaryFolder.newFolder("usb").toPath();
        Path existing = Files.write(destination.resolve("a.txt"), new byte[]{7});
        assertThrows(IOException.class, () -> createFileOperations().copyFileTree(source, destination));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(existing));
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(source));
    }

    /** 自身及子目录目标必须在创建数据前拒绝，防止无限递归复制。 */
    @Test
    public void copyIntoDescendantIsRejected() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Path child = Files.createDirectory(source.resolve("child"));
        assertThrows(IOException.class, () -> createFileOperations().copyFileTree(source, child));
        assertFalse(Files.exists(child.resolve("source")));
    }

    /** 中途取消清理新建目标，保留完整源文件。 */
    @Test
    public void cancelledCopyRemovesPartialTarget() throws IOException {
        Path source = temporaryFolder.newFile("large.bin").toPath();
        byte[] content = new byte[512 * 1024];
        Files.write(source, content);
        Path destination = temporaryFolder.newFolder("usb").toPath();
        AtomicBoolean cancelled = new AtomicBoolean();
        FileOperations fileOperations = new FileOperations(cancelled, (name, bytes) -> cancelled.set(true), this::formatTestFileMessage);
        assertThrows(IOException.class, () -> fileOperations.copyFileTree(source, destination));
        assertFalse(Files.exists(destination.resolve("large.bin")));
        assertArrayEquals(content, Files.readAllBytes(source));
    }

    /** 最后一个数据块刚写完收到取消，仍须清理目标而非报告成功。 */
    @Test
    public void cancelledCopyAfterLastChunkRemovesTarget() throws IOException {
        Path source = temporaryFolder.newFile("small.bin").toPath();
        Files.write(source, new byte[]{1});
        Path destination = temporaryFolder.newFolder("usb").toPath();
        AtomicBoolean cancelled = new AtomicBoolean();
        FileOperations fileOperations = new FileOperations(cancelled, (name, bytes) -> cancelled.set(true), this::formatTestFileMessage);
        assertThrows(IOException.class, () -> fileOperations.copyFileTree(source, destination));
        assertFalse(Files.exists(destination.resolve("small.bin")));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(source));
    }

    /** 移动目录完成后目标保留数据，源目录才移除。 */
    @Test
    public void moveDirectoryRetainsVerifiedDestination() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.write(source.resolve("file"), new byte[]{4, 5, 6});
        Path destination = temporaryFolder.newFolder("usb").toPath();
        createFileOperations().moveFileTree(source, destination);
        assertFalse(Files.exists(source));
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(destination.resolve("source/file")));
    }

    /** 取消移动的复制阶段不能删除源。 */
    @Test
    public void cancelledMovePreservesSource() throws IOException {
        Path source = temporaryFolder.newFile("large.bin").toPath();
        Files.write(source, new byte[512 * 1024]);
        Path destination = temporaryFolder.newFolder("usb").toPath();
        AtomicBoolean cancelled = new AtomicBoolean();
        FileOperations fileOperations = new FileOperations(cancelled, (name, bytes) -> cancelled.set(true), this::formatTestFileMessage);
        assertThrows(IOException.class, () -> fileOperations.moveFileTree(source, destination));
        assertTrue(Files.exists(source));
    }

    /** 源在复制结束时被同大小、同时间戳内容替换，移动仍须通过内容核对阻止源删除。 */
    @Test
    public void moveDetectsChangedContentEvenWhenMetadataMatches() throws IOException {
        Path source = temporaryFolder.newFile("changed.bin").toPath();
        Files.write(source, new byte[]{1, 2, 3});
        FileTime timestamp = Files.getLastModifiedTime(source);
        Path destination = temporaryFolder.newFolder("usb").toPath();
        AtomicBoolean changed = new AtomicBoolean();
        FileOperations fileOperations = new FileOperations(new AtomicBoolean(), (name, bytes) -> {
            if (changed.compareAndSet(false, true)) {
                try {
                    Files.write(source, new byte[]{7, 8, 9});
                    Files.setLastModifiedTime(source, timestamp);
                } catch (IOException exception) {
                    throw new AssertionError("测试数据修改失败", exception);
                }
            }
        }, this::formatTestFileMessage);
        assertThrows(IOException.class, () -> fileOperations.moveFileTree(source, destination));
        assertArrayEquals(new byte[]{7, 8, 9}, Files.readAllBytes(source));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(destination.resolve("changed.bin")));
    }

    /** 重命名冲突时不能破坏目标，源保留供用户重新选择名称。 */
    @Test
    public void renameConflictPreservesExistingTarget() throws IOException {
        Path source = temporaryFolder.newFile("source.txt").toPath();
        Path target = temporaryFolder.newFile("target.txt").toPath();
        Files.write(target, new byte[]{5});
        assertThrows(IOException.class, () -> createFileOperations().renameFileEntry(source, "target.txt"));
        assertTrue(Files.exists(source));
        assertArrayEquals(new byte[]{5}, Files.readAllBytes(target));
    }

    /** 路径穿越与分隔符不能通过重命名进入其他目录。 */
    @Test
    public void renameRejectsPathTraversalAndEmptyNames() throws IOException {
        Path source = temporaryFolder.newFile("a.txt").toPath();
        for (String invalid : new String[]{"", " ", ".", "..", "../escape", "a/b", "a\\b", "a\nb\nc"}) {
            assertThrows(IOException.class, () -> createFileOperations().renameFileEntry(source, invalid));
        }
        assertTrue(Files.exists(source));
    }

    /** 合法中文名称可完整保存。 */
    @Test
    public void renamePreservesContents() throws IOException {
        Path source = temporaryFolder.newFile("a.txt").toPath();
        Files.write(source, new byte[]{9});
        createFileOperations().renameFileEntry(source, "中文名字.txt");
        assertFalse(Files.exists(source));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(source.resolveSibling("中文名字.txt")));
    }

    /** 删除仅影响指定树，不改变同级文件。 */
    @Test
    public void deleteTreePreservesSibling() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.write(source.resolve("file"), new byte[]{1});
        Path sibling = temporaryFolder.newFile("keep").toPath();
        createFileOperations().deleteFileTree(source);
        assertFalse(Files.exists(source));
        assertTrue(Files.exists(sibling));
    }

    /** 删除开始前取消不会修改源树。 */
    @Test
    public void cancelledDeletionPreservesSource() throws IOException {
        Path source = temporaryFolder.newFile("keep").toPath();
        FileOperations fileOperations = new FileOperations(new AtomicBoolean(true), (name, bytes) -> { }, this::formatTestFileMessage);
        assertThrows(IOException.class, () -> fileOperations.deleteFileTree(source));
        assertTrue(Files.exists(source));
    }

    /** 创建不取消、不消费进度的独立测试任务实例。 */
    private FileOperations createFileOperations() {
        return new FileOperations(new AtomicBoolean(), (name, bytes) -> { }, this::formatTestFileMessage);
    }
    /** 测试不加载 Android 资源，只保留错误标识与参数用于断言诊断。 */
    private String formatTestFileMessage(int resourceId, Object... arguments) {
        return resourceId + ": " + Arrays.toString(arguments);
    }

}
