package syntricdb

import (
	"database/sql/driver"
	"encoding/json"
	"io"
)

type Rows struct {
	columns []string
	rows    []map[string]any
	index   int
}

func newRows(resp response) (*Rows, error) {
	rawRows, ok := resp.rawArray("data")
	if !ok {
		return &Rows{columns: []string{}}, nil
	}

	rows := make([]map[string]any, 0, len(rawRows))
	var columns []string
	for _, raw := range rawRows {
		keys, values, err := decodeOrderedRow(raw)
		if err != nil {
			return nil, err
		}
		if columns == nil {
			columns = keys
		}
		row := make(map[string]any, len(keys))
		for i, k := range keys {
			row[k] = values[i]
		}
		rows = append(rows, row)
	}
	if columns == nil {
		columns = []string{}
	}
	return &Rows{columns: columns, rows: rows}, nil
}

func (r *Rows) Columns() []string {
	return r.columns
}

func (r *Rows) Close() error {
	r.index = len(r.rows)
	return nil
}

func (r *Rows) Next(dest []driver.Value) error {
	if r.index >= len(r.rows) {
		return io.EOF
	}
	row := r.rows[r.index]
	for i, col := range r.columns {
		v, err := toDriverValue(row[col])
		if err != nil {
			return err
		}
		dest[i] = v
	}
	r.index++
	return nil
}

// toDriverValue converts a JSON-decoded value into one of the scalar types
// database/sql/driver.Value permits (int64/float64/bool/[]byte/string/
// time.Time/nil). Arrays and objects (e.g. a vector embedding column) don't
// have a natural scalar representation, so they're exposed as their JSON text
// for the caller to unmarshal further if needed.
func toDriverValue(v any) (driver.Value, error) {
	switch val := v.(type) {
	case nil, bool, float64, string:
		return val, nil
	default:
		b, err := json.Marshal(val)
		if err != nil {
			return nil, err
		}
		return string(b), nil
	}
}
