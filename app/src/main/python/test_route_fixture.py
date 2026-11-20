"""Host tests for the routefixture mode (bot_runtime.run_route_fixture):
the REAL BotRuntime.on_new_message capture route over the fixture account."""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import bot_runtime as br

class RouteFixtureTest(unittest.TestCase):
    """The routefixture path itself: the REAL BotRuntime.on_new_message
    (capture route included) over the fixture account, no account, no
    network. Proves the queue doc leaves for the pinned peer's own chat
    and results/files route back to the requesting chat only."""

    PEER = "bidik@relay.example"

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        br.save_state(self.dir.name, bidik_peer_addr=self.PEER)
        self.runtime, self.acct = br._route_fixture_runtime(self.dir.name)

    def tearDown(self):
        self.dir.cleanup()

    def sends(self):
        path = os.path.join(self.dir.name, "fixture-sends.jsonl")
        if not os.path.exists(path):
            return []
        with open(path) as f:
            return [json.loads(l) for l in f if l.strip()]

    def _inject(self, **spec):
        br._route_fixture_apply(self.dir.name, self.runtime, self.acct, spec)

    def test_capture_round_trip_routes_to_requester_only(self):
        self._inject(chat_id=11, from_id=2, **{
            "from": "user@x.example",
            "text": "capture https://example.com/"})
        peer_chat = self.acct.peer_chat_id(self.PEER)
        to_peer = [x for x in self.sends() if x["chat_id"] == peer_chat]
        to_chat = [x for x in self.sends() if x["chat_id"] == 11]
        self.assertEqual(len(to_peer), 1, "queue doc must leave for the peer")
        doc = json.loads(to_peer[0]["text"])
        self.assertEqual(doc["type"], "capture-queue")
        self.assertEqual(doc["pages"][0]["url"], "https://example.com/")
        qid = doc["queue_id"]
        self.assertEqual(len(to_chat), 1, "ack lands alongside ingest")
        self.assertIn("queued", to_chat[0]["text"])

        result = {"type": "capture-result", "queue_id": qid, "page_id": "p1",
                  "captures": ["a.png"], "status": "ok", "load_ms": 500}
        self._inject(chat_id=peer_chat, from_id=3,
                     **{"from": self.PEER, "text": json.dumps(result)})
        self._inject(chat_id=peer_chat, from_id=3, file="/tmp/a.png",
                     **{"from": self.PEER,
                        "text": "capture-result-file:%s:p1:a.png" % qid})
        sends = self.sends()
        fwd_text = [x for x in sends
                    if x["chat_id"] == 11 and x["text"] and "p1: ok" in x["text"]]
        fwd_file = [x for x in sends
                    if x["chat_id"] == 11 and x["file"] == "/tmp/a.png"]
        self.assertEqual(len(fwd_text), 1)
        self.assertEqual(len(fwd_file), 1)
        # nothing about the result may leak to any other chat
        others = [x for x in sends
                  if x["chat_id"] not in (11, peer_chat)]
        self.assertEqual(others, [])

    def test_result_from_non_peer_and_stale_file_refused(self):
        self._inject(chat_id=11, from_id=2, **{
            "from": "user@x.example",
            "text": "capture https://example.com/"})
        peer_chat = self.acct.peer_chat_id(self.PEER)
        doc = json.loads([x for x in self.sends()
                          if x["chat_id"] == peer_chat][0]["text"])
        qid = doc["queue_id"]
        n0 = len(self.sends())
        # a result-shaped message from someone else is not the peer:
        impostor = {"type": "capture-result", "queue_id": qid,
                    "page_id": "p1", "captures": [], "status": "ok"}
        self._inject(chat_id=11, from_id=9,
                     **{"from": "mallory@x.example",
                        "text": json.dumps(impostor)})
        # a stale/foreign queue's file from the peer: refused
        self._inject(chat_id=peer_chat, from_id=3,
                     **{"from": self.PEER,
                        "text": "capture-result-file:oldqueue:p1:a.png"})
        self.assertEqual(len(self.sends()), n0, "no sends may result")


if __name__ == "__main__":
    unittest.main()
