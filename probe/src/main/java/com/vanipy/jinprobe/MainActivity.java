package com.vanipy.jinprobe;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Self-driving probe for jin's cross-app transport gate (see
 * tools/transport-gate.sh). Runs one scenario per launch (--es scenario),
 * calls the provider through the REAL binder + content:// pipe path like any
 * client app, and records PROBE lines to files/probe-<scenario>.log.
 * Synthetic content only: fixture bot address, marker text, zero-byte files.
 */
public class MainActivity extends Activity {
    private static final String AUTH = "content://com.vanipy.jin.transport";
    private final AtomicInteger failures = new AtomicInteger();
    private FileWriter log;
    private String scenario;
    private String handle;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        scenario = getIntent().getStringExtra("scenario");
        if (scenario == null) scenario = "ungranted";
        handle = getIntent().getStringExtra("handle");
        new Thread(() -> {
            try {
                log = new FileWriter(new File(getFilesDir(), "probe-" + scenario + ".log"), false);
                switch (scenario) {
                    case "ungranted": ungranted(); break;
                    case "granted": grantedBotDown(); break;
                    case "live": live(); break;
                    default: step("args", false, "unknown scenario " + scenario);
                }
            } catch (Throwable t) {
                step("crash", false, String.valueOf(t));
            }
            try {
                log.write("PROBE DONE " + scenario + " failures=" + failures.get() + "\n");
                log.close();
            } catch (Exception ignored) {}
            finish();
        }, "probe").start();
    }

    private Bundle call(String method, String arg, Bundle extras) {
        return getContentResolver().call(Uri.parse(AUTH), method, arg, extras);
    }

    private void step(String name, boolean pass, String detail) {
        if (!pass) failures.incrementAndGet();
        try {
            log.write("PROBE " + scenario + " " + name + " " + (pass ? "PASS " : "FAIL ") + detail + "\n");
            log.flush();
        } catch (Exception ignored) {}
    }

    private static Bundle meta(String chat, String text, boolean withFile) throws Exception {
        JSONObject m = new JSONObject();
        m.put("chat", chat).put("text", text).put("ts", System.currentTimeMillis());
        if (withFile) m.put("filename", "probe.bin").put("mime", "application/octet-stream");
        Bundle b = new Bundle();
        b.putString("meta", m.toString());
        return b;
    }

    /** Streams size zero-ish bytes through a REAL content:// pipe. */
    private void streamFile(String token, long size) throws Exception {
        Bundle st = call("stage", token, null);
        Uri uri = Uri.parse(st.getString("uri"));
        ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
        FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor());
        byte[] chunk = new byte[262144];
        long left = size;
        try {
            while (left > 0) {
                int n = (int) Math.min(chunk.length, left);
                out.write(chunk, 0, n);
                left -= n;
            }
        } catch (java.io.IOException expectedOnOverflowAbort) {
            // the provider aborts the pipe at the byte cap: EPIPE here is the
            // designed overflow signal, the commit below must say FILE_TOO_LARGE
        }
        try { out.close(); } catch (Exception ignored) {}
        try { pfd.close(); } catch (Exception ignored) {}
    }

    private void ungranted() throws Exception {
        Bundle caps = call("capabilities", null, null);
        step("capabilities", caps.getBoolean("ok") && !caps.getBoolean("granted")
                && caps.getInt("contract_version") == 1 && caps.getLong("max_bytes") > 0,
                "granted=" + caps.getBoolean("granted"));
        Bundle c = call("commit", "probeU01_text", meta("x", "hi", false));
        step("commit-refused", !c.getBoolean("ok") && "NOT_AUTHORIZED".equals(c.getString("error")),
                c.getString("error"));
        Bundle p = call("poll", "0", null);
        step("poll-refused", !p.getBoolean("ok") && "NOT_AUTHORIZED".equals(p.getString("error")),
                p.getString("error"));
        Bundle s = call("stage", "probeU02_stage", null);
        step("stage-refused", !s.getBoolean("ok") && "NOT_AUTHORIZED".equals(s.getString("error")),
                s.getString("error"));
    }

    private void grantedBotDown() throws Exception {
        Bundle caps = call("capabilities", null, null);
        step("capabilities", caps.getBoolean("ok") && caps.getBoolean("granted")
                && "/probe".equals(caps.getString("prefix")), caps.getString("prefix"));
        Bundle c = call("commit", "probeG01_text", meta(handle, "hi", false));
        step("commit-bot-down", !c.getBoolean("ok") && "BOT_NOT_RUNNING".equals(c.getString("error")),
                c.getString("error"));
        Bundle p = call("poll", "0", null);
        step("poll-bot-down", !p.getBoolean("ok") && "BOT_NOT_RUNNING".equals(p.getString("error")),
                p.getString("error"));
        Bundle u = call("bogusMethod", null, null);
        step("unknown-method", !u.getBoolean("ok") && "UNKNOWN_METHOD".equals(u.getString("error")),
                u.getString("error"));
    }

    private void live() throws Exception {
        Bundle lc = call("listChats", null, null);
        boolean listed = false;
        try {
            org.json.JSONArray items = new org.json.JSONArray(lc.getString("items"));
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                if (handle.equals(it.getString("handle")))
                    listed = it.getBoolean("send") && it.getBoolean("recv");
            }
        } catch (Exception ignored) {}
        step("listChats", lc.getBoolean("ok") && listed, lc.getString("items"));

        // The fixture bot has NO account: sends must fail VISIBLY at the rpc
        // boundary, proving the full pipe+bridge round trip without a relay.
        Bundle c1 = call("commit", "probeL01_text", meta(handle, "probe marker 1", false));
        step("text-commit-visible-failure", !c1.getBoolean("ok") && "FAILED".equals(c1.getString("error")),
                c1.getString("error"));
        Bundle c2 = call("commit", "probeL01_text", meta(handle, "probe marker 1", false));
        step("failed-retry-reattempts", !c2.getBoolean("ok") && "FAILED".equals(c2.getString("error")),
                c2.getString("error"));

        // Real ParcelFileDescriptor pipe: 1 MiB streamed, drained, hashed.
        streamFile("probeL02_file", 1024 * 1024);
        Bundle c3 = call("commit", "probeL02_file", meta(handle, "probe file 1MiB", true));
        step("file-pipe-roundtrip", !c3.getBoolean("ok") && "FAILED".equals(c3.getString("error")),
                c3.getString("error"));

        // Overflow: 21 MiB exceeds the 20 MiB cap and must fail visibly.
        streamFile("probeL03_big", 21L * 1024 * 1024);
        Bundle c4 = call("commit", "probeL03_big", meta(handle, "probe file 21MiB", true));
        step("overflow-visible", !c4.getBoolean("ok") && "FILE_TOO_LARGE".equals(c4.getString("error")),
                c4.getString("error"));

        Bundle st = call("stage", "probeL04_cancel", null);
        Bundle cc = call("cancel", "probeL04_cancel", null);
        step("cancel-stage", st.getBoolean("ok") && cc.getBoolean("ok"), "");

        Bundle p = call("poll", "0", null);
        step("poll-empty-page", p.getBoolean("ok") && "[]".equals(p.getString("items"))
                && !p.getBoolean("gap", false), p.getString("cursor"));

        Bundle s = call("status", "m1", null);
        step("status-unknown-ref", !s.getBoolean("ok"), s.getString("error"));
    }
}
