"""No-network tests for entrypoint config validation (fail-closed)."""
import base64
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "app"))
import start  # noqa: E402

CODE = base64.urlsafe_b64encode(b"x" * 32).decode()


def env(**kw):
    e = {"JIN_TRANSFER_CODE": CODE, "JIN_ALLOW_EPHEMERAL_DB": "1"}
    e.update(kw)
    return e


class ConfigTest(unittest.TestCase):
    def test_defaults_ok(self):
        c = start.Config(env())
        self.assertEqual(c.relay, "nine.testrun.org")
        self.assertEqual(c.port, 8080)
        self.assertIsNone(c.s3)

    def test_missing_code_refused(self):
        with self.assertRaises(start.ConfigError):
            start.Config({"JIN_ALLOW_EPHEMERAL_DB": "1"})

    def test_short_code_refused(self):
        with self.assertRaises(start.ConfigError):
            start.Config(env(JIN_TRANSFER_CODE="tooshort"))

    def test_sqlite_dsn_refused_without_opt_in(self):
        e = env()
        del e["JIN_ALLOW_EPHEMERAL_DB"]
        with self.assertRaises(start.ConfigError):
            start.Config(e)

    def test_pg_dsn_accepted(self):
        c = start.Config(env(
            JIN_DB_DSN="postgres://u:p@db.internal:5432/jin"))
        self.assertTrue(c.db_dsn.startswith("postgres://"))

    def test_bucket_all_or_none(self):
        with self.assertRaises(start.ConfigError):
            start.Config(env(JIN_S3_ENDPOINT="https://nos.jkt-1.neo.id"))
        c = start.Config(env(
            JIN_S3_ENDPOINT="https://nos.jkt-1.neo.id",
            JIN_S3_REGION="jkt-1", JIN_S3_BUCKET="b",
            JIN_S3_ACCESS_KEY="a", JIN_S3_SECRET_KEY="s"))
        self.assertIsNotNone(c.s3)

    def test_signing_region_override_wins_over_empty_binding_region(self):
        # the NEO typed bucket binding injects JIN_S3_REGION as ""
        c = start.Config(env(
            JIN_S3_ENDPOINT="https://nos.wjv-1.neo.id",
            JIN_S3_REGION="",
            JIN_S3_SIGNING_REGION="WJV-1",
            JIN_S3_BUCKET="b", JIN_S3_ACCESS_KEY="a", JIN_S3_SECRET_KEY="s"))
        self.assertEqual(c.s3["REGION"], "WJV-1")

    def test_region_falls_back_when_no_override(self):
        c = start.Config(env(
            JIN_S3_ENDPOINT="https://nos.jkt-1.neo.id",
            JIN_S3_REGION="jkt-1", JIN_S3_BUCKET="b",
            JIN_S3_ACCESS_KEY="a", JIN_S3_SECRET_KEY="s"))
        self.assertEqual(c.s3["REGION"], "jkt-1")

    def test_http_bucket_endpoint_refused(self):
        from bucket import Bucket, BucketError
        with self.assertRaises(BucketError):
            Bucket("http://nos.jkt-1.neo.id", "r", "b", "a", "s")


if __name__ == "__main__":
    unittest.main()
