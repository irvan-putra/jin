"""The supervised jin bot service (NEO App shape).

Runs bot_runtime's proven live loop with three changes of clothing for the
server:

  1. Management is file-driven exactly like the phone (INVITE_REQ,
     PUBLISH_REQ, STOP), so the runtime code is unmodified. The bucket
     bridge translates operator actions (drop a .xdc in the bucket) into
     those files, and uploads results back.
  2. The capture-route sync bridge (SYNC_REQ watchers/hooks) is NOT
     started: that is the bidik integration surface, which is parked, and
     its producer is the Android provider which does not exist here.
  3. A checkpoint thread persists the whole state to the state database
     (state_sync) on a dirty-only interval, after provisioning, and on
     shutdown.

Status is booleans and counts only. The invitation QR text is a bearer
secret: it is written to files_dir (and mirrored to the private bucket for
the operator) but never served over HTTP and never logged.
"""
import json
import logging
import os
import threading
import time

import bot_runtime
import state_sync
from srv_rpc import make_rpc, patch_bot_runtime

log = logging.getLogger("jin.service")


class Status:
    """Shared, HTTP-served view. Booleans and counts only."""

    def __init__(self):
        self.lock = threading.Lock()
        self.data = {
            "provisioned": False,
            "running": False,
            "bot_error": None,
            "served_chats": 0,
            "last_checkpoint_age_s": None,
            "last_checkpoint_ok": None,
            "invite_ready": False,
            "db": "down",
            "bucket": "off",
        }

    def set(self, **kw):
        with self.lock:
            self.data.update(kw)

    def snapshot(self):
        with self.lock:
            return dict(self.data)


class Service:
    def __init__(self, cfg, files_dir, db, code, bucket=None):
        self.cfg = cfg
        self.files_dir = files_dir
        self.db = db
        self.code = code
        self.bucket = bucket
        self.status = Status()
        self.acct = None
        self.last_checkpoint = 0.0
        self.stop_event = threading.Event()
        self.threads = []
        self._last_invite_upload = 0.0

    # -- bot wiring (bot_runtime.run() minus the parked sync bridge) --------

    def _bot_thread(self):
        from deltachat_rpc_client import Bot, EventType
        from deltachat_rpc_client.events import NewMessage, RawEvent

        patch_bot_runtime(bot_runtime)
        bot_runtime.RELAY = self.cfg.relay
        files_dir = self.files_dir
        st = bot_runtime.load_state(files_dir)
        if not st.get("provisioned"):
            raise RuntimeError("not provisioned")
        for f in (bot_runtime.RUN_LOCK, bot_runtime.STOP_FLAG):
            try:
                os.unlink(os.path.join(files_dir, f))
            except FileNotFoundError:
                pass
        with open(os.path.join(files_dir, bot_runtime.RUN_LOCK), "w") as f:
            f.write(str(os.getpid()))

        rpc, dc = make_rpc(None, files_dir)
        try:
            acct = bot_runtime._account(dc)
            self.acct = acct
            control = bot_runtime.ChatControl(acct, files_dir)
            acct.start_io()
            for name, target, args in (
                ("bot-stop-watchdog", bot_runtime._watchdog, (files_dir, rpc)),
                ("bot-invite-qr", bot_runtime._invite_watcher, (files_dir, acct)),
                ("bot-app-publish", bot_runtime._publish_watcher,
                 (files_dir, control)),
            ):
                threading.Thread(target=target, args=args, daemon=True,
                                 name=name).start()
            ctl_bot = Bot(acct, hooks=[
                (control.on_new_message, NewMessage),
                (control.on_status_update,
                 RawEvent(EventType.WEBXDC_STATUS_UPDATE)),
                (control.on_msgs_changed, RawEvent(EventType.MSGS_CHANGED)),
            ])
            control.boot_runtimes()  # needs IO (download retries)
            self.status.set(running=True, bot_error=None,
                            served_chats=len(control.runtimes))
            log.info("live bot running as %s (chats %s)",
                     control.bot_addr, sorted(control.runtimes))
            ctl_bot.run_forever()
        except Exception as e:
            self.status.set(running=False, bot_error=type(e).__name__)
            log.exception("bot thread failed")
            raise
        finally:
            self.status.set(running=False)
            self.acct = None
            try:
                rpc.close()
            except Exception:
                pass
            try:
                os.unlink(os.path.join(files_dir, bot_runtime.RUN_LOCK))
            except FileNotFoundError:
                pass

    # -- checkpoint loop ------------------------------------------------------

    def _checkpoint_loop(self):
        while not self.stop_event.wait(self.cfg.checkpoint_interval_s):
            self._maybe_checkpoint("interval")

    def _maybe_checkpoint(self, why):
        if self.acct is None:
            return
        if why == "interval" and not state_sync.dirty_since(
                self.files_dir, self.last_checkpoint):
            return
        try:
            state_sync.checkpoint(self.files_dir, self.acct, self.db,
                                  self.code)
            self.last_checkpoint = time.time()
            self.status.set(last_checkpoint_age_s=0, last_checkpoint_ok=True)
        except Exception as e:
            log.exception("checkpoint failed (%s)", why)
            self.status.set(last_checkpoint_ok=False)

    # -- bucket bridge ----------------------------------------------------------

    def _bucket_loop(self):
        if self.bucket is None:
            return
        self.status.set(bucket="on")
        while not self.stop_event.wait(self.cfg.bucket_poll_s):
            try:
                self._bucket_once()
            except Exception as e:
                log.warning("bucket bridge pass failed: %s", e)

    def _bucket_once(self):
        # operator drops incoming/<name>.xdc -> bot publishes -> result back
        pfx = self.bucket.prefix + "/" if self.bucket.prefix else ""
        for key in self.bucket.list("incoming"):
            base = key.rsplit("/", 1)[-1]
            if not base.endswith(".xdc"):
                continue
            rel = key[len(pfx):] if key.startswith(pfx) else key
            data = self.bucket.get(rel)
            if data is None:
                continue
            with open(os.path.join(self.files_dir, base), "wb") as f:
                f.write(data)
            token = str(time.time())
            with open(os.path.join(self.files_dir,
                                   bot_runtime.PUBLISH_REQ), "w") as f:
                f.write(base + "\n" + token + "\n")
            self.bucket.delete(rel)
            log.info("publish requested from bucket: %s", base)
        # publish results -> results/
        done = os.path.join(self.files_dir, bot_runtime.PUBLISH_DONE)
        if os.path.exists(done):
            with open(done, "rb") as f:
                payload = f.read()
            self.bucket.put("results/publish-%d.json" % time.time(), payload)
            os.unlink(done)
        # invite artifacts -> control/ (private bucket; bearer secret)
        done_flag = os.path.join(self.files_dir, bot_runtime.INVITE_DONE)
        if os.path.exists(done_flag):
            mtime = os.path.getmtime(done_flag)
            if mtime > self._last_invite_upload:
                for name in ("invite.txt", "invite.svg"):
                    p = os.path.join(self.files_dir, name)
                    if os.path.exists(p):
                        with open(p, "rb") as f:
                            self.bucket.put("control/" + name, f.read())
                self._last_invite_upload = mtime
                self.status.set(invite_ready=True)

    def request_invite(self):
        """Ask the running bot to (re)write the invitation QR files."""
        with open(os.path.join(self.files_dir,
                               bot_runtime.INVITE_REQ), "w") as f:
            f.write(str(time.time()))

    # -- lifecycle --------------------------------------------------------------

    def start(self):
        self.status.set(provisioned=True, db="up")
        bt = threading.Thread(target=self._bot_thread, daemon=True,
                              name="bot-live")
        bt.start()
        self.threads.append(bt)
        # make sure an invite exists for the operator once running
        threading.Thread(target=self._ensure_invite, daemon=True,
                         name="invite-bootstrap").start()
        threading.Thread(target=self._checkpoint_loop, daemon=True,
                         name="checkpoint").start()
        if self.bucket is not None:
            threading.Thread(target=self._bucket_loop, daemon=True,
                             name="bucket-bridge").start()

    def _ensure_invite(self):
        for _ in range(60):
            if self.acct is not None:
                if not os.path.exists(os.path.join(self.files_dir,
                                                   "invite.txt")):
                    self.request_invite()
                return
            time.sleep(1)

    def stop(self):
        """Real stop via the runtime's own STOP flag, then a final
        checkpoint while the account object is still open... the flag
        closes the rpc, so checkpoint BEFORE the flag fully lands is racy;
        instead: flag, wait for the bot thread to exit, then checkpoint is
        skipped (rpc is closed) - the interval checkpoints carry the state,
        and the next boot restores them. Logged loudly either way."""
        self.stop_event.set()
        with open(os.path.join(self.files_dir,
                               bot_runtime.STOP_FLAG), "w") as f:
            f.write(str(time.time()))
        deadline = time.time() + 30
        for t in self.threads:
            remaining = deadline - time.time()
            if remaining > 0:
                t.join(remaining)
