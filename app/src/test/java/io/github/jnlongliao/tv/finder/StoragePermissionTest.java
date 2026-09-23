package io.github.jnlongliao.tv.finder;

import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.os.Process;
import android.os.Looper;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowEnvironment;
import org.robolectric.shadows.StorageVolumeBuilder;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 在 Android 11 模拟框架中验证首次启动的权限交互，不代表厂商固件验收。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class StoragePermissionTest {
    /** 每个用例从未授权状态开始，避免模拟 AppOps 默认放行掩盖启动缺陷。 */
    @Before
    public void denyStorageAccessBeforeTest() {
        // Android 11 的权限查询先定位主存储；模拟器必须提供目录和卷，不能用空列表代替设备环境。
        ShadowEnvironment.addExternalDir(Environment.getExternalStorageDirectory().getAbsolutePath());
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(StorageManager.class)).addStorageVolume(
                new StorageVolumeBuilder("primary", Environment.getExternalStorageDirectory(), "Internal",
                        Process.myUserHandle(), Environment.MEDIA_MOUNTED).setIsPrimary(true).build());
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(AppOpsManager.class)).setMode(
                "android:manage_external_storage", Process.myUid(),
                RuntimeEnvironment.getApplication().getPackageName(), AppOpsManager.MODE_ERRORED);
    }

    /** 新安装且未授权时必须主动显示引导，避免只有手动入口而让用户无从开始。 */
    @Test
    public void firstLaunchShowsStoragePermissionGuidance() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("首次启动应显示文件访问授权引导", dialog);
            assertTrue(dialog.isShowing());
        }
    }

    /** 取消后重新启动不重复打扰，但不将引导记录当作授权成功。 */
    @Test
    public void cancelledStorageGuidanceDoesNotRepeatOnRestart() {
        AlertDialog firstDialog;
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            firstDialog = ShadowAlertDialog.getLatestAlertDialog();
            firstDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        }
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertEquals(firstDialog, ShadowAlertDialog.getLatestAlertDialog());
            assertFalse(firstDialog.isShowing());
            assertNull(shadowOf(controller.get()).getNextStartedActivity());
        }
    }

    /** 已获得所有文件访问权限时不再展示首次引导。 */
    @Test
    public void grantedStorageAccessSkipsGuidance() {
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(AppOpsManager.class)).setMode(
                "android:manage_external_storage", Process.myUid(),
                RuntimeEnvironment.getApplication().getPackageName(), AppOpsManager.MODE_ALLOWED);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    /** 默认仍显示存储首页，但侧栏可以显式进入系统根并生成可点击的根面包屑。 */
    @Test
    public void systemRootNavigationOpensRootBreadcrumb() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            ArrayList<View> rootEntries = new ArrayList<>();
            controller.get().getWindow().getDecorView().findViewsWithText(rootEntries,
                    controller.get().getString(R.string.system_root), View.FIND_VIEWS_WITH_TEXT);
            assertEquals(1, rootEntries.size());
            assertTrue(rootEntries.get(0) instanceof Button);
            rootEntries.get(0).performClick();

            ArrayList<View> rootBreadcrumbs = new ArrayList<>();
            controller.get().getWindow().getDecorView().findViewsWithText(rootBreadcrumbs, "/", View.FIND_VIEWS_WITH_TEXT);
            assertTrue(rootBreadcrumbs.stream().anyMatch(view -> view instanceof Button));
        }
    }

    /** 终端必须提供独立页面和明确退出按钮，退出后返回默认存储首页。 */
    @Test
    public void terminalExitReturnsToStorageHome() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            ArrayList<View> terminalEntries = new ArrayList<>();
            controller.get().getWindow().getDecorView().findViewsWithText(terminalEntries,
                    controller.get().getString(R.string.terminal), View.FIND_VIEWS_WITH_TEXT);
            assertTrue(terminalEntries.stream().anyMatch(view -> view instanceof Button));
            terminalEntries.stream().filter(view -> view instanceof Button).findFirst().orElseThrow().performClick();

            ArrayList<View> exitButtons = new ArrayList<>();
            controller.get().getWindow().getDecorView().findViewsWithText(exitButtons,
                    controller.get().getString(R.string.terminal_exit), View.FIND_VIEWS_WITH_TEXT);
            assertEquals(1, exitButtons.size());
            exitButtons.get(0).performClick();

            ArrayList<View> storageTitles = new ArrayList<>();
            controller.get().getWindow().getDecorView().findViewsWithText(storageTitles,
                    controller.get().getString(R.string.storage_home), View.FIND_VIEWS_WITH_TEXT);
            assertTrue(storageTitles.size() >= 2);
        }
    }

    /** 应用专属入口存在时应携带当前包名，并停止回退。 */
    @Test
    public void storagePermissionOpensAppSpecificSettings() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            confirmLatestStoragePermissionDialog();
            Intent intent = shadowOf(controller.get()).getNextStartedActivity();
            assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, intent.getAction());
            assertEquals("package:" + controller.get().getPackageName(), intent.getDataString());
            assertNull(shadowOf(controller.get()).getNextStartedActivity());
        }
    }

    /** 专属授权页不存在时应继续尝试通用授权页。 */
    @Test
    public void missingAppPermissionPageFallsBackToGeneralPage() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        registerStorageSettingsActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            confirmLatestStoragePermissionDialog();
            assertEquals(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                    shadowOf(controller.get()).getNextStartedActivity().getAction());
        }
    }

    /** 模拟电视缺少两个特殊授权页时，提示应提供真正可点击的应用设置入口。 */
    @Test
    public void missingPermissionPagesOfferAppDetails() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        registerStorageSettingsActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + RuntimeEnvironment.getApplication().getPackageName())));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            confirmLatestStoragePermissionDialog();
            AlertDialog fallbackDialog = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(controller.get().getString(R.string.open_app_settings),
                    fallbackDialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            confirmLatestStoragePermissionDialog();
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    shadowOf(controller.get()).getNextStartedActivity().getAction());
        }
    }

    /** 应用详情与应用列表均缺失时仍可回退到系统设置。 */
    @Test
    public void missingApplicationSettingsFallBackToSystemSettings() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        registerStorageSettingsActivity(new Intent(Settings.ACTION_SETTINGS));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            confirmLatestStoragePermissionDialog();
            confirmLatestStoragePermissionDialog();
            assertEquals(Settings.ACTION_SETTINGS, shadowOf(controller.get()).getNextStartedActivity().getAction());
        }
    }

    /** 应用详情缺失但应用列表可用时，应停止回退，避免无谓打开更宽泛的系统设置。 */
    @Test
    public void missingAppDetailsFallsBackToApplicationList() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        registerStorageSettingsActivity(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            confirmLatestStoragePermissionDialog();
            confirmLatestStoragePermissionDialog();
            assertEquals(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
                    shadowOf(controller.get()).getNextStartedActivity().getAction());
            assertNull(shadowOf(controller.get()).getNextStartedActivity());
        }
    }

    /** 所有系统入口均缺失时提示人工诊断，既不崩溃也不误报授权成功。 */
    @Test
    public void missingAllSettingsShowsDiagnosticGuidance() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            confirmLatestStoragePermissionDialog();
            confirmLatestStoragePermissionDialog();
            assertEquals(controller.get().getString(R.string.permission_settings_missing_hint),
                    shadowOf(ShadowAlertDialog.getLatestAlertDialog()).getMessage().toString());
            assertNull(shadowOf(controller.get()).getNextStartedActivity());
            assertEquals(5, ShadowLog.getLogsForTag("TvFinder").size());
            for (ShadowLog.LogItem logItem : ShadowLog.getLogsForTag("TvFinder")) {
                assertNotNull(logItem.throwable);
                assertTrue(logItem.msg.contains("action=android.settings."));
                assertTrue(logItem.msg.contains("sdk=30"));
                assertTrue(logItem.msg.contains("reason=" + logItem.throwable.getMessage()));
            }
        }
    }

    /** 确认当前授权对话框并执行主线程消息，确保检查发生在 Android 按钮回调之后。 */
    private void confirmLatestStoragePermissionDialog() {
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * 为指定标准动作注册唯一模拟系统页面，其余动作保持不可解析。
     * @param intent 本测试允许打开的设置入口
     */
    private void registerStorageSettingsActivity(Intent intent) {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.packageName = "test.settings";
        resolveInfo.activityInfo.name = "test.settings.SettingsActivity";
        resolveInfo.activityInfo.exported = true;
        shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).addResolveInfoForIntent(intent, resolveInfo);
    }
}
