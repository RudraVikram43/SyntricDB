"""
End-to-end tests for the PEP 249 (DB-API 2.0) driver against a live SyntricDB
server. Requires a running server; point at it with SYNTRICDB_TEST_PORT
(default 8080) and SYNTRICDB_TEST_HOST (default localhost).
"""

import itertools
import os
import uuid

import pytest

import syntricdb

HOST = os.environ.get("SYNTRICDB_TEST_HOST", "localhost")
PORT = int(os.environ.get("SYNTRICDB_TEST_PORT", "8080"))

_table_counter = itertools.count()


def _unique_table():
    return f"dbapi_test_{next(_table_counter)}_{uuid.uuid4().hex[:8]}"


def connect(**kwargs):
    return syntricdb.connect(host=HOST, port=PORT, database="default", **kwargs)


@pytest.fixture
def table():
    name = _unique_table()
    with connect(autocommit=True) as conn:
        cur = conn.cursor()
        cur.execute(
            f"CREATE TABLE {name} (id VARCHAR PRIMARY KEY, name VARCHAR, city VARCHAR, age INT, bio VARCHAR)"
        )
        cur.close()
    return name


def test_module_globals():
    assert syntricdb.apilevel == "2.0"
    assert syntricdb.paramstyle == "qmark"
    assert syntricdb.threadsafety == 1


def test_connect_with_dsn():
    conn = syntricdb.connect(f"syntricdb://admin:syntricdb_secret_pass@{HOST}:{PORT}/default", autocommit=True)
    try:
        cur = conn.cursor()
        cur.execute("SHOW TABLES")
        assert cur.description is not None or cur.rowcount >= 0
    finally:
        conn.close()


def test_insert_and_select(table):
    with connect(autocommit=True) as conn:
        cur = conn.cursor()
        cur.execute(f"INSERT INTO {table} VALUES ('u1', 'Alice', 'Hyderabad', 30, 'Java Engineer')")
        assert cur.rowcount == 1

        cur.execute(f"SELECT id, name, city FROM {table} WHERE id = 'u1'")
        row = cur.fetchone()
        assert row == ("u1", "Alice", "Hyderabad")
        assert cur.fetchone() is None


def test_parameterized_query_with_embedded_quote(table):
    with connect(autocommit=True) as conn:
        cur = conn.cursor()
        cur.execute(f"INSERT INTO {table} (id, name, city, age) VALUES (?, ?, ?, ?)",
                    ("u2", "O'Brien", "London", 40))
        cur.execute(f"SELECT name FROM {table} WHERE id = ?", ("u2",))
        assert cur.fetchone() == ("O'Brien",)


def test_fetchmany_and_fetchall_and_iteration(table):
    with connect(autocommit=True) as conn:
        cur = conn.cursor()
        for i in range(5):
            cur.execute(f"INSERT INTO {table} VALUES (?, ?, ?, ?, ?)",
                        (f"u{i}", f"Name{i}", "Hyderabad", 20 + i, None))

        cur.execute(f"SELECT id FROM {table} ORDER BY id")
        first_two = cur.fetchmany(2)
        assert len(first_two) == 2
        rest = cur.fetchall()
        assert len(rest) == 3

        cur.execute(f"SELECT id FROM {table} ORDER BY id")
        ids = [row[0] for row in cur]
        assert len(ids) == 5


def test_update_and_delete_rowcount(table):
    with connect(autocommit=True) as conn:
        cur = conn.cursor()
        cur.execute(f"INSERT INTO {table} VALUES ('u1', 'Alice', 'Hyderabad', 30, null)")
        cur.execute(f"INSERT INTO {table} VALUES ('u2', 'Bob', 'London', 40, null)")

        cur.execute(f"UPDATE {table} SET age = 99 WHERE city = 'Hyderabad'")
        assert cur.rowcount == 1

        cur.execute(f"DELETE FROM {table} WHERE age = 99")
        assert cur.rowcount == 1

        cur.execute(f"SELECT id FROM {table}")
        assert cur.fetchall() == [("u2",)]


def test_advanced_where_clause_operators(table):
    with connect(autocommit=True) as conn:
        cur = conn.cursor()
        cur.execute(f"INSERT INTO {table} VALUES ('u1', 'Alice', 'Hyderabad', 30, 'Engineer')")
        cur.execute(f"INSERT INTO {table} VALUES ('u2', 'Bob', 'London', 40, null)")
        cur.execute(f"INSERT INTO {table} VALUES ('u3', 'Carol', 'Berlin', 25, 'Scientist')")

        cur.execute(f"SELECT id FROM {table} WHERE city = 'London' OR city = 'Berlin' ORDER BY id")
        assert [r[0] for r in cur.fetchall()] == ["u2", "u3"]

        cur.execute(f"SELECT id FROM {table} WHERE name LIKE 'A%'")
        assert cur.fetchall() == [("u1",)]

        cur.execute(f"SELECT id FROM {table} WHERE age BETWEEN 26 AND 35")
        assert cur.fetchall() == [("u1",)]

        cur.execute(f"SELECT id FROM {table} WHERE bio IS NULL")
        assert cur.fetchall() == [("u2",)]

        cur.execute(f"SELECT id FROM {table} WHERE city IN ('London', 'Berlin') ORDER BY id")
        assert [r[0] for r in cur.fetchall()] == ["u2", "u3"]


def test_manual_transaction_commit_is_visible_to_other_connections(table):
    writer = connect(autocommit=False)
    try:
        wcur = writer.cursor()
        wcur.execute(f"INSERT INTO {table} VALUES ('u1', 'Alice', 'Hyderabad', 30, null)")

        with connect(autocommit=True) as reader:
            rcur = reader.cursor()
            rcur.execute(f"SELECT id FROM {table}")
            assert rcur.fetchall() == []  # not committed yet, invisible to other connections

        writer.commit()

        with connect(autocommit=True) as reader:
            rcur = reader.cursor()
            rcur.execute(f"SELECT id FROM {table}")
            assert rcur.fetchall() == [("u1",)]
    finally:
        writer.close()


def test_manual_transaction_rollback_discards_writes(table):
    with connect(autocommit=False) as conn:
        cur = conn.cursor()
        cur.execute(f"INSERT INTO {table} VALUES ('u1', 'Alice', 'Hyderabad', 30, null)")
        conn.rollback()

    with connect(autocommit=True) as reader:
        rcur = reader.cursor()
        rcur.execute(f"SELECT id FROM {table}")
        assert rcur.fetchall() == []


def test_context_manager_closes_and_rolls_back_open_transaction(table):
    conn = connect(autocommit=False)
    with conn:
        cur = conn.cursor()
        cur.execute(f"INSERT INTO {table} VALUES ('u1', 'Alice', 'Hyderabad', 30, null)")
    # __exit__ closed the connection without an explicit commit -> implicit rollback

    with connect(autocommit=True) as reader:
        rcur = reader.cursor()
        rcur.execute(f"SELECT id FROM {table}")
        assert rcur.fetchall() == []


def test_programming_error_on_bad_sql(table):
    with connect(autocommit=True) as conn:
        cur = conn.cursor()
        with pytest.raises(syntricdb.ProgrammingError):
            cur.execute(f"SELECT * FROM {table} WHERE age BETWEEN")


def test_interface_error_on_bad_credentials():
    with pytest.raises(syntricdb.InterfaceError):
        connect(user="admin", password="definitely-wrong-password")
