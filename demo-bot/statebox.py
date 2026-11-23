"""Encrypt/restore the bot's state directory between runs.
The directory (account keys and lists) is packed and encrypted with a
Fernet key from the environment. Only ciphertext ever leaves the runner."""
import io, os, tarfile
from cryptography.fernet import Fernet


def seal(state_dir, out_path, key):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        for name in sorted(os.listdir(state_dir)):
            tar.add(os.path.join(state_dir, name), arcname=name)
    with open(out_path, "wb") as f:
        f.write(Fernet(key).encrypt(buf.getvalue()))


def unseal(in_path, state_dir, key):
    data = Fernet(key).decrypt(open(in_path, "rb").read())
    os.makedirs(state_dir, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        for m in tar.getmembers():
            if m.name.startswith(("/", "..")) or ".." in m.name.split("/") or not (m.isfile() or m.isdir()):
                raise ValueError("unsafe path in state bundle")
        tar.extractall(state_dir)
