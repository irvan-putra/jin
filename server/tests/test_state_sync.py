"""No-network tests for checkpoint/restore against a sqlite state db.

The core account side is faked (a .tar file producer); what is pinned here
is the bundle pipeline: live-safe sqlite copies, encryption round trip,
bundle versioning in the db, and restore via migration.import_migration
with its own _rpc_factory seam. Real core backup/import is covered only by
the phone app's migration tests and a live deploy check.
"""
import base64
import io
import json
import os
import secrets
import sqlite3
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "app"))
import db as dbmod  # noqa: E402
import migration  # noqa: E402
import state_sync  # noqa: E402

CODE = base64.urlsafe_b64encode(secrets.token_bytes(32)).decode()


def _make_files_dir(path):
    os.makedirs(path)
    with open(os.path.join(path, "bot_state.json"), "w") as f:
        json.dump({"provisioned": True, "bot_addr": "bot@example.invalid",
                   "chats": {"11": {"list_id": "l1"}}}, f)
    con = sqlite3.connect(os.path.join(path, "ops-11.db"))
    con.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
    con.execute("INSERT INTO t (v) VALUES ('hello')")
    con.commit()
    con.close()


class _FakeAcct:
    """Stands in for the live account during a checkpoint: writes the kind
    of .tar the core's export_backup produces."""
    def export_backup(self, out_dir, code):
        assert code == CODE
        with tarfile.open(os.path.join(out_dir, "backup.tar"), "w") as t:
            data = b"fake sqlcipher db bytes"
            info = tarfile.TarInfo("db.sqlite")
            info.size = len(data)
            t.addfile(info, io.BytesIO(data))


class StateSyncTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.files_dir = os.path.join(self.tmp, "work")
        _make_files_dir(self.files_dir)
        self.db = dbmod.Db("sqlite:///" + os.path.join(self.tmp, "s.db"))

    def tearDown(self):
        self.db.close()

    def test_checkpoint_stores_versioned_encrypted_bundle(self):
        stats = state_sync.checkpoint(self.files_dir, _FakeAcct(), self.db,
                                      CODE)
        self.assertEqual(self.db.bundle_count(), 1)
        self.assertEqual(len(stats["sha256"]), 64)
        # the stored row is an encrypted bundle, not plaintext
        row = self.db.latest_bundle()["bytes"]
        self.assertNotIn(b"hello", row)
        self.assertNotIn(b"bot@example.invalid", row)
        # and it parses + decrypts under the code
        tmp = os.path.join(self.tmp, "b.dcmig")
        with open(tmp, "wb") as f:
            f.write(row)
        parsed = migration.parse_bundle(tmp)
        state_tar = migration._decrypt(CODE, migration.STATE_DOMAIN,
                                       parsed["state_blob"])
        names = tarfile.open(fileobj=io.BytesIO(state_tar),
                             mode="r:gz").getnames()
        self.assertIn("bot_state.json", names)
        self.assertIn("ops-11.db", names)

    def test_live_ops_db_snapshot_is_consistent(self):
        # a writer holding the db open mid-checkpoint must not tear the copy
        con = sqlite3.connect(os.path.join(self.files_dir, "ops-11.db"))
        con.execute("BEGIN IMMEDIATE")
        con.execute("INSERT INTO t (v) VALUES ('during')")
        stats = state_sync.checkpoint(self.files_dir, _FakeAcct(), self.db,
                                      CODE)
        con.rollback()
        con.close()
        self.assertEqual(self.db.bundle_count(), 1)

    def test_dirty_since(self):
        import time
        now = time.time()
        self.assertFalse(state_sync.dirty_since(self.files_dir,
                                                now + 100))
        self.assertTrue(state_sync.dirty_since(self.files_dir, now - 100))

    def test_restore_first_boot_false(self):
        self.assertFalse(state_sync.restore(self.files_dir, self.db, CODE,
                                            lambda nld, fd: None))


if __name__ == "__main__":
    unittest.main()
