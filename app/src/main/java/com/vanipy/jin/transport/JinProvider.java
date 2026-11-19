package com.vanipy.jin.transport;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Cross-app transport endpoint (contract v1, docs/transport-contract.md).
 * Exported, but every call authenticates the caller via binder uid ->
 * (package, signing-cert SHA-256) against default-off owner grants.
 * Capabilities is the only unauthenticated method; it records the caller as
 * "seen" (package + cert hash + timestamps, never content).
 */
public class JinProvider extends ContentProvider {
    private static final long BRIDGE_TIMEOUT_MS = 20000;
    private static final long BRIDGE_POLL_MS = 250;

    private GrantsStore grants;
    private SyncLedger ledger;
    private File stageDir;

    @Override public boolean onCreate() {
        grants = new GrantsStore(getContext());
        ledger = new SyncLedger(getContext());
        stageDir = new File(getContext().getFilesDir(), "stage");
        return true;
    }

    /** @return "pkg|cert" of the verified caller, or null (fail closed). */
    private String authenticate() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return null; // internal code does not use the provider
        PackageManager pm = getContext().getPackageManager();
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs == null || pkgs.length != 1) return null; // shared uid: fail closed
        String cert = certSha256(pm, pkgs[0]);
        return cert == null ? null : pkgs[0] + "|" + cert;
    }

    private static String certSha256(PackageManager pm, String pkg) {
        try {
            PackageInfo pi;
            byte[] certBytes = null;
            if (Build.VERSION.SDK_INT >= 28) {
                pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES
                        | PackageManager.GET_SIGNATURES);
                if (pi.signingInfo != null
                        && pi.signingInfo.getApkContentsSigners() != null
                        && pi.signingInfo.getApkContentsSigners().length > 0)
                    certBytes = pi.signingInfo.getApkContentsSigners()[0].toByteArray();
            } else {
                pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            }
            if (certBytes == null && pi.signatures != null && pi.signatures.length > 0)
                certBytes = pi.signatures[0].toByteArray();
            return certBytes == null ? null : SyncLedger.sha256Hex(certBytes);
        } catch (Exception e) { return null; }
    }

    private static Bundle err(String code) {
        Bundle b = new Bundle();
        b.putBoolean(TransportContract.K_OK, false);
        b.putString(TransportContract.K_ERROR, code);
        return b;
    }

    private static Bundle ok() {
        Bundle b = new Bundle();
        b.putBoolean(TransportContract.K_OK, true);
        return b;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        String who = authenticate();
        if (who == null) return err(TransportContract.E_NOT_AUTHORIZED);
        String pkg = who.substring(0, who.indexOf('|'));
        String cert = who.substring(who.indexOf('|') + 1);
        grants.recordSeen(pkg, cert);

        if (TransportContract.M_CAPABILITIES.equals(method)) {
            Bundle b = ok();
            b.putInt(TransportContract.K_CONTRACT_VERSION, TransportContract.VERSION);
            b.putLong(TransportContract.K_MAX_BYTES, TransportContract.DEFAULT_MAX_BYTES);
            boolean granted = grants.isAuthorized(pkg, cert);
            b.putBoolean(TransportContract.K_GRANTED, granted);
            if (granted) b.putString(TransportContract.K_PREFIX, grants.prefixFor(pkg));
            return b;
        }
        if (!grants.isAuthorized(pkg, cert)) return err(TransportContract.E_NOT_AUTHORIZED);

        switch (method == null ? "" : method) {
            case TransportContract.M_STAGE: return stage(arg);
            case TransportContract.M_CANCEL: return cancelStage(arg);
            case TransportContract.M_COMMIT: return commit(pkg, arg, extras);
            case TransportContract.M_LIST_CHATS: return bridge("listChats", null, pkg);
            case TransportContract.M_POLL: return bridge("poll", arg, pkg);
            case TransportContract.M_STATUS: {
                if (arg == null) return err(TransportContract.E_MALFORMED);
                JSONObject st = ledger.statusOf(getContext(), arg);
                if (st == null) return err(TransportContract.E_MALFORMED);
                Bundle b = ok();
                b.putString(TransportContract.K_STATUS, st.optString("status"));
                b.putString("reason", st.optString("reason"));
                return b;
            }
            default: return err(TransportContract.E_UNKNOWN_METHOD);
        }
    }

    private static boolean validToken(String t) {
        return t != null && t.matches(TransportContract.TOKEN_PATTERN);
    }

    private Bundle stage(String token) {
        if (!validToken(token)) return err(TransportContract.E_BAD_TOKEN);
        // A token already in the ledger MAY be re-staged: it is the recovery
        // route for a lost commit response after the retained bytes were
        // swept. commit() re-verifies metadata and byte identity against the
        // ledger entry and returns the original ref, never a duplicate send.
        // A fresh token for the same logical send WOULD duplicate, so the
        // contract forbids it. Only byte/meta mismatches stay TOKEN_REUSED.
        if (ledger.entry(token) == null) {
            try {
                if (ledger.check(token, "") != null) return err(TransportContract.E_TOKEN_REUSED);
            } catch (SyncLedger.ReuseException e) {
                return err(TransportContract.E_TOKEN_REUSED);
            }
        }
        getContext().getSharedPreferences("transport_stage", 0).edit()
            .putLong("ts." + token, System.currentTimeMillis()).apply();
        Bundle b = ok();
        b.putString(TransportContract.K_URI, "content://" + TransportContract.AUTHORITY + "/stage/" + token);
        return b;
    }

    private boolean stageTokenLive(String token) {
        long ts = getContext().getSharedPreferences("transport_stage", 0).getLong("ts." + token, 0);
        return ts > 0 && System.currentTimeMillis() - ts <= TransportContract.STAGE_TOKEN_TTL_MS;
    }

    private Bundle cancelStage(String token) {
        if (!validToken(token)) return err(TransportContract.E_BAD_TOKEN);
        File part = new File(stageDir, token + ".part");
        if (part.exists()) part.delete();
        new File(stageDir, token + ".overflow").delete();
        new File(stageDir, token + ".done").delete();
        return ok();
    }

    private Bundle refBundle(String ref) {
        Bundle b = ok();
        b.putString(TransportContract.K_REF, ref);
        b.putString(TransportContract.K_STATUS, TransportContract.S_QUEUED);
        return b;
    }

    // Striped per-token locks: overlapping same-token binder commits
    // serialize here, so the second observes the ledger entry the first
    // wrote and returns the original ref instead of sending twice.
    private final Object[] commitLocks = new Object[64];
    { for (int i = 0; i < commitLocks.length; i++) commitLocks[i] = new Object(); }

    private Bundle commit(String pkg, String token, Bundle extras) {
        if (!validToken(token)) return err(TransportContract.E_BAD_TOKEN);
        synchronized (commitLocks[(token.hashCode() & 0x7fffffff) % commitLocks.length]) {
            return commitLocked(pkg, token, extras);
        }
    }

    private Bundle commitLocked(String pkg, String token, Bundle extras) {
        String metaRaw = extras == null ? null : extras.getString(TransportContract.K_META);
        if (metaRaw == null) return err(TransportContract.E_MALFORMED);
        JSONObject meta;
        try { meta = new JSONObject(metaRaw); } catch (Exception e) { return err(TransportContract.E_MALFORMED); }

        long clientTs = meta.optLong("ts", 0);
        if (clientTs > System.currentTimeMillis() + TransportContract.MAX_FUTURE_SKEW_MS)
            return err(TransportContract.E_FUTURE_TS);
        String chat = meta.optString("chat", "");
        if (!grants.isChatSendGranted(pkg, chat)) return err(TransportContract.E_CHAT_NOT_GRANTED);

        // Canonical metadata: method + chat + text + filename/mime. The full
        // dedup hash appends the file-bytes hash; the metadata hash alone is
        // what a retry can still be checked against after bytes moved on.
        String canonMeta = "send|" + chat + "|" + meta.optString("text", "")
                + "|" + meta.optString("filename", "") + "|" + meta.optString("mime", "");
        String metaHash = SyncLedger.sha256Hex(canonMeta.getBytes());
        boolean wantsFile = !meta.optString("filename", "").isEmpty();

        File staged = new File(stageDir, token + ".part");
        File done = new File(stageDir, token + ".done");
        File overflow = new File(stageDir, token + ".overflow");
        File bin = new File(stageDir, token + ".bin");

        // Idempotent retry: the token is already in the ledger (an earlier
        // commit succeeded but its response may have been lost). Return the
        // original ref when the retry provably carries the same body.
        JSONObject existing = ledger.entry(token);
        if (existing != null) {
            if (!metaHash.equals(existing.optString("metaHash")))
                return err(TransportContract.E_TOKEN_REUSED);
            String wantSha = existing.optString("fileSha", "");
            if (wantSha.isEmpty())
                return refBundle(existing.optString("ref")); // text-only: no bytes to re-verify
            File f = bin.exists() ? bin
                    : (staged.exists() && done.exists() ? staged : null);
            if (f == null) {
                // staged bytes swept after the TTL window: identity can no
                // longer be re-verified, so say so instead of guessing
                return err(TransportContract.E_EXPIRED);
            }
            try {
                if (!SyncLedger.sha256Hex(readAll(f)).equals(wantSha))
                    return err(TransportContract.E_TOKEN_REUSED);
            } catch (IOException e) { return err(TransportContract.E_MALFORMED); }
            // a verified re-stage has served its purpose: clear it, or a
            // later "bare" retry would silently re-verify these bytes
            if (f == staged) { staged.delete(); done.delete(); }
            return refBundle(existing.optString("ref"));
        }

        if (staged.exists() && !stageTokenLive(token)) {
            staged.delete();
            return err(TransportContract.E_BAD_TOKEN); // expired staging token
        }
        // A caller can close() its pipe end and commit before the drain thread
        // has consumed the final bytes: wait (bounded) for the drain's EOF
        // marker so the hash never covers truncated bytes.
        if (staged.exists() && !done.exists() && !overflow.exists()) {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline
                    && !done.exists() && !overflow.exists()) {
                try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
            }
        }
        String fileSha = "";
        byte[] fileBytes = null;
        if (overflow.exists()) {
            staged.delete();
            return err(TransportContract.E_FILE_TOO_LARGE);
        }
        if (staged.exists()) {
            if (!done.exists()) {
                staged.delete();
                return err(TransportContract.E_STAGE_INCOMPLETE);
            }
            try {
                fileBytes = readAll(staged);
                fileSha = SyncLedger.sha256Hex(fileBytes);
            } catch (IOException e) { return err(TransportContract.E_MALFORMED); }
        } else if (wantsFile) {
            return err(TransportContract.E_STAGE_INCOMPLETE); // stream never arrived
        }
        String hash = SyncLedger.sha256Hex((canonMeta + "|" + fileSha).getBytes());

        // Hand to the python runtime through the SYNC file bridge (PUBLISH_REQ pattern).
        try {
            JSONObject req = new JSONObject();
            req.put("id", token).put("method", "send").put("pkg", pkg)
               .put("chat", chat).put("text", meta.optString("text", ""))
               .put("hash", hash).put("ts", System.currentTimeMillis());
            if (fileBytes != null) {
                FileOutputStream fo = new FileOutputStream(bin);
                fo.write(fileBytes);
                fo.close();
                staged.delete();
                done.delete();
                req.put("file", bin.getName())
                   .put("filename", meta.optString("filename", ""))
                   .put("mime", meta.optString("mime", ""));
            }
            File tmp = new File(getContext().getFilesDir(), "SYNC_REQ_" + token + ".tmp");
            FileWriter w = new FileWriter(tmp);
            w.write(req.toString());
            w.close();
            if (!tmp.renameTo(new File(getContext().getFilesDir(), "SYNC_REQ_" + token))) {
                tmp.delete();
                return err(TransportContract.E_MALFORMED);
            }
        } catch (Exception e) { return err(TransportContract.E_MALFORMED); }

        JSONObject resp = awaitResp(token);
        if (resp == null) return err(TransportContract.E_BOT_NOT_RUNNING);
        if (resp.optBoolean("ok")) {
            String ref = resp.optString("ref");
            ledger.put(token, hash, metaHash, fileSha, ref);
            return refBundle(ref);
        }
        return err(resp.optString("error", TransportContract.E_MALFORMED));
    }

    private Bundle bridge(String method, String cursor) { return bridge(method, cursor, null); }

    private Bundle bridge(String method, String cursor, String pkg) {
        String id = "br" + System.currentTimeMillis() + "_" + (int) (Math.random() * 1e6);
        try {
            JSONObject req = new JSONObject();
            req.put("id", id).put("method", method);
            if (cursor != null) req.put("cursor", cursor);
            if (pkg != null) req.put("pkg", pkg);
            File tmp = new File(getContext().getFilesDir(), "SYNC_REQ_" + id + ".tmp");
            FileWriter w = new FileWriter(tmp);
            w.write(req.toString());
            w.close();
            if (!tmp.renameTo(new File(getContext().getFilesDir(), "SYNC_REQ_" + id))) {
                tmp.delete();
                return err(TransportContract.E_MALFORMED);
            }
        } catch (Exception e) { return err(TransportContract.E_MALFORMED); }
        JSONObject resp = awaitResp(id);
        if (resp == null) return err(TransportContract.E_BOT_NOT_RUNNING);
        if (!resp.optBoolean("ok")) {
            // propagate the runtime's failure: an error must never look like
            // an empty result with a reset cursor
            return err(resp.optString("error", TransportContract.E_MALFORMED));
        }
        Bundle b = ok();
        b.putString(TransportContract.K_ITEMS, resp.optString("items", "[]"));
        b.putString(TransportContract.K_CURSOR, resp.optString("cursor", ""));
        if (resp.optBoolean("gap")) {
            b.putBoolean(TransportContract.K_GAP, true);
            b.putString(TransportContract.K_FIRST_AVAILABLE, resp.optString("first_available", ""));
        }
        return b;
    }

    private JSONObject awaitResp(String id) {
        File resp = new File(getContext().getFilesDir(), "SYNC_RESP_" + id);
        long deadline = System.currentTimeMillis() + BRIDGE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (resp.exists()) {
                try {
                    byte[] buf = readAll(resp);
                    resp.delete();
                    return new JSONObject(new String(buf, "UTF-8"));
                } catch (Exception e) { return null; }
            }
            // Bot loop writes SYNC_ALIVE each pass; if it's stale the bot is not running.
            File alive = new File(getContext().getFilesDir(), "SYNC_ALIVE");
            if (!alive.exists() || System.currentTimeMillis() - alive.lastModified() > 15000)
                return null;
            try { Thread.sleep(BRIDGE_POLL_MS); } catch (InterruptedException ie) { return null; }
        }
        return null;
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        int off = 0, n;
        while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
        in.close();
        return buf;
    }

    /** Pipe-drain staging: caller writes into the returned descriptor; we cap and abort visibly. */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        if (mode == null || !mode.contains("w")) return null;
        String who = authenticate();
        if (who == null) return null;
        String pkg = who.substring(0, who.indexOf('|'));
        String cert = who.substring(who.indexOf('|') + 1);
        if (!grants.isAuthorized(pkg, cert)) return null;
        if (uri.getPathSegments().size() != 2 || !"stage".equals(uri.getPathSegments().get(0))) return null;
        final String token = uri.getPathSegments().get(1);
        if (!validToken(token) || !stageTokenLive(token)) return null;
        try {
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            final File part = new File(stageDir, token + ".part");
            final File overflow = new File(stageDir, token + ".overflow");
            stageDir.mkdirs();
            overflow.delete();
            new Thread(new Runnable() {
                @Override public void run() {
                    long total = 0;
                    try {
                        FileOutputStream out = new FileOutputStream(part);
                        FileInputStream in = new FileInputStream(pipe[0].getFileDescriptor());
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            total += n;
                            if (total > TransportContract.DEFAULT_MAX_BYTES) {
                                out.close(); in.close();
                                part.delete();
                                overflow.createNewFile();
                                return;
                            }
                            out.write(buf, 0, n);
                        }
                        out.close(); in.close();
                        new File(stageDir, token + ".done").createNewFile();
                    } catch (IOException e) {
                        part.delete();
                    }
                }
            }, "jin-stage-drain").start();
            return pipe[1];
        } catch (IOException e) { return null; }
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
