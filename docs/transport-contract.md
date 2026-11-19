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
  bytes.
- Token reuse with identical hash returns the original message reference
  (idempotent retry). Token reuse with a different body is rejected as
  malformed, fail closed. Ledger entries prune after 7 days; use fresh tokens.

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
