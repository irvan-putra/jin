# jin demo bot

A throwaway public demo of what a jin bot does in a chat. It is **not** the real
jin bot, holds no personal data, and is separate from every account the owner
uses. It runs for free as a scheduled GitHub Actions job
(`.github/workflows/demo-bot.yml`).

## What it can do

- Each chat that joins through the demo invite gets its own small list:
  `add <text>`, `list`, `done <n>`, `clear`, `help`. Other text is echoed back (up to 2,000 characters).
- Contact requests are never accepted. Only chats started by scanning the
  invite are served. No command administers the bot.

## What it cannot do

- **Replies take 1-2 days, sometimes longer.** It wakes once daily at 03:17 UTC
  (10:17 WIB), fetches messages, answers, and stops. GitHub may delay or skip
  scheduled runs. Joining also needs the bot online, so the handshake can take
  one run and your first reply another. There is no guaranteed delivery time.
- **Not private.** Anyone with the invite can chat with it. Do not send anything
  personal. Lists are capped (20 items, 200 characters, 200 chats) and may be
  reset at any time.
- It can lose its account: state lives in the Actions cache, which can be
  evicted, and GitHub disables scheduled workflows on repos with 60 days without repository
  activity (such as commits). If that happens the invite stops working until a new account is
  provisioned and the page is updated.

## How state is kept

The account keys and lists are packed and encrypted (Fernet) with a key stored
only in the `JIN_DEMO_STATE_KEY` Actions secret and in the owner's vault. Only
ciphertext goes into the Actions cache. No key, password or state is in this
repository.

## Running it

- First time only: run the workflow by hand with `provision` ticked. It creates
  one new chatmail account and prints its address and invite in the log. If
  state already exists it refuses to create another.
- After that the schedule handles everything. Without saved state the scheduled
  runs only print a warning.
- Local tests: `cd demo-bot && python -m unittest test_commands`.

## Restart, state check and teardown

Run the workflow manually with `provision=false` to wake the existing identity.
The restore step must find a saved cache; `inspect` prints the current bearer
invite and only saved-list counts, then the run answers messages and saves a
new sealed cache. Check that the restored cache is the previous successful run,
then use `list` from the same synthetic chat to verify its item survived. A
green job alone is not a real message round trip.

If schedules were disabled, enable the workflow in GitHub Actions after checking
that the daily cron still exists on the default branch. If the cache was
lost, do not tick provision expecting to restore the old identity. It creates
a replacement account with a different invite. Take down the page's old QR,
provision deliberately, test a real round trip, and replace/decode-check the QR.
The state key alone cannot recover an evicted cache; retain an encrypted backup
if identity continuity matters. Never commit plaintext state or keys.

To pause, disable the `demo bot` workflow in Actions. To remove it, disable it,
remove the public demo invite from the page and README, then delete only caches
whose key starts `demo-bot-state-`. Delete `JIN_DEMO_STATE_KEY` only after deciding
the sealed state is no longer needed. Do not delete Android build caches or
unrelated secrets. Relay-side account retention is governed by the relay, not
by deleting the workflow. No paid host was created for this demo.

## Verified checks (2026-11-24)

- [Separate run restored the previous account and synthetic list item](https://github.com/irvan-putra/jin/actions/runs/45268588348).
- [Fresh tester received an actual echo and add reply](https://github.com/irvan-putra/jin/actions/runs/45269308466).
- [Later run served list from the same tester](https://github.com/irvan-putra/jin/actions/runs/45269636162), confirming its synthetic item survived a stop/start.
- Seven local tests cover commands, isolation, caps and sealed-state restore.

GitHub policy references: [schedule](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule),
[cache retention](https://docs.github.com/en/actions/using-workflows/caching-dependencies-to-speed-up-workflows#usage-limits-and-eviction-policy).
