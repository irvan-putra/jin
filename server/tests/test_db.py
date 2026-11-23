"""No-network tests for the state database layer (sqlite driver)."""
import os
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "app"))
import db as dbmod  # noqa: E402


class DbTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.db = dbmod.Db("sqlite:///" + os.path.join(self.tmp, "s.db"))

    def tearDown(self):
        self.db.close()

    def test_bundle_roundtrip_and_prune(self):
        for i in range(5):
            self.db.put_bundle(b"bundle-%d" % i, keep=3)
        self.assertEqual(self.db.bundle_count(), 3)
        latest = self.db.latest_bundle()
        self.assertEqual(latest["bytes"], b"bundle-4")
        self.assertEqual(len(latest["sha256"]), 64)

    def test_latest_bundle_empty(self):
        self.assertIsNone(self.db.latest_bundle())

    def test_lease_single_holder(self):
        self.assertTrue(self.db.acquire_lease("a", 60))
        self.assertFalse(self.db.acquire_lease("b", 60))
        self.assertTrue(self.db.renew_lease("a", 60))
        self.assertFalse(self.db.renew_lease("b", 60))
        self.db.release_lease("a")
        self.assertTrue(self.db.acquire_lease("b", 60))

    def test_lease_expiry_takeover(self):
        self.assertTrue(self.db.acquire_lease("a", -1))  # already expired
        self.assertTrue(self.db.acquire_lease("b", 60))

    def test_meta(self):
        self.assertIsNone(self.db.get_meta("k"))
        self.db.put_meta("k", "v1")
        self.db.put_meta("k", "v2")
        self.assertEqual(self.db.get_meta("k"), "v2")

    def test_bad_scheme_refused(self):
        with self.assertRaises(dbmod.DbError):
            dbmod.Db("mysql://example.invalid/db")


if __name__ == "__main__":
    unittest.main()
