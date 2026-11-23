"""No-network tests for the server-side bot_runtime patches."""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "app"))
import bot_runtime  # noqa: E402
import srv_rpc  # noqa: E402


class PatchTest(unittest.TestCase):
    def test_rpc_factory_swapped(self):
        srv_rpc.patch_bot_runtime(bot_runtime)
        self.assertIs(bot_runtime._rpc, srv_rpc.make_rpc)

    def test_capture_route_disabled_when_module_absent(self):
        # this package deliberately ships without capture_route.py
        import importlib.util
        self.assertIsNone(importlib.util.find_spec("capture_route"))
        srv_rpc.patch_bot_runtime(bot_runtime)
        # never consumes, never raises - the message falls through to
        # normal served-chat handling in on_new_message
        ctl = bot_runtime.ChatControl.__new__(bot_runtime.ChatControl)
        self.assertFalse(ctl._capture_route_handle(None))

    def test_patch_is_idempotent(self):
        srv_rpc.patch_bot_runtime(bot_runtime)
        srv_rpc.patch_bot_runtime(bot_runtime)
        ctl = bot_runtime.ChatControl.__new__(bot_runtime.ChatControl)
        self.assertFalse(ctl._capture_route_handle(None))


if __name__ == "__main__":
    unittest.main()
