# jin demo bot

A throwaway public demo of what a jin bot does in a chat. It is **not** the real
jin bot, holds no personal data, and is separate from every account the owner
uses. It runs for free as a scheduled GitHub Actions job
(`.github/workflows/demo-bot.yml`).

## What it can do

- Each chat that joins through the demo invite gets its own small list:
  `add <text>`, `list`, `done <n>`, `clear`, `help`. Nothing else.
- Contact requests are never accepted. Only chats started by scanning the
  invite are served. No command administers the bot.

## What it cannot do

- **Replies are slow.** It wakes about every 10 minutes (GitHub's scheduler can
  delay or skip runs), fetches messages, answers, and stops. Expect minutes,
  sometimes much longer. The invite handshake completes at the next run too.
- **Not private.** Anyone with the invite can chat with it. Do not send anything
  personal. Lists are capped (20 items, 200 characters, 200 chats) and may be
  reset at any time.
- It can lose its account: state lives in the Actions cache, which can be
  evicted, and GitHub disables scheduled workflows on repos with 60 days of no
  activity. If that happens the invite stops working until a new account is
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
