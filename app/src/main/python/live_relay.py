"""One-shot live-relay sign-in proof for CI (mode 'selfecho').

Reads a credential file written by the workflow (files_dir/live_login.json,
keys: addr, mail_pw), signs the vendored core into that EXISTING account via
a dclogin: transport URI, then proves a real relay round trip by sending a
message to the account's own address and waiting for the incoming copy the
relay delivers back (the same path multi-device sync uses).

The credential file is deleted in a finally block, before any result is
written. The result file (LIVE_ECHO_RESULT) contains only a status string
and timings - never credentials. Nothing is printed to logcat beyond the
account address, which the workflow already knows.
"""

import json
import os
import time
import urllib.parse


def _rpc(native_lib_dir, files_dir):
    from deltachat_rpc_client import DeltaChat, Rpc
    rpc = Rpc(accounts_dir=os.path.join(files_dir, "accounts"),
              rpc_server_path=os.path.join(native_lib_dir, "librpcsrv.so"))
    rpc.start()
    return rpc, DeltaChat(rpc)


def run(native_lib_dir, files_dir):
    cred_path = os.path.join(files_dir, "live_login.json")
    result_path = os.path.join(files_dir, "LIVE_ECHO_RESULT")
    started = time.time()
    status = "failed"
    detail = ""
    try:
        with open(cred_path) as f:
            creds = json.load(f)
        addr = creds["addr"]
        uri = "dclogin:" + addr + "?" + urllib.parse.urlencode(
            {"p": creds["mail_pw"], "v": "1"})
        rpc, dc = _rpc(native_lib_dir, files_dir)
        try:
            acct = dc.add_account()
            acct.init_transports(uri)
            if not acct.is_configured():
                raise RuntimeError("dclogin transport did not configure")
            t = acct.list_transports()[0]
            if t.get("addr") != addr:
                raise RuntimeError("configured transport address mismatch")
            acct.start_io()
            token = "live-echo-%d" % started
            chat = acct.create_contact(addr).create_chat()
            chat.send_text(token)
            ev = acct.wait_for_incoming_msg_event(timeout=120)
            if ev is None:
                raise RuntimeError("no incoming echo within 120s")
            status = "ok"
            detail = "round_trip_s=%d" % (time.time() - started)
        finally:
            rpc.close()
    except Exception as e:
        detail = type(e).__name__ + ": " + str(e)[:200]
    finally:
        try:
            os.remove(cred_path)
        except OSError:
            pass
        with open(result_path, "w") as f:
            json.dump({"status": status, "detail": detail}, f)
    if status != "ok":
        raise RuntimeError("live relay selfecho failed: " + detail)
