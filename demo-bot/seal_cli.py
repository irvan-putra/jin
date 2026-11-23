"""CLI for the workflow: seal <state_dir> <out> | unseal <in> <state_dir>. Key from env DEMO_STATE_KEY."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import statebox
key = os.environ["DEMO_STATE_KEY"].strip().encode()
if sys.argv[1] == "seal":
    statebox.seal(sys.argv[2], sys.argv[3], key)
else:
    statebox.unseal(sys.argv[2], sys.argv[3], key)
