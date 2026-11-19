package com.vanipy.jin;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import org.json.JSONObject;

public class MainActivity extends Activity {
    // Bot states, computed from the runtime's own files (no parallel bookkeeping):
    //   SETUP   - no bot account yet
    //   READY   - account created, service stopped
    //   RUNNING - live bot service running
    private static final int SETUP = 0, READY = 1, RUNNING = 2;

    private DrawerLayout drawer;
    private TextView statusTitle, statusText, statusMeta, versionText;
    private Button primary, publishBtn, stopBtn;
    private final Handler handler = new Handler();
    private String lastFingerprint = null;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        drawer = findViewById(R.id.drawerLayout);
        statusTitle = findViewById(R.id.statusTitle);
        statusText = findViewById(R.id.statusText);
        statusMeta = findViewById(R.id.statusMeta);
        primary = findViewById(R.id.primaryButton);
        publishBtn = findViewById(R.id.publishButton);
        stopBtn = findViewById(R.id.stopButton);
        versionText = findViewById(R.id.versionText);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        primary.setOnClickListener(v -> onPrimary());
        publishBtn.setOnClickListener(v -> startPublishFlow());
        stopBtn.setOnClickListener(v -> {
            // Clear the persisted mode first so START_STICKY / the boot
            // receiver treat this as a real stop, not a restart request.
            getSharedPreferences("bot", MODE_PRIVATE).edit().putString("last_mode", "").apply();
            stopService(new Intent(this, BotService.class));
        });
        findViewById(R.id.menuProbe).setOnClickListener(v -> {
            drawer.closeDrawer(GravityCompat.START);
            startMode("validate");
            Toast.makeText(this, "Offline probe running - see View logs for its output", Toast.LENGTH_LONG).show();
        });
        findViewById(R.id.menuLogs).setOnClickListener(v -> showLogs());
        findViewById(R.id.menuExport).setOnClickListener(v -> {
            drawer.closeDrawer(GravityCompat.START);
            startExportFlow();
        });

        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            long vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            versionText.setText("jin " + pi.versionName + " (" + vc + ")");
        } catch (Exception ignored) {}

        // Menu without visible chrome, same as intodo: left-edge swipe (DrawerLayout)
        // plus long-press anywhere / a TalkBack action as the accessible fallback.
        installMenuAccess(findViewById(R.id.contentRoot));

        // Reattach to a publish that is still in flight across recreation:
        // the request or its crash-resume checkpoint must still exist, and
        // the persisted token/sha let the result poll correlate without
        // re-picking or re-queueing the file.
        android.content.SharedPreferences prefs = getSharedPreferences("bot", MODE_PRIVATE);
        String savedToken = prefs.getString("pending_publish_token", null);
        if (savedToken != null
                && (new File(getFilesDir(), "PUBLISH_REQ").exists()
                    || new File(getFilesDir(), "PUBLISH_PROG").exists())) {
            pendingPublishToken = savedToken;
            pendingPublishSha = prefs.getString("pending_publish_sha", null);
            Toast.makeText(this, "A publish is still running - reattached to its result", Toast.LENGTH_SHORT).show();
            pollPublishResult(System.currentTimeMillis());
        } else if (savedToken != null) {
            // Nothing in flight: drop the stale reference. A completed
            // run's DONE stays as the runtime's dedup input, untouched.
            prefs.edit().remove("pending_publish_sha").remove("pending_publish_token").apply();
        }

        Runnable tick = new Runnable() { public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }};
        handler.post(tick);
    }

    // ---- state + rendering ----------------------------------------------

    private int botState() {
        if (new File(getFilesDir(), "RUNNING").exists()) return RUNNING;
        return botAddr().isEmpty() ? SETUP : READY;
    }

    private String botAddr() {
        File stf = new File(getFilesDir(), "bot_state.json");
        if (!stf.exists()) return "";
        try {
            String a = new JSONObject(read(stf)).optString("bot_addr", "");
            return a.equals("null") ? "" : a;
        } catch (Exception e) { return ""; }
    }

    private int servedChatCount() {
        File stf = new File(getFilesDir(), "bot_state.json");
        if (!stf.exists()) return 0;
        try {
            JSONObject chats = new JSONObject(read(stf)).optJSONObject("chats");
            return chats == null ? 0 : chats.length();
        } catch (Exception e) { return 0; }
    }

    private void onPrimary() {
        switch (botState()) {
            case SETUP:
                startMode("provision");
                Toast.makeText(this, "Creating the bot account...", Toast.LENGTH_SHORT).show();
                break;
            case READY:
                startLiveWithBatteryPrompt();
                break;
            default:
                showInvite();
        }
    }

    private void render() {
        switch (botState()) {
            case SETUP:
                statusTitle.setText("Set up your bot");
                statusText.setText("jin runs a DeltaChat bot on this phone. The bot gets its own account on a public chatmail relay; your personal DeltaChat account is never used.\n\nSetup: 1) create the bot account, 2) start the bot, 3) show its invitation and scan it from DeltaChat, then send the bot a message.");
                statusMeta.setText("Each chat the bot joins gets its own separate list: a group shares one, a direct chat stays personal.");
                primary.setText("Create bot account");
                primary.setVisibility(View.VISIBLE);
                publishBtn.setVisibility(View.GONE);
                stopBtn.setVisibility(View.GONE);
                break;
            case READY:
                statusTitle.setText("Bot account ready");
                statusText.setText("Bot address: " + botAddr());
                statusMeta.setText("Start the bot so it can answer messages and deliver your apps. It keeps working while this app is closed (permanent notification).");
                primary.setText("Start bot");
                primary.setVisibility(View.VISIBLE);
                publishBtn.setVisibility(View.GONE);
                stopBtn.setVisibility(View.GONE);
                break;
            default:
                int n = servedChatCount();
                statusTitle.setText("Bot is running");
                statusText.setText("Bot address: " + botAddr());
                statusMeta.setText(n == 0
                        ? "No chats yet - show the invitation and scan it from DeltaChat, then send the bot any message."
                        : "Serving " + n + (n == 1 ? " chat." : " chats."));
                primary.setText("Show invitation QR");
                primary.setVisibility(View.VISIBLE);
                publishBtn.setVisibility(View.VISIBLE);
                stopBtn.setVisibility(View.VISIBLE);
        }
    }

    private void refresh() {
        File f = new File(getFilesDir(), "bot_state.json");
        String fp = (f.exists() ? read(f) : "") + "|" + botState();
        if (fp.equals(lastFingerprint)) return;
        lastFingerprint = fp;
        render();
    }

    private void startLiveWithBatteryPrompt() {
        // Battery cost disclosure + exemption prompt before first live start.
        if (Build.VERSION.SDK_INT >= 23) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Background battery use")
                    .setMessage("The bot keeps a permanent notification and a low-power connection to the chatmail relay so it can deliver updates at any time. This uses some battery. Android will next ask to exclude this app from battery optimization; stopping the bot releases everything.")
                    .setPositiveButton("Continue", (d, w) -> {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) {}
                        startMode("live");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
                return;
            }
        }
        startMode("live");
    }

    private void showInvite() {
        if (!new File(getFilesDir(), "RUNNING").exists()) {
            Toast.makeText(this, "Start the bot first", Toast.LENGTH_LONG).show();
            return;
        }
        new File(getFilesDir(), "INVITE_DONE").delete();
        try { new FileWriter(new File(getFilesDir(), "INVITE_REQ")).close(); }
        catch (Exception e) { return; }
        Toast.makeText(this, "Preparing the invitation...", Toast.LENGTH_SHORT).show();
        final long tap = System.currentTimeMillis();
        final Runnable[] poll = new Runnable[1];
        poll[0] = new Runnable() { public void run() {
            File done = new File(getFilesDir(), "INVITE_DONE");
            File svg = new File(getFilesDir(), "invite.svg");
            if (done.exists() && svg.exists() && svg.lastModified() >= tap) {
                WebView w = new WebView(MainActivity.this);
                w.getSettings().setAllowFileAccess(true);
                w.loadUrl("file://" + svg.getAbsolutePath());
                new android.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("Bot invitation - scan it from your DeltaChat")
                    .setView(w)
                    // Same-device pairing: the QR content IS the deep link
                    // (openpgp4fpr scheme, handled by Delta Chat's exported
                    // RoutingActivity VIEW filter -> checkQr -> SecureJoin).
                    .setPositiveButton("Open in DeltaChat", (d, which) -> {
                        String qr = read(new File(getFilesDir(), "invite.txt")).trim();
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(qr)));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "Delta Chat app not found - scan the QR instead", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
            } else if (System.currentTimeMillis() - tap < 10000) {
                handler.postDelayed(poll[0], 500);
            } else {
                Toast.makeText(MainActivity.this, "QR not ready - is the bot service running?", Toast.LENGTH_LONG).show();
            }
        }};
        handler.postDelayed(poll[0], 500);
    }

    private void showLogs() {
        TextView tv = new TextView(this);
        tv.setText(readLog());
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11f);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new android.app.AlertDialog.Builder(this)
            .setTitle("Logs")
            .setView(sv)
            .setPositiveButton("Close", null)
            .show();
    }

    private void installMenuAccess(View v) {
        v.setOnLongClickListener(view -> {
            drawer.openDrawer(GravityCompat.START);
            return true;
        });
        ViewCompat.addAccessibilityAction(v, "Open menu", (view, args) -> {
            drawer.openDrawer(GravityCompat.START);
            return true;
        });
    }

    @Override public void onBackPressed() {
        // an open menu consumes Back first; only a shut menu leaves the activity
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
            return;
        }
        super.onBackPressed();
    }

    private void startMode(String mode) {
        Intent i = new Intent(this, BotService.class);
        i.putExtra("mode", mode);
        startForegroundService(i);
    }

    // ---- export flow (jin -> itdapk migration) --------------------------
    private static final int REQ_SAVE_BUNDLE = 42;
    private String pendingBundlePath = null;

    private void startExportFlow() {
        File stf = new File(getFilesDir(), "bot_state.json");
        String js = stf.exists() ? read(stf) : "";
        boolean prov = false;
        try { prov = new JSONObject(js).optBoolean("provisioned", false); } catch (Exception ignored) {}
        if (!prov) {
            Toast.makeText(this, "Create the bot account first", Toast.LENGTH_LONG).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("Export bot for itdapk")
            .setMessage("This pauses the live bot and writes ONE protected bundle holding the bot's Delta Chat account AND its owner/chat state, both encrypted with a one-time transfer code shown next.\n\nKeep the bundle file and the code private: together they ARE the bot. You pick where the file goes and copy the code yourself; nothing is sent anywhere.\n\nThe live bot stays paused until the import finishes in itdapk (or start the bot again here to resume it).")
            .setPositiveButton("Export", (d, w) -> runExportAfterStop())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void runExportAfterStop() {
        getSharedPreferences("bot", MODE_PRIVATE).edit().putString("last_mode", "").apply();
        stopService(new Intent(this, BotService.class));
        Toast.makeText(this, "Pausing the live bot...", Toast.LENGTH_SHORT).show();
        final long t0 = System.currentTimeMillis();
        final Runnable[] poll = new Runnable[1];
        poll[0] = new Runnable() { public void run() {
            if (!new File(getFilesDir(), "RUNNING").exists()) {
                new File(getFilesDir(), "EXPORT_RESULT").delete();
                startMode("export");
                pollExportResult(System.currentTimeMillis());
            } else if (System.currentTimeMillis() - t0 < 15000) {
                handler.postDelayed(poll[0], 500);
            } else {
                Toast.makeText(MainActivity.this, "Live bot did not stop in time - stop the bot, then retry", Toast.LENGTH_LONG).show();
            }
        }};
        handler.postDelayed(poll[0], 500);
    }

    private void pollExportResult(final long t0) {
        final Runnable[] poll = new Runnable[1];
        poll[0] = new Runnable() { public void run() {
            File res = new File(getFilesDir(), "EXPORT_RESULT");
            if (res.exists()) {
                // Export done: leave no persisted mode so a START_STICKY
                // restart does not silently re-run the export.
                getSharedPreferences("bot", MODE_PRIVATE).edit().putString("last_mode", "").apply();
                try {
                    JSONObject r = new JSONObject(read(res));
                    String err = r.optString("error", "");
                    if (!err.isEmpty()) {
                        res.delete();
                        new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("Export failed")
                            .setMessage(err + "\n\nThe live bot will restart.")
                            .setPositiveButton("OK", (d, w) -> startMode("live"))
                            .show();
                    } else {
                        pendingBundlePath = r.getString("bundle");
                        showTransferCode(r.getString("code"));
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Export result unreadable: " + e, Toast.LENGTH_LONG).show();
                }
                return;
            }
            if (System.currentTimeMillis() - t0 < 120000) {
                handler.postDelayed(poll[0], 1000);
            } else {
                Toast.makeText(MainActivity.this, "Export timed out - resuming the live bot", Toast.LENGTH_LONG).show();
                startMode("live");
            }
        }};
        handler.postDelayed(poll[0], 1000);
    }

    private void showTransferCode(final String code) {
        TextView tv = new TextView(this);
        tv.setText(code);
        tv.setTextIsSelectable(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        new android.app.AlertDialog.Builder(this)
            .setTitle("1/2: one-time transfer code")
            .setMessage("Enter this code in itdapk's import screen. It protects the bot's whole identity - never send it through a chat.")
            .setView(tv)
            .setNeutralButton("Copy code", (d, w) -> { copyCode(code); showTransferCode(code); })
            .setPositiveButton("Next", (d, w) -> showSaveStep(code))
            .setNegativeButton("Abort + resume bot", (d, w) -> abortExport())
            .show();
    }

    private void showSaveStep(final String code) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("2/2: save the bundle file")
            .setMessage("Choose where the .dcmig bundle is written (Downloads is fine). You will pick this file in itdapk's import screen.")
            .setPositiveButton("Choose location", (d, w) -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                i.setType("application/octet-stream");
                i.putExtra(Intent.EXTRA_TITLE, "jin-bot-export.dcmig");
                startActivityForResult(i, REQ_SAVE_BUNDLE);
            })
            .setNegativeButton("Abort + resume bot", (d, w) -> abortExport())
            .show();
    }

    private void copyCode(String code) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("itdapk migration code", code);
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle b = new android.os.PersistableBundle();
            b.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(b);
        }
        cm.setPrimaryClip(clip);
        Toast.makeText(this, "Code copied (marked sensitive)", Toast.LENGTH_SHORT).show();
    }

    private void abortExport() {
        new File(getFilesDir(), "EXPORT_RESULT").delete();
        startMode("live");
    }

    // ---- publish flow (app-side only: the runtime refuses chat-originated publishing) ----
    private static final int REQ_PICK_XDC = 43;
    private String pendingPublishSha = null;
    private String pendingPublishToken = null;

    private void startPublishFlow() {
        File stf = new File(getFilesDir(), "bot_state.json");
        String js = stf.exists() ? read(stf) : "";
        boolean prov = false;
        try { prov = new JSONObject(js).optBoolean("provisioned", false); } catch (Exception ignored) {}
        if (!prov) {
            Toast.makeText(this, "Create the bot account first", Toast.LENGTH_LONG).show();
            return;
        }
        if (!new File(getFilesDir(), "RUNNING").exists()) {
            Toast.makeText(this, "Start the bot first", Toast.LENGTH_LONG).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("Publish new app version")
            .setMessage("Pick the new .xdc file. The bot sends it to EVERY chat it serves (each group and each direct chat with the bot).")
            .setPositiveButton("Pick .xdc", (d, w) -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_PICK_XDC);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void pollPublishResult(final long tap) {
        final Runnable[] poll = new Runnable[1];
        poll[0] = new Runnable() { public void run() {
            File done = new File(getFilesDir(), "PUBLISH_DONE");
            if (done.exists()) {
                try {
                    JSONObject r = new JSONObject(read(done));
                    // Correlate the result with THIS request: the request
                    // token is echoed back by the runtime, so a DONE from an
                    // earlier publish never shows as this one's result.
                    String doneReq = r.optString("request", "");
                    if (pendingPublishToken == null || !pendingPublishToken.equals(doneReq)) {
                        if (System.currentTimeMillis() - tap < 60000) handler.postDelayed(poll[0], 1000);
                        return;
                    }
                    String doneSha = r.optString("sha256", "");
                    if (pendingPublishSha != null && !doneSha.isEmpty()
                            && !pendingPublishSha.equals(doneSha)) {
                        if (System.currentTimeMillis() - tap < 60000) handler.postDelayed(poll[0], 1000);
                        return;
                    }
                    JSONObject pub = r.optJSONObject("published");
                    JSONObject skip = r.optJSONObject("skipped");
                    JSONObject errs = r.optJSONObject("errors");
                    JSONObject part = r.optJSONObject("partial");
                    int nPub = pub == null ? 0 : pub.length();
                    int nSkip = skip == null ? 0 : skip.length();
                    int nAdopt = 0, nHist = 0;
                    if (pub != null) {
                        java.util.Iterator<String> ait = pub.keys();
                        while (ait.hasNext()) {
                            JSONObject ao = pub.optJSONObject(ait.next());
                            if (ao != null && ao.optBoolean("adopted", false)) nAdopt++;
                            if (ao != null && ao.optBoolean("ingest_pending", false)) nHist++;
                        }
                    }
                    int nPart = part == null ? 0 : part.length();
                    StringBuilder msg = new StringBuilder();
                    if (nPart > 0) {
                        // Delivered vs fully-ready counts stay DISTINCT: a
                        // partial run DID deliver the bubble.
                        msg.append("Delivered to ").append(nPub + nPart)
                           .append(" chat(s); fully ready in ").append(nPub).append(".");
                    } else {
                        msg.append("Published to ").append(nPub).append(" chat(s).");
                    }
                    if (nSkip > 0) msg.append(" Already had this exact file: ").append(nSkip).append(".");
                    if (nAdopt > 0) msg.append(" Recovered the already-delivered app bubble without sending a duplicate: ").append(nAdopt).append(".");
                    if (nHist > 0) msg.append(" Earlier history recovery is pending in ").append(nHist).append(" chats.");
                    if (part != null && part.length() > 0) {
                        // Third state: the app WAS delivered (bubble sent +
                        // registered) but the history replay failed. Retrying
                        // the SAME file completes the replay into the existing
                        // instance - no duplicate bubble.
                        msg.append("\n\nDelivered but history replay failed in ")
                           .append(part.length())
                           .append(" chat(s); retry the SAME file to finish:");
                        java.util.Iterator<String> pit = part.keys();
                        while (pit.hasNext()) {
                            String pk = pit.next();
                            JSONObject po = part.optJSONObject(pk);
                            msg.append("\nchat ").append(pk).append(": ")
                               .append(po == null ? "" : po.optString("error"));
                        }
                    }
                    if (errs != null && errs.length() > 0) {
                        msg.append("\n\nErrors:");
                        java.util.Iterator<String> it = errs.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            msg.append("\nchat ").append(k).append(": ").append(errs.optString(k));
                        }
                    }
                    getSharedPreferences("bot", MODE_PRIVATE).edit()
                        .remove("pending_publish_sha")
                        .remove("pending_publish_token")
                        .apply();
                    new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("Publish result")
                        .setMessage(msg.toString())
                        .setPositiveButton("OK", null)
                        .show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Publish result unreadable: " + e, Toast.LENGTH_LONG).show();
                }
                return;
            }
            if (System.currentTimeMillis() - tap < 60000) {
                handler.postDelayed(poll[0], 1000);
            } else {
                Toast.makeText(MainActivity.this, "Publish timed out - is the bot service running?", Toast.LENGTH_LONG).show();
            }
        }};
        handler.postDelayed(poll[0], 1000);
    }

    // Package-private for the local IPC contract tests. No network/core calls.
    void queuePublish(InputStream input) throws Exception {
        File req = new File(getFilesDir(), "PUBLISH_REQ");
        File prog = new File(getFilesDir(), "PUBLISH_PROG");
        if (req.exists() || prog.exists()) {
            input.close();
            throw new java.io.IOException("A publish is still pending; wait for it or restart the bot to resume");
        }
        File stage = new File(getFilesDir(), "publish-" + java.util.UUID.randomUUID() + ".tmp");
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = input; FileOutputStream out = new FileOutputStream(stage)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) { md.update(buf, 0, n); out.write(buf, 0, n); }
            out.getFD().sync();
        } catch (Exception e) { stage.delete(); throw e; }
        StringBuilder hex = new StringBuilder();
        for (byte bb : md.digest()) hex.append(String.format("%02x", bb));
        pendingPublishSha = hex.toString();
        pendingPublishToken = java.util.UUID.randomUUID().toString();
        File immutable = new File(getFilesDir(), "publish-" + pendingPublishSha + ".xdc");
        if (immutable.exists()) { stage.delete(); }
        else if (!stage.renameTo(immutable)) throw new java.io.IOException("Could not retain publish file");
        // Never overwrite an older current_app's bytes, nor delete DONE's dedup input.
        File tmp = new File(getFilesDir(), "PUBLISH_REQ.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write((immutable.getName() + "\n" + pendingPublishToken).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(req)) throw new java.io.IOException("Could not queue publish request");
        // Persist the pending reference privately: if the activity is
        // recreated while the bot is still working, the new instance
        // reattaches to THIS operation instead of losing correlation.
        getSharedPreferences("bot", MODE_PRIVATE).edit()
            .putString("pending_publish_sha", pendingPublishSha)
            .putString("pending_publish_token", pendingPublishToken)
            .apply();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_XDC) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            try {
                queuePublish(getContentResolver().openInputStream(data.getData()));
                final long tap = System.currentTimeMillis();
                Toast.makeText(this, "Publishing to all served chats...", Toast.LENGTH_SHORT).show();
                pollPublishResult(tap);
            } catch (Exception e) {
                Toast.makeText(this, "Could not read the picked file: " + e, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode != REQ_SAVE_BUNDLE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || pendingBundlePath == null) {
            Toast.makeText(this, "Bundle not saved - export result kept, you can save again", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            InputStream in = new FileInputStream(pendingBundlePath);
            FileOutputStream out = (FileOutputStream) getContentResolver().openOutputStream(data.getData());
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close(); in.close();
            new File(getFilesDir(), "EXPORT_RESULT").delete();
            new android.app.AlertDialog.Builder(this)
                .setTitle("Bundle saved")
                .setMessage("In itdapk: choose 'Import bot backup', pick this file, enter the transfer code.\n\nThe live bot here stays PAUSED. Only uninstall this app after itdapk shows the bot running with the same address. Starting the bot again here resumes it if you abort the migration.")
                .setPositiveButton("OK", null)
                .show();
        } catch (Exception e) {
            Toast.makeText(this, "Save failed: " + e, Toast.LENGTH_LONG).show();
        }
    }

    private static String read(File f) {
        try {
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(buf); in.close();
            return n > 0 ? new String(buf, 0, n) : "";
        } catch (Exception e) { return ""; }
    }

    private String readLog() {
        StringBuilder sb = new StringBuilder();
        for (String name : new String[]{"bot.log", "validation.log"}) {
            try {
                File f = new File(getFilesDir(), name);
                if (f.exists()) {
                    String s = read(f);
                    int cut = Math.max(0, s.length() - 4000);
                    sb.append("== ").append(name).append(" ==\n").append(s.substring(cut)).append("\n\n");
                }
            } catch (Exception ignored) {}
        }
        return sb.length() == 0 ? "(nothing yet)" : sb.toString();
    }
}
