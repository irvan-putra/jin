"""S3-compatible object storage client, stdlib only (AWS SigV4 over HTTPS).

NEO Object Storage is S3-compatible (official Biznet Gio docs; the known
endpoints are nos.<region>.neo.id on HTTPS/443). The endpoint, region,
bucket name and key pair are deploy-time config from the portal - never
hardcoded, never logged.

Path-style addressing (<endpoint>/<bucket>/<key>) is the default because it
is the most widely supported shape across S3-compatible services; set
JIN_S3_PATH_STYLE=0 if the provider insists on virtual-hosted style.

Only what the service needs: GET, PUT, DELETE, and ListObjectsV2 by prefix.
"""
import hashlib
import hmac
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone


class BucketError(Exception):
    pass


def _sha256_hex(b):
    return hashlib.sha256(b).hexdigest()


def _hmac(key, msg):
    return hmac.new(key, msg.encode(), hashlib.sha256).digest()


class Bucket:
    def __init__(self, endpoint, region, bucket, access_key, secret_key,
                 prefix="", path_style=True):
        if not endpoint.startswith("https://"):
            raise BucketError("endpoint must be https:// (http is refused)")
        self.endpoint = endpoint.rstrip("/")
        self.region = region
        self.bucket = bucket
        self.ak = access_key
        self.sk = secret_key
        self.prefix = prefix.strip("/")
        self.path_style = path_style
        self.host = urllib.parse.urlparse(self.endpoint).netloc

    # -- SigV4 ---------------------------------------------------------------

    def _signing_key(self, datestamp):
        k = _hmac(("AWS4" + self.sk).encode(), datestamp)
        k = _hmac(k, self.region)
        k = _hmac(k, "s3")
        return _hmac(k, "aws4_request")

    def _request(self, method, key, data=b"", query=None):
        # Canonical URI / host depend on the addressing style.
        enc_bucket = urllib.parse.quote(self.bucket, safe="")
        enc_key = "/".join(urllib.parse.quote(p, safe="")
                           for p in key.split("/"))
        if self.path_style:
            path = "/%s/%s" % (enc_bucket, enc_key) if key else "/%s/" % enc_bucket
            url_host = self.host
        else:
            path = "/%s" % enc_key if key else "/"
            url_host = "%s.%s" % (self.bucket, self.host)
        url = "https://%s%s" % (url_host, path)
        if query:
            url += "?" + urllib.parse.urlencode(query)

        now = datetime.now(timezone.utc)
        amz_date = now.strftime("%Y%m%dT%H%M%SZ")
        datestamp = now.strftime("%Y%m%d")
        payload_hash = _sha256_hex(data)

        canon_qs = ""
        if query:
            canon_qs = urllib.parse.urlencode(
                sorted((k, v) for k, v in query.items()))
        headers = {
            "host": url_host,
            "x-amz-content-sha256": payload_hash,
            "x-amz-date": amz_date,
        }
        signed = ";".join(sorted(headers))
        canon_headers = "".join("%s:%s\n" % (k, headers[k])
                                for k in sorted(headers))
        canon_req = "\n".join([method, path, canon_qs, canon_headers,
                               signed, payload_hash])
        scope = "%s/%s/s3/aws4_request" % (datestamp, self.region)
        sts = "\n".join(["AWS4-HMAC-SHA256", amz_date, scope,
                         _sha256_hex(canon_req.encode())])
        sig = hmac.new(self._signing_key(datestamp), sts.encode(),
                       hashlib.sha256).hexdigest()
        headers["Authorization"] = (
            "AWS4-HMAC-SHA256 Credential=%s/%s, SignedHeaders=%s, "
            "Signature=%s" % (self.ak, scope, signed, sig))
        req = urllib.request.Request(url, data=(data if data else None),
                                     method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            body = e.read()
            if e.code == 404 and method == "GET":
                return 404, body
            raise BucketError("S3 %s %s -> HTTP %d: %s"
                              % (method, key, e.code,
                                 body[:300].decode("utf-8", "replace")))
        except urllib.error.URLError as e:
            raise BucketError("S3 %s %s -> %s" % (method, key, e.reason))

    # -- operations ------------------------------------------------------------

    def _full(self, key):
        return "%s/%s" % (self.prefix, key) if self.prefix else key

    def get(self, key):
        """Object bytes, or None when absent."""
        status, body = self._request("GET", self._full(key))
        return None if status == 404 else body

    def put(self, key, data):
        self._request("PUT", self._full(key), data=data)

    def delete(self, key):
        self._request("DELETE", self._full(key))

    def list(self, prefix=""):
        """All keys under the service prefix + the given sub-prefix."""
        full = self._full(prefix)
        out = []
        token = None
        ns = "{http://s3.amazonaws.com/doc/2006-03-01/}"
        while True:
            query = {"list-type": "2", "prefix": full}
            if token:
                query["continuation-token"] = token
            _, body = self._request("GET", "", query=query)
            root = ET.fromstring(body)
            for c in root.findall(ns + "Contents"):
                out.append(c.find(ns + "Key").text)
            trunc = root.find(ns + "IsTruncated")
            if trunc is not None and trunc.text == "true":
                nxt = root.find(ns + "NextContinuationToken")
                token = nxt.text if nxt is not None else None
                if token:
                    continue
            break
        return out
