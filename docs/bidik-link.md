# Jin and Bidik: the parked link

Status as of 2026-11-24: **parked**. Nothing depends on it. It was an idea,
not a need, and work resumes only when the owner unparks it.

## Purpose

Jin is the shared, on-phone DeltaChat bot host for the owner's apps: one bot
account and one foreground service that other apps talk through, instead of
each app carrying its own messaging stack. Bidik is the first app meant to use
it.

## The intended Bidik-Jin link (both directions)

1. **Requests in.** An assistant, or a person in a chat the bot serves, sends
   Bidik a capture request over DeltaChat. Jin receives it and hands it to
   Bidik, which renders the pages on the phone.
2. **Results out.** Bidik sends its results (the per-page result messages and
   screenshots) back over DeltaChat through Jin.

The point is that this works with no phone number and no login: DeltaChat
over a public chatmail relay, with the bot's invitation QR as the only way in.

## What exists

- **Jin side, capture route** (`app/src/main/python/capture_route.py`): parses
  `capture <url> ...`, builds queue documents matching Bidik's wire format,
  routes result messages and files back. Inert until a Bidik peer is paired by
  the owner; refuses clearly otherwise. Disabled when its module is absent.
  Covered by `test_capture_route.py` and an offline fixture of the real message
  path (`test_route_fixture.py`).
- **Jin side, cross-app transport** (`docs/transport-contract.md`,
  `app/src/main/java/com/vanipy/jin/transport/`): an authenticated provider
  with owner-set, default-off grants per app and per chat. Merged to main.
- **Bidik side** (`irvan-putra/bidik`): a client and contract mirror under
  `com.vanipy.bidik.jin` with host unit tests, and manifest visibility for
  Jin's provider.

## What is unproven or missing

- Bidik's UI does not use the Jin client yet; its working path is still its
  own legacy messaging account inside Bidik.
- No joint run of both apps on a real phone. Provider cold start, force-stop
  and background-launch behavior are assumptions until then.
- The live-relay route runs dispatched on 2026-11-20 failed and have not been
  re-run; no live end-to-end capture over a real relay is recorded.
- Delivery is at-least-once, caller-polled, with no push (see the contract).

## Decision log

- 2026-11-19: Jin is the planned hub for apps that need DeltaChat sync,
  starting with Bidik, in both directions.
- 2026-11-20: parked. 2026-11-24: still parked; context recorded here.
- 2026-11-24: Jin web page published, see the README. A separate public
  demo bot uses free daily GitHub Actions jobs, with replies taking 1-2 days
  (sometimes longer), not an always-on paid host. The page labels its bearer
  invite as a demo, not private. It echoes text and handles small per-chat
  lists only. This does not unpark Bidik-Jin or prove the real-phone link.
  Recovery and limits: `demo-bot/README.md`; full-host runbook: `server/DEPLOY.md`.
