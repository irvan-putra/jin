"""Demo bot chat commands. Pure logic, no network: each chat has its own
small list, nothing else is stored. No admin surface: every command only
touches the list of the chat it came from."""

MAX_ITEMS = 20
MAX_TEXT = 200
MAX_CHATS = 200

HELP = (
    "Jin demo bot. Not private: anyone with the invite can talk to it, and "
    "replies can be slow (it checks for messages every few minutes).\n"
    "Each chat has its own small list.\n"
    "add <text>  - add an item\n"
    "list        - show items\n"
    "done <n>    - remove item n\n"
    "clear       - empty this chat's list\n"
    "help        - this message\n"
    "Please do not send anything personal."
)


def handle(lists, chat_key, text):
    """Return the reply text for one incoming message, or None to stay silent.
    `lists` maps chat key -> list of strings and is updated in place."""
    t = (text or "").strip()
    if not t:
        return None
    cmd, _, rest = t.partition(" ")
    cmd = cmd.lower()
    rest = rest.strip()
    items = lists.get(chat_key)
    if cmd == "add":
        if not rest:
            return "Usage: add <text>"
        if items is None:
            if len(lists) >= MAX_CHATS:
                oldest = next(iter(lists))
                del lists[oldest]
            items = lists[chat_key] = []
        if len(items) >= MAX_ITEMS:
            return "This list is full (%d items). Use done <n> or clear." % MAX_ITEMS
        items.append(rest[:MAX_TEXT])
        return "Added as item %d." % len(items)
    if cmd == "list":
        if not items:
            return "This chat's list is empty. Try: add buy milk"
        return "\n".join("%d. %s" % (i + 1, x) for i, x in enumerate(items))
    if cmd == "done":
        if not rest.isdigit() or not items or not (1 <= int(rest) <= len(items)):
            return "Usage: done <n>, with n from list."
        gone = items.pop(int(rest) - 1)
        return "Removed: %s" % gone
    if cmd == "clear":
        lists.pop(chat_key, None)
        return "Cleared."
    if cmd in ("help", "hi", "hello", "start", "/help"):
        return HELP
    return "I did not understand that. Send help."
