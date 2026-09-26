# 🔷 SyntricDB Official Go Driver (`database/sql`)

A real `database/sql/driver.Driver` for **SyntricDB** — not a hand-rolled HTTP wrapper.
It implements the standard connection/statement/rows/transaction interfaces
(`driver.Conn`, `driver.Stmt`, `driver.Rows`, `driver.Tx`, plus the context-aware and
`Pinger` extensions), so it works with the standard library's connection pooling and
with anything built on `database/sql` — `sqlx`, `GORM`'s generic driver support, or
plain `database/sql` code.

## Install

```bash
go get github.com/upendra-manike/SyntricDB/deploy/clients/go
```

## Quick start

```go
package main

import (
	"database/sql"
	"log"

	_ "github.com/upendra-manike/SyntricDB/deploy/clients/go/syntricdb"
)

func main() {
	db, err := sql.Open("syntricdb", "syntricdb://admin:syntricdb_secret_pass@localhost:8080/default")
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()

	if err := db.Ping(); err != nil {
		log.Fatal(err)
	}

	_, err = db.Exec("INSERT INTO developers (id, name, role) VALUES (?, ?, ?)",
		"dev_1", "Alice", "Senior Engineer")
	if err != nil {
		log.Fatal(err)
	}

	rows, err := db.Query("SELECT id, name FROM developers WHERE role LIKE ?", "%Engineer%")
	if err != nil {
		log.Fatal(err)
	}
	defer rows.Close()
	for rows.Next() {
		var id, name string
		if err := rows.Scan(&id, &name); err != nil {
			log.Fatal(err)
		}
		log.Println(id, name)
	}
}
```

## Transactions

Standard `database/sql` semantics: use `db.Begin()` / `db.BeginTx(ctx, opts)` to get a
`*sql.Tx`, then `tx.Commit()` / `tx.Rollback()`. Under the hood this is wired to
SyntricDB's `/api/transaction/begin|commit|rollback` endpoints: `INSERT`/`UPDATE`/
`DELETE` issued through the `Tx` are queued server-side and only applied atomically on
`Commit`; `Rollback` (or letting the `Tx` go out of scope via a `Conn.Close()`) discards
them entirely — nothing is ever written. `CREATE`/`DROP`/`SELECT` run immediately
regardless of transaction state and only ever see already-committed data (no
read-your-own-writes while a transaction is open).

```go
tx, err := db.Begin()
if err != nil {
	log.Fatal(err)
}
if _, err := tx.Exec("INSERT INTO accounts VALUES (?, ?)", "a1", 100.0); err != nil {
	tx.Rollback()
	log.Fatal(err)
}
if err := tx.Commit(); err != nil {
	log.Fatal(err)
}
```

## Notes on the driver's contract

- **Placeholders**: `?`, matching the JDBC and Python drivers. A `?` inside a
  single-quoted string literal is left alone, and embedded single quotes in string
  parameters are escaped by doubling.
- **`LastInsertId`** is not supported — SyntricDB primary keys are client-supplied
  strings, not server-generated auto-increment integers. Use `RowsAffected()`.
- **Column values**: scalar values (`string`, `float64`, `bool`, `nil`) map directly to
  `driver.Value`. Arrays and objects (e.g. a vector embedding column) don't have a
  natural scalar representation in `database/sql`, so they come through as their raw
  JSON text — `json.Unmarshal` it further if you need the structured value.
- **`Rows.Columns()`** on a zero-row result returns an empty slice: the HTTP API
  doesn't carry column metadata independent of the returned rows.

## Running the driver's own tests

Requires a running SyntricDB server:

```bash
SYNTRICDB_TEST_HOST=localhost SYNTRICDB_TEST_PORT=8080 go test ./... -v
```
