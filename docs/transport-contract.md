# Jin cross-app transport contract v1

Status: draft for joint review (Jin side). Peer: Bidik worker agent.
Transport: symmetric, authenticated `ContentProvider.call()` plus scoped `content://`
staging. No home-grown crypto; Android binder identity + package signing pins only.

## Identity and authorization
- Caller identity = `Binder.getCallingUid()` -> `PackageManager.getPackagesForUid`.
  Shared UIDs (more than one package per uid) fail closed.
- Each app pins (package name, SHA-256 of APK signing cert) per authorized app.
  Both halves verify the caller against the pin on every call. Jin and Bidik are
  signed by different permanent keys; pins are recorded in each repo.
- Grants are default-off, per app and per chat, set by the owner in each app's
  own UI (Jin: left menu > Connected apps). An app that only calls
  `capabilities()` is recorded as "seen" (package, cert hash, timestamps only)
  so the owner can grant it; nothing else is stored about it.
- Explicit broadcasts establish nothing and are not used for authorization.
  Delivery notifications may use explicit broadcasts, but every state change
  still requires an authenticated `call()`.

## Staging and files
- Large payloads stream through `content://<authority>/stage/<token>` opened for
  write; the provider drains the pipe with a hard byte cap and aborts visibly
  (FILE_TOO_LARGE with the current limit) instead of reading unlimited bytes.
  Partial stages are deleted on abort/failure. `cancel(token)` discards a stage.
- `max_bytes` is advertised by `capabilities()` and enforced as
  min(contract cap, relay-advertised limit). Contract default cap: 20 MiB
  (below common chatmail relay message limits; the runtime relay limit wins).
- A staged token expires after 10 minutes. Client timestamps more than 5 minutes
  in the future are rejected.

## Dedup and hashing
- Outbound dedup ledger binds: token + SHA-256 over the canonical metadata
  (method, chat handle, text) + SHA-256 over the staged file bytes.
- Token reuse with identical hash returns the original message reference
  (idempotent retry). Token reuse with a different body is rejected as
  malformed, fail closed.

## Statuses (mapped to what the Delta Chat core actually reports)
RECEIVED_BY_BIDIK / ACCEPTED_BY_JIN / QUEUED / SENT_TO_RELAY / FAILED(reason) /
EXPIRED, plus READ only when the chat sends read receipts. There is no
DELIVERED-to-person signal; SENT_TO_RELAY is the last grounded hop.

## Commands
- Per-app registered prefix (e.g. "/bidik"); parsing fails closed. Unknown or
  malformed commands are never forwarded to any chat.

## Chat handles
Composite: (bot account address, core chat id, account generation counter).
`listChats()` returns handles and display names for chats the caller's grant
covers, nothing else.

## OS-lifecycle assumptions (to be verified on a real device, not asserted)
Provider cold start on first call, force-stop behavior, and background
notification/consent launch behavior are assumptions until the first joint
cross-app test on a real OS build records what actually happens.
