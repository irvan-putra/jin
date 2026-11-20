# Jin cross-app transport contract v1

Status: implementation-matched draft (Jin side, transport-v1 branch). Peer:
Bidik worker agent. Transport: authenticated `ContentProvider.call()` into Jin
plus scoped `content://` staging. Inbound delivery is CALLER-POLLED: Jin never
pushes into the calling app, starts it, or runs in its process. No home-grown
crypto; Android binder identity + package signing pins only.

## Methods
`capabilities()` (unauthenticated), `stage(token)`, `commit(token, meta)`,
`cancel(token)`, `status(ref)`, `listChats()`, `poll(cursor)`.

## Identity and authorization
- Caller identity = `Binder.getCallingUid()` -> `PackageManager.getPackagesForUid`.
  Shared UIDs (more than one package per uid) fail closed.
- Each app pins (package name, SHA-256 of APK signing cert) per authorized app.
  The pin is checked on every call. Jin and Bidik are signed by different
  permanent keys; pins are recorded in each repo.
- `capabilities()` is callable by any app and reveals only service metadata
  (contract version, max_bytes, whether the caller already holds a grant, its
  prefix). Calling it records the caller as "seen" (package, cert hash,
  timestamps only, never content) so the owner can decide.
- SEEN IS NOT AUTHORITY. A seen app can do nothing beyond `capabilities()`.
  Every grant is default-off and set by the owner in Jin's left menu >
  Connected apps, per app (enable + command prefix) and per chat with TWO
  independent scopes:
  - SEND: the app may put messages/files into that chat.
  - READ COMMANDS: the app receives that chat's messages that start with the
    app's registered prefix.
  The owner-verified (package, cert) pair is the only path to a grant; no
  first-use, timeout, or heuristics ever elevate a caller.
- Explicit broadcasts establish nothing and are not used. There is no push
  channel in v1.

## Inbound delivery (poll model, stated plainly)
- The caller polls `poll(cursor)`; Jin returns up to 100 items newer than the
  cursor plus the new cursor. There is no callback, no wake, no background
  start of the caller. A command sent while the caller is stopped is delivered
  only on its next poll.
- End-to-end inbound lag = the caller's poll cadence + bridge latency
  (~1 s while the bot runs) + Delta Chat delivery time. Choose the cadence
  accordingly; nothing in this contract implies immediacy.
- Each poll item is filtered by the CALLER's own registered prefix: an app
  never sees another app's commands, even when both are granted the same chat.
  Apps with an empty prefix capture and receive nothing (fail closed).
- Retention is bounded: Jin keeps the newest 500 captured items. If a caller's
  cursor is older than the oldest retained item, `poll` returns `gap: true`
  with `first_available` (Bundle keys `gap` / `first_available`) so the caller
  can surface possibly-missed commands instead of silently skipping them.
- Runtime failures propagate as errors, never as empty pages: a failed `poll`
  returns `ok=false` with the error code and leaves the caller's cursor
  semantics untouched.
- While Jin's bot is not running, bridge calls fail visibly with
  BOT_NOT_RUNNING; sends do not queue for later, they error.

## Staging and files
- Large payloads stream through `content://<authority>/stage/<token>` opened
  for write; the provider drains the pipe with a hard byte cap and aborts
  visibly (FILE_TOO_LARGE with the current limit) instead of reading unlimited
  bytes. Partial stages are deleted on abort/failure. `cancel(token)` discards
  a stage.
- `max_bytes` is advertised by `capabilities()` and enforced as
  min(contract cap, relay-advertised limit). Contract default cap: 20 MiB -
  a conservative implementation budget, NOT a relay guarantee; the runtime
  relay limit wins.
- A staged token expires after 10 minutes (enforced at stage, stream-open and
  commit; the runtime sweeps expired stage files). Client timestamps more than
  5 minutes in the future are rejected.
- Commit/drain ordering is explicit: the drain signals EOF per token and
  `commit` waits (bounded, 5 s) for that signal before hashing. A commit that
  declares a file without a completed stream fails visibly with
  STAGE_INCOMPLETE; truncated bytes are never hashed or sent.

## Dedup and hashing
- Outbound dedup ledger binds: token + SHA-256 over the canonical metadata
  (method, chat handle, text, filename, mime) + SHA-256 over the staged file
  bytes + the original message reference.
- Lost commit responses are retriable: a `commit` carrying the same token and
  the same canonical metadata returns the original reference WITHOUT
  re-sending, so a caller that never saw the ack recovers it instead of
  duplicating the message. Text-only sends stay idempotent for the ledger
  lifetime (7 days).
- File sends keep the staged bytes after commit (they move to
  `stage/<token>.bin` and age out with the stage TTL, ~10 minutes) so a retry
  can re-verify byte identity against the recorded hash. A retry whose bytes
  are still retained, or that re-supplies identical bytes, returns the
  original reference. Once the retained bytes are swept, a BARE retry fails
  visibly with EXPIRED.
- Recovery after EXPIRED is SAME-token, never fresh-token: the caller
  re-stages the same token with the same bytes and commits the same metadata.
  The provider re-verifies both against the ledger entry (7-day lifetime) and
  returns the original reference without re-sending. Minting a fresh token to
  retry the same logical send is a contract violation: a fresh token is a NEW
  send and silently duplicates the message when the original commit landed.
- Re-staged bytes or metadata that do NOT match the ledger entry are rejected
  with TOKEN_REUSED, fail closed. Use fresh tokens only for genuinely new
  sends.
- `status(ref)` takes the message reference returned by `commit`, not the
  client token; the reference is what a successful (or retried) commit
  returns.

## Delivery guarantees (stated plainly)
- Sends are AT-LEAST-ONCE with idempotent replay, NOT exactly-once. Three
  layers shrink the duplicate window: the Java dedup ledger (7 days), the
  per-token commit lock, and the bridge's durable processed-token record
  (500 newest tokens).
- Residual duplicate windows, honestly stated:
  - A crash between the DeltaChat core accepting the message and the
    processed-token record landing on disk replays as a second send when the
    request is retried. Closing that window needs a transactional
    send-and-record inside the core, which v1 does not attempt.
  - The processed-token record evicts beyond its 500 newest entries while
    the Java ledger lives 7 days: a retry whose ledger entry is also gone
    (crash before the ledger write, then 500 newer sends) would send again.
  - Ledger entries prune after 7 days regardless; use fresh tokens for new
    sends, as already stated.
- A client that cannot tolerate a duplicate in these residual windows must
  dedup downstream on message content. This contract never promises
  exactly-once.

## Statuses (mapped to what the Delta Chat core actually reports)
RECEIVED_BY_BIDIK / ACCEPTED_BY_JIN / QUEUED / SENT_TO_RELAY / FAILED(reason) /
EXPIRED, plus READ only when the chat sends read receipts. There is no
DELIVERED-to-person signal; SENT_TO_RELAY is the last grounded hop. A
synchronous commit ack is ACCEPTED/QUEUED only, never delivery.

## Commands
- Per-app registered prefix (e.g. "/bidik"); parsing fails closed. Unknown or
  malformed commands are never forwarded to any chat, and prefix-less apps get
  no inbound items.

## Chat handles
Composite: base64url(bot account address | core chat id | g<generation>), the
generation set at provision so account reset invalidates every old handle.
`listChats()` returns handles, display names, and the caller's send/recv
scope per chat, for granted chats only.

## OS-lifecycle assumptions (to be verified on a real device, not asserted)
Provider cold start on first call, force-stop behavior, and background
notification/consent launch behavior are assumptions until the first joint
cross-app test on a real OS build records what actually happens.
