package com.vanipy.jin.transport;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Iterator;

/**
 * Outbound dedup ledger: token -> {hash, ref, ts}. Token reuse with the same
 * hash returns the original ref (idempotent retry); a different hash rejects.
 * Entries older than 7 days are pruned on write.
 */
public class SyncLedger {
    private static final String FILE = "SYNC_LEDGER";
    private static final long PRUNE_AFTER_MS = 7L * 24 * 3600 * 1000;
    private final File file;

    public SyncLedger(Context ctx) {
        file = new File(ctx.getFilesDir(), FILE);
    }

    private synchronized JSONObject load() {
        try {
            if (!file.exists()) return new JSONObject();
            byte[] buf = new byte[(int) file.length()];
            FileInputStream in = new FileInputStream(file);
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            in.close();
            return new JSONObject(new String(buf, 0, off, "UTF-8"));
        } catch (Exception e) { return new JSONObject(); }
    }

    private synchronized void save(JSONObject o) {
        try {
            File tmp = new File(file.getParentFile(), FILE + ".tmp");
            FileWriter w = new FileWriter(tmp);
            w.write(o.toString());
            w.close();
            if (!tmp.renameTo(file)) { tmp.delete(); }
        } catch (IOException ignored) {}
    }

    /** @return existing ref if token present with same hash, null if absent.
     *  @throws ReuseException if token present with a different hash. */
    public synchronized String check(String token, String hash) throws ReuseException {
        JSONObject all = load();
        JSONObject e = all.optJSONObject(token);
        if (e == null) return null;
        if (hash.equals(e.optString("hash"))) return e.optString("ref");
        throw new ReuseException();
    }

    public synchronized void put(String token, String hash, String metaHash,
                                 String fileSha, String ref) {
        JSONObject all = load();
        try {
            long now = System.currentTimeMillis();
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                JSONObject e = all.optJSONObject(it.next());
                if (e != null && now - e.optLong("ts") > PRUNE_AFTER_MS) it.remove();
            }
            JSONObject e = new JSONObject();
            e.put("hash", hash).put("metaHash", metaHash)
             .put("fileSha", fileSha).put("ref", ref).put("ts", now);
            all.put(token, e);
        } catch (JSONException ignored) {}
        save(all);
    }

    /** @return the ledger entry for a token, or null. */
    public synchronized JSONObject entry(String token) {
        return load().optJSONObject(token);
    }

    /** @return JSON {status, reason?} for a ref, or null when unknown. */
    public JSONObject statusOf(Context ctx, String ref) {
        File f = new File(ctx.getFilesDir(), "SYNC_STATUS_" + ref);
        try {
            if (!f.exists()) return null;
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            in.close();
            return new JSONObject(new String(buf, 0, off, "UTF-8"));
        } catch (Exception e) { return null; }
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString().toUpperCase();
        } catch (Exception e) { return ""; }
    }

    public static class ReuseException extends Exception {}
}
