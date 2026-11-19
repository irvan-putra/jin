package com.vanipy.jin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

/**
 * Renders MainActivity through Robolectric native graphics (real Skia on the
 * JVM) in each bot state, and exercises the click/state wiring behind the
 * buttons and the left menu. sdk=32 avoids the POST_NOTIFICATIONS prompt;
 * onCreate never starts Python, so the tests touch no core/account/network.
 * All bot state below is synthetic (fixture address, no real account data).
 */
// xxhdpi = 480dpi: the 1080px-wide canvas is then 360dp, a real phone width
// (Galaxy A24 renders ~396ppi, bucketed xxhdpi), not a tablet-width render.
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 32, qualifiers = "xxhdpi")
public class MainActivityRenderTest {

    private static final String FIXTURE_ADDR = "bot-fixture@chatmail.example";

    private Activity freshActivity() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    private Activity activityWithState(String stateJson, boolean running) throws Exception {
        File dir = RuntimeEnvironment.application.getFilesDir();
        if (stateJson != null) {
            try (FileWriter w = new FileWriter(new File(dir, "bot_state.json"))) {
                w.write(stateJson);
            }
        }
        if (running) {
            new File(dir, "RUNNING").createNewFile();
        }
        return freshActivity();
    }

    private static void renderToPng(Activity a, String path) throws Exception {
        View root = a.getWindow().getDecorView();
        int w = 1080, h = 2340;
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        File out = new File(path);
        try (FileOutputStream f = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        assertTrue("render written", out.length() > 10000);
    }

    private static String text(Activity a, int id) {
        return ((TextView) a.findViewById(id)).getText().toString();
    }

    @Test
    public void renderSetupState() throws Exception {
        Activity a = freshActivity();
        assertEquals("Set up your bot", text(a, R.id.statusTitle));
        assertEquals("Create bot account", text(a, R.id.primaryButton));
        assertEquals(View.GONE, a.findViewById(R.id.publishButton).getVisibility());
        assertEquals(View.GONE, a.findViewById(R.id.stopButton).getVisibility());
        renderToPng(a, "/tmp/jin-0-setup.png");
        // Accessibility check: 1.3x font scale must still lay out without
        // pushing controls off the screen (the content scrolls instead).
        RuntimeEnvironment.setFontScale(1.3f);
        Activity big = freshActivity();
        renderToPng(big, "/tmp/jin-0-setup-fontscale.png");
        RuntimeEnvironment.setFontScale(1.0f);
    }

    @Test
    public void renderReadyState() throws Exception {
        Activity a = activityWithState(
                "{\"provisioned\":true,\"bot_addr\":\"" + FIXTURE_ADDR + "\",\"chats\":{}}", false);
        assertEquals("Bot account ready", text(a, R.id.statusTitle));
        assertEquals("Start bot", text(a, R.id.primaryButton));
        assertTrue(text(a, R.id.statusText).contains(FIXTURE_ADDR));
        assertEquals(View.GONE, a.findViewById(R.id.publishButton).getVisibility());
        renderToPng(a, "/tmp/jin-1-ready.png");
    }

    @Test
    public void renderRunningState() throws Exception {
        Activity a = activityWithState(
                "{\"provisioned\":true,\"bot_addr\":\"" + FIXTURE_ADDR + "\",\"chats\":{\"11\":{},\"22\":{}}}", true);
        assertEquals("Bot is running", text(a, R.id.statusTitle));
        assertEquals("Show invitation QR", text(a, R.id.primaryButton));
        assertEquals("Serving 2 chats.", text(a, R.id.statusMeta));
        assertEquals(View.VISIBLE, a.findViewById(R.id.publishButton).getVisibility());
        assertEquals(View.VISIBLE, a.findViewById(R.id.stopButton).getVisibility());
        renderToPng(a, "/tmp/jin-2-running.png");
    }

    @Test
    public void setupPrimaryStartsProvisioning() {
        Activity a = freshActivity();
        a.findViewById(R.id.primaryButton).performClick();
        Intent svc = shadowOf((Application) RuntimeEnvironment.application).getNextStartedService();
        assertNotNull("primary tap starts the service", svc);
        assertEquals("provision", svc.getStringExtra("mode"));
    }

    @Test
    public void readyPrimaryStartsLiveAfterBatteryGate() throws Exception {
        Activity a = activityWithState(
                "{\"provisioned\":true,\"bot_addr\":\"" + FIXTURE_ADDR + "\"}", false);
        android.os.PowerManager pm = (android.os.PowerManager) a.getSystemService(Activity.POWER_SERVICE);
        // Gate not yet cleared: the disclosure dialog must appear instead of a start.
        shadowOf(pm).setIgnoringBatteryOptimizations(a.getPackageName(), false);
        a.findViewById(R.id.primaryButton).performClick();
        assertNotNull("battery disclosure shown before first start",
                ShadowAlertDialog.getLatestAlertDialog());
        shadowOf(pm).setIgnoringBatteryOptimizations(a.getPackageName(), true);
        a.findViewById(R.id.primaryButton).performClick();
        Intent svc = shadowOf((Application) RuntimeEnvironment.application).getNextStartedService();
        assertNotNull(svc);
        assertEquals("live", svc.getStringExtra("mode"));
    }

    @Test
    public void stopClearsPersistedMode() throws Exception {
        Activity a = activityWithState(
                "{\"provisioned\":true,\"bot_addr\":\"" + FIXTURE_ADDR + "\"}", true);
        a.getSharedPreferences("bot", Activity.MODE_PRIVATE).edit().putString("last_mode", "live").apply();
        a.findViewById(R.id.stopButton).performClick();
        assertEquals("", a.getSharedPreferences("bot", Activity.MODE_PRIVATE).getString("last_mode", "unset"));
    }

    @Test
    public void leftMenuHoldsDiagnostics() throws Exception {
        Activity a = freshActivity();
        DrawerLayout drawer = a.findViewById(R.id.drawerLayout);
        assertFalse(drawer.isDrawerVisible(GravityCompat.START));
        assertEquals("View logs", text(a, R.id.menuLogs));
        assertEquals("Run offline validation probe", text(a, R.id.menuProbe));
        assertEquals("Export bot for itdapk", text(a, R.id.menuExport));
        // Long-press wiring: DrawerLayout accepted the open request (the
        // animated path's visibility flips need real layout frames a JVM
        // cannot run; the open-request flags are the synchronous signal).
        a.findViewById(R.id.contentRoot).performLongClick();
        DrawerLayout.LayoutParams lp =
                (DrawerLayout.LayoutParams) a.findViewById(R.id.drawerPane).getLayoutParams();
        java.lang.reflect.Field onScreen = DrawerLayout.LayoutParams.class.getDeclaredField("onScreen");
        onScreen.setAccessible(true);
        java.lang.reflect.Field openState = DrawerLayout.LayoutParams.class.getDeclaredField("openState");
        openState.setAccessible(true);
        assertTrue("long-press opened the menu",
                drawer.isDrawerVisible(GravityCompat.START)
                        || onScreen.getFloat(lp) > 0 || (openState.getInt(lp) & 3) != 0);
        // Render the open menu; the non-animated open settles synchronously.
        drawer.openDrawer(GravityCompat.START, false);
        assertTrue(drawer.isDrawerVisible(GravityCompat.START));
        // Robolectric never settles DrawerLayout's drag, so render the pane
        // standalone at its real 300dp width for pixel inspection.
        View pane = a.findViewById(R.id.drawerPane);
        int pw = (int) (300 * a.getResources().getDisplayMetrics().density);
        int ph = (int) (2200 * a.getResources().getDisplayMetrics().density);
        pane.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY));
        pane.layout(0, 0, pw, ph);
        Bitmap pb = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
        pane.draw(new Canvas(pb));
        File pout = new File("/tmp/jin-3-menu.png");
        try (FileOutputStream f = new FileOutputStream(pout)) {
            pb.compress(Bitmap.CompressFormat.PNG, 100, f);
        }
        assertTrue("menu render written", pout.length() > 5000);
        // Back consumes an open menu first; only a shut menu leaves the activity.
        a.onBackPressed();
        assertFalse("open menu consumed Back", a.isFinishing());
        drawer.closeDrawer(GravityCompat.START, false);
        a.onBackPressed();
        assertTrue("Back leaves a shut menu to finish", a.isFinishing());
    }

    @Test
    public void logsOpenFromMenu() throws Exception {
        Activity a = freshActivity();
        a.findViewById(R.id.menuLogs).performClick();
        assertNotNull("logs dialog shown", ShadowAlertDialog.getLatestAlertDialog());
    }
}
