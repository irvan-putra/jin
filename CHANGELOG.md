# Changelog

## 0.5.0 (13) — 2026-11-19

Rebuilt the screen for people who did not write it:

- Status-first home: one card says where the bot is (no account / ready /
  running, with its address and served-chat count) and one primary button
  offers the next step, instead of seven numbered validation buttons.
- Everyday controls (start/stop, invitation QR, publish .xdc) appear when
  they can actually work.
- Diagnostics moved to a hidden left menu (same pattern as intodo): logs,
  offline validation probe, and the itdapk export. Swipe from the left edge
  or long-press anywhere; nothing was removed.
- Dark theme matching the other com.vanipy apps.

No bot-runtime changes: the python modules, service, IPC contract and trust
model are identical to 0.4.16.

## 0.4.16 (12) and earlier

Phone-side DeltaChat bot runtime validation builds (ex-dcbot): offline
probe, live delivery bot, invite QR, publish flow, itdapk export.
