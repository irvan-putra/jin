"""Systemic guard: the server package must contain the full transitive
import closure of the hosted runtime, minus documented exclusions.

Walks every import statement in server/app/*.py (stdlib and third-party
excepted) and asserts each local module resolves to a file in server/app.
This is the test that would have caught the missing bot.py /
delivery_bot.py dependency before deploy.
"""
import ast
import os
import sys
import unittest

APP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app")

# Local modules deliberately NOT shipped in this package:
# - capture_route: parked phone-only bidik integration (its handler is
#   disabled by srv_rpc.patch_bot_runtime when absent)
# - live_relay: CI-only phone modes (liveroute/selfecho), never entered by
#   the hosted service
# - bot_validation, turso_store: phone/desktop tooling, not hosted
# - libsql_experimental: optional in ops_store._new_conn (ImportError
#   falls back to stdlib sqlite); the hosted service only opens local
#   sqlite files, so neither libsql branch is reachable
EXCLUDED = {"capture_route", "live_relay", "bot_validation", "turso_store",
            "libsql_experimental"}


def local_imports(path):
    with open(path) as f:
        tree = ast.parse(f.read(), path)
    mods = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for a in node.names:
                mods.add(a.name.split(".")[0])
        elif isinstance(node, ast.ImportFrom) and node.level == 0 and \
                node.module:
            mods.add(node.module.split(".")[0])
    return mods


class DepClosureTest(unittest.TestCase):
    def test_full_transitive_closure(self):
        present = {n[:-3] for n in os.listdir(APP) if n.endswith(".py")}
        seen, frontier = set(), set(present)
        missing = {}
        while frontier:
            mod = frontier.pop()
            if mod in seen:
                continue
            seen.add(mod)
            for dep in local_imports(os.path.join(APP, mod + ".py")):
                if dep in present:
                    frontier.add(dep)
                elif dep in EXCLUDED:
                    continue
                else:
                    import importlib.util
                    # stdlib/third-party is fine; anything else must ship
                    if importlib.util.find_spec(dep) is None:
                        missing.setdefault(mod, set()).add(dep)
        self.assertEqual(missing, {},
                         "server/app is missing modules: %s" % missing)

    def test_exclusions_stay_excluded(self):
        present = {n[:-3] for n in os.listdir(APP) if n.endswith(".py")}
        self.assertFalse(EXCLUDED & present,
                         "excluded modules must not be packaged: %s"
                         % (EXCLUDED & present))


if __name__ == "__main__":
    unittest.main()
