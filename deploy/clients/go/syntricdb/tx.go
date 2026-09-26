package syntricdb

import "context"

type Tx struct {
	conn *Conn
}

func (t *Tx) Commit() error {
	return t.conn.commitTxn(context.Background())
}

func (t *Tx) Rollback() error {
	return t.conn.rollbackTxn(context.Background())
}
