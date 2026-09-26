package syntricdb

// Error is returned for SyntricDB API/auth failures. Auth is true for HTTP
// 401/403 responses (bad credentials), which callers may want to distinguish
// from an ordinary rejected statement.
type Error struct {
	Message string
	Auth    bool
}

func (e *Error) Error() string {
	return "syntricdb: " + e.Message
}

func errNotSupported(what string) error {
	return &Error{Message: what + " is not supported by SyntricDB"}
}
