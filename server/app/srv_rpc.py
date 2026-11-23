"""Server-side rpc factory.

On the phone, bot_runtime._rpc loads the rpc server from the app's native
lib dir (Chaquopy). On the server the deltachat-rpc-server wheel installs
the binary on PATH, so the client library finds it itself and no path is
passed. The factory keeps bot_runtime's (native_lib_dir, files_dir) shape
so it can replace bot_runtime._rpc without editing that proven file.
"""
import os


def make_rpc(native_lib_dir, files_dir):
    from deltachat_rpc_client import DeltaChat, Rpc
    rpc = Rpc(accounts_dir=os.path.join(files_dir, "accounts"))
    rpc.start()
    return rpc, DeltaChat(rpc)


def patch_bot_runtime(bot_runtime):
    """Route the proven phone runtime at the server rpc factory."""
    bot_runtime._rpc = make_rpc
    return bot_runtime
