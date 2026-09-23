package io.github.jnlongliao.tv.finder;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

/**
 * 适配旧版电视的应用内语言选择；空标签跟随系统，其他值仅允许 zh-CN 或 en。
 * 只保存应用偏好，不改变电视全局语言；生效需要重建页面，不能在文件任务中调用。
 *
 * @see <a href="https://developer.android.com/guide/topics/resources/localization">Android localization</a>
 */
public final class AppLanguage {
    /**
     * 应用私有偏好文件，不包含文件数据、凭据或设备唯一标识。
     */
    private static final String PREFERENCES = "appearance";
    /**
     * BCP 47 语言标签键；默认空字符串表示系统语言。
     */
    private static final String LANGUAGE_KEY = "language_tag";

    /**
     * 禁止实例化；配置读取和更新仅在主线程执行。
     */
    private AppLanguage() {
    }

    /**
     * 为新页面创建所选语言的资源上下文，系统模式直接保留原上下文。
     *
     * @param context 原始 Activity 基础上下文
     * @return 当前语言的上下文，不修改全局 Resources
     */
    public static Context localizeAppContext(Context context) {
        String tag = readLanguageTag(context);
        if (tag.isEmpty()) {
            return context;
        }
        // 仅覆盖语言，避免复制 uiMode 后阻止“跟随系统”接收明暗变化。
        Configuration configuration = new Configuration();
        configuration.setLocales(new LocaleList(Locale.forLanguageTag(tag)));
        return context.createConfigurationContext(configuration);
    }

    /**
     * 读取并规范化应用语言；无效旧值回退为系统语言。
     *
     * @param context 应用上下文
     * @return 空、zh-CN 或 en
     */
    public static String readLanguageTag(Context context) {
        String tag = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(LANGUAGE_KEY, "");
        return "zh-CN".equals(tag) || "en".equals(tag) ? tag : "";
    }

    /**
     * 异步持久化用户选择；当前进程可立即读取，进程突然终止时以磁盘最近结果为准。
     *
     * @param context 应用上下文
     * @param tag     空表示跟随系统，其他值必须为 zh-CN 或 en
     * @throws IllegalArgumentException 不支持的语言标签
     */
    public static void saveLanguageTag(Context context, String tag) {
        if (!"".equals(tag) && !"zh-CN".equals(tag) && !"en".equals(tag)) {
            throw new IllegalArgumentException("Unsupported language tag: " + tag);
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(LANGUAGE_KEY, tag).apply();
    }
}
