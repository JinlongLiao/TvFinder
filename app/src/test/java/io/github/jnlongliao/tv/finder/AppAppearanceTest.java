package io.github.jnlongliao.tv.finder;

import android.content.res.Configuration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 验证系统设备类型位不会污染明暗判断，以及固定主题和缺失配置的回退行为。 */
public final class AppAppearanceTest {
    /** 固定浅色即使在系统夜间模式下也不变暗。 */
    @Test
    public void lightThemeOverridesSystemNight() {
        assertEquals(Configuration.UI_MODE_NIGHT_NO, AppAppearance.resolveNightMode(AppAppearance.LIGHT,
                Configuration.UI_MODE_TYPE_TELEVISION | Configuration.UI_MODE_NIGHT_YES));
    }

    /** 固定深色不受系统白天模式影响。 */
    @Test
    public void darkThemeOverridesSystemDay() {
        assertEquals(Configuration.UI_MODE_NIGHT_YES, AppAppearance.resolveNightMode(AppAppearance.DARK,
                Configuration.UI_MODE_TYPE_TELEVISION | Configuration.UI_MODE_NIGHT_NO));
    }

    /** 跟随系统只读取夜间位，兼容电视类型和不提供明暗标志的旧系统。 */
    @Test
    public void systemThemeUsesNightFlagAndDefaultsToLight() {
        assertEquals(Configuration.UI_MODE_NIGHT_YES, AppAppearance.resolveNightMode(AppAppearance.SYSTEM,
                Configuration.UI_MODE_TYPE_TELEVISION | Configuration.UI_MODE_NIGHT_YES));
        assertEquals(Configuration.UI_MODE_NIGHT_NO, AppAppearance.resolveNightMode(AppAppearance.SYSTEM,
                Configuration.UI_MODE_TYPE_TELEVISION | Configuration.UI_MODE_NIGHT_NO));
        assertEquals(Configuration.UI_MODE_NIGHT_NO, AppAppearance.resolveNightMode(AppAppearance.SYSTEM,
                Configuration.UI_MODE_TYPE_TELEVISION));
    }

    /** 无效或缺失偏好采用用户指定的默认浅色，不跟随系统夜间模式。 */
    @Test
    public void unknownThemeDefaultsToLight() {
        assertEquals(Configuration.UI_MODE_NIGHT_NO,
                AppAppearance.resolveNightMode("old-value", Configuration.UI_MODE_NIGHT_YES));
        assertEquals(Configuration.UI_MODE_NIGHT_NO,
                AppAppearance.resolveNightMode(null, Configuration.UI_MODE_NIGHT_YES));
    }
}
