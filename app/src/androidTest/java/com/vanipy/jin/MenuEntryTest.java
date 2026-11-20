package com.vanipy.jin;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
 * diagnostics drawer. The shell smoke cannot synthesize these gestures
 * reliably through `input swipe` on the ARM-translating emulator (three
 * failed attempts), so this drives them through UiAutomator's real
 * touch-injection path - the same path an accessibility service uses.
 *
 * Entries under test, matching the in-app hint "swipe from the left
 * edge, or long-press anywhere":
 *  1. long-press on the scroll content (regression: the ScrollView
 *     consumed every touch below the header until contentScroll got
 *     its own listener);
 *  2. a slow drag from the left screen edge.
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
        UiObject2 scroll = device.wait(Until.findObject(By.scrollable(true)), TIMEOUT);
        assertNotNull("no scrollable content on the main screen", scroll);
        scroll.longClick();
        UiObject2 logs = device.wait(Until.findObject(By.text("View logs")), TIMEOUT);
        assertNotNull("long-press on the scroll area did not open the menu", logs);
        closeMenu();
    }

    @Test
    public void edgeDragOpensMenu() {
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
