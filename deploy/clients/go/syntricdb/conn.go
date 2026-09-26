package syntricdb

import (
	"context"
	"database/sql/driver"
	"errors"
)

// Conn implements driver.Conn plus the optional driver.ConnPrepareContext,
// driver.ConnBeginTx, driver.ExecerContext, driver.QueryerContext, and
// driver.Pinger interfaces.
//
// Transactions: BeginTx starts a server-side transaction (see
// /api/transaction/*) and stores its id on this Conn; database/sql guarantees
// a Conn is dedicated exclusively to its open Tx until Commit/Rollback, so
// conn-level state is exactly the right place for it. Only INSERT/UPDATE/
// DELETE participate — they're queued server-side until Commit, and
// discarded entirely on Rollback or Close. DDL/SELECT run immediately
// regardless of transaction state and only ever see already-committed data.
type Conn struct {
	transport *transport
	database  string
	txnID     *float64
	closed    bool
}

func newConn(cfg *Config) *Conn {
	return &Conn{
		transport: newTransport(cfg),
		database:  cfg.Database,
	}
}

func (c *Conn) Prepare(query string) (driver.Stmt, error) {
	return c.PrepareContext(context.Background(), query)
}

func (c *Conn) PrepareContext(ctx context.Context, query string) (driver.Stmt, error) {
	if c.closed {
		return nil, driver.ErrBadConn
	}
	return &Stmt{conn: c, query: query}, nil
}

func (c *Conn) Close() error {
	if !c.closed && c.txnID != nil {
		// Best-effort implicit rollback of any open transaction, mirroring the
		// JDBC/Python drivers' Connection.close() semantics.
		_, _ = c.transport.do(context.Background(), "POST", "/api/transaction/rollback", map[string]any{"txnId": *c.txnID})
	}
	c.closed = true
	return nil
}

func (c *Conn) Begin() (driver.Tx, error) {
	return c.BeginTx(context.Background(), driver.TxOptions{})
}

func (c *Conn) BeginTx(ctx context.Context, opts driver.TxOptions) (driver.Tx, error) {
	if c.closed {
		return nil, driver.ErrBadConn
	}
	if c.txnID != nil {
		return nil, errors.New("syntricdb: a transaction is already open on this connection")
	}
	resp, err := c.transport.do(ctx, "POST", "/api/transaction/begin", map[string]any{})
	if err != nil {
		return nil, err
	}
	id, ok := resp.float("txnId")
	if !ok {
		return nil, errors.New("syntricdb: server did not return a transaction id")
	}
	c.txnID = &id
	return &Tx{conn: c}, nil
}

// Ping verifies both connectivity and that the connection's credentials are
// valid. /api/health would only prove the server is reachable — it doesn't
// require authentication at all, so it can't detect bad credentials.
func (c *Conn) Ping(ctx context.Context) error {
	resp, err := c.transport.do(ctx, "GET", "/api/auth/verify", nil)
	if err != nil {
		return err
	}
	if !resp.bool("success") {
		return driver.ErrBadConn
	}
	return nil
}

// ExecContext/QueryContext let *sql.DB skip the emulated Prepare+Exec/Query
// round trip for one-shot statements, since our "prepare" step doesn't do
// anything the server needs ahead of time anyway.
func (c *Conn) ExecContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	return c.exec(ctx, query, args)
}

func (c *Conn) QueryContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	return c.query(ctx, query, args)
}

func (c *Conn) exec(ctx context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	if c.closed {
		return nil, driver.ErrBadConn
	}
	sql, err := bindParams(query, args)
	if err != nil {
		return nil, err
	}
	resp, err := c.runSQL(ctx, sql)
	if err != nil {
		return nil, err
	}
	return newResult(resp), nil
}

func (c *Conn) query(ctx context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	if c.closed {
		return nil, driver.ErrBadConn
	}
	sql, err := bindParams(query, args)
	if err != nil {
		return nil, err
	}
	resp, err := c.runSQL(ctx, sql)
	if err != nil {
		return nil, err
	}
	return newRows(resp)
}

func (c *Conn) runSQL(ctx context.Context, sql string) (response, error) {
	body := map[string]any{"sql": sql, "database": c.database}
	if c.txnID != nil {
		body["txnId"] = *c.txnID
	}
	resp, err := c.transport.do(ctx, "POST", "/api/sql", body)
	if err != nil {
		return nil, err
	}
	if !resp.bool("success") {
		return nil, &Error{Message: resp.errorOr("unknown SyntricDB error")}
	}
	return resp, nil
}

func (c *Conn) commitTxn(ctx context.Context) error {
	if c.txnID == nil {
		return nil
	}
	_, err := c.transport.do(ctx, "POST", "/api/transaction/commit", map[string]any{"txnId": *c.txnID})
	c.txnID = nil
	return err
}

func (c *Conn) rollbackTxn(ctx context.Context) error {
	if c.txnID == nil {
		return nil
	}
	_, err := c.transport.do(ctx, "POST", "/api/transaction/rollback", map[string]any{"txnId": *c.txnID})
	c.txnID = nil
	return err
}
