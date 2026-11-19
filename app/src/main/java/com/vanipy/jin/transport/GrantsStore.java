package com.vanipy.jin.transport;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Default-off, per-app/per-chat grants for the cross-app transport, plus the
 * "seen apps" list (package name, cert hash, timestamps only - never content).
 * Backed by SharedPreferences; the ledger of outbound tokens lives in files.
 */
public class GrantsStore {
    private static final String PREFS = "transport_grants";
    private static final String K_SEEN = "seen";

    private final SharedPreferences prefs;
    private final java.io.File mirror;

    public GrantsStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mirror = new java.io.File(ctx.getFilesDir(), "SYNC_GRANTS");
    }

    /** Mirror of the grants the python runtime enforces (defense in depth):
     *  {pkg: {enabled, prefix, chats: [handle,...]}}. Written atomically. */
    private void writeMirror() {
        try {
            JSONObject root = new JSONObject();
            for (String key : prefs.getAll().keySet()) {
                if (!key.startsWith("grant.")) continue;
                String pkg = key.substring("grant.".length());
                JSONObject g = grantJson(pkg);
                if (g == null) continue;
                JSONObject out = new JSONObject();
                out.put("enabled", g.optBoolean("enabled"));
                out.put("prefix", g.optString("prefix", ""));
                JSONArray chats = new JSONArray();
                JSONObject cj = g.optJSONObject("chats");
                if (cj != null) {
                    Iterator<String> it = cj.keys();
                    while (it.hasNext()) {
                        String h = it.next();
                        if (cj.optBoolean(h)) chats.put(h);
                    }
                }
                out.put("chats", chats);
                root.put(pkg, out);
            }
            java.io.File tmp = new java.io.File(mirror.getParentFile(), "SYNC_GRANTS.tmp");
            java.io.FileWriter w = new java.io.FileWriter(tmp);
            w.write(root.toString());
            w.close();
            if (!tmp.renameTo(mirror)) tmp.delete();
        } catch (Exception ignored) {}
    }

    public static final class SeenApp {
        public String pkg, cert;
        public long firstSeen, lastSeen;
        public boolean enabled;
        public String prefix;
    }

    public synchronized void recordSeen(String pkg, String cert) {
        try {
            JSONArray arr = new JSONArray(prefs.getString(K_SEEN, "[]"));
            long now = System.currentTimeMillis();
            boolean found = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.getString("pkg").equals(pkg) && o.getString("cert").equals(cert)) {
                    o.put("last", now);
                    found = true;
                }
            }
            if (!found) {
                JSONObject o = new JSONObject();
                o.put("pkg", pkg).put("cert", cert).put("first", now).put("last", now);
                arr.put(o);
            }
            prefs.edit().putString(K_SEEN, arr.toString()).apply();
        } catch (JSONException ignored) {}
    }

    public synchronized List<SeenApp> seenApps() {
        List<SeenApp> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(K_SEEN, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                SeenApp s = new SeenApp();
                s.pkg = o.getString("pkg");
                s.cert = o.getString("cert");
                s.firstSeen = o.optLong("first");
                s.lastSeen = o.optLong("last");
                JSONObject g = grantJson(s.pkg);
                s.enabled = g != null && g.optBoolean("enabled") && g.optString("cert").equals(s.cert);
                s.prefix = g != null ? g.optString("prefix", "") : "";
                out.add(s);
            }
        } catch (JSONException ignored) {}
        return out;
    }

    private JSONObject grantJson(String pkg) {
        try {
            String raw = prefs.getString("grant." + pkg, null);
            return raw == null ? null : new JSONObject(raw);
        } catch (JSONException e) { return null; }
    }

    /** A grant is valid only when enabled AND the stored cert pin matches the caller's current cert. */
    public boolean isAuthorized(String pkg, String cert) {
        JSONObject g = grantJson(pkg);
        return g != null && g.optBoolean("enabled") && cert.equals(g.optString("cert"));
    }

    public boolean isChatGranted(String pkg, String handle) {
        JSONObject g = grantJson(pkg);
        if (g == null || !g.optBoolean("enabled")) return false;
        JSONObject chats = g.optJSONObject("chats");
        return chats != null && chats.optBoolean(handle);
    }

    public String prefixFor(String pkg) {
        JSONObject g = grantJson(pkg);
        return g == null ? "" : g.optString("prefix", "");
    }

    public List<String> grantedChats(String pkg) {
        List<String> out = new ArrayList<>();
        JSONObject g = grantJson(pkg);
        if (g == null) return out;
        JSONObject chats = g.optJSONObject("chats");
        if (chats != null) {
            Iterator<String> it = chats.keys();
            while (it.hasNext()) {
                String h = it.next();
                if (chats.optBoolean(h)) out.add(h);
            }
        }
        return out;
    }

    public synchronized void setEnabled(String pkg, String cert, boolean enabled, String prefix) {
        JSONObject g = grantJson(pkg);
        try {
            if (g == null) g = new JSONObject();
            g.put("cert", cert).put("enabled", enabled).put("updated", System.currentTimeMillis());
            if (prefix != null) g.put("prefix", prefix);
            if (!g.has("chats")) g.put("chats", new JSONObject());
            prefs.edit().putString("grant." + pkg, g.toString()).apply();
        } catch (JSONException ignored) {}
        writeMirror();
    }

    public synchronized void setChatGranted(String pkg, String handle, boolean granted) {
        JSONObject g = grantJson(pkg);
        if (g == null) return;
        try {
            JSONObject chats = g.optJSONObject("chats");
            if (chats == null) chats = new JSONObject();
            chats.put(handle, granted);
            g.put("chats", chats).put("updated", System.currentTimeMillis());
            prefs.edit().putString("grant." + pkg, g.toString()).apply();
        } catch (JSONException ignored) {}
        writeMirror();
    }
}
