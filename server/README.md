# jin web companion (NEO App service)

The jin bot core running 24/7 as a hosted service instead of on the phone.
Same proven runtime as the Android app (`app/src/main/python/`, copied here
unmodified: `bot_runtime.py`, `ops_store.py`, `migration.py`), driven
through its existing file contract. Users interact through their normal
DeltaChat clients; the service holds the bot account, serves chats, and
bridges webxdc app ops to durable storage.

Deploys to **Biznet Gio NEO App** as a Dockerfile app, alongside Naung.
State lives in **managed PostgreSQL** (bot identity + op histories, as
versioned encrypted bundles) and **NEO Object Storage** (blobs: `.xdc`
publish payloads, publish results, the invitation QR for the operator).
No block storage: NEO volumes meter separately (learned from Naung,
2026-11-23) and were dropped for this service.

## What runs where

- **Bot runtime** — accepts chats from the bot's invitation QR holders,
  serves each chat its isolated list (own store, own list id), fans out
  published `.xdc` versions. Trust model is unchanged from the phone: the
  QR is the boundary, contact requests stay refused, management is
  operator-side only (no chat-originated admin).
- **Checkpoint loop** — dirty-only, every `JIN_CHECKPOINT_INTERVAL_S`
  (default 900 s): core account backup + bot state, Fernet-encrypted under
  the operator-held transfer code, stored as a new versioned row in the
  database (last 3 kept). A fresh container boots from the newest row.
- **Bucket bridge** — polls `incoming/` for `.xdc` files to publish,
  writes results to `results/`, mirrors the invitation QR to `control/`
  (private; the QR text is a bearer secret and is never logged or served
  over HTTP).
- **HTTP status** — `$PORT` (default 8080): `/healthz` and `/status`,
  booleans and counts only.
- **Single-writer discipline** — OS flock on the work dir plus a database
  lease row with heartbeat; a second replica refuses to start (exit 73).
  Run ONE always-on replica.

The capture-route sync bridge (bidik integration) is phone-only and is not
started here; that integration is parked. Because `bot_runtime.py` stays
byte-identical to the phone (where the handler imports `capture_route`
lazily per message), the service adapter (`srv_rpc.patch_bot_runtime`)
replaces the capture handler with a no-op that never consumes a message
when the module is absent - normal chat handling is unchanged.

## Configuration (env vars, injected by NEO App)

| Var | Required | Meaning |
| --- | --- | --- |
| `JIN_TRANSFER_CODE` | yes | 32 random bytes base64url (44 chars). Encrypts every state bundle; also the core backup's SQLCipher passphrase. Generate once, keep safe: `python3 -c "import secrets,base64;print(base64.urlsafe_b64encode(secrets.token_bytes(32)).decode())"` |
| `JIN_DB_DSN` | yes in production | `postgres://user:pass@host:5432/dbname` from the NEO App database binding. Exact host/port/TLS are only visible in the portal at bind time; do not guess. `sslmode` as the binding dictates. |
| `JIN_S3_ENDPOINT` / `JIN_S3_REGION` / `JIN_S3_BUCKET` / `JIN_S3_ACCESS_KEY` / `JIN_S3_SECRET_KEY` | for blobs | NEO Object Storage (S3-compatible, e.g. `https://nos.jkt-1.neo.id`). All-or-none; without them the bucket bridge stays off and publishing is operator-local only. The region feeds only the SigV4 signing scope, never endpoint routing. |
| `JIN_S3_SIGNING_REGION` | when the binding injects an empty region | Signing-region override that wins over `JIN_S3_REGION`. Needed because the NEO typed bucket binding injects `JIN_S3_REGION` as an empty string and binding vars take precedence over per-service vars; set it to the verified signing region (e.g. `WJV-1`). |
| `JIN_S3_PREFIX` | no | Object key prefix, default `jin`. |
| `JIN_CHATMAIL_RELAY` | no | Chatmail relay for a fresh account, default `nine.testrun.org`. Only used on first boot with an empty database. |
| `JIN_WORK_DIR` | no | Scratch working dir inside the container, default `/work`. Ephemeral by design; the database is the durable state. |
| `JIN_CHECKPOINT_INTERVAL_S` | no | Default 900 (min 60). |
| `JIN_BUCKET_POLL_S` | no | Default 60 (min 10). |
| `PORT` | no | Default 8080. |
| `JIN_ALLOW_EPHEMERAL_DB` | no | `1` permits a sqlite DSN for disposable local runs; refused otherwise. |

All env values are sensitive: NEO App injects plain key/value env vars, so
never echo them into logs, status, or the repo.

## Deploy on NEO App (needs the owner's account; not done from the repo)

1. In the NEO portal: create a managed **PostgreSQL** database (14–16) and
   note its connection details; create or pick an **Object Storage**
   bucket and access key pair.
2. Create a new NEO App **from this repo**, root directory `server/`,
   Dockerfile deploy, one always-on replica. Keep it a separate app from
   Naung (`irvan.app.neoapp.id`); give it its own hostname.
3. Bind the database to the app; set the env vars above (DSN from the
   binding, transfer code you generated, bucket keys).
4. Deploy. First boot provisions a fresh bot account on the chatmail
   relay and checkpoints it. Watch the log for `bot account created`.
5. Get the invitation QR from the private bucket (`jin/control/invite.txt`
   / `.svg`) and add the bot to chats as before.
6. Verify billing afterwards: database and bucket meter separately — check
   the portal's usage lines directly (the Naung volume lesson: deletion
   alone is not proof that charges stopped).

Portal-check leftovers for deploy day: exact DB DSN/TLS flags, DB minimum
cost, bucket endpoint/key behavior, and whether a masked secret-env type
exists (plain env assumed).

## Updating the service (learned on first redeploy, 2026-11-23)

NEO's default rolling update cannot replace this single-writer service.
The new pod's readiness waits on the old pod's database lease, which the
old pod never releases while alive, so a rolling update crash-loops the
new image. Updates must stop the service fully, then start the desired
image (a recreate strategy). The lease heartbeat keeps the stop-then-
start gap short: a stopped pod's lease expires within the 90 s TTL.

Deployment tooling caveat: a rolloutReady-style check that counts Ready
pods is misleading here - it counts the OLD pod. Confirm the new image
tag is actually serving after an update, not just that something is
Ready.

## Durability caveat

Configuring a database or bucket is not proof the provider retains data
through restarts/redeploys. The checkpoint cadence bounds state loss to
`JIN_CHECKPOINT_INTERVAL_S`; the bundle tail (3 versions) bounds a bad
checkpoint. Local checks do not certify hosted durability.

## Local development

```
pip install -r requirements.txt
JIN_ALLOW_EPHEMERAL_DB=1 JIN_TRANSFER_CODE=$(python3 -c "import secrets,base64;print(base64.urlsafe_b64encode(secrets.token_bytes(32)).decode())") \
  python app/start.py
```

Tests (no network): `python -m unittest discover -s tests -v`

## Provenance

`app/bot_runtime.py`, `app/ops_store.py`, `app/migration.py`,
`app/bot.py`, `app/delivery_bot.py` are byte-identical copies of the
phone runtime (`../app/src/main/python/` at the same commit). Port
changes deliberately; do not let them drift. Deliberately NOT packaged:
`capture_route.py` and `live_relay.py` (parked/CI-only phone paths) -
`tests/test_depclosure.py` walks the transitive import closure and
proves the package is complete minus these documented exclusions.

`tests/test_bot_runtime.py` is the phone's 58-function offline
conformance suite (real rpc-client classes over a fake transport),
adapted two lines for the server layout plus a `load_tests` wrapper;
the 9 `test_sync_*` functions cover the parked sync bridge and stay
phone-only. The other 49 run against the exact hosted configuration
(srv_rpc patch applied), proving the package can serve accepted
group/DM chats end to end, not just pass unit tests.
