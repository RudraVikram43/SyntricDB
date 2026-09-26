package syntricdb

import "database/sql/driver"

// Compile-time checks that each type satisfies the driver interfaces it's
// meant to; a signature typo here would otherwise only surface as a confusing
// runtime type-assertion failure deep inside database/sql.
var (
	_ driver.Driver        = (*Driver)(nil)
	_ driver.DriverContext = (*Driver)(nil)
	_ driver.Connector     = (*connector)(nil)

	_ driver.Conn               = (*Conn)(nil)
	_ driver.ConnPrepareContext = (*Conn)(nil)
	_ driver.ConnBeginTx        = (*Conn)(nil)
	_ driver.ExecerContext      = (*Conn)(nil)
	_ driver.QueryerContext     = (*Conn)(nil)
	_ driver.Pinger             = (*Conn)(nil)

	_ driver.Stmt             = (*Stmt)(nil)
	_ driver.StmtExecContext  = (*Stmt)(nil)
	_ driver.StmtQueryContext = (*Stmt)(nil)

	_ driver.Rows   = (*Rows)(nil)
	_ driver.Result = (*Result)(nil)
	_ driver.Tx     = (*Tx)(nil)
)
