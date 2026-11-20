import json
import unittest

import capture_route as cr


class ParseCommandTest(unittest.TestCase):
    def test_not_a_command(self):
        self.assertIsNone(cr.parse_capture_command("hello"))
        self.assertIsNone(cr.parse_capture_command(""))
        self.assertIsNone(cr.parse_capture_command(None))
        self.assertIsNone(cr.parse_capture_command("captured this earlier"))

    def test_single_url(self):
        self.assertEqual(cr.parse_capture_command("capture https://example.com/"),
                         ["https://example.com/"])

    def test_multiple_urls(self):
        urls = cr.parse_capture_command(
            "capture https://a.example/ https://b.example/x")
        self.assertEqual(urls, ["https://a.example/", "https://b.example/x"])

    def test_rejects_bad_scheme_and_empty(self):
        with self.assertRaises(ValueError):
            cr.parse_capture_command("capture")
        with self.assertRaises(ValueError):
            cr.parse_capture_command("capture ftp://x.example/")
        with self.assertRaises(ValueError):
            cr.parse_capture_command("capture notaurl")

    def test_caps_page_count(self):
        with self.assertRaises(ValueError):
            cr.parse_capture_command(
                "capture " + " ".join("https://e.example/%d" % i
                                      for i in range(cr.MAX_PAGES + 1)))


class BuildQueueTest(unittest.TestCase):
    def test_doc_matches_bidik_contract(self):
        doc = cr.build_queue_doc(["https://example.com/"], queue_id="q-1")
        self.assertEqual(doc["type"], "capture-queue")
        self.assertEqual(doc["v"], 1)
        self.assertEqual(doc["queue_id"], "q-1")
        (p,) = doc["pages"]
        self.assertEqual(p["id"], "p1")
        self.assertEqual(p["url"], "https://example.com/")
        self.assertEqual(p["mode"], "webview")
        self.assertTrue(p["full_page"])
        self.assertEqual(p["wait_ms"], 3000)

    def test_wait_ms_clamped(self):
        doc = cr.build_queue_doc(["https://example.com/"], wait_ms=999999)
        self.assertEqual(doc["pages"][0]["wait_ms"], cr.MAX_WAIT_MS)

    def test_derived_id_matches_bidik_derivation(self):
        # Bidik derives the id from the doc WITHOUT the queue_id field;
        # build the same way and compare.
        import hashlib
        urls = ["https://example.com/"]
        doc = cr.build_queue_doc(urls)
        bare = {k: v for k, v in doc.items() if k != "queue_id"}
        expect = hashlib.sha256(
            json.dumps(bare, sort_keys=True).encode()).hexdigest()[:16]
        self.assertEqual(doc["queue_id"], expect)


class ResultTest(unittest.TestCase):
    def test_parse_result(self):
        self.assertIsNone(cr.parse_result("plain text"))
        self.assertIsNone(cr.parse_result('{"type":"capture-queue"}'))
        r = cr.parse_result('{"type":"capture-result","page_id":"p1","status":"ok","captures":["a.png"],"load_ms":1200}')
        self.assertEqual(r["page_id"], "p1")

    def test_status_lines(self):
        ok = {"page_id": "p1", "status": "ok", "captures": ["a", "b"], "load_ms": 1200}
        self.assertEqual(cr.result_status_line(ok), "p1: ok, 2 captures in 1200ms")
        bad = {"page_id": "p2", "status": "failed", "error": "boom"}
        self.assertEqual(cr.result_status_line(bad), "p2: failed - boom")

    def test_caption_parse(self):
        self.assertEqual(cr.parse_result_caption("capture-result-file:p1:shot.png"),
                         ("p1", "shot.png"))
        self.assertIsNone(cr.parse_result_caption("capture-result-file:p1"))
        self.assertIsNone(cr.parse_result_caption("hello"))


class RouteTableTest(unittest.TestCase):
    def test_add_lookup_drop(self):
        st = {}
        cr.RouteTable.add(st, "q-1", 42)
        self.assertEqual(cr.RouteTable.lookup(st, "q-1"), 42)
        self.assertIsNone(cr.RouteTable.lookup(st, "q-2"))
        cr.RouteTable.drop(st, "q-1")
        self.assertIsNone(cr.RouteTable.lookup(st, "q-1"))


class HandlerTest(unittest.TestCase):
    PEER = "bidik@x.example"

    def _queue(self, st, chat_id=7, url="https://example.com/"):
        acts = cr.handle_incoming(st, "user@x.example", chat_id,
                                  "capture " + url, True)
        doc = json.loads(acts[0]["text"])
        return doc["queue_id"]

    def _result(self, qid, page, names, chat=99):
        return json.dumps({"type": "capture-result", "queue_id": qid,
                           "page_id": page, "status": "ok",
                           "captures": names, "load_ms": 900})

    def test_command_without_peer_refuses(self):
        acts = cr.handle_incoming({}, "user@x.example", 7,
                                  "capture https://example.com/", True)
        self.assertEqual(acts[0]["action"], "reply")
        self.assertIn("not configured", acts[0]["text"])

    def test_command_builds_queue_and_route(self):
        st = {"bidik_peer_addr": self.PEER}
        qid = self._queue(st)
        self.assertEqual(cr.RouteTable.lookup(st, qid), 7)

    def test_command_ignored_outside_served_chat(self):
        st = {"bidik_peer_addr": self.PEER}
        self.assertEqual(cr.handle_incoming(st, "user@x.example", 7,
                                            "capture https://example.com/", False), [])

    def test_result_routes_and_pins_files(self):
        st = {"bidik_peer_addr": self.PEER}
        qid = self._queue(st)
        acts = cr.handle_incoming(st, self.PEER, 99,
                                  self._result(qid, "p1", ["a.png"]), False)
        self.assertEqual(acts, [{"action": "forward_status", "chat_id": 7,
                                 "text": "p1: ok, 1 capture in 900ms"}])
        f = cr.handle_incoming(st, self.PEER, 99,
                               "capture-result-file:p1:a.png", False)
        self.assertEqual(f, [{"action": "forward_file", "chat_id": 7,
                              "caption": "capture-result-file:p1:a.png"}])

    def test_interleaved_queues_cannot_cross_files(self):
        st = {"bidik_peer_addr": self.PEER}
        q1 = self._queue(st, chat_id=7)
        q2 = self._queue(st, chat_id=8, url="https://other.example/")
        # q1 result pins chat 7 / page p1 / a.png
        cr.handle_incoming(st, self.PEER, 99, self._result(q1, "p1", ["a.png"]), False)
        # q2 result for the SAME generic page id re-pins to chat 8
        cr.handle_incoming(st, self.PEER, 99, self._result(q2, "p1", ["b.png"]), False)
        # q1's late file now mismatches the pin: refused, never to chat 7 or 8
        self.assertEqual(cr.handle_incoming(st, self.PEER, 99,
                                            "capture-result-file:p1:a.png", False), [])
        # q2's declared file goes to chat 8
        f = cr.handle_incoming(st, self.PEER, 99,
                               "capture-result-file:p1:b.png", False)
        self.assertEqual(f[0]["chat_id"], 8)

    def test_undeclared_filename_refused(self):
        st = {"bidik_peer_addr": self.PEER}
        qid = self._queue(st)
        cr.handle_incoming(st, self.PEER, 99, self._result(qid, "p1", ["a.png"]), False)
        self.assertEqual(cr.handle_incoming(st, self.PEER, 99,
                                            "capture-result-file:p1:evil.png", False), [])

    def test_duplicate_file_and_result_refused(self):
        st = {"bidik_peer_addr": self.PEER}
        qid = self._queue(st)
        res = self._result(qid, "p1", ["a.png"])
        cr.handle_incoming(st, self.PEER, 99, res, False)
        # same result again: deduped
        self.assertEqual(cr.handle_incoming(st, self.PEER, 99, res, False), [])
        cap = "capture-result-file:p1:a.png"
        cr.handle_incoming(st, self.PEER, 99, cap, False)
        # same file again: deduped
        self.assertEqual(cr.handle_incoming(st, self.PEER, 99, cap, False), [])

    def test_file_without_any_result_refused(self):
        st = {"bidik_peer_addr": self.PEER, cr.RouteTable.KEY: {"q": 7}}
        self.assertEqual(cr.handle_incoming(st, self.PEER, 99,
                                            "capture-result-file:p1:a.png", False), [])

    def test_unsolicited_result_unknown_queue_refused(self):
        st = {"bidik_peer_addr": self.PEER}
        res = self._result("no-such-queue", "p1", ["a.png"])
        self.assertEqual(cr.handle_incoming(st, self.PEER, 99, res, False), [])

    def test_state_survives_json_restart(self):
        st = {"bidik_peer_addr": self.PEER}
        qid = self._queue(st)
        cr.handle_incoming(st, self.PEER, 99, self._result(qid, "p1", ["a.png"]), False)
        # restart: state round-trips through json exactly like save/load
        st2 = json.loads(json.dumps(st))
        f = cr.handle_incoming(st2, self.PEER, 99,
                               "capture-result-file:p1:a.png", False)
        self.assertEqual(f[0]["chat_id"], 7)

    def test_sender_match_is_exact_not_substring(self):
        st = {"bidik_peer_addr": self.PEER}
        res = self._result("q", "p1", ["a.png"])
        self.assertEqual(cr.handle_incoming(st, "evil-bidik@x.example", 99, res, False), [])

    def test_peer_chatter_not_forwarded_without_pending(self):
        st = {"bidik_peer_addr": self.PEER}
        self.assertEqual(cr.handle_incoming(st, self.PEER, 99,
                                            "pairing hello", False), [])

    def test_bad_command_replies_error(self):
        st = {"bidik_peer_addr": self.PEER}
        acts = cr.handle_incoming(st, "user@x.example", 7, "capture", True)
        self.assertEqual(acts[0]["action"], "reply")
        self.assertIn("at least one URL", acts[0]["text"])


if __name__ == "__main__":
    unittest.main()
