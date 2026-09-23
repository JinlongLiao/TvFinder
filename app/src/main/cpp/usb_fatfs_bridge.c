#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "fatfs/ff.h"
#include "fatfs/diskio.h"

/* FatFs R0.16 的磁盘回调以单个逻辑卷为全局对象；Java 层在专用线程串行调用全部 JNI 方法。 */
static FATFS volume;
static JavaVM *java_vm;
static jobject block_device;
static jmethodID read_sectors_method;
static jmethodID write_sectors_method;
static jmethodID sector_count_method;
static jmethodID sector_size_method;
static jmethodID sync_device_method;
static int mounted;

/* 只从已进入 JNI 的线程获取环境，避免磁盘回调在未经注册的线程上访问 Java 对象。 */
static JNIEnv *current_environment(void) {
    JNIEnv *environment = NULL;
    if (java_vm == NULL || (*java_vm)->GetEnv(java_vm, (void **) &environment, JNI_VERSION_1_6) != JNI_OK) {
        return NULL;
    }
    return environment;
}

/* 将 USB I/O 异常记录到 logcat 并转成 FatFs 磁盘错误，避免带异常继续调用 JNI。 */
static int clear_usb_exception(JNIEnv *environment) {
    if ((*environment)->ExceptionCheck(environment)) {
        (*environment)->ExceptionDescribe(environment);
        (*environment)->ExceptionClear(environment);
        __android_log_print(ANDROID_LOG_ERROR, "TvFinderFatFs", "USB sector transfer failed");
        return 1;
    }
    return 0;
}

/* JNI 的 GetStringUTFChars/NewStringUTF 使用改造 UTF-8；FatFs 配置要求标准 UTF-8。
 * 通过 Java 标准字符集显式转换，避免非 BMP 文件名（如 emoji）在两层之间损坏。 */
static char *java_string_to_utf8(JNIEnv *environment, jstring value) {
    if (value == NULL) {
        return NULL;
    }
    jclass string_class = (*environment)->FindClass(environment, "java/lang/String");
    if (string_class == NULL) {
        return NULL;
    }
    jmethodID get_bytes = (*environment)->GetMethodID(environment, string_class, "getBytes",
            "(Ljava/lang/String;)[B");
    jstring charset = (*environment)->NewStringUTF(environment, "UTF-8");
    jbyteArray encoded = get_bytes != NULL && charset != NULL
            ? (*environment)->CallObjectMethod(environment, value, get_bytes, charset) : NULL;
    char *bytes = NULL;
    if (encoded != NULL && !(*environment)->ExceptionCheck(environment)) {
        jsize length = (*environment)->GetArrayLength(environment, encoded);
        bytes = malloc((size_t) length + 1);
        if (bytes != NULL) {
            (*environment)->GetByteArrayRegion(environment, encoded, 0, length, (jbyte *) bytes);
            if ((*environment)->ExceptionCheck(environment)) {
                free(bytes);
                bytes = NULL;
            } else {
                bytes[length] = '\0';
            }
        }
    }
    if (encoded != NULL) (*environment)->DeleteLocalRef(environment, encoded);
    if (charset != NULL) (*environment)->DeleteLocalRef(environment, charset);
    (*environment)->DeleteLocalRef(environment, string_class);
    return bytes;
}

/* FatFs 返回标准 UTF-8，交给 Java String(String bytes, charset) 解码。 */
static jstring utf8_to_java_string(JNIEnv *environment, const char *value) {
    size_t length = strlen(value);
    if (length > INT32_MAX) {
        return NULL;
    }
    jbyteArray encoded = (*environment)->NewByteArray(environment, (jsize) length);
    if (encoded == NULL) {
        return NULL;
    }
    (*environment)->SetByteArrayRegion(environment, encoded, 0, (jsize) length, (const jbyte *) value);
    if ((*environment)->ExceptionCheck(environment)) {
        (*environment)->DeleteLocalRef(environment, encoded);
        return NULL;
    }
    jclass string_class = (*environment)->FindClass(environment, "java/lang/String");
    jmethodID constructor = string_class == NULL ? NULL : (*environment)->GetMethodID(environment,
            string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = constructor == NULL ? NULL : (*environment)->NewStringUTF(environment, "UTF-8");
    jstring result = constructor != NULL && charset != NULL && !(*environment)->ExceptionCheck(environment)
            ? (*environment)->NewObject(environment, string_class, constructor, encoded, charset) : NULL;
    if (charset != NULL) (*environment)->DeleteLocalRef(environment, charset);
    if (string_class != NULL) (*environment)->DeleteLocalRef(environment, string_class);
    (*environment)->DeleteLocalRef(environment, encoded);
    return result;
}

DSTATUS disk_initialize(BYTE physical_drive) {
    return physical_drive == 0 && block_device != NULL ? 0 : STA_NOINIT;
}

DSTATUS disk_status(BYTE physical_drive) {
    return disk_initialize(physical_drive);
}

/* FatFs 按扇区请求完整数据；Java 驱动负责 SCSI READ(10) 分块和边界检查。 */
DRESULT disk_read(BYTE physical_drive, BYTE *buffer, LBA_t sector, UINT count) {
    JNIEnv *environment = current_environment();
    if (physical_drive != 0 || buffer == NULL || count == 0 || environment == NULL || block_device == NULL) {
        return RES_PARERR;
    }
    jint sector_size = (*environment)->CallIntMethod(environment, block_device, sector_size_method);
    if (clear_usb_exception(environment) || sector_size <= 0 || count > INT32_MAX / sector_size) {
        return RES_ERROR;
    }
    jsize byte_count = (jsize) count * sector_size;
    jbyteArray bytes = (*environment)->NewByteArray(environment, byte_count);
    if (bytes == NULL) {
        clear_usb_exception(environment);
        return RES_ERROR;
    }
    (*environment)->CallVoidMethod(environment, block_device, read_sectors_method, (jlong) sector, bytes);
    if (clear_usb_exception(environment)) {
        (*environment)->DeleteLocalRef(environment, bytes);
        return RES_ERROR;
    }
    (*environment)->GetByteArrayRegion(environment, bytes, 0, byte_count, (jbyte *) buffer);
    int failed = clear_usb_exception(environment);
    (*environment)->DeleteLocalRef(environment, bytes);
    return failed ? RES_ERROR : RES_OK;
}

/* 磁盘写入必须收到完整 SCSI 成功状态；任何失败由 FatFs 向上层返回。 */
DRESULT disk_write(BYTE physical_drive, const BYTE *buffer, LBA_t sector, UINT count) {
    JNIEnv *environment = current_environment();
    if (physical_drive != 0 || buffer == NULL || count == 0 || environment == NULL || block_device == NULL) {
        return RES_PARERR;
    }
    jint sector_size = (*environment)->CallIntMethod(environment, block_device, sector_size_method);
    if (clear_usb_exception(environment) || sector_size <= 0 || count > INT32_MAX / sector_size) {
        return RES_ERROR;
    }
    jsize byte_count = (jsize) count * sector_size;
    jbyteArray bytes = (*environment)->NewByteArray(environment, byte_count);
    if (bytes == NULL) {
        clear_usb_exception(environment);
        return RES_ERROR;
    }
    (*environment)->SetByteArrayRegion(environment, bytes, 0, byte_count, (const jbyte *) buffer);
    if (clear_usb_exception(environment)) {
        (*environment)->DeleteLocalRef(environment, bytes);
        return RES_ERROR;
    }
    (*environment)->CallVoidMethod(environment, block_device, write_sectors_method, (jlong) sector, bytes);
    int failed = clear_usb_exception(environment);
    (*environment)->DeleteLocalRef(environment, bytes);
    return failed ? RES_ERROR : RES_OK;
}

/* FatFs 需要磁盘几何信息；USB Mass Storage 不支持的 TRIM 不做虚假成功承诺。 */
DRESULT disk_ioctl(BYTE physical_drive, BYTE command, void *buffer) {
    JNIEnv *environment = current_environment();
    if (physical_drive != 0 || environment == NULL || block_device == NULL) {
        return RES_PARERR;
    }
    if (command == CTRL_SYNC) {
        (*environment)->CallVoidMethod(environment, block_device, sync_device_method);
        return clear_usb_exception(environment) ? RES_ERROR : RES_OK;
    }
    if (buffer == NULL) {
        return RES_PARERR;
    }
    if (command == GET_SECTOR_COUNT) {
        jlong count = (*environment)->CallLongMethod(environment, block_device, sector_count_method);
        if (clear_usb_exception(environment)) {
            return RES_ERROR;
        }
        *(LBA_t *) buffer = (LBA_t) count;
        return RES_OK;
    }
    if (command == GET_SECTOR_SIZE) {
        jint size = (*environment)->CallIntMethod(environment, block_device, sector_size_method);
        if (clear_usb_exception(environment)) {
            return RES_ERROR;
        }
        *(WORD *) buffer = (WORD) size;
        return RES_OK;
    }
    if (command == GET_BLOCK_SIZE) {
        *(DWORD *) buffer = 1;
        return RES_OK;
    }
    return RES_PARERR;
}

/* 本 JNI 桥只支持一个已授权 USB 卷；先卸载后关闭 USB 连接。 */
JNIEXPORT jint JNICALL
Java_io_github_jnlongliao_tv_finder_FatFsVolume_nativeMount(JNIEnv *environment, jclass type, jobject device) {
    if (mounted || block_device != NULL || device == NULL) {
        return FR_INVALID_OBJECT;
    }
    (*environment)->GetJavaVM(environment, &java_vm);
    block_device = (*environment)->NewGlobalRef(environment, device);
    if (block_device == NULL) {
        return FR_NOT_ENOUGH_CORE;
    }
    jclass device_class = (*environment)->GetObjectClass(environment, device);
    read_sectors_method = (*environment)->GetMethodID(environment, device_class, "readSectors", "(J[B)V");
    write_sectors_method = (*environment)->GetMethodID(environment, device_class, "writeSectors", "(J[B)V");
    sector_count_method = (*environment)->GetMethodID(environment, device_class, "getSectorCount", "()J");
    sector_size_method = (*environment)->GetMethodID(environment, device_class, "getSectorSize", "()I");
    sync_device_method = (*environment)->GetMethodID(environment, device_class, "syncDevice", "()V");
    (*environment)->DeleteLocalRef(environment, device_class);
    if (clear_usb_exception(environment) || read_sectors_method == NULL || write_sectors_method == NULL
            || sector_count_method == NULL || sector_size_method == NULL || sync_device_method == NULL) {
        (*environment)->DeleteGlobalRef(environment, block_device);
        block_device = NULL;
        return FR_INVALID_OBJECT;
    }
    FRESULT result = f_mount(&volume, "0:", 1);
    if (result == FR_OK) {
        mounted = 1;
    } else {
        f_mount(NULL, "0:", 0);
        (*environment)->DeleteGlobalRef(environment, block_device);
        block_device = NULL;
    }
    return result;
}

JNIEXPORT void JNICALL
Java_io_github_jnlongliao_tv_finder_FatFsVolume_nativeUnmount(JNIEnv *environment, jclass type) {
    if (block_device != NULL) {
        f_mount(NULL, "0:", 0);
        (*environment)->DeleteGlobalRef(environment, block_device);
        block_device = NULL;
    }
    mounted = 0;
}

/* 返回 D/F 前缀加文件名；调用方仅在独占卷工作线程读取，防止目录变化竞态。 */
JNIEXPORT jobjectArray JNICALL
Java_io_github_jnlongliao_tv_finder_FatFsVolume_nativeList(JNIEnv *environment, jclass type, jstring path) {
    if (!mounted || path == NULL) {
        return NULL;
    }
    char *directory_path = java_string_to_utf8(environment, path);
    if (directory_path == NULL) {
        return NULL;
    }
    DIR directory;
    FILINFO file_info;
    FRESULT result = f_opendir(&directory, directory_path);
    free(directory_path);
    if (result != FR_OK) {
        return NULL;
    }
    size_t capacity = 32;
    size_t length = 0;
    char **names = calloc(capacity, sizeof(char *));
    if (names == NULL) {
        f_closedir(&directory);
        return NULL;
    }
    while ((result = f_readdir(&directory, &file_info)) == FR_OK && file_info.fname[0] != '\0') {
        if (length == capacity) {
            capacity *= 2;
            char **larger = realloc(names, capacity * sizeof(char *));
            if (larger == NULL) {
                result = FR_NOT_ENOUGH_CORE;
                break;
            }
            names = larger;
        }
        size_t size = strlen(file_info.fname) + 2;
        names[length] = malloc(size);
        if (names[length] == NULL) {
            result = FR_NOT_ENOUGH_CORE;
            break;
        }
        names[length][0] = (file_info.fattrib & AM_DIR) ? 'D' : 'F';
        memcpy(names[length] + 1, file_info.fname, size - 1);
        length++;
    }
    f_closedir(&directory);
    jobjectArray output = NULL;
    if (result == FR_OK && length <= INT32_MAX) {
        jclass string_class = (*environment)->FindClass(environment, "java/lang/String");
        if (string_class != NULL) {
            output = (*environment)->NewObjectArray(environment, (jsize) length, string_class, NULL);
            for (size_t index = 0; output != NULL && index < length; index++) {
                jstring name = utf8_to_java_string(environment, names[index]);
                if (name == NULL) {
                    output = NULL;
                    break;
                }
                (*environment)->SetObjectArrayElement(environment, output, (jsize) index, name);
                (*environment)->DeleteLocalRef(environment, name);
            }
            (*environment)->DeleteLocalRef(environment, string_class);
        }
    }
    for (size_t index = 0; index < length; index++) {
        free(names[index]);
    }
    free(names);
    return output;
}

/* 无需先枚举目录；FatFs 负责拒绝不存在路径或非法目标。 */
JNIEXPORT jint JNICALL
Java_io_github_jnlongliao_tv_finder_FatFsVolume_nativeMakeDirectory(JNIEnv *environment, jclass type, jstring path) {
    char *value = java_string_to_utf8(environment, path);
    if (value == NULL) {
        return FR_NOT_ENOUGH_CORE;
    }
    FRESULT result = mounted ? f_mkdir(value) : FR_NOT_READY;
    free(value);
    return result;
}

JNIEXPORT jint JNICALL
Java_io_github_jnlongliao_tv_finder_FatFsVolume_nativeDelete(JNIEnv *environment, jclass type, jstring path) {
    char *value = java_string_to_utf8(environment, path);
    if (value == NULL) {
        return FR_NOT_ENOUGH_CORE;
    }
    FRESULT result = mounted ? f_unlink(value) : FR_NOT_READY;
    free(value);
    return result;
}

JNIEXPORT jint JNICALL
Java_io_github_jnlongliao_tv_finder_FatFsVolume_nativeRename(JNIEnv *environment, jclass type,
        jstring source, jstring destination) {
    char *source_path = java_string_to_utf8(environment, source);
    char *destination_path = java_string_to_utf8(environment, destination);
    FRESULT result = source_path != NULL && destination_path != NULL && mounted
            ? f_rename(source_path, destination_path) : FR_NOT_READY;
    if (source_path != NULL) {
        free(source_path);
    }
    if (destination_path != NULL) {
        free(destination_path);
    }
    return result;
}

/* 同一卷内文件复制采用固定 16 KiB 缓冲；失败时移除未完成的新目标。 */
JNIEXPORT jint JNICALL
Java_io_github_jnlongliao_tv_finder_FatFsVolume_nativeCopyFile(JNIEnv *environment, jclass type,
        jstring source, jstring destination) {
    char *source_path = java_string_to_utf8(environment, source);
    char *destination_path = java_string_to_utf8(environment, destination);
    if (source_path == NULL || destination_path == NULL || !mounted) {
        free(source_path);
        free(destination_path);
        return FR_NOT_READY;
    }
    FIL input;
    FIL output;
    FRESULT result = f_open(&input, source_path, FA_READ);
    if (result == FR_OK) {
        result = f_open(&output, destination_path, FA_WRITE | FA_CREATE_NEW);
        if (result == FR_OK) {
            BYTE bytes[16384];
            UINT bytes_read = 0;
            UINT bytes_written = 0;
            do {
                result = f_read(&input, bytes, sizeof(bytes), &bytes_read);
                if (result == FR_OK && bytes_read != 0) {
                    result = f_write(&output, bytes, bytes_read, &bytes_written);
                    if (result == FR_OK && bytes_written != bytes_read) {
                        result = FR_DISK_ERR;
                    }
                }
            } while (result == FR_OK && bytes_read != 0);
            FRESULT close_result = f_close(&output);
            if (result == FR_OK) result = close_result;
            if (result != FR_OK) f_unlink(destination_path);
        }
        FRESULT close_result = f_close(&input);
        if (result == FR_OK) result = close_result;
    }
    free(source_path);
    free(destination_path);
    return result;
}

/* 以负 FatFs 错误码区分失败与合法的零字节文件。 */
JNIEXPORT jlong JNICALL
Java_io_github_jnlongliao_tv_finder_FatFsVolume_nativeFileSize(JNIEnv *environment, jclass type, jstring path) {
    char *value = java_string_to_utf8(environment, path);
    if (value == NULL) return -FR_NOT_ENOUGH_CORE;
    FILINFO information;
    FRESULT result = mounted ? f_stat(value, &information) : FR_NOT_READY;
    free(value);
    return result == FR_OK ? (jlong) information.fsize : -(jlong) result;
}

/* 每次仅读取调用方给出的有界缓冲区；大文件由 Java 层逐块导出。 */
JNIEXPORT jint JNICALL
Java_io_github_jnlongliao_tv_finder_FatFsVolume_nativeReadChunk(JNIEnv *environment, jclass type,
        jstring path, jlong offset, jbyteArray destination) {
    char *value = java_string_to_utf8(environment, path);
    if (value == NULL || destination == NULL || offset < 0 || !mounted) {
        free(value);
        return -FR_INVALID_PARAMETER;
    }
    FIL file;
    FRESULT result = f_open(&file, value, FA_READ);
    jint bytes_read = 0;
    if (result == FR_OK) {
        result = f_lseek(&file, (FSIZE_t) offset);
        if (result == FR_OK) {
            jsize length = (*environment)->GetArrayLength(environment, destination);
            jbyte *bytes = (*environment)->GetByteArrayElements(environment, destination, NULL);
            if (bytes == NULL) {
                result = FR_NOT_ENOUGH_CORE;
            } else {
                UINT count = 0;
                result = f_read(&file, bytes, (UINT) length, &count);
                bytes_read = (jint) count;
                (*environment)->ReleaseByteArrayElements(environment, destination, bytes, 0);
            }
        }
        FRESULT close_result = f_close(&file);
        if (result == FR_OK) result = close_result;
    }
    free(value);
    return result == FR_OK ? bytes_read : -(jint) result;
}

/* create=true 仅用于新文件首块；后续块要求现有文件，防止意外截断。 */
JNIEXPORT jint JNICALL
Java_io_github_jnlongliao_tv_finder_FatFsVolume_nativeWriteChunk(JNIEnv *environment, jclass type,
        jstring path, jlong offset, jbyteArray source, jint length, jboolean create) {
    char *value = java_string_to_utf8(environment, path);
    if (value == NULL || source == NULL || offset < 0 || length < 0 || !mounted
            || length > (*environment)->GetArrayLength(environment, source)) {
        free(value);
        return -FR_INVALID_PARAMETER;
    }
    FIL file;
    FRESULT result = f_open(&file, value, FA_WRITE | (create ? FA_CREATE_NEW : FA_OPEN_EXISTING));
    jint written = 0;
    if (result == FR_OK) {
        if (!create) result = f_lseek(&file, (FSIZE_t) offset);
        if (result == FR_OK) {
            jbyte *bytes = (*environment)->GetByteArrayElements(environment, source, NULL);
            if (bytes == NULL) {
                result = FR_NOT_ENOUGH_CORE;
            } else {
                UINT count = 0;
                result = f_write(&file, bytes, (UINT) length, &count);
                written = (jint) count;
                (*environment)->ReleaseByteArrayElements(environment, source, bytes, JNI_ABORT);
                if (result == FR_OK && written != length) result = FR_DISK_ERR;
            }
        }
        FRESULT close_result = f_close(&file);
        if (result == FR_OK) result = close_result;
    }
    free(value);
    return result == FR_OK ? written : -(jint) result;
}
