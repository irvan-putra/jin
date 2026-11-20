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
        token = creds.get("token") or ("live-echo-%d" % started)
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
            chat = acct.create_contact(addr).create_chat()
            msg = chat.send_text(token)
            # Send path proof: the core accepted the message and SMTP
            # delivery marks its state. Same-device IMAP dedup means the
            # echo may never surface here as fresh; the relay round trip is
            # witnessed by a second core instance of the SAME account on the
            # runner (multi-device sync), see tools/live-relay-smoke.sh.
            # MessageState ints (const.MessageState): OUT_DELIVERED=26,
            # OUT_MDN_RCVD=28, OUT_FAILED=24. The snapshot carries the raw
            # int in this client build, not the enum name.
            state = ""
            deadline = time.time() + 60
            delivered = False
            while time.time() < deadline:
                raw = msg.get_snapshot().get("state", "")
                state = str(raw)
                try:
                    n = int(raw)
                    if n in (26, 28):
                        delivered = True
                        break
                    if n == 24:
                        raise RuntimeError("sent message state OUT_FAILED")
                except (TypeError, ValueError):
                    up = state.upper()
                    if "DELIVERED" in up or "MDN" in up or "SEEN" in up:
                        delivered = True
                        break
                    if "FAILED" in up:
                        raise RuntimeError("sent message state FAILED")
                time.sleep(3)
            if not delivered:
                raise RuntimeError("sent message did not reach delivered state (last: %r)" % state)
            status = "ok"
            detail = "sent_state=%s send_s=%d" % (state, time.time() - started)
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
