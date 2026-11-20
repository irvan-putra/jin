"""Runner-side Bidik device for the live capture-route proof.

Signs a runner core into the throwaway B account and plays the Bidik end
of the wire against the bot (A) running on the emulator:

  1. sends a plain hello so A's core creates the DM chat;
  2. reads A's chat id for that DM from the pulled accounts DB and pins
     a capture route (queue_id -> that chat) into A's bot_state.json, the
     same pin a served-chat "capture" command would have created (a real
     command needs a third identity, which the approved scope does not
     include - peer and requester must differ by design);
  3. sends the capture-result JSON and expects the status line back;
  4. sends the declared PNG with its queue_id caption and expects the
     exact bytes forwarded back with the same caption;
  5. sends a stale-caption PNG (unknown queue) and expects NO forward.

Credentials come only from the environment; nothing is printed but
outcomes. Synthetic content only.
"""

import hashlib
import json
import os
import sqlite3
import subprocess
import sys
import threading
import tempfile
import time
import urllib.parse

JIN = "com.vanipy.jin"
QID = "liveq"
PAGE = "p1"
NAME = "shot.png"
STALE_QID = "oldqueue"

A = os.environ["JIN_RELAY_A_ADDR"]
B = os.environ["JIN_RELAY_B_ADDR"]


def adb(*args, stdin=None, check=True):
    r = subprocess.run(["adb"] + list(args), capture_output=True, input=stdin)
    if check and r.returncode:
        raise RuntimeError("adb %s: %s" % (args[0], r.stderr.decode()[:200]))
    return r.stdout


def pull_state():
    return json.loads(adb("shell", "run-as", JIN, "cat", "files/bot_state.json"))


def push_state(st):
    adb("shell", "run-as %s sh -c 'cat > files/bot_state.json'" % JIN,
        stdin=json.dumps(st).encode())


def incoming(chat, addr, since_id=0):
    out = []
    for m in chat.get_messages()[-40:]:
        try:
            snap = m.get_snapshot()
        except Exception:
            continue
        if snap.get("id", 0) <= since_id:
            continue
        from deltachat_rpc_client import Contact
        try:
            snd = Contact(chat.account, snap.get("from_id")).get_snapshot().get("addr")
        except Exception:
            continue
        if snd and snd.lower() == addr.lower():
            out.append((snap, m))
    return out


def main():
    from deltachat_rpc_client import DeltaChat, Rpc
    workdir = tempfile.mkdtemp(prefix="live-route-b-")
    rpc = Rpc(accounts_dir=os.path.join(workdir, "accounts"))
    rpc.start()
    try:
        dc = DeltaChat(rpc)
        acct = dc.add_account()
        uri = "dclogin:" + B + "?" + urllib.parse.urlencode(
            {"p": os.environ["JIN_RELAY_B_PASSWORD"], "v": "1"})
        acct.init_transports(uri)
        if not acct.is_configured():
            raise RuntimeError("B account did not configure")
        acct.start_io()

        # 1. key exchange: chatmail requires encrypted mail between
        #    accounts, so B must hold A's autocrypt key before ANY send
        #    (the first attempt failed here: "e2e encryption unavailable").
        #    The running bot emits its setup-contact QR through the
        #    owner-only invite watcher; B joins it and the cores run the
        #    SecureJoin handshake between themselves.
        adb("shell", "run-as", JIN, "touch", "files/INVITE_REQ")
        deadline = time.time() + 90
        while not adb("shell", "run-as", JIN, "cat", "files/INVITE_DONE",
                      check=False).strip():
            if time.time() > deadline:
                raise RuntimeError("bot did not emit its invite QR")
            time.sleep(2)
        qr = adb("shell", "run-as", JIN, "cat", "files/invite.txt").decode().strip()
        adb("shell", "run-as", JIN, "rm", "-f",
            "files/invite.txt", "files/invite.svg", "files/INVITE_DONE",
            check=False)
        if not qr:
            raise RuntimeError("invite QR file was empty")
        # Use the chat RETURNED by secure_join: it is bound to the
        # contact that holds A's key. create_contact(A).create_chat() can
        # bind a keyless shadow contact when the address form differs,
        # and the second live run died exactly there ("e2e encryption
        # unavailable") while handshake messages to A encrypted fine.
        chat = acct.secure_join(qr)
        joined = []
        waiter = threading.Thread(
            target=lambda: (acct.wait_for_securejoin_joiner_success(),
                            joined.append(1)), daemon=True)
        waiter.start()
        waiter.join(240)
        if not joined:
            raise RuntimeError("securejoin handshake did not finish in 240s")
        print("live route: securejoin handshake complete (B holds A key)")

        # 2. hello: creates normal traffic in the fresh chat; consumed by
        #    the peer branch with no pins (no action taken by the bot).
        #    One bounded settle-retry separates a key-visibility race
        #    from a hard chat/contact identity defect.
        try:
            chat.send_text("hello live route")
        except Exception:
            time.sleep(20)
            chat = acct.create_contact(A).create_chat()
            chat.send_text("hello live route")
        time.sleep(20)

        # 3. pin the route into A's state (chat id discovered from A's db)
        entry = adb("shell", "run-as", JIN, "ls", "files/accounts").decode().split()[0]
        base = "files/accounts/%s" % entry
        tmp = os.path.join(workdir, "a.db")
        with open(tmp, "wb") as f:
            f.write(subprocess.run(
                ["adb", "exec-out", "run-as", JIN, "cat", base + "/dc.db"],
                capture_output=True, check=True).stdout)
        for suf in ("-wal", "-shm"):
            r = subprocess.run(
                ["adb", "exec-out", "run-as", JIN, "cat", base + "/dc.db" + suf],
                capture_output=True)
            if r.returncode == 0 and r.stdout:
                with open(tmp + suf, "wb") as f:
                    f.write(r.stdout)
        con = sqlite3.connect(tmp)
        ids = [r[0] for r in con.execute("SELECT id FROM chats WHERE id > 9")]
        con.close()
        if len(ids) != 1:
            raise RuntimeError("expected exactly one DM chat on A, got %r" % ids)
        chat_id = ids[0]
        st = pull_state()
        routes = st.get("capture_routes") or {}
        routes[QID] = chat_id
        st["capture_routes"] = routes
        push_state(st)
        st2 = pull_state()
        if (st2.get("capture_routes") or {}).get(QID) != chat_id:
            raise RuntimeError("route pin did not land in bot state")
        print("live route: pinned %s -> chat %d" % (QID, chat_id))

        # 4. result JSON -> status line forwarded back
        result = {"type": "capture-result", "queue_id": QID, "page_id": PAGE,
                  "captures": [NAME], "status": "ok", "load_ms": 500}
        chat.send_text(json.dumps(result))
        deadline = time.time() + 150
        status_ok = False
        while time.time() < deadline and not status_ok:
            for snap, _ in incoming(chat, A):
                if (snap.get("text") or "").startswith(PAGE + ": ok"):
                    status_ok = True
                    break
            if not status_ok:
                time.sleep(4)
        if not status_ok:
            raise RuntimeError("status line was not routed back within 150s")
        print("live route: status line routed A->B")

        # 5. declared PNG -> forwarded back with identical bytes + caption
        png = bytes.fromhex(
            "89504e470d0a1a0a0000000d494844520000000100000001080600000"
            "01f15c4890000000d49444154789c626001000000ffff030000060005"
            "57bfabd40000000049454e44ae426082")
        path = os.path.join(workdir, NAME)
        with open(path, "wb") as f:
            f.write(png)
        caption = "capture-result-file:%s:%s:%s" % (QID, PAGE, NAME)
        chat.send_message(text=caption, file=path)
        deadline = time.time() + 180
        got = None
        while time.time() < deadline and got is None:
            for snap, m in incoming(chat, A):
                if (snap.get("text") or "") == caption:
                    got = snap
                    break
            if got is None:
                time.sleep(4)
        if got is None:
            raise RuntimeError("capture file was not routed back within 180s")
        fpath = got.get("file")
        if not fpath or not os.path.exists(fpath):
            raise RuntimeError("forwarded message has no downloaded file")
        if hashlib.sha256(open(fpath, "rb").read()).digest() != \
                hashlib.sha256(png).digest():
            raise RuntimeError("forwarded bytes differ from sent bytes")
        print("live route: capture file routed A->B with identical bytes")

        # 6. stale caption (unknown queue) must NOT be forwarded
        spath = os.path.join(workdir, "stale.png")
        with open(spath, "wb") as f:
            f.write(b"stale-bytes-not-a-png")
        scap = "capture-result-file:%s:%s:%s" % (STALE_QID, PAGE, NAME)
        chat.send_message(text=scap, file=spath)
        deadline = time.time() + 45
        while time.time() < deadline:
            for snap, _ in incoming(chat, A):
                if (snap.get("text") or "") == scap:
                    raise RuntimeError("STALE caption was forwarded - refusal broken")
            time.sleep(4)
        print("live route: stale-caption refusal held over the wire")
        return 0
    finally:
        try:
            rpc.close()
        except Exception:
            pass


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as e:
        print("live route FAIL: %s: %s" % (type(e).__name__, str(e)[:300]),
              file=sys.stderr)
        raise SystemExit(1)
