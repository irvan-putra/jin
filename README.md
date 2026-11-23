# jin — run a DeltaChat bot from your phone

jin turns an Android phone into a small bot host: it creates the bot's own
chatmail account, answers messages in the chats you invite it to, and
delivers webxdc apps (`.xdc` files) to those chats. Everything runs on the
phone as a foreground service; the only network traffic is IMAP/SMTP to the
public chatmail relay. No Google push, no third-party servers.

The bot's trust model is Delta Chat's own contact flow: whoever holds the
bot's invitation QR can reach it. Any accepted chat is served with its own
isolated list — a group gets a shared list, a direct chat gets a personal
list. There is no owner identity and no chat-side management; all management
is app-side.

Web page: <https://vanipy.com/jin/> explains jin, shows install steps and
links the APK. It also has a separate throwaway **Jin demo (public)** contact
QR: replies take **1-2 days**, sometimes longer, and it is **not private**.
Ordinary text is echoed back; list commands are available too. The free demo
runs once daily in GitHub Actions, not continuously, and does not host the
full Android runtime. See [demo-bot/README.md](demo-bot/README.md) for caps,
cache-loss/schedule limits and recovery. Source: `web/`.

## Using jin

1. **Create bot account** — one tap, one time. jin provisions ONE dedicated
   profile on the public chatmail relay via its dcaccount invite. This is the
   bot's own identity, never your personal account; the address is shown in
   the app.
2. **Start bot** — a permanent-notification foreground service
   (`specialUse` type, `START_STICKY`), same background model as the official
   DeltaChat-Android F-Droid flavor. Stopping the bot writes a STOP flag;
   the runtime closes its connection and exits (real stop, single-run lock).
3. **Show invitation QR** — the bot's SecureJoin setup-contact QR, generated
   on demand. Scan it from another device, or on the same phone tap
   "Open in DeltaChat", then send the bot any message. Add the bot to any
   chat: each group gets its own shared list and each direct chat its own
   personal list, kept separate. The QR text is a bearer invitation and is
   never logged.
4. **Publish new app version (.xdc)** — pick a `.xdc` file; the bot sends it
   to every chat it serves, with per-chat checkpoints and a correlated
   result. A chat that misses a publish is healed lazily on its next
   traffic — never deleting old instances, never downgrading a newer one.

**Diagnostics, logs and export** live in the left-edge menu: swipe from the
left edge of the screen, or long-press anywhere. The menu holds the log
viewer, the offline validation probe (no network), and "Export bot for
tindak", which pauses the live bot and writes ONE encrypted `.dcmig` bundle
(account backup + bot state) protected by a one-time transfer code — see
[integration/MIGRATION.md](integration/MIGRATION.md).

## Trust and isolation

- Contact requests stay refused (the core's own boundary).
- Removal: when the core records the bot removed from a chat, access pauses;
  the list id, registrations and store file are kept, and re-adding resumes
  the same list (core chat ids are stable across remove/re-add). A revoked
  chat resumes only on grounded rejoin evidence — a late in-flight message
  alone never revokes the pause.
- Each served chat gets its OWN OpsStore (`ops-<id>.db`), its OWN stable
  list id and its OWN gate/bridge; histories never mix across chats.
- No chat-originated input can publish or administer: the per-chat gates are
  built with no owner address, so their DM command surface refuses everyone
  by construction.

## Purpose and the parked Bidik link

jin is the shared on-phone DeltaChat bot host that the owner's other apps
can talk through. The first planned user is bidik (capture requests in,
results out, both over DeltaChat, no phone number or login). That link is
parked: the Jin side route and transport provider exist, bidik does not use
them yet, and nothing is proven on a real phone. See
[docs/bidik-link.md](docs/bidik-link.md).

The public demo uses free daily scheduled jobs, not paid always-on hosting.
The full hosted service remains a separate option, with no new hosting approved.
From-scratch redeploy, restart, state checks and teardown: [server/DEPLOY.md](server/DEPLOY.md).

## Related projects

- [tindak](https://github.com/irvan-putra/tindak) embeds this same bot
  runtime, alongside the todo lists it serves. jin is the
  standalone runner.

## Build

Host: JDK 17, Android SDK 34, gradle 8.7:

    gradle :app:assembleRelease

The APK is arm64-v8a only (the bundled Delta Chat core lib exists for no
other ABI). Native libs ship compressed (legacy packaging,
`extractNativeLibs=true`); zipalign before signing.

Local JVM tests (Robolectric, no device, no network/core):

    gradle -Pandroid.useAndroidX=true :app:testDebugUnitTest

They cover the publish IPC contract (immutable file, atomic request, DONE
dedup, pending REQ/PROG refusal, recreation reattach), the screen's state
machine and click wiring, and native-graphics renders of the three bot
states plus the diagnostics menu. The python wrapper suite
(`app/src/main/python/test_bot_runtime.py`) runs the trust/isolation model
against the shipped modules.
