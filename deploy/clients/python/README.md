# 🐍 SyntricDB Official Python SDK (`pip install syntricdb-client`)

Official Python Client for **SyntricDB**—the AI-Native Unified Database Engine.

---

## ⚡ Quick Start

```bash
pip install syntricdb-client
```

```python
from syntricdb import SyntricDBClient

client = SyntricDBClient(host="http://localhost:8080")

# Perform Hybrid SQL + Vector Search
results = client.vector_search(
    table="developers",
    query_text="LLM fine-tuning vector index",
    top_k=3,
    where_clause="experience_years > 4"
)

print(results)
```

---

## 🔌 PEP 249 (DB-API 2.0) Driver

For real applications — anything that expects a standard Python database driver
(SQLAlchemy, Django, or your own connection-pooling code) — use `syntricdb.connect()`
instead of `SyntricDBClient`. It implements the standard `Connection`/`Cursor`
interface (`apilevel`, `paramstyle`, exception hierarchy, type constructors) so it
behaves like `sqlite3`, `psycopg2`, or `pymysql`.

```python
import syntricdb

conn = syntricdb.connect(
    "syntricdb://admin:syntricdb_secret_pass@localhost:8080/default"
)
# or: syntricdb.connect(host="localhost", port=8080, user="admin",
#                        password="...", database="default")

with conn:
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO developers (id, name, role) VALUES (?, ?, ?)",
        ("dev_1", "Alice", "Senior Engineer"),
    )
    cur.execute("SELECT id, name FROM developers WHERE role LIKE ?", ("%Engineer%",))
    for row in cur:
        print(row)
    conn.commit()
```

**Transactions**: `autocommit` defaults to `False`, matching `sqlite3`/`psycopg2`
convention — the connection opens an implicit transaction on connect, and you must
call `conn.commit()` or `conn.rollback()`. Pass `autocommit=True` to `connect()` for
immediate-effect statements (each call to `.execute()` on `INSERT`/`UPDATE`/`DELETE`
takes effect right away, no explicit commit needed). Under a manual transaction,
only `INSERT`/`UPDATE`/`DELETE` are deferred until commit — `CREATE`/`DROP`/`SELECT`
run immediately and only ever see already-committed data (there's no
read-your-own-writes while a transaction is open).

**Errors** map onto the standard PEP 249 hierarchy: bad credentials raise
`InterfaceError`, a malformed or rejected statement raises `ProgrammingError`, and a
connection failure raises `OperationalError`.

Run the driver's own test suite against a live server:

```bash
pip install pytest
SYNTRICDB_TEST_HOST=localhost SYNTRICDB_TEST_PORT=8080 pytest tests/test_dbapi.py -v
```
