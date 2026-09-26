import urllib.parse

from . import exceptions
from ._transport import Transport
from .cursor import Cursor


class Connection:
    """PEP 249 Connection. Created via syntricdb.connect(), never directly.

    Autocommit defaults to False (matching sqlite3/psycopg2 convention): the
    connection opens an implicit transaction that must be closed with commit()
    or rollback(). Only INSERT/UPDATE/DELETE participate in that transaction —
    they're queued server-side and applied atomically on commit, or discarded
    entirely on rollback. CREATE/DROP/SELECT run immediately regardless of
    transaction state and always see only already-committed data (no
    read-your-own-writes within an open transaction).
    """

    def __init__(self, host="localhost", port=8080, user="admin",
                 password="syntricdb_secret_pass", database="default",
                 autocommit=False, timeout=15.0):
        self._transport = Transport(host, port, user, password, timeout)
        self.database = database or "default"
        self._autocommit = True  # set for real via the property setter below
        self._txn_id = None
        self._closed = False
        self._verify()
        self.autocommit = autocommit

    @classmethod
    def from_dsn(cls, dsn, **overrides):
        clean = dsn.replace("jdbc:syntricdb://", "http://").replace("syntricdb://", "http://")
        parsed = urllib.parse.urlparse(clean)
        kwargs = {
            "host": parsed.hostname or "localhost",
            "port": parsed.port or 8080,
            "user": urllib.parse.unquote(parsed.username) if parsed.username else "admin",
            "password": urllib.parse.unquote(parsed.password) if parsed.password else "syntricdb_secret_pass",
            "database": parsed.path.strip("/") or "default",
        }
        kwargs.update(overrides)
        return cls(**kwargs)

    def _verify(self):
        try:
            resp = self._transport.request("GET", "/api/auth/verify")
            if not resp.get("success"):
                raise exceptions.InterfaceError(
                    f"SyntricDB authentication failed for user '{self._transport.user}'"
                )
        except exceptions.InterfaceError:
            raise
        except exceptions.Error:
            health = self._transport.request("GET", "/api/health")
            if health.get("status") != "UP":
                raise exceptions.OperationalError("Could not connect to SyntricDB server")

    @property
    def autocommit(self):
        return self._autocommit

    @autocommit.setter
    def autocommit(self, value):
        self._check_closed()
        value = bool(value)
        if value == self._autocommit:
            return
        if not value:
            self._begin()
        elif self._txn_id is not None:
            self.commit()
        self._autocommit = value

    def _begin(self):
        resp = self._transport.request("POST", "/api/transaction/begin", {})
        txn_id = resp.get("txnId")
        if txn_id is None:
            raise exceptions.OperationalError("Server did not return a transaction id for BEGIN")
        self._txn_id = txn_id

    def commit(self):
        self._check_closed()
        if self._txn_id is not None:
            self._transport.request("POST", "/api/transaction/commit", {"txnId": self._txn_id})
            self._txn_id = None
            if not self._autocommit:
                self._begin()

    def rollback(self):
        self._check_closed()
        if self._txn_id is not None:
            self._transport.request("POST", "/api/transaction/rollback", {"txnId": self._txn_id})
            self._txn_id = None
            if not self._autocommit:
                self._begin()

    def cursor(self):
        self._check_closed()
        return Cursor(self)

    def close(self):
        if not self._closed and self._txn_id is not None:
            try:
                self._transport.request("POST", "/api/transaction/rollback", {"txnId": self._txn_id})
            except exceptions.Error:
                pass  # best-effort: the connection is going away regardless
        self._closed = True

    def _check_closed(self):
        if self._closed:
            raise exceptions.InterfaceError("Connection is closed")

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()

    def _execute_sql(self, sql):
        self._check_closed()
        body = {"sql": sql, "database": self.database}
        if self._txn_id is not None:
            body["txnId"] = self._txn_id
        return self._transport.request("POST", "/api/sql", body)
