package com.vanipy.jin;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;

import com.vanipy.jin.transport.GrantsStore;
import com.vanipy.jin.transport.JinProvider;
import com.vanipy.jin.transport.TransportContract;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowBinder;

/** Auth-boundary tests for the cross-app transport provider (contract v1). */
@RunWith(RobolectricTestRunner.class)
public class TransportProviderTest {
    private static final int UID = 10123;
    private static final String PKG = "com.fake.caller";
    // Throwaway self-signed cert generated for these tests (public, signs nothing).
    private static final String TEST_CERT_PEM =
            "-----BEGIN CERTIFICATE-----\n" +
            "MIIDGzCCAgOgAwIBAgIUcqiZKEB8Nreii7kje5iGlXku66IwDQYJKoZIhvcNAQEL\n" +
            "BQAwHTEbMBkGA1UEAwwSamluLXRyYW5zcG9ydC10ZXN0MB4XDTI2MTExOTAyMzI1\n" +
            "OVoXDTM2MTExNjAyMzI1OVowHTEbMBkGA1UEAwwSamluLXRyYW5zcG9ydC10ZXN0\n" +
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyx8bPOlPhAmhE1+olFVy\n" +
            "cLgqmoGEHUfS7HNFQjIbZ9reYnnCs1SmvpoSsEa5+PkEq+C1aUoi66lxZh8d9Dhe\n" +
            "bYFxVbCOnmfRRhUT6IQ2Qp7Meg2xgC2Mi3ky6D5cil/VYSsp45otPvSJ25ZmVj83\n" +
            "Cn4LQKF7FnloqKVY95duLRevceDM/mp/WhiRkY10Cmd/RXFBQHim6VaaU3/ZmQTe\n" +
            "4gBC4GMERaTlxdRMHfjPO1VJqD/PTFvX4P3zPCJl0yRHg4e9igxDtU0ag7P80mcF\n" +
            "tauxQuY98feomOvUKBfGIyTKMmzwplfAQwfhT6GRizXvHNb+2d/FBsSHF4B/lBA8\n" +
            "3QIDAQABo1MwUTAdBgNVHQ4EFgQUm2e5nchX1BYy5dbm80SLkQhsPx0wHwYDVR0j\n" +
            "BBgwFoAUm2e5nchX1BYy5dbm80SLkQhsPx0wDwYDVR0TAQH/BAUwAwEB/zANBgkq\n" +
            "hkiG9w0BAQsFAAOCAQEAxS7hrgzeC3+AQ6ksWNjCKjri8dA6GGjWRyLoI6EOiVMf\n" +
            "Rzov3ghpJzI1Vnk0IIhVTfLNDorigJBJJhP2ozDP+N0MyG5CQCrUJW2e96amKfJQ\n" +
            "WT2oGO4Zvj2XVRdkzvjx+H1o7weRUoYLFbZXfQv+4WK2kDnwzQZqfSMClDJtVB78\n" +
            "cwwFQTT46BhWiL8ilmLzKiCbuIT3nmNfxHDYKdbG/MwxRZVswtAIK5C+Ohkk3dDX\n" +
            "qbctPdDkCY+FsS8/fDz6R05V18iKl/BF1SaE6JA5Bob4mM28sHugPoWYy7G7PzHc\n" +
            "/TeDdmGJUOS47mAp51pJZbhXCpByB8knRoFdiMpw0Q==\n" +
            "-----END CERTIFICATE-----\n";
    private static String CERT_A;  // sha256 of TEST_CERT_PEM bytes, set in setUp
    private static final String CERT_B =
            "FCDE2B2EDBA56BF408601FB721FE9B5C338D10EE429EA04FAE5511B68FBF8FB9"; // not the callers cert

    private JinProvider provider;
    private GrantsStore grants;

    private static byte[] testCertBytes() {
        try {
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.cert.Certificate c = cf.generateCertificate(
                    new java.io.ByteArrayInputStream(TEST_CERT_PEM.getBytes("UTF-8")));
            return c.getEncoded();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static PackageInfo pkgInfo(String pkg, byte[] sigBytes) {
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        Signature sig = new Signature(sigBytes);
        pi.signatures = new Signature[]{sig};
        // Robolectric does not derive signingInfo from legacy signatures, so
        // populate the API-28+ field the provider actually reads.
        try {
            Class<?> sd = Class.forName("android.content.pm.SigningDetails");
            java.lang.reflect.Constructor<?> dc = sd.getDeclaredConstructor(Signature[].class, Integer.TYPE);
            dc.setAccessible(true);
            Object details = dc.newInstance(new Signature[]{sig}, 3);
            Class<?> si = Class.forName("android.content.pm.SigningInfo");
            java.lang.reflect.Constructor<?> ic = si.getDeclaredConstructor(sd);
            ic.setAccessible(true);
            pi.signingInfo = (android.content.pm.SigningInfo) ic.newInstance(details);
        } catch (Exception e) {
            throw new RuntimeException("test fixture: signingInfo injection failed", e);
        }
        return pi;
    }

    @Before
    public void setUp() {
        provider = Robolectric.buildContentProvider(JinProvider.class).create().get();
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        grants = new GrantsStore(ctx);
        ctx.getSharedPreferences("transport_grants", 0).edit().clear().commit();
        ctx.getSharedPreferences("transport_stage", 0).edit().clear().commit();
        for (String n : new String[]{"SYNC_ALIVE", "SYNC_LEDGER"}) {
            new java.io.File(ctx.getFilesDir(), n).delete();
        }
        for (String n : ctx.getFilesDir().list()) {
            if (n.startsWith("SYNC_REQ_") || n.startsWith("SYNC_RESP_"))
                new java.io.File(ctx.getFilesDir(), n).delete();
        }
        java.io.File sd = new java.io.File(ctx.getFilesDir(), "stage");
        if (sd.isDirectory()) for (java.io.File f : sd.listFiles()) f.delete();
        ShadowBinder.setCallingUid(UID);
        PackageManager pm = ctx.getPackageManager();
        byte[] certBytes = testCertBytes();
        CERT_A = com.vanipy.jin.transport.SyncLedger.sha256Hex(certBytes);
        shadowOf(pm).installPackage(pkgInfo(PKG, certBytes));
        shadowOf(pm).setPackagesForUid(UID, PKG);
    }

    private static Bundle call(JinProvider p, String m, String arg, Bundle ex) {
        return p.call(m, arg, ex);
    }

    @Test
    public void unknownAppGetsOnlyCapabilities() {
        Bundle caps = call(provider, TransportContract.M_CAPABILITIES, null, null);
        assertTrue(caps.getBoolean(TransportContract.K_OK));
        assertFalse(caps.getBoolean(TransportContract.K_GRANTED));
        assertEquals(TransportContract.VERSION, caps.getInt(TransportContract.K_CONTRACT_VERSION));
        for (String m : new String[]{TransportContract.M_STAGE, TransportContract.M_COMMIT,
                TransportContract.M_POLL, TransportContract.M_LIST_CHATS, TransportContract.M_STATUS}) {
            Bundle r = call(provider, m, "sometoken123", null);
            assertFalse(m, r.getBoolean(TransportContract.K_OK));
            assertEquals(m, TransportContract.E_NOT_AUTHORIZED, r.getString(TransportContract.K_ERROR));
        }
        // seen, not authority: the app is recorded for the owner UI
        assertEquals(1, grants.seenApps().size());
        assertEquals(PKG, grants.seenApps().get(0).pkg);
    }

    @Test
    public void sharedUidFailsClosed() {
        shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
                .setPackagesForUid(UID, PKG, "com.other.app");
        grants.setEnabled(PKG, CERT_A, true, "/x");
        Bundle r = call(provider, TransportContract.M_CAPABILITIES, null, null);
        assertFalse(r.getBoolean(TransportContract.K_OK));
        assertEquals(TransportContract.E_NOT_AUTHORIZED, r.getString(TransportContract.K_ERROR));
    }

    @Test
    public void wrongCertNeverMatchesGrant() {
        grants.setEnabled(PKG, CERT_B, true, "/x"); // pinned cert differs from caller's
        Bundle r = call(provider, TransportContract.M_STAGE, "sometoken123", null);
        assertEquals(TransportContract.E_NOT_AUTHORIZED, r.getString(TransportContract.K_ERROR));
        // capabilities answers but reports not granted
        Bundle caps = call(provider, TransportContract.M_CAPABILITIES, null, null);
        assertTrue(caps.getBoolean(TransportContract.K_OK));
        assertFalse(caps.getBoolean(TransportContract.K_GRANTED));
    }

    @Test
    public void grantedAppFlowAndChatScope() {
        grants.setEnabled(PKG, CERT_A, true, "/x");
        Bundle caps = call(provider, TransportContract.M_CAPABILITIES, null, null);
        assertTrue(caps.getBoolean(TransportContract.K_GRANTED));
        assertEquals("/x", caps.getString(TransportContract.K_PREFIX));

        // stage needs a sane token
        Bundle bad = call(provider, TransportContract.M_STAGE, "no", null);
        assertEquals(TransportContract.E_BAD_TOKEN, bad.getString(TransportContract.K_ERROR));
        Bundle st = call(provider, TransportContract.M_STAGE, "tok_abc123", null);
        assertTrue(st.getBoolean(TransportContract.K_OK));
        assertTrue(st.getString(TransportContract.K_URI).endsWith("/stage/tok_abc123"));

        // commit without send scope on the chat -> refused
        String handle = "aGFuZGxl"; // opaque in this test
        Bundle meta = new Bundle();
        meta.putString(TransportContract.K_META,
                "{\"chat\":\"" + handle + "\",\"text\":\"hi\",\"ts\":" + System.currentTimeMillis() + "}");
        Bundle c = call(provider, TransportContract.M_COMMIT, "tok_abc123", meta);
        assertEquals(TransportContract.E_CHAT_NOT_GRANTED, c.getString(TransportContract.K_ERROR));

        // recv scope alone does not authorize sending
        grants.setChatScope(PKG, handle, "recv", true);
        c = call(provider, TransportContract.M_COMMIT, "tok_abc123", meta);
        assertEquals(TransportContract.E_CHAT_NOT_GRANTED, c.getString(TransportContract.K_ERROR));

        // send scope granted -> request is accepted to the bridge, and with the
        // bot down it fails visibly BOT_NOT_RUNNING (no silent queue)
        grants.setChatScope(PKG, handle, "send", true);
        c = call(provider, TransportContract.M_COMMIT, "tok_abc123", meta);
        assertEquals(TransportContract.E_BOT_NOT_RUNNING, c.getString(TransportContract.K_ERROR));
        assertTrue(new java.io.File(RuntimeEnvironment.getApplication().getFilesDir(),
                "SYNC_REQ_tok_abc123").exists());
    }

    @Test
    public void futureTimestampRejected() {
        grants.setEnabled(PKG, CERT_A, true, "/x");
        String handle = "aGFuZGxl";
        grants.setChatScope(PKG, handle, "send", true);
        Bundle meta = new Bundle();
        meta.putString(TransportContract.K_META, "{\"chat\":\"" + handle
                + "\",\"text\":\"hi\",\"ts\":" + (System.currentTimeMillis() + 3600_000) + "}");
        Bundle c = call(provider, TransportContract.M_COMMIT, "tok_future1", meta);
        assertEquals(TransportContract.E_FUTURE_TS, c.getString(TransportContract.K_ERROR));
    }

    /** Watches for one SYNC_REQ_* and answers it with the canned payload. */
    private Thread respondOnce(final String respJson) {
        final java.io.File dir = RuntimeEnvironment.getApplication().getFilesDir();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long deadline = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < deadline) {
                    String[] names = dir.list();
                    if (names != null) {
                        for (String n : names) {
                            if (!n.startsWith("SYNC_REQ_")) continue;
                            String id = n.substring("SYNC_REQ_".length());
                            new java.io.File(dir, n).delete();
                            try {
                                java.io.File tmp = new java.io.File(dir, "SYNC_RESP_" + id + ".tmp");
                                java.io.FileWriter w = new java.io.FileWriter(tmp);
                                w.write(respJson);
                                w.close();
                                tmp.renameTo(new java.io.File(dir, "SYNC_RESP_" + id));
                            } catch (Exception ignored) {}
                            return;
                        }
                    }
                    try { Thread.sleep(50); } catch (InterruptedException ie) { return; }
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void botAlive() throws Exception {
        java.io.File f = new java.io.File(RuntimeEnvironment.getApplication().getFilesDir(), "SYNC_ALIVE");
        java.io.FileWriter w = new java.io.FileWriter(f);
        w.write("1");
        w.close();
    }

    @Test
    public void bridgePropagatesPythonError() throws Exception {
        grants.setEnabled(PKG, CERT_A, true, "/x");
        botAlive();
        respondOnce("{\"ok\":false,\"error\":\"NOT_AUTHORIZED\"}");
        Bundle r = call(provider, TransportContract.M_POLL, "0", null);
        assertFalse(r.getBoolean(TransportContract.K_OK));
        // an error must never masquerade as an empty page with a reset cursor
        assertEquals(TransportContract.E_NOT_AUTHORIZED, r.getString(TransportContract.K_ERROR));
    }

    @Test
    public void bridgePassesGapThrough() throws Exception {
        grants.setEnabled(PKG, CERT_A, true, "/x");
        botAlive();
        respondOnce("{\"ok\":true,\"items\":\"[]\",\"cursor\":\"41\",\"gap\":true,\"first_available\":\"40\"}");
        Bundle r = call(provider, TransportContract.M_POLL, "10", null);
        assertTrue(r.getBoolean(TransportContract.K_OK));
        assertEquals("41", r.getString(TransportContract.K_CURSOR));
        assertTrue(r.getBoolean(TransportContract.K_GAP));
        assertEquals("40", r.getString(TransportContract.K_FIRST_AVAILABLE));
    }

    private java.io.File stageFile(String token, String suffix, byte[] data) throws Exception {
        java.io.File dir = new java.io.File(RuntimeEnvironment.getApplication().getFilesDir(), "stage");
        dir.mkdirs();
        java.io.File f = new java.io.File(dir, token + suffix);
        java.io.FileOutputStream o = new java.io.FileOutputStream(f);
        if (data != null) o.write(data);
        o.close();
        return f;
    }

    private Bundle commitWithFileMeta(String token, String handle) {
        Bundle meta = new Bundle();
        meta.putString(TransportContract.K_META, "{\"chat\":\"" + handle
                + "\",\"text\":\"\",\"filename\":\"x.bin\",\"mime\":\"application/octet-stream\",\"ts\":"
                + System.currentTimeMillis() + "}");
        return call(provider, TransportContract.M_COMMIT, token, meta);
    }

    private void assertReqHasFullFile(String token, int len) throws Exception {
        java.io.File dir = RuntimeEnvironment.getApplication().getFilesDir();
        java.io.File req = new java.io.File(dir, "SYNC_REQ_" + token);
        assertTrue(req.exists());
        byte[] raw = new byte[(int) req.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(req);
        int off = 0, n;
        while (off < raw.length && (n = in.read(raw, off, raw.length - off)) > 0) off += n;
        in.close();
        String reqJson = new String(raw, 0, off, "UTF-8");
        assertTrue(reqJson, reqJson.contains("\"file\":\"" + token + ".bin\""));
        java.io.File bin = new java.io.File(dir, "stage/" + token + ".bin");
        assertTrue(bin.exists());
        assertEquals(len, bin.length());
    }

    @Test
    public void commitAttachesCompletedStage() throws Exception {
        // a finished drain (part + done marker) is consumed fully
        grants.setEnabled(PKG, CERT_A, true, "/x");
        String handle = "aGFuZGxl";
        grants.setChatScope(PKG, handle, "send", true);
        call(provider, TransportContract.M_STAGE, "tok_stream1", null);
        byte[] payload = new byte[100000];
        java.util.Arrays.fill(payload, (byte) 7);
        stageFile("tok_stream1", ".part", payload);
        stageFile("tok_stream1", ".done", null);
        Bundle c = commitWithFileMeta("tok_stream1", handle);
        assertEquals(TransportContract.E_BOT_NOT_RUNNING, c.getString(TransportContract.K_ERROR));
        assertReqHasFullFile("tok_stream1", payload.length);
    }

    @Test
    public void commitAwaitsLateDrainEof() throws Exception {
        // part exists but the drain has not signalled EOF yet: commit waits
        // (bounded) for the marker instead of hashing truncated bytes
        grants.setEnabled(PKG, CERT_A, true, "/x");
        String handle = "aGFuZGxl";
        grants.setChatScope(PKG, handle, "send", true);
        call(provider, TransportContract.M_STAGE, "tok_stream2", null);
        final byte[] payload = new byte[100000];
        java.util.Arrays.fill(payload, (byte) 9);
        stageFile("tok_stream2", ".part", payload);
        final Bundle[] holder = new Bundle[1];
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                holder[0] = commitWithFileMeta("tok_stream2", handle);
            }
        });
        t.start();
        Thread.sleep(400);
        assertTrue("commit must still be waiting for drain EOF", t.isAlive());
        stageFile("tok_stream2", ".done", null);
        t.join(6000);
        assertFalse(t.isAlive());
        assertEquals(TransportContract.E_BOT_NOT_RUNNING, holder[0].getString(TransportContract.K_ERROR));
        assertReqHasFullFile("tok_stream2", payload.length);
    }

    @Test
    public void commitAbortsWhenDrainNeverFinishes() throws Exception {
        grants.setEnabled(PKG, CERT_A, true, "/x");
        String handle = "aGFuZGxl";
        grants.setChatScope(PKG, handle, "send", true);
        call(provider, TransportContract.M_STAGE, "tok_stream3", null);
        stageFile("tok_stream3", ".part", new byte[10]);
        long t0 = System.currentTimeMillis();
        Bundle c = commitWithFileMeta("tok_stream3", handle);
        assertEquals(TransportContract.E_STAGE_INCOMPLETE, c.getString(TransportContract.K_ERROR));
        assertTrue("bounded wait", System.currentTimeMillis() - t0 < 7000);
        assertFalse(new java.io.File(RuntimeEnvironment.getApplication().getFilesDir(),
                "SYNC_REQ_tok_stream3").exists());
    }

    @Test
    public void commitWithoutStreamedFileFailsVisibly() {
        grants.setEnabled(PKG, CERT_A, true, "/x");
        String handle = "aGFuZGxl";
        grants.setChatScope(PKG, handle, "send", true);
        call(provider, TransportContract.M_STAGE, "tok_nofile1", null);
        Bundle meta = new Bundle();
        meta.putString(TransportContract.K_META, "{\"chat\":\"" + handle
                + "\",\"filename\":\"x.bin\",\"ts\":" + System.currentTimeMillis() + "}");
        Bundle c = call(provider, TransportContract.M_COMMIT, "tok_nofile1", meta);
        assertEquals(TransportContract.E_STAGE_INCOMPLETE, c.getString(TransportContract.K_ERROR));
    }

    private Bundle commitOk(String token, String handle, String text,
                            boolean withFile, byte[] payload) throws Exception {
        call(provider, TransportContract.M_STAGE, token, null);
        if (withFile) {
            stageFile(token, ".part", payload);
            stageFile(token, ".done", null);
        }
        Bundle meta = new Bundle();
        String m = "{\"chat\":\"" + handle + "\",\"text\":\"" + text + "\""
                + (withFile ? ",\"filename\":\"x.bin\",\"mime\":\"application/octet-stream\"" : "")
                + ",\"ts\":" + System.currentTimeMillis() + "}";
        meta.putString(TransportContract.K_META, m);
        return call(provider, TransportContract.M_COMMIT, token, meta);
    }

    @Test
    public void retryReturnsOriginalRefWithoutResending() throws Exception {
        grants.setEnabled(PKG, CERT_A, true, "/x");
        String handle = "aGFuZGxl";
        grants.setChatScope(PKG, handle, "send", true);
        botAlive();
        respondOnce("{\"ok\":true,\"ref\":\"m77\"}");
        byte[] payload = new byte[5000];
        java.util.Arrays.fill(payload, (byte) 3);
        Bundle c1 = commitOk("tok_retry1", handle, "hi", true, payload);
        assertTrue(c1.getBoolean(TransportContract.K_OK));
        assertEquals("m77", c1.getString(TransportContract.K_REF));
        // lost response: the caller retries the same token + same meta
        Bundle c2 = commitOk("tok_retry1", handle, "hi", true, payload);
        assertTrue(c2.getBoolean(TransportContract.K_OK));
        assertEquals("m77", c2.getString(TransportContract.K_REF));
        // and nothing was handed to the runtime a second time
        java.io.File dir = RuntimeEnvironment.getApplication().getFilesDir();
        for (String n : dir.list()) assertFalse(n, n.startsWith("SYNC_REQ_"));
    }

    @Test
    public void retryWithDifferentBodyRejected() throws Exception {
        grants.setEnabled(PKG, CERT_A, true, "/x");
        String handle = "aGFuZGxl";
        grants.setChatScope(PKG, handle, "send", true);
        botAlive();
        respondOnce("{\"ok\":true,\"ref\":\"m78\"}");
        Bundle c1 = commitOk("tok_retry2", handle, "hi", false, null);
        assertTrue(c1.getBoolean(TransportContract.K_OK));
        Bundle c2 = commitOk("tok_retry2", handle, "CHANGED", false, null);
        assertEquals(TransportContract.E_TOKEN_REUSED, c2.getString(TransportContract.K_ERROR));
    }

    @Test
    public void textOnlyRetryStaysIdempotent() throws Exception {
        grants.setEnabled(PKG, CERT_A, true, "/x");
        String handle = "aGFuZGxl";
        grants.setChatScope(PKG, handle, "send", true);
        botAlive();
        respondOnce("{\"ok\":true,\"ref\":\"m79\"}");
        commitOk("tok_retry3", handle, "hi", false, null);
        Bundle c2 = commitOk("tok_retry3", handle, "hi", false, null);
        assertEquals("m79", c2.getString(TransportContract.K_REF));
    }

    @Test
    public void fileRetryAfterBytesSweptSaysExpired() throws Exception {
        grants.setEnabled(PKG, CERT_A, true, "/x");
        String handle = "aGFuZGxl";
        grants.setChatScope(PKG, handle, "send", true);
        botAlive();
        respondOnce("{\"ok\":true,\"ref\":\"m80\"}");
        byte[] payload = new byte[100];
        commitOk("tok_retry4", handle, "hi", true, payload);
        // simulate the stage-TTL sweep removing the retained bytes, then a
        // bare commit-only retry (the caller re-sends nothing)
        new java.io.File(RuntimeEnvironment.getApplication().getFilesDir(),
                "stage/tok_retry4.bin").delete();
        Bundle meta = new Bundle();
        meta.putString(TransportContract.K_META, "{\"chat\":\"" + handle
                + "\",\"text\":\"hi\",\"filename\":\"x.bin\",\"mime\":\"application/octet-stream\",\"ts\":"
                + System.currentTimeMillis() + "}");
        Bundle c2 = call(provider, TransportContract.M_COMMIT, "tok_retry4", meta);
        assertEquals(TransportContract.E_EXPIRED, c2.getString(TransportContract.K_ERROR));
        // a retry that DOES re-supply identical bytes still re-verifies to the ref
        Bundle c3 = commitOk("tok_retry4", handle, "hi", true, payload);
        assertEquals("m80", c3.getString(TransportContract.K_REF));
    }

    @Test
    public void unknownMethodRejected() {
        grants.setEnabled(PKG, CERT_A, true, "/x");
        Bundle r = call(provider, "exec", null, null);
        assertEquals(TransportContract.E_UNKNOWN_METHOD, r.getString(TransportContract.K_ERROR));
    }
}
