"""Server state database: bundle versions, meta, and the single-writer lease.

DSN-driven. Two drivers:

  sqlite:///<abs path>   stdlib sqlite3 - dev and tests only. On NEO App the
                         container filesystem is ephemeral, so a sqlite DSN
                         there loses state on every redeploy. It must NEVER
                         be the production DSN.
  postgres://...         NEO App managed PostgreSQL 14-16 via psycopg 3
  postgresql://...       (pinned in requirements). The exact host, port, TLS
                         mode and injected env names are deploy-time config;
                         official public docs do not specify them, so nothing
                         about the connection is hardcoded here.

The schema is deliberately tiny and portable across both engines. All state
rows are byte bundles produced by state_sync; the ops stores themselves ride
inside the bundles (see server/README.md for why).
"""
import os
import time


class DbError(Exception):
    pass


def _sqlite_conn(path):
    import sqlite3
    conn = sqlite3.connect(path, timeout=30, isolation_level=None)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=30000")
    return conn


class Db:
    """Thin wrapper hiding the sqlite/postgres dialect differences."""

    def __init__(self, dsn):
        self.dsn = dsn
        if dsn.startswith("sqlite:///"):
            self.kind = "sqlite"
            self.ph = "?"
            self.conn = _sqlite_conn(dsn[len("sqlite:///"):])
        elif dsn.startswith("postgres://") or dsn.startswith("postgresql://"):
            self.kind = "pg"
            self.ph = "%s"
            try:
                import psycopg
            except ImportError:
                raise DbError("postgres DSN but psycopg is not installed")
            self.conn = psycopg.connect(dsn, autocommit=True)
        else:
            raise DbError("unsupported DSN scheme (want sqlite:/// or postgres://)")
        self._setup()

    def _setup(self):
        if self.kind == "sqlite":
            idcol = "INTEGER PRIMARY KEY AUTOINCREMENT"
            blob = "BLOB"
        else:
            idcol = "BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY"
            blob = "BYTEA"
        cur = self.conn.cursor()
        cur.execute("CREATE TABLE IF NOT EXISTS jin_meta ("
                    "k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        cur.execute("CREATE TABLE IF NOT EXISTS jin_bundles ("
                    "id %s, created_at DOUBLE PRECISION NOT NULL, "
                    "sha256 TEXT NOT NULL, bytes %s NOT NULL)" % (idcol, blob))
        cur.execute("CREATE TABLE IF NOT EXISTS jin_lease ("
                    "id INTEGER PRIMARY KEY, holder TEXT NOT NULL, "
                    "expires_at DOUBLE PRECISION NOT NULL)")

    def close(self):
        try:
            self.conn.close()
        except Exception:
            pass

    # -- bundle versions ----------------------------------------------------

    def put_bundle(self, data, keep=3):
        """Insert one bundle version, then prune all but the newest `keep`."""
        import hashlib
        ph = self.ph
        cur = self.conn.cursor()
        digest = hashlib.sha256(data).hexdigest()
        cur.execute(
            "INSERT INTO jin_bundles (created_at, sha256, bytes) VALUES "
            "(%s, %s, %s)" % (ph, ph, ph), (time.time(), digest, data))
        cur.execute(
            "DELETE FROM jin_bundles WHERE id NOT IN "
            "(SELECT id FROM jin_bundles ORDER BY id DESC LIMIT %d)" % keep)
        return digest

    def latest_bundle(self):
        cur = self.conn.cursor()
        cur.execute("SELECT id, created_at, sha256, bytes FROM jin_bundles "
                    "ORDER BY id DESC LIMIT 1")
        row = cur.fetchone()
        if not row:
            return None
        return {"id": row[0], "created_at": row[1], "sha256": row[2],
                "bytes": bytes(row[3])}

    def bundle_count(self):
        cur = self.conn.cursor()
        cur.execute("SELECT COUNT(*) FROM jin_bundles")
        return cur.fetchone()[0]

    # -- single-writer lease ------------------------------------------------
    # One row (id=1). Acquisition is an atomic conditional update, so two
    # pods starting together cannot both hold it. A crashed holder's lease
    # expires and is taken over after ttl seconds.

    def acquire_lease(self, holder, ttl_s):
        ph = self.ph
        now = time.time()
        cur = self.conn.cursor()
        cur.execute("DELETE FROM jin_lease WHERE id = 1 AND expires_at < %s"
                    % ph, (now,))
        try:
            cur.execute("INSERT INTO jin_lease (id, holder, expires_at) "
                        "VALUES (1, %s, %s)" % (ph, ph),
                        (holder, now + ttl_s))
        except Exception:
            # row exists and is held: take it only if it is ours already
            cur.execute("UPDATE jin_lease SET expires_at = %s "
                        "WHERE id = 1 AND holder = %s" % (ph, ph),
                        (now + ttl_s, holder))
            if cur.rowcount != 1:
                return False
        return self._lease_holder() == holder

    def _lease_holder(self):
        cur = self.conn.cursor()
        cur.execute("SELECT holder FROM jin_lease WHERE id = 1")
        row = cur.fetchone()
        return row[0] if row else None

    def renew_lease(self, holder, ttl_s):
        ph = self.ph
        cur = self.conn.cursor()
        cur.execute("UPDATE jin_lease SET expires_at = %s "
                    "WHERE id = 1 AND holder = %s" % (ph, ph),
                    (time.time() + ttl_s, holder))
        return cur.rowcount == 1

    def release_lease(self, holder):
        ph = self.ph
        cur = self.conn.cursor()
        cur.execute("DELETE FROM jin_lease WHERE id = 1 AND holder = %s" % ph,
                    (holder,))

    # -- meta ---------------------------------------------------------------

    def get_meta(self, k):
        cur = self.conn.cursor()
        cur.execute("SELECT v FROM jin_meta WHERE k = %s" % self.ph, (k,))
        row = cur.fetchone()
        return row[0] if row else None

    def put_meta(self, k, v):
        ph = self.ph
        cur = self.conn.cursor()
        if self.kind == "sqlite":
            cur.execute("INSERT INTO jin_meta (k, v) VALUES (%s, %s) "
                        "ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                        % (ph, ph), (k, v))
        else:
            cur.execute("INSERT INTO jin_meta (k, v) VALUES (%s, %s) "
                        "ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                        % (ph, ph), (k, v))

    def ping(self):
        cur = self.conn.cursor()
        cur.execute("SELECT 1")
        cur.fetchone()
        return True
