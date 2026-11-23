"""Checkpoint/restore of the full bot state to the state database.

One bundle = migration.py's proven .dcmig (core account backup + bot state,
Fernet-wrapped under the operator-held transfer code). Bundles are versioned
rows in the state database (jin_bundles), so a fresh container boots from
the newest row, and a bounded tail of older rows survives a bad checkpoint.

Live-safety: the phone export paused the bot first, so migration.py's
build_state_tar reads quiescent files. Here the bot KEEPS RUNNING during
interval checkpoints, so ops-*.db files are copied through sqlite's online
backup API before tarring (torn page images otherwise). The core account
backup is exported by the core itself (acct.export_backup), which is
consistent while running - same call DeltaChat desktop makes.

The transfer code comes from the environment and is never logged.
"""
import io
import json
import logging
import os
import shutil
import tarfile
import tempfile
import time

import migration

log = logging.getLogger("jin.state_sync")

BUNDLE_TMP = ".checkpoint-bundle.tmp"


def _safe_state_tar(files_dir):
    """migration.build_state_tar with sqlite-online-backup copies of *.db.

    Every *.db member is first duplicated via the sqlite backup API into a
    temp file, so the tar never contains a half-written page image. Non-db
    members (bot_state.json) are small and written atomically by their
    writers, so they are read directly.
    """
    members = migration.state_members(files_dir)
    tmp = tempfile.mkdtemp(prefix="jin-statetar-")
    try:
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w:gz") as tar:
            for name in members:
                src = os.path.join(files_dir, name)
                if name.endswith(".db"):
                    safe = _sqlite_snapshot(src, tmp)
                    tar.add(safe, arcname=name)
                else:
                    tar.add(src, arcname=name)
        return buf.getvalue()
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def _sqlite_snapshot(path, tmpdir):
    import sqlite3
    dst = os.path.join(tmpdir, os.path.basename(path))
    src = sqlite3.connect("file:%s?mode=ro" % path, uri=True)
    try:
        out = sqlite3.connect(dst)
        try:
            src.backup(out)
        finally:
            out.close()
    finally:
        src.close()
    return dst


def checkpoint(files_dir, acct, db, code, keep=3):
    """Export account + state, encrypt, store as a new bundle row.

    Returns a stats dict (never contains the code or payload bytes).
    The live-safe tar builder is swapped into migration.build_bundle only
    for the duration of this call so other consumers of migration.py (and
    its tests, which use fake non-sqlite ops files) keep the stock
    behavior."""
    tmp = tempfile.mkdtemp(prefix="jin-checkpoint-")
    orig_tar = migration.build_state_tar
    migration.build_state_tar = _safe_state_tar
    try:
        acct.export_backup(tmp, code)
        produced = [n for n in os.listdir(tmp) if n.endswith(".tar")]
        if len(produced) != 1:
            raise migration.MigrationError(
                "core backup produced %s in %s" % (produced or "nothing", tmp))
        bundle_path = os.path.join(tmp, "bundle.dcmig")
        migration.build_bundle(os.path.join(tmp, produced[0]), files_dir,
                               bundle_path, code=code)
        with open(bundle_path, "rb") as f:
            data = f.read()
        digest = db.put_bundle(data, keep=keep)
        stats = {"bytes": len(data), "sha256": digest,
                 "kept": db.bundle_count()}
        log.info("checkpoint stored: %d bytes, sha256 %s..., %d kept",
                 stats["bytes"], digest[:12], stats["kept"])
        return stats
    finally:
        migration.build_state_tar = orig_tar
        shutil.rmtree(tmp, ignore_errors=True)


def restore(files_dir, db, code, rpc_factory):
    """Restore the newest bundle into files_dir. Returns True if a bundle
    was found and committed, False when no bundle exists yet (first boot)."""
    latest = db.latest_bundle()
    if latest is None:
        log.info("no bundle in state database - first boot")
        return False
    tmp = tempfile.mkdtemp(prefix="jin-restore-")
    try:
        path = os.path.join(tmp, "bundle.dcmig")
        with open(path, "wb") as f:
            f.write(latest["bytes"])
        migration.import_migration(None, files_dir, path, code,
                                   _rpc_factory=rpc_factory)
        log.info("restored bundle id %s (created %s)",
                 latest["id"], time.ctime(latest["created_at"]))
        return True
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def dirty_since(files_dir, since_ts):
    """True when any state member changed after since_ts (mtime-based)."""
    for name in migration.state_members(files_dir):
        try:
            if os.path.getmtime(os.path.join(files_dir, name)) > since_ts:
                return True
        except OSError:
            continue
    # accounts/ changes are not in state_members (core backup carries them);
    # treat any core db mtime change as dirty too.
    accts = os.path.join(files_dir, "accounts")
    if os.path.isdir(accts):
        for root, _dirs, files in os.walk(accts):
            for n in files:
                try:
                    if os.path.getmtime(os.path.join(root, n)) > since_ts:
                        return True
                except OSError:
                    continue
    return False
