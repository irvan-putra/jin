"""No-network tests for the stdlib S3 client against a local stub.

The stub verifies the SigV4 Authorization header shape (algorithm, scope,
signed headers) and serves a dict-backed bucket. It does not recompute the
signature - the wire-format shape is what is pinned here.
"""
import os
import re
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "app"))
import bucket as bucketmod  # noqa: E402

STORE = {}


class Stub(BaseHTTPRequestHandler):
    def _key(self):
        # path-style: /bucket/<key...>
        path = self.path.split("?")[0]
        parts = path.lstrip("/").split("/", 1)
        return parts[1] if len(parts) > 1 else ""

    def _auth_ok(self):
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("AWS4-HMAC-SHA256 Credential=test-ak/"):
            return False
        if "/test-region/s3/aws4_request" not in auth:
            return False
        if "SignedHeaders=host;x-amz-content-sha256;x-amz-date" not in auth:
            return False
        return bool(re.search(r"Signature=[0-9a-f]{64}$", auth))

    def _send(self, code, body=b""):
        self.send_response(code)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_PUT(self):
        if not self._auth_ok():
            return self._send(403, b"bad auth")
        n = int(self.headers.get("Content-Length", 0))
        STORE[self._key()] = self.rfile.read(n)
        self._send(200)

    def do_GET(self):
        if not self._auth_ok():
            return self._send(403, b"bad auth")
        if "list-type=2" in self.path:
            import urllib.parse
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            pfx = q.get("prefix", [""])[0]
            keys = sorted(k for k in STORE if k.startswith(pfx))
            body = ("<?xml version='1.0'?><ListBucketResult "
                    "xmlns='http://s3.amazonaws.com/doc/2006-03-01/'>"
                    + "".join("<Contents><Key>%s</Key></Contents>" % k
                              for k in keys)
                    + "<IsTruncated>false</IsTruncated>"
                    "</ListBucketResult>").encode()
            return self._send(200, body)
        k = self._key()
        if k not in STORE:
            return self._send(404, b"no such key")
        self._send(200, STORE[k])

    def do_DELETE(self):
        if not self._auth_ok():
            return self._send(403, b"bad auth")
        STORE.pop(self._key(), None)
        self._send(204)

    def log_message(self, *a):
        pass


class BucketTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), Stub)
        cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()

    def setUp(self):
        STORE.clear()
        # the stub speaks http; the client requires https, so test the
        # signing path by pointing the client at an https endpoint but
        # rewriting urlopen. Simpler: subclass for tests.
        client = bucketmod.Bucket("https://example.neo.id", "test-region",
                                  "test-bucket", "test-ak", "test-sk",
                                  prefix="jin")
        client.endpoint = "http://127.0.0.1:%d" % self.port
        client.host = "127.0.0.1:%d" % self.port
        orig = bucketmod.urllib.request.urlopen

        def http_open(req, timeout=60):
            req.full_url = req.full_url.replace("https://", "http://")
            return orig(req, timeout=timeout)

        bucketmod.urllib.request.urlopen = http_open
        self.addCleanup(setattr, bucketmod.urllib.request, "urlopen", orig)
        self.client = client

    def test_https_required(self):
        with self.assertRaises(bucketmod.BucketError):
            bucketmod.Bucket("http://insecure.example", "r", "b", "a", "s")

    def test_put_get_list_delete(self):
        self.client.put("incoming/a.xdc", b"payload-a")
        self.client.put("incoming/b.xdc", b"payload-b")
        self.client.put("results/x.json", b"{}")
        self.assertEqual(self.client.get("incoming/a.xdc"), b"payload-a")
        self.assertEqual(sorted(self.client.list("incoming")),
                         ["jin/incoming/a.xdc", "jin/incoming/b.xdc"])
        self.client.delete("incoming/a.xdc")
        self.assertIsNone(self.client.get("incoming/a.xdc"))


if __name__ == "__main__":
    unittest.main()
