package io.github.jnlongliao.tv.finder;

import android.content.Context;
import android.content.res.Configuration;

/**
 * 管理浅色、深色和跟随系统的应用主题，不改变电视全局设置。
 * 偏好与页面配置仅在主线程访问；主题变化由 Activity 在文件任务结束后重建页面。
 *
 * @see <a href="https://developer.android.com/develop/ui/views/theming/darktheme">Android dark theme</a>
 */
public final class AppAppearance {
    /**
     * 强制浅色；首次安装和无效偏好都使用该模式。
     */
    public static final String LIGHT = "light";
    /**
     * 强制深色，不受系统明暗模式影响。
     */
    public static final String DARK = "dark";
    /**
     * 采用系统 uiMode；系统未声明夜间模式时使用浅色。
     */
    public static final String SYSTEM = "system";
    /**
     * 与语言设置共用的应用私有偏好文件。
     */
    private static final String PREFERENCES = "appearance";
    /**
     * 主题模式键，仅接受 light、dark、system。
     */
    private static final String THEME_KEY = "theme_mode";

    /**
     * 禁止实例化；所有方法不持有 Activity 或其他可变全局状态。
     */
    private AppAppearance() {
    }

    /**
     * 在界面创建前覆盖夜间标志，保留语言、字体大小和电视设备类型等配置。
     *
     * @param context 已应用语言偏好的基础上下文
     * @return 固定主题的资源上下文；跟随系统时返回原上下文
     */
    public static Context applyThemeContext(Context context) {
        String mode = readThemeMode(context);
        if (SYSTEM.equals(mode)) {
            return context;
        }
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
            | resolveNightMode(mode, configuration.uiMode);
        return context.createConfigurationContext(configuration);
    }

    /**
     * 读取已保存主题，未知值回退浅色；只读取应用偏好。
     *
     * @param context 应用上下文
     * @return light、dark 或 system
     */
    public static String readThemeMode(Context context) {
        String mode = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(THEME_KEY, LIGHT);
        return DARK.equals(mode) || SYSTEM.equals(mode) ? mode : LIGHT;
    }

    /**
     * 保存有效主题；内存立即更新，磁盘由 SharedPreferences 异步持久化。
     *
     * @param context 应用上下文
     * @param mode    light、dark 或 system
     * @throws IllegalArgumentException 不支持的主题值
     */
    public static void saveThemeMode(Context context, String mode) {
        if (!LIGHT.equals(mode) && !DARK.equals(mode) && !SYSTEM.equals(mode)) {
            throw new IllegalArgumentException("Unsupported theme mode: " + mode);
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(THEME_KEY, mode).apply();
    }

    /**
     * 解析有效明暗标志，仅比较夜间位，不将电视设备类型位作为主题判断。
     *
     * @param mode         应用主题模式；未知值按默认浅色处理
     * @param systemUiMode 系统完整 uiMode 配置
     * @return UI_MODE_NIGHT_YES 或 UI_MODE_NIGHT_NO
     */
    public static int resolveNightMode(String mode, int systemUiMode) {
        boolean dark = DARK.equals(mode) || (SYSTEM.equals(mode)
            && (systemUiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        return dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
    }
}
