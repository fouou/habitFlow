package com.fouu.habitflow;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import android.content.Context;
import android.content.Intent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 正规 UIAutomator 插桩测试：按控件（resource-id）点击，不依赖截图算坐标。
 *
 * 验证点（针对 v3 改动）：
 *  - 勾选习惯 checkbox 应直接切换状态，不弹出任何对话框（无"今日打卡"/"备注与心情"弹窗）。
 *  - "备注"chip（id/chip_note）应已彻底移除。
 *
 * 运行前提：先在 Android Studio 用最新代码 build + install（debug），确保装的是 v3。
 *   运行方式（AS）：右键本类/方法 → Run；
 *   或命令行：adb shell am instrument -w com.fouu.habitflow.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4.class)
public class HabitCheckInUiTest {

    private static final String PKG = "com.fouu.habitflow";
    private static final long TIMEOUT = 5000;

    private UiDevice device;

    @Before
    public void setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        // 回到桌面，避免停留在其它页面
        device.pressHome();
        // 通过 launch intent 启动 app（会经过 AuthActivity → MainActivity → 习惯列表）
        Context ctx = InstrumentationRegistry.getInstrumentation().getContext();
        Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(PKG);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
        // 等待习惯列表的 checkbox 出现（已登录才会到习惯页）
        device.wait(Until.hasObject(By.res(PKG, "cb_today")), TIMEOUT);
    }

    /** 核心：按控件（resource-id）点击 checkbox，验证不弹任何对话框。 */
    @Test
    public void checkInByControl_NoDialog() {
        UiObject2 cb = device.findObject(By.res(PKG, "cb_today"));
        assertNotNull("未找到习惯 checkbox (id/cb_today)", cb);

        boolean before = cb.isChecked();
        // 直接点这个控件本身（框架把事件派发给该 view，不受坐标重叠影响）
        cb.click();
        device.waitForIdle();

        // v3 之后：勾选不应弹出任何对话框
        UiObject2 dialogNote = device.findObject(By.text("备注与心情"));
        assertNull("勾选后不应弹出备注对话框", dialogNote);
        UiObject2 dialogCheckIn = device.findObject(By.text("保存打卡"));
        assertNull("勾选后不应弹出保存打卡对话框", dialogCheckIn);

        // 状态应翻转（已勾→取消，或 未勾→勾上）
        UiObject2 cbAfter = device.findObject(By.res(PKG, "cb_today"));
        assertNotNull(cbAfter);
        assertTrue("勾选后 checkbox 状态应改变", cbAfter.isChecked() != before);
    }

    /** 验证"备注"chip 已被移除（v3）。 */
    @Test
    public void noteChipRemoved() {
        UiObject2 note = device.findObject(By.res(PKG, "chip_note"));
        assertNull("备注 chip (id/chip_note) 应已移除", note);
    }
}
