from syntricdb.client import SyntricDBClient
from syntricdb.langchain import SyntricDBVectorStore
from syntricdb.llamaindex import SyntricDBLlamaIndexVectorStore

from syntricdb.connection import Connection
from syntricdb.exceptions import (
    Warning,
    Error,
    InterfaceError,
    DatabaseError,
    DataError,
    OperationalError,
    IntegrityError,
    InternalError,
    ProgrammingError,
    NotSupportedError,
)
from syntricdb.dbapi_types import (
    Date,
    Time,
    Timestamp,
    DateFromTicks,
    TimeFromTicks,
    TimestampFromTicks,
    Binary,
    STRING,
    BINARY,
    NUMBER,
    DATETIME,
    ROWID,
)

# PEP 249 (DB-API 2.0) module globals
apilevel = "2.0"
threadsafety = 1  # threads may share the module, but not a Connection
paramstyle = "qmark"


def connect(dsn=None, *, host="localhost", port=8080, user="admin",
            password="syntricdb_secret_pass", database="default",
            autocommit=False, timeout=15.0):
    """
    Open a PEP 249 (DB-API 2.0) connection to SyntricDB.

    Either pass a connection string::

        conn = syntricdb.connect("syntricdb://admin:secret@localhost:8080/default")

    or individual keyword arguments::

        conn = syntricdb.connect(host="localhost", port=8080, user="admin",
                                  password="secret", database="default")
    """
    if dsn:
        return Connection.from_dsn(dsn, autocommit=autocommit, timeout=timeout)
    return Connection(host=host, port=port, user=user, password=password,
                       database=database, autocommit=autocommit, timeout=timeout)


__all__ = [
    "SyntricDBClient", "SyntricDBVectorStore", "SyntricDBLlamaIndexVectorStore",
    "connect", "Connection",
    "apilevel", "threadsafety", "paramstyle",
    "Warning", "Error", "InterfaceError", "DatabaseError", "DataError",
    "OperationalError", "IntegrityError", "InternalError", "ProgrammingError",
    "NotSupportedError",
    "Date", "Time", "Timestamp", "DateFromTicks", "TimeFromTicks", "TimestampFromTicks",
    "Binary", "STRING", "BINARY", "NUMBER", "DATETIME", "ROWID",
]
