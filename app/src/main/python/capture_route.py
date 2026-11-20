"""Jin-side route for Bidik capture requests (pure logic, no rpc import).

Mirrors Bidik's on-the-wire contract (bidik QueueProtocol.kt / dc_transport.py):
- queue doc: {"type":"capture-queue","v":1,"queue_id":..,"pages":[{"id","url",
  "mode","full_page","wait_ms","notes"}]}, max 20 pages, sent as message body
  or capture-queue.json attachment;
- result: one {"type":"capture-result",...} JSON message per page plus PNG
  attachments captioned "capture-result-file:<page_id>:<name>", then a run
  summary.

This module only parses, builds and routes. Sending/receiving stays in
bot_runtime, which wires these functions into the live message path. The
route is inert until state carries bidik_peer_addr (explicit setup), so a
bot without a paired Bidik answers the command with a clear refusal.
"""

import hashlib
import json
import re

QUEUE_TYPE = "capture-queue"
RESULT_TYPE = "capture-result"
VERSION = 1
MAX_PAGES = 20
DEFAULT_WAIT_MS = 3000
MAX_WAIT_MS = 15000

_URL_RE = re.compile(r"^https?://[^\s/$.?#].[^\s]*$", re.IGNORECASE)
_CMD_RE = re.compile(r"^capture\b(.*)$", re.IGNORECASE | re.DOTALL)


def parse_capture_command(text):
    """Return the list of URLs from a 'capture <url> [url...]' command,
    or None when the text is not a capture command. Raises ValueError for
    a command whose arguments are not usable http(s) URLs."""
    if not text:
        return None
    m = _CMD_RE.match(text.strip())
    if not m:
        return None
    parts = m.group(1).split()
    if not parts:
        raise ValueError("capture needs at least one URL")
    if len(parts) > MAX_PAGES:
        raise ValueError("too many URLs: %d (max %d)" % (len(parts), MAX_PAGES))
    bad = [p for p in parts if not _URL_RE.match(p)]
    if bad:
        raise ValueError("not an http(s) URL: %s" % bad[0][:80])
    return parts


def build_queue_doc(urls, queue_id=None, wait_ms=DEFAULT_WAIT_MS, notes=""):
    """Build the capture-queue document Bidik consumes. Page ids are p1..pN
    so result captions line up with the request order."""
    if not urls:
        raise ValueError("queue needs at least one page")
    if len(urls) > MAX_PAGES:
        raise ValueError("too many pages: %d (max %d)" % (len(urls), MAX_PAGES))
    wait_ms = max(0, min(int(wait_ms), MAX_WAIT_MS))
    doc = {
        "type": QUEUE_TYPE,
        "v": VERSION,
        "pages": [
            {
                "id": "p%d" % (i + 1),
                "url": u,
                "mode": "webview",
                "full_page": True,
                "wait_ms": wait_ms,
                "notes": notes[:500],
            }
            for i, u in enumerate(urls)
        ],
    }
    if queue_id:
        doc["queue_id"] = queue_id
    else:
        # Same derivation Bidik applies to id-less docs, so both sides agree
        # on the id a re-sent queue dedupes to.
        doc["queue_id"] = hashlib.sha256(
            json.dumps(doc, sort_keys=True).encode()).hexdigest()[:16]
    return doc


def parse_result(text):
    """Return the capture-result dict when text carries one, else None."""
    if not text:
        return None
    t = text.strip()
    if not t.startswith("{"):
        return None
    try:
        doc = json.loads(t)
    except Exception:
        return None
    return doc if doc.get("type") == RESULT_TYPE else None


def result_status_line(result):
    """One human line per page result, for forwarding into the request chat."""
    page = result.get("page_id") or result.get("id") or "page"
    status = result.get("status", "?")
    if status == "ok":
        n = len(result.get("captures") or [])
        ms = result.get("load_ms")
        timing = (" in %dms" % ms) if isinstance(ms, int) else ""
        return "%s: ok, %d capture%s%s" % (page, n, "" if n == 1 else "s", timing)
    err = (result.get("error") or "unknown error")[:200]
    return "%s: failed - %s" % (page, err)


def parse_result_caption(text):
    """Return (page_id, filename) from a capture-result-file caption, else None."""
    if not text or not text.startswith("capture-result-file:"):
        return None
    rest = text[len("capture-result-file:"):]
    page, sep, name = rest.partition(":")
    if not sep or not page or not name:
        return None
    return page, name


class RouteTable:
    """Pending queue_id -> requester chat_id, as a plain dict for state."""

    KEY = "capture_routes"

    @staticmethod
    def add(state, queue_id, chat_id):
        routes = state.setdefault(RouteTable.KEY, {})
        routes[queue_id] = chat_id
        return routes

    @staticmethod
    def lookup(state, queue_id):
        return state.get(RouteTable.KEY, {}).get(queue_id)

    @staticmethod
    def drop(state, queue_id):
        return state.get(RouteTable.KEY, {}).pop(queue_id, None)
