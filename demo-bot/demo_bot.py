"""Jin demo bot: a throwaway DeltaChat bot that runs in short scheduled
windows. Modes:
  provision  create ONE new chatmail account (refuses if one exists)
  run        start, fetch, answer new messages, send, stop
Account keys and lists live in STATE_DIR (sealed between runs by statebox).
Contact requests are never accepted; only chats created by scanning the
bot's invite are served. No chat can administer anything."""
import json, os, sys, time, logging
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from commands import handle

log = logging.getLogger("demo-bot")
RELAY = os.environ.get("DEMO_RELAY", "nine.testrun.org")
MAX_REPLIES_PER_CHAT_PER_RUN = 5
MAX_REPLIES_PER_RUN = 60


def _open(state_dir):
    from deltachat_rpc_client import DeltaChat, Rpc
    rpc = Rpc(accounts_dir=os.path.join(state_dir, "accounts"))
    rpc.start()
    return rpc, DeltaChat(rpc)


def _addr(acct):
    for t in acct.list_transports() or []:
        if (t or {}).get("addr"):
            return t["addr"]
    return acct.get_config("addr")


def provision(state_dir):
    rpc, dc = _open(state_dir)
    try:
        if dc.get_all_accounts():
            raise SystemExit("an account already exists; refusing to create another")
        acct = dc.add_account()
        acct.add_transport_from_qr("dcaccount:" + RELAY)
        if not acct.is_configured():
            raise SystemExit("relay did not configure the account")
        acct.set_config("displayname", "Jin demo bot (not private)")
        acct.set_config("bot", "1")
        print("ADDR", _addr(acct))
        print("QR", acct.get_qr_code())
    finally:
        rpc.close()


def _lists_path(state_dir):
    return os.path.join(state_dir, "lists.json")


def run(state_dir, min_s, max_s):
    from deltachat_rpc_client import Chat
    rpc, dc = _open(state_dir)
    sent = []
    try:
        accts = [a for a in dc.get_all_accounts() if a.is_configured()]
        if not accts:
            raise SystemExit("no configured account; run provision first")
        acct = accts[0]
        try:
            lists = json.load(open(_lists_path(state_dir)))
        except (OSError, ValueError):
            lists = {}
        acct.start_io()
        start = time.time()
        last_activity = start
        per_chat = {}
        total = 0
        while True:
            now = time.time()
            if now - start >= max_s or (now - start >= min_s and now - last_activity >= 30):
                break
            for msg in sorted(acct.get_fresh_messages(), key=lambda m: m.id):
                last_activity = time.time()
                snap = msg.get_snapshot()
                chat = Chat(acct, snap.chat_id)
                try:
                    basic = chat.get_basic_snapshot()
                except Exception:
                    msg.mark_seen(); continue
                if basic.get("is_contact_request") or basic.get("is_device_chat") or snap.get("is_info") or snap.get("from_id") == 1:
                    msg.mark_seen(); continue  # never accept requests; ignore system messages
                key = str(snap.chat_id)
                if per_chat.get(key, 0) >= MAX_REPLIES_PER_CHAT_PER_RUN or total >= MAX_REPLIES_PER_RUN:
                    msg.mark_seen(); continue
                reply = handle(lists, key, snap.get("text") or "")
                msg.mark_seen()
                if reply:
                    sent.append(chat.send_text(reply))
                    per_chat[key] = per_chat.get(key, 0) + 1
                    total += 1
                    last_activity = time.time()
            time.sleep(2)
        json.dump(lists, open(_lists_path(state_dir), "w"))
        # let outgoing mail leave before stopping
        deadline = time.time() + 40
        while sent and time.time() < deadline:
            states = [m.get_snapshot().get("state") for m in sent]
            if all(s in (26, 28, 24) for s in states):  # delivered, read, failed
                break
            time.sleep(2)
        print("replies sent:", total)
        acct.stop_io()
    finally:
        rpc.close()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    mode, state = sys.argv[1], os.environ.get("STATE_DIR", "./state")
    os.makedirs(state, exist_ok=True)
    if mode == "provision":
        provision(state)
    elif mode == "run":
        run(state, int(os.environ.get("MIN_S", "45")), int(os.environ.get("MAX_S", "150")))
    else:
        raise SystemExit("usage: demo_bot.py provision|run")
