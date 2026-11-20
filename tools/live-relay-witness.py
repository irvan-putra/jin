"""Runner-side relay witness for live-relay-smoke (no new account).

Signs a second core instance into the SAME throwaway account the Android
app uses (multi-device semantics: one account, two cores) and waits for the
app's self-chat message to arrive through the relay via IMAP sync. That
arrival is the round-trip proof the app itself cannot give: Delta Chat
deduplicates a device's own messages on that device, so the sender never
sees its own echo as fresh.

Credentials come only from the environment (GitHub secrets); nothing is
printed but the outcome. Writes WITNESS_RESULT (status + timing only).
"""

import json
import os
import time
import urllib.parse

def main():
    addr = os.environ["JIN_RELAY_C_ADDR"]
    pw = os.environ["JIN_RELAY_C_PASSWORD"]
    token = os.environ["LIVE_ECHO_TOKEN"]
    from deltachat_rpc_client import DeltaChat, Rpc
    rpc = Rpc(accounts_dir="/tmp/witness-accounts")
    rpc.start()
    status, detail = "failed", ""
    try:
        dc = DeltaChat(rpc)
        acct = dc.add_account()
        uri = "dclogin:" + addr + "?" + urllib.parse.urlencode({"p": pw, "v": "1"})
        acct.init_transports(uri)
        if not acct.is_configured():
            raise RuntimeError("witness did not configure")
        acct.start_io()
        started = time.time()
        deadline = started + 180
        found = False
        while time.time() < deadline and not found:
            for m in acct.get_fresh_messages():
                snap = m.get_snapshot()
                if token in (snap.get("text") or ""):
                    found = True
                    break
            if not found:
                time.sleep(4)
        if not found:
            raise RuntimeError("token not witnessed within 180s")
        status = "ok"
        detail = "witnessed_s=%d" % (time.time() - started)
    except Exception as e:
        detail = type(e).__name__ + ": " + str(e)[:200]
    finally:
        try:
            rpc.close()
        except Exception:
            pass
        with open("WITNESS_RESULT", "w") as f:
            json.dump({"status": status, "detail": detail}, f)
    return 0 if status == "ok" else 1

if __name__ == "__main__":
    raise SystemExit(main())
