package com.vanipy.jin;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import android.os.SystemClock;

import org.junit.Before;
import org.junit.Test;

/**
 * On-device proof that the documented menu entries really open the
 * diagnostics drawer, driven through UiAutomator's real touch-injection
 * path (the path an accessibility service uses).
 *
 * Entries under test, matching the in-app hint "swipe from the left
 * edge, or long-press anywhere":
 *  1. long-press on empty scroll content (regression: the ScrollView
 *     consumed every touch below the header until contentScroll got
 *     its own listener). A dwell-swipe at an empty point below the
 *     controls is used because the content fits the viewport, so the
 *     ScrollView is not "scrollable" for By.scrollable(true), and a
 *     longClick at its center would land on a button and be consumed.
 *  2. a slow drag from the left screen edge, verified under 3-button
 *     navigation. Under gesture navigation the left edge zone belongs
 *     to the system back gesture, so the test temporarily switches the
 *     emulator to 3-button nav; if that switch fails the leg is reported
 *     UNPROVEN (skipped), never as passed.
 */
public class MenuEntryTest {

    private static final String PKG = "com.vanipy.jin";
    private static final long TIMEOUT = 10_000;

    private UiDevice device;

    @Before
    public void launch() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(PKG);
        assertNotNull(intent);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        ctx.startActivity(intent);
        assertTrue("app did not come to the foreground",
                device.wait(Until.hasObject(By.pkg(PKG).depth(0)), TIMEOUT));
    }

    @Test
    public void longPressOnScrollContentOpensMenu() {
        int w = device.getDisplayWidth();
        int h = device.getDisplayHeight();
        // Dwell on empty content below the controls: same start/end,
        // 200 steps ~= 1s hold, past the 500ms long-press timeout.
        device.swipe(w / 2, (int) (h * 0.8), w / 2, (int) (h * 0.8), 200);
        UiObject2 logs = device.wait(Until.findObject(By.text("View logs")), TIMEOUT);
        assertNotNull("long-press on the scroll area did not open the menu", logs);
        closeMenu();
    }

    @Test
    public void edgeDragOpensMenu() throws Exception {
        // Under gesture navigation Android reserves the left edge for the
        // system back gesture. Prove the app's edge entry where the
        // platform permits it: switch to 3-button navigation for this
        // test and switch back afterwards. If the switch does not take,
        // the leg stays explicitly UNPROVEN - it is not reported as passed.
        String navMode = readNavMode();
        boolean forced = false;
        if (!"0".equals(navMode)) {
            device.executeShellCommand("settings put secure navigation_mode 0");
            SystemClock.sleep(2000);
            navMode = readNavMode();
            forced = "0".equals(navMode);
        }
        assumeTrue("edge entry UNPROVEN: could not switch emulator out of gesture nav "
                + "(navigation_mode=" + navMode + "); needs a 3-button-nav device to verify",
                "0".equals(navMode));
        try {
            int w = device.getDisplayWidth();
            int h = device.getDisplayHeight();
            device.swipe(1, h / 2, w / 3, h / 2, 60);
            UiObject2 logs = device.wait(Until.findObject(By.text("View logs")), TIMEOUT);
            assertNotNull("left-edge drag did not open the menu", logs);
            closeMenu();
        } finally {
            if (forced) {
                device.executeShellCommand("settings put secure navigation_mode 2");
            }
        }
    }

    private String readNavMode() throws Exception {
        return device.executeShellCommand("settings get secure navigation_mode").trim();
    }

    private void closeMenu() {
        device.pressBack();
        assertTrue("Back did not close the menu",
                device.wait(Until.gone(By.text("View logs")), TIMEOUT));
    }
}
