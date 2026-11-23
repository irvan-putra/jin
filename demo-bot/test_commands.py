import unittest, commands as c


class T(unittest.TestCase):
    def test_chats_are_isolated(self):
        L = {}
        c.handle(L, "a", "add one"); c.handle(L, "b", "add two")
        self.assertEqual(c.handle(L, "a", "list"), "1. one")
        self.assertEqual(c.handle(L, "b", "list"), "1. two")

    def test_empty_list_and_unknown(self):
        self.assertIn("empty", c.handle({}, "a", "list"))
        self.assertEqual(c.handle({}, "a", "dance"), "Echo: dance")
        self.assertIsNone(c.handle({}, "a", "   "))

    def test_done_and_clear(self):
        L = {}
        c.handle(L, "a", "add x"); c.handle(L, "a", "add y")
        self.assertIn("Removed: x", c.handle(L, "a", "done 1"))
        self.assertEqual(c.handle(L, "a", "list"), "1. y")
        self.assertIn("Usage", c.handle(L, "a", "done 9"))
        self.assertEqual(c.handle(L, "a", "clear"), "Cleared.")
        self.assertNotIn("a", L)

    def test_caps(self):
        L = {}
        for i in range(c.MAX_ITEMS):
            c.handle(L, "a", "add %d" % i)
        self.assertIn("full", c.handle(L, "a", "add more"))
        L2 = {}
        c.handle(L2, "a", "add " + "x" * 999)
        self.assertEqual(len(L2["a"][0]), c.MAX_TEXT)
        L3 = {}
        for i in range(c.MAX_CHATS + 5):
            c.handle(L3, str(i), "add a")
        self.assertEqual(len(L3), c.MAX_CHATS)

    def test_help_says_not_private(self):
        self.assertIn("Not private", c.handle({}, "a", "help"))

    def test_no_admin_commands(self):
        for cmd in ("publish x", "kick", "admin", "shell ls", "capture http://x"):
            self.assertEqual(c.handle({}, "a", cmd), "Echo: " + cmd)


class S(unittest.TestCase):
    def test_seal_roundtrip_and_wrong_key(self):
        import os, tempfile, statebox
        from cryptography.fernet import Fernet, InvalidToken
        k = Fernet.generate_key(); d = tempfile.mkdtemp(); out = tempfile.mkdtemp()
        os.makedirs(d + "/accounts"); open(d + "/lists.json", "w").write("{}"); open(d + "/accounts/x", "w").write("k")
        statebox.seal(d, out + "/s.bin", k)
        self.assertNotIn(b"lists.json", open(out + "/s.bin", "rb").read())
        r = tempfile.mkdtemp(); statebox.unseal(out + "/s.bin", r, k)
        self.assertEqual(open(r + "/accounts/x").read(), "k")
        with self.assertRaises(InvalidToken):
            statebox.unseal(out + "/s.bin", tempfile.mkdtemp(), Fernet.generate_key())


if __name__ == "__main__":
    unittest.main()
