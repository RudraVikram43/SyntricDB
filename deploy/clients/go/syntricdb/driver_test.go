// End-to-end tests against a live SyntricDB server. Requires a running
// server; point at it with SYNTRICDB_TEST_HOST (default localhost) and
// SYNTRICDB_TEST_PORT (default 8080).
package syntricdb_test

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	_ "github.com/upendra-manike/SyntricDB/deploy/clients/go/syntricdb"
)

var tableCounter int64

func testHost() string {
	if h := os.Getenv("SYNTRICDB_TEST_HOST"); h != "" {
		return h
	}
	return "localhost"
}

func testPort() int {
	if p := os.Getenv("SYNTRICDB_TEST_PORT"); p != "" {
		if n, err := strconv.Atoi(p); err == nil {
			return n
		}
	}
	return 8080
}

func dsn() string {
	return fmt.Sprintf("syntricdb://admin:syntricdb_secret_pass@%s:%d/default", testHost(), testPort())
}

func uniqueTable(t *testing.T) string {
	t.Helper()
	n := atomic.AddInt64(&tableCounter, 1)
	return fmt.Sprintf("go_dbapi_test_%d_%d", time.Now().UnixNano(), n)
}

func openDB(t *testing.T) *sql.DB {
	t.Helper()
	db, err := sql.Open("syntricdb", dsn())
	if err != nil {
		t.Fatalf("sql.Open: %v", err)
	}
	t.Cleanup(func() { db.Close() })
	if err := db.Ping(); err != nil {
		t.Fatalf("Ping: %v", err)
	}
	return db
}

func createTable(t *testing.T, db *sql.DB) string {
	t.Helper()
	table := uniqueTable(t)
	_, err := db.Exec(fmt.Sprintf(
		"CREATE TABLE %s (id VARCHAR PRIMARY KEY, name VARCHAR, city VARCHAR, age INT, bio VARCHAR)", table))
	if err != nil {
		t.Fatalf("CREATE TABLE: %v", err)
	}
	return table
}

func TestInsertAndSelect(t *testing.T) {
	db := openDB(t)
	table := createTable(t, db)

	res, err := db.Exec(fmt.Sprintf("INSERT INTO %s VALUES ('u1', 'Alice', 'Hyderabad', 30, 'Java Engineer')", table))
	if err != nil {
		t.Fatalf("INSERT: %v", err)
	}
	if n, _ := res.RowsAffected(); n != 1 {
		t.Fatalf("expected 1 row affected, got %d", n)
	}

	var id, name, city string
	err = db.QueryRow(fmt.Sprintf("SELECT id, name, city FROM %s WHERE id = 'u1'", table)).Scan(&id, &name, &city)
	if err != nil {
		t.Fatalf("SELECT: %v", err)
	}
	if id != "u1" || name != "Alice" || city != "Hyderabad" {
		t.Fatalf("unexpected row: %s %s %s", id, name, city)
	}
}

func TestParameterizedQueryWithEmbeddedQuote(t *testing.T) {
	db := openDB(t)
	table := createTable(t, db)

	_, err := db.Exec(fmt.Sprintf("INSERT INTO %s (id, name, city, age) VALUES (?, ?, ?, ?)", table),
		"u2", "O'Brien", "London", 40)
	if err != nil {
		t.Fatalf("INSERT: %v", err)
	}

	var name string
	err = db.QueryRow(fmt.Sprintf("SELECT name FROM %s WHERE id = ?", table), "u2").Scan(&name)
	if err != nil {
		t.Fatalf("SELECT: %v", err)
	}
	if name != "O'Brien" {
		t.Fatalf("expected O'Brien, got %q", name)
	}
}

func TestUpdateAndDeleteRowsAffected(t *testing.T) {
	db := openDB(t)
	table := createTable(t, db)

	mustExec(t, db, fmt.Sprintf("INSERT INTO %s VALUES ('u1', 'Alice', 'Hyderabad', 30, null)", table))
	mustExec(t, db, fmt.Sprintf("INSERT INTO %s VALUES ('u2', 'Bob', 'London', 40, null)", table))

	res := mustExec(t, db, fmt.Sprintf("UPDATE %s SET age = 99 WHERE city = 'Hyderabad'", table))
	if n, _ := res.RowsAffected(); n != 1 {
		t.Fatalf("expected 1 row updated, got %d", n)
	}

	res = mustExec(t, db, fmt.Sprintf("DELETE FROM %s WHERE age = 99", table))
	if n, _ := res.RowsAffected(); n != 1 {
		t.Fatalf("expected 1 row deleted, got %d", n)
	}

	rows := mustQuery(t, db, fmt.Sprintf("SELECT id FROM %s", table))
	defer rows.Close()
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			t.Fatalf("Scan: %v", err)
		}
		ids = append(ids, id)
	}
	if len(ids) != 1 || ids[0] != "u2" {
		t.Fatalf("expected only u2 remaining, got %v", ids)
	}
}

func TestAdvancedWhereClauseOperators(t *testing.T) {
	db := openDB(t)
	table := createTable(t, db)

	mustExec(t, db, fmt.Sprintf("INSERT INTO %s VALUES ('u1', 'Alice', 'Hyderabad', 30, 'Engineer')", table))
	mustExec(t, db, fmt.Sprintf("INSERT INTO %s VALUES ('u2', 'Bob', 'London', 40, null)", table))
	mustExec(t, db, fmt.Sprintf("INSERT INTO %s VALUES ('u3', 'Carol', 'Berlin', 25, 'Scientist')", table))

	assertIDs(t, db, fmt.Sprintf("SELECT id FROM %s WHERE city = 'London' OR city = 'Berlin' ORDER BY id", table), "u2", "u3")
	assertIDs(t, db, fmt.Sprintf("SELECT id FROM %s WHERE name LIKE 'A%%'", table), "u1")
	assertIDs(t, db, fmt.Sprintf("SELECT id FROM %s WHERE age BETWEEN 26 AND 35", table), "u1")
	assertIDs(t, db, fmt.Sprintf("SELECT id FROM %s WHERE bio IS NULL", table), "u2")
	assertIDs(t, db, fmt.Sprintf("SELECT id FROM %s WHERE city IN ('London', 'Berlin') ORDER BY id", table), "u2", "u3")
}

func TestTransactionCommitVisibleAfterCommit(t *testing.T) {
	db := openDB(t)
	table := createTable(t, db)

	writer, err := db.Conn(context.Background())
	if err != nil {
		t.Fatalf("Conn: %v", err)
	}
	defer writer.Close()

	tx, err := writer.BeginTx(context.Background(), nil)
	if err != nil {
		t.Fatalf("BeginTx: %v", err)
	}
	if _, err := tx.Exec(fmt.Sprintf("INSERT INTO %s VALUES ('u1', 'Alice', 'Hyderabad', 30, null)", table)); err != nil {
		t.Fatalf("Exec inside tx: %v", err)
	}

	// Not committed yet: invisible to a separate connection.
	assertIDs(t, db, fmt.Sprintf("SELECT id FROM %s", table))

	if err := tx.Commit(); err != nil {
		t.Fatalf("Commit: %v", err)
	}
	assertIDs(t, db, fmt.Sprintf("SELECT id FROM %s", table), "u1")
}

func TestTransactionRollbackDiscardsWrites(t *testing.T) {
	db := openDB(t)
	table := createTable(t, db)

	conn, err := db.Conn(context.Background())
	if err != nil {
		t.Fatalf("Conn: %v", err)
	}
	defer conn.Close()

	tx, err := conn.BeginTx(context.Background(), nil)
	if err != nil {
		t.Fatalf("BeginTx: %v", err)
	}
	if _, err := tx.Exec(fmt.Sprintf("INSERT INTO %s VALUES ('u1', 'Alice', 'Hyderabad', 30, null)", table)); err != nil {
		t.Fatalf("Exec inside tx: %v", err)
	}
	if err := tx.Rollback(); err != nil {
		t.Fatalf("Rollback: %v", err)
	}

	assertIDs(t, db, fmt.Sprintf("SELECT id FROM %s", table))
}

func TestProgrammingErrorOnBadSQL(t *testing.T) {
	db := openDB(t)
	table := createTable(t, db)

	_, err := db.Query(fmt.Sprintf("SELECT * FROM %s WHERE age BETWEEN", table))
	if err == nil {
		t.Fatal("expected an error for a malformed WHERE clause")
	}
}

func TestAuthErrorOnBadCredentials(t *testing.T) {
	badDSN := fmt.Sprintf("syntricdb://admin:definitely-wrong@%s:%d/default", testHost(), testPort())
	db, err := sql.Open("syntricdb", badDSN)
	if err != nil {
		t.Fatalf("sql.Open: %v", err)
	}
	defer db.Close()

	if err := db.Ping(); err == nil {
		t.Fatal("expected an auth error for bad credentials")
	}
}

func mustExec(t *testing.T, db *sql.DB, query string) sql.Result {
	t.Helper()
	res, err := db.Exec(query)
	if err != nil {
		t.Fatalf("Exec(%q): %v", query, err)
	}
	return res
}

func mustQuery(t *testing.T, db *sql.DB, query string) *sql.Rows {
	t.Helper()
	rows, err := db.Query(query)
	if err != nil {
		t.Fatalf("Query(%q): %v", query, err)
	}
	return rows
}

func assertIDs(t *testing.T, db *sql.DB, query string, want ...string) {
	t.Helper()
	rows := mustQuery(t, db, query)
	defer rows.Close()
	var got []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			t.Fatalf("Scan: %v", err)
		}
		got = append(got, id)
	}
	if len(got) != len(want) {
		t.Fatalf("query %q: expected %v, got %v", query, want, got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("query %q: expected %v, got %v", query, want, got)
		}
	}
}
