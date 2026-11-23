"""No-network tests for the live /status snapshot."""
import os
import sys
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "app"))
import start  # noqa: E402


class _Status:
    def snapshot(self):
        return {"running": True, "served_chats": 0,
                "last_checkpoint_age_s": None}


class _Control:
    def __init__(self, n):
        self.runtimes = {i: object() for i in range(n)}


class _Service:
    def __init__(self):
        self.status = _Status()
        self.last_checkpoint = 0.0
        self.control = None


class StatusSnapshotTest(unittest.TestCase):
    def test_served_chats_live_when_control_up(self):
        s = _Service()
        s.control = _Control(3)
        self.assertEqual(start.status_snapshot(s)["served_chats"], 3)

    def test_served_chats_falls_back_before_control(self):
        s = _Service()
        self.assertEqual(start.status_snapshot(s)["served_chats"], 0)

    def test_checkpoint_age_computed(self):
        s = _Service()
        s.last_checkpoint = time.time() - 30
        age = start.status_snapshot(s)["last_checkpoint_age_s"]
        self.assertGreaterEqual(age, 29)

    def test_no_checkpoint_yet(self):
        s = _Service()
        self.assertIsNone(
            start.status_snapshot(s)["last_checkpoint_age_s"])


if __name__ == "__main__":
    unittest.main()
