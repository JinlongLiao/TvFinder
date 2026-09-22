# 电视文件管家

[English](README.en.md) | [版本说明](docs/版本说明.md) | [第三方声明](docs/第三方声明.md) | [Apache-2.0](LICENSE)

![TV Finder 标志](交付/应用标志.svg)

面向海信 E5Q 的本地文件管理 Android 应用。采用适合遥控器的侧栏导航、四列文件网格、大字体和高对比焦点，布局参考电视文件浏览器的使用习惯，未复制 ES 的商标或图片资产。

- 作者：**liaojinlong**。
- 项目：[JinlongLiao/TvFinder](https://github.com/JinlongLiao/TvFinder)。SSH 地址：`git@github.com:JinlongLiao/TvFinder.git`。
- 包名：`io.github.jnlongliao.tv.finder`，按作者指定拼写，与 GitHub 用户名大小写无绑定要求。
- 当前版本：**0.1.0**，个人试用预览版，尚未完成海信 E5Q 真机验收。
- 开源协议：**Apache-2.0**，详见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。第三方依赖保留各自的归属与许可。

## 界面与语言

![存储首页](交付/首页.png)

![文件浏览](交付/文件浏览.png)

中文、英文默认跟随系统，也可在“关于 → 语言 / Language”选择简体中文或 English。语言切换保留当前目录与待粘贴项；文件操作过程中不可切换。未提供翻译的系统语言回退英文。文件名与用户数据不会翻译或改名。

图标区分安装包（APK / EXE / RPM / DEB 等）、Word、Excel、PPT、PDF、文本、音频、视频、图片、压缩包、源代码、字幕、电子书、磁盘镜像等；扩展名大小写不敏感，未知类型显示普通文件。**识别 EXE / RPM 不表示 Android 可以运行 Windows / Linux 程序。**

## 外观主题

侧栏“外观设置 → 主题”提供 **浅色、深色、跟随系统**，首次安装默认浅色，选择会持久保存。主题覆盖首页、文件网格、按钮、焦点、输入框和弹窗；切换后保留目录及待粘贴项。跟随系统模式读取电视实际的明暗配置，系统不提供夜间标志时显示浅色；固定模式不随电视设置变化。系统主题在文件任务中变化时，任务结束后再重建界面；操作失败时先显示错误，关闭提示后再应用。

## 安装与使用

1. 安装 `交付/电视文件管家-0.1.0.apk`。这是用于个人试用的 debug 签名安装包。
2. 在电视应用列表打开“电视文件管家”，选择“开启文件访问权限”，按系统页面授权。
3. 选择电视内部存储或 USB 磁盘。USB 未显示时选择“刷新磁盘”。
4. 方向键移动焦点，确定键打开文件夹或文件，返回键回到上一级。
5. 菜单键、长按确定键或顶部“操作”按钮打开复制、移动、重命名、删除和详细信息菜单。
6. 复制或移动后进入目标目录，选择“粘贴”。操作前显示源和目标，避免选错磁盘。

打开视频、图片等文件会调用电视已安装的兼容应用。本版不内置播放器，不支持 NAS/SMB；不申请网络权限。

## 文件与权限边界

- 最低 Android 8.0（API 26）；Android 11 及以上使用“管理所有文件”授权，旧版申请存储读写权限。
- “内部存储”指用户共享存储，不等同于整台电视的所有系统文件。其他应用的私有目录以及受系统保护的目录不能保证访问。
- 磁盘容量显示文件系统提供的总量、可用量和二者差值，不等于商品标注的闪存容量，也没有把差值全部解释为可清理文件。
- USB 发现优先使用系统存储卷 API，并兼容可读的 `/storage` 挂载目录。厂商隐藏挂载、缺少权限入口、NTFS/exFAT 只读驱动等情况需要实机适配；本版不会绕过系统限制。
- 同名目标直接报错，不自动覆盖或合并目录。重命名可以保留中文名称；拒绝空名称、路径穿越及常见文件系统不允许的字符。
- 复制失败或取消会尝试删除本次新建的目标残留；清理失败会显示路径。突然断电、杀进程或拔盘不能保证清理完成，需要检查目标。
- 移动按“复制全部数据 → 比对内容 → 删除源”执行，会额外读取源和目标。空间不足时失败，不自动腾出空间。源删除阶段不可取消；若此阶段失败，目标保留，源可能部分删除。
- 文件操作不是跨应用事务。执行中请勿通过其他应用修改同一源文件或目标目录，也不要拔盘。
- 删除为永久删除，无回收站；确认框默认焦点在“取消”。中途取消不会恢复已经删除的文件。
- 文件操作日志记录动作、路径和完整异常，不记录文件正文；日志通过 Android logcat 获取。

## 海信系统信息核对

核对日期：2026-09-22。

海信官方 65E5Q 商品页标明 4 GB 内存、64 GB 存储，但页面可检索文字没有明确底层 Android 版本。“U8 系统”名称与 E5Q 实际固件的对应关系也未由官方资料确认，因此应用不按这个名称硬编码权限或挂载路径。

安装后可在侧栏“设备信息”查看实际厂商、型号、Android 版本、API 级别、CPU 架构、固件标识和授权状态，无需依赖电视设置页。

## 能否成为默认文件管理器

本版是独立文件管理应用，未实现 `ACTION_GET_CONTENT` 文件选择处理器或 `DocumentsProvider`，没有声称可以成为全局默认文件管理器。

Android 标准存储访问框架由系统文件选择界面汇集文件提供者。增加文件提供接口不等于替换系统界面。海信是否允许选择默认文件管理器、接管插入 U 盘后的入口，需在 E5Q 的当前固件实测。

“管理所有文件”权限控制可访问的文件范围，不授予替换系统文档管理器的权限。安装应用、改包名、授权存储，都不会自动改变电视默认文件管理器。

## 构建

使用 JDK 17、Android SDK 34、Gradle Wrapper 8.9、Android Gradle Plugin 8.5.2。设置 `ANDROID_HOME` 或在本机 `local.properties` 配置 `sdk.dir`，不要把本机路径提交到其他环境。

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

原始 APK 位置：`app/build/outputs/apk/debug/app-debug.apk`。

源码采用 Android 字符串资源管理界面和应用错误提示；文件操作按枚举分派，避免语言切换影响业务行为。版本号由 `app/build.gradle` 提供，关于页读取生成的 `BuildConfig`。内置版本说明和许可在构建时从 `docs/`、`LICENSE`、`NOTICE` 同步，避免文档与应用显示不一致。

当前环境没有可复用的指定 `com.wlzn.common.util` 工具源码，工程也没有这项依赖；文件操作采用 Android 提供的 `java.nio.file`、Java 标准流与 `Objects`，没有另引重叠的第三方工具库，也没有新增自定义业务异常。

## 实机验收建议

先在测试文件夹放入几个可丢弃的文件，分别验证内部存储与 USB 双向复制、移动、重命名和删除。确认遥控器菜单/长按行为、文件打开方式以及拒绝授权后重新授权流程。大文件、空间不足、USB 拔插和电视待机需要另做实机检查。

如果磁盘未识别或只读，请记录“设备信息”、USB 文件系统类型及错误提示。模拟器成功不代表海信驱动行为相同。

## 官方参考

- [海信官方 65E5Q 商品资料](https://mall.hisense.com/items/6096)
- [Android TV 应用入口](https://developer.android.com/training/tv/get-started/create)
- [遥控器导航](https://developer.android.com/training/tv/get-started/navigation)
- [管理所有文件权限及限制](https://developer.android.com/training/data-storage/manage-all-files)
- [存储卷 API](https://developer.android.com/reference/android/os/storage/StorageVolume)
- [系统存储访问框架与文件选择器](https://developer.android.com/guide/topics/providers/document-provider)
- [AGP 8.5 构建兼容要求](https://developer.android.com/build/releases/agp-8-5-0-release-notes)
