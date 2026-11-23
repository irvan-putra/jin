"""Server-side rpc factory.

On the phone, bot_runtime._rpc loads the rpc server from the app's native
lib dir (Chaquopy). On the server the deltachat-rpc-server wheel installs
the binary on PATH, so the client library finds it itself and no path is
passed. The factory keeps bot_runtime's (native_lib_dir, files_dir) shape
so it can replace bot_runtime._rpc without editing that proven file.
"""
import importlib.util
import logging
import os

log = logging.getLogger("jin.srv_rpc")


def make_rpc(native_lib_dir, files_dir):
    from deltachat_rpc_client import DeltaChat, Rpc
    rpc = Rpc(accounts_dir=os.path.join(files_dir, "accounts"))
    rpc.start()
    return rpc, DeltaChat(rpc)


def patch_bot_runtime(bot_runtime):
    """Route the proven phone runtime at the server rpc factory, and
    disable the phone-only capture route when its module is absent.

    The bidik capture-route integration is parked and capture_route.py is
    deliberately not part of this package. bot_runtime.ChatControl.
    _capture_route_handle imports it per message, which would kill every
    incoming message with ModuleNotFoundError. When the module is absent,
    replace the handler with a no-op that never consumes a message, so
    normal chat handling (served chats, contact-request refusal) is
    unchanged. bot_runtime.py itself stays byte-identical to the phone."""
    bot_runtime._rpc = make_rpc
    if importlib.util.find_spec("capture_route") is None:
        def _no_capture_route(self, event):
            return False
        bot_runtime.ChatControl._capture_route_handle = _no_capture_route
        log.info("capture_route module absent - parked phone-only capture "
                 "route disabled for this service")
    return bot_runtime
