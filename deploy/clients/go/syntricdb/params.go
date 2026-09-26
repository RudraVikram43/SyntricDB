package syntricdb

import (
	"database/sql/driver"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// bindParams substitutes '?' placeholders in query with literal SQL built
// from args, in order. A '?' inside a single-quoted string literal is left
// alone, and embedded single quotes in string values are escaped by
// doubling — the same rules the JDBC and Python drivers use.
func bindParams(query string, args []driver.NamedValue) (string, error) {
	if len(args) == 0 {
		return query, nil
	}
	var b strings.Builder
	argIndex := 0
	inSingle := false
	for _, r := range query {
		switch {
		case r == '\'':
			inSingle = !inSingle
			b.WriteRune(r)
		case r == '?' && !inSingle:
			if argIndex >= len(args) {
				return "", fmt.Errorf("syntricdb: not enough parameters supplied for the '?' placeholders in this statement")
			}
			lit, err := literal(args[argIndex].Value)
			if err != nil {
				return "", err
			}
			b.WriteString(lit)
			argIndex++
		default:
			b.WriteRune(r)
		}
	}
	if argIndex != len(args) {
		return "", fmt.Errorf("syntricdb: too many parameters supplied for the '?' placeholders in this statement")
	}
	return b.String(), nil
}

func literal(v driver.Value) (string, error) {
	switch val := v.(type) {
	case nil:
		return "NULL", nil
	case bool:
		if val {
			return "TRUE", nil
		}
		return "FALSE", nil
	case int64:
		return strconv.FormatInt(val, 10), nil
	case float64:
		return strconv.FormatFloat(val, 'f', -1, 64), nil
	case []byte:
		return quoteString(string(val)), nil
	case string:
		return quoteString(val), nil
	case time.Time:
		return quoteString(val.Format("2006-01-02 15:04:05")), nil
	default:
		return "", fmt.Errorf("syntricdb: unsupported parameter type %T", v)
	}
}

func quoteString(s string) string {
	return "'" + strings.ReplaceAll(s, "'", "''") + "'"
}

func valuesToNamed(args []driver.Value) []driver.NamedValue {
	named := make([]driver.NamedValue, len(args))
	for i, v := range args {
		named[i] = driver.NamedValue{Ordinal: i + 1, Value: v}
	}
	return named
}
