package syntricdb

import (
	"context"
	"database/sql/driver"
)

type Stmt struct {
	conn  *Conn
	query string
}

func (s *Stmt) Close() error {
	return nil
}

// NumInput returns -1: SyntricDB doesn't parse placeholder counts ahead of
// execution, so database/sql skips its own arg-count validation and leaves it
// to bindParams to report a mismatch.
func (s *Stmt) NumInput() int {
	return -1
}

func (s *Stmt) Exec(args []driver.Value) (driver.Result, error) {
	return s.ExecContext(context.Background(), valuesToNamed(args))
}

func (s *Stmt) Query(args []driver.Value) (driver.Rows, error) {
	return s.QueryContext(context.Background(), valuesToNamed(args))
}

func (s *Stmt) ExecContext(ctx context.Context, args []driver.NamedValue) (driver.Result, error) {
	return s.conn.exec(ctx, s.query, args)
}

func (s *Stmt) QueryContext(ctx context.Context, args []driver.NamedValue) (driver.Rows, error) {
	return s.conn.query(ctx, s.query, args)
}
