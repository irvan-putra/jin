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
 *  2. a slow drag from the left screen edge. Skipped under gesture
 *     navigation: Android reserves the left edge zone for the system
 *     back gesture and DrawerLayout excludes that inset, so edge drag
 *     opening is impossible there by platform design (real phones on
 *     gesture nav behave the same - those users must long-press).
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
        String navMode = device.executeShellCommand("settings get secure navigation_mode").trim();
        assumeTrue("gesture navigation owns the left edge (navigation_mode=" + navMode
                + "); edge-drag entry is unavailable by platform design", !"2".equals(navMode));
        int w = device.getDisplayWidth();
        int h = device.getDisplayHeight();
        device.swipe(1, h / 2, w / 3, h / 2, 60);
        UiObject2 logs = device.wait(Until.findObject(By.text("View logs")), TIMEOUT);
        assertNotNull("left-edge drag did not open the menu", logs);
        closeMenu();
    }

    private void closeMenu() {
        device.pressBack();
        assertTrue("Back did not close the menu",
                device.wait(Until.gone(By.text("View logs")), TIMEOUT));
    }
}
