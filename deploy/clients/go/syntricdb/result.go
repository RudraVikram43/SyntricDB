package syntricdb

type Result struct {
	rowsAffected int64
}

func newResult(resp response) *Result {
	affected, _ := resp.float("affectedRows")
	return &Result{rowsAffected: int64(affected)}
}

// LastInsertId is not supported: SyntricDB primary keys are client-supplied
// strings, not server-generated auto-increment integers.
func (r *Result) LastInsertId() (int64, error) {
	return 0, errNotSupported("LastInsertId")
}

func (r *Result) RowsAffected() (int64, error) {
	return r.rowsAffected, nil
}
