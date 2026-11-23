"""jin web companion - NEO App service entrypoint.

Boot order, every step fail-closed:
  1. read + validate env config (never logged)
  2. local writer lock on the work dir (fcntl flock, same-node pods)
  3. connect the state database, take the single-writer lease
  4. restore the newest state bundle, or provision a fresh bot account
     (chatmail invite from env, default nine.testrun.org), then checkpoint
     immediately so a fresh identity is never left unpersisted
  5. serve: live bot, checkpoint loop, bucket bridge, HTTP status on $PORT

SIGTERM/SIGINT: final live checkpoint, then the runtime's STOP flag.

Secrets (transfer code, bucket keys, DSN) arrive as plain env vars - the
only kind NEO App is documented to inject - and are never logged.
"""
import base64
import fcntl
import logging
import os
import signal
import sys
import threading
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import bot_runtime  # noqa: E402
import db as dbmod  # noqa: E402
import state_sync  # noqa: E402
from bucket import Bucket  # noqa: E402
from service import Service  # noqa: E402
from srv_rpc import make_rpc, patch_bot_runtime  # noqa: E402

log = logging.getLogger("jin.start")

LEASE_TTL_S = 90
LEASE_HEARTBEAT_S = 30


class ConfigError(Exception):
    pass


class Config:
    def __init__(self, env):
        self.work_dir = env.get("JIN_WORK_DIR") or "./work"
        if not os.path.isabs(self.work_dir):
            self.work_dir = os.path.abspath(self.work_dir)
        self.db_dsn = env.get("JIN_DB_DSN") or (
            "sqlite:///" + os.path.join(self.work_dir, "service.db"))
        self.relay = env.get("JIN_CHATMAIL_RELAY") or "nine.testrun.org"
        self.port = int(env.get("PORT") or "8080")
        self.checkpoint_interval_s = max(
            60, int(env.get("JIN_CHECKPOINT_INTERVAL_S") or "900"))
        self.bucket_poll_s = max(10, int(env.get("JIN_BUCKET_POLL_S") or "60"))

        code = env.get("JIN_TRANSFER_CODE") or ""
        try:
            raw = base64.urlsafe_b64decode(code + "=" * (-len(code) % 4))
        except Exception:
            raw = b""
        if len(raw) != 32:
            raise ConfigError(
                "JIN_TRANSFER_CODE must be 32 random bytes, base64url "
                "(44 chars) - generate once with: python3 -c \"import "
                "secrets,base64;print(base64.urlsafe_b64encode("
                "secrets.token_bytes(32)).decode())\" and set it in the "
                "NEO App env config before first boot")
        self.transfer_code = code

        if self.db_dsn.startswith("sqlite:///") and \
                env.get("JIN_ALLOW_EPHEMERAL_DB") != "1":
            raise ConfigError(
                "JIN_DB_DSN is sqlite (ephemeral on NEO App). Set the "
                "managed PostgreSQL DSN from the NEO App database binding, "
                "or set JIN_ALLOW_EPHEMERAL_DB=1 for a disposable local run.")

        s3 = {k: env.get("JIN_S3_" + k) for k in
              ("ENDPOINT", "REGION", "BUCKET", "ACCESS_KEY", "SECRET_KEY")}
        have = {k: v for k, v in s3.items() if v}
        if have and len(have) != 5:
            raise ConfigError("bucket config is all-or-none: set all of "
                              "JIN_S3_ENDPOINT/REGION/BUCKET/ACCESS_KEY/"
                              "SECRET_KEY or none")
        self.s3 = s3 if have else None
        self.s3_prefix = env.get("JIN_S3_PREFIX") or "jin"
        self.s3_path_style = env.get("JIN_S3_PATH_STYLE", "1") != "0"


def serve_http(service, port):
    """Booleans-and-counts status over HTTP. No secrets, no QR, no addr."""
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    import json as jsonlib

    class H(BaseHTTPRequestHandler):
        def _json(self, code, obj):
            body = jsonlib.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/healthz":
                self._json(200, {"ok": True})
            elif self.path == "/status":
                snap = service.status.snapshot()
                if service.last_checkpoint:
                    snap["last_checkpoint_age_s"] = int(
                        time.time() - service.last_checkpoint)
                self._json(200, snap)
            else:
                self._json(404, {"error": "not found"})

        def log_message(self, *a):
            pass

    ThreadingHTTPServer(("0.0.0.0", port), H).serve_forever()


def _lease_heartbeat(db, holder, stop_event):
    while not stop_event.wait(LEASE_HEARTBEAT_S):
        if not db.renew_lease(holder, LEASE_TTL_S):
            log.error("lease lost - another writer may be active; exiting")
            os._exit(73)


def main():
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(name)s %(message)s")
    try:
        cfg = Config(os.environ)
    except ConfigError as e:
        log.error("config: %s", e)
        return 2

    os.makedirs(cfg.work_dir, mode=0o700, exist_ok=True)
    os.chmod(cfg.work_dir, 0o700)
    lock_path = os.path.join(cfg.work_dir, "writer.lock")
    lock_fd = open(lock_path, "w")
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        log.error("another local writer holds %s", lock_path)
        return 73

    patch_bot_runtime(bot_runtime)
    bot_runtime.RELAY = cfg.relay

    state_db = dbmod.Db(cfg.db_dsn)
    holder = uuid.uuid4().hex
    if not state_db.acquire_lease(holder, LEASE_TTL_S):
        log.error("state lease held by another live writer; refusing "
                  "to start (single always-on replica)")
        return 73
    stop_event = threading.Event()
    threading.Thread(target=_lease_heartbeat,
                     args=(state_db, holder, stop_event),
                     daemon=True, name="lease-heartbeat").start()

    bucket = None
    if cfg.s3:
        bucket = Bucket(cfg.s3["ENDPOINT"], cfg.s3["REGION"],
                        cfg.s3["BUCKET"], cfg.s3["ACCESS_KEY"],
                        cfg.s3["SECRET_KEY"], prefix=cfg.s3_prefix,
                        path_style=cfg.s3_path_style)

    try:
        restored = state_sync.restore(cfg.work_dir, state_db,
                                      cfg.transfer_code, make_rpc)
        st = bot_runtime.load_state(cfg.work_dir)
        if not st.get("provisioned"):
            if restored:
                raise RuntimeError(
                    "bundle restored but state is not provisioned - "
                    "manual review needed")
            log.info("provisioning a fresh bot account on relay %s",
                     cfg.relay)
            addr = bot_runtime.provision(None, cfg.work_dir)
            log.info("bot account created: %s", addr)

        service = Service(cfg, cfg.work_dir, state_db, cfg.transfer_code,
                          bucket=bucket)
        # bootstrap the account object for the mandatory first checkpoint
        # through the service itself; the checkpoint after start covers a
        # freshly provisioned identity.
        service.start()
        # first checkpoint: wait for the account object, then persist
        threading.Thread(target=_first_checkpoint, args=(service,),
                         daemon=True, name="first-checkpoint").start()

        threading.Thread(target=serve_http, args=(service, cfg.port),
                         daemon=True, name="http").start()
        log.info("jin web companion up on port %d", cfg.port)

        exit_code = 0
        def handle(sig, _frame):
            log.info("signal %s - stopping", sig)
            service.stop()
            stop_event.set()
        signal.signal(signal.SIGTERM, handle)
        signal.signal(signal.SIGINT, handle)
        while not stop_event.wait(5):
            snap = service.status.snapshot()
            if snap["bot_error"]:
                log.error("bot failed: %s", snap["bot_error"])
                exit_code = 1
                service.stop()
                stop_event.set()
        return exit_code
    finally:
        stop_event.set()
        try:
            state_db.release_lease(holder)
        except Exception:
            pass
        state_db.close()


def _first_checkpoint(service):
    for _ in range(120):
        if service.acct is not None:
            break
        if service.stop_event.wait(1):
            return
    service._maybe_checkpoint("boot")


if __name__ == "__main__":
    sys.exit(main())
