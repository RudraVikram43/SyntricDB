from . import exceptions


class Cursor:
    """PEP 249 Cursor. Created via Connection.cursor(), never directly."""

    def __init__(self, connection):
        self._connection = connection
        self._rows = []
        self._index = 0
        self._closed = False
        self.description = None
        self.rowcount = -1
        self.arraysize = 1

    def execute(self, sql, params=None):
        self._check_closed()
        bound_sql = _bind_params(sql, params)
        response = self._connection._execute_sql(bound_sql)
        if not response.get("success"):
            raise exceptions.ProgrammingError(response.get("error", "Unknown SyntricDB error"))

        data = response.get("data")
        if data is not None:
            self._rows = data
            self.rowcount = len(data)
            self.description = _build_description(data)
        else:
            self._rows = []
            self.rowcount = response.get("affectedRows", -1)
            self.description = None
        self._index = 0
        return self

    def executemany(self, sql, seq_of_params):
        self._check_closed()
        total = 0
        for params in seq_of_params:
            self.execute(sql, params)
            if isinstance(self.rowcount, int) and self.rowcount > 0:
                total += self.rowcount
        self.rowcount = total

    def fetchone(self):
        self._check_closed()
        if self._index >= len(self._rows):
            return None
        row = _row_as_tuple(self._rows[self._index])
        self._index += 1
        return row

    def fetchmany(self, size=None):
        self._check_closed()
        if size is None:
            size = self.arraysize
        result = []
        for _ in range(size):
            row = self.fetchone()
            if row is None:
                break
            result.append(row)
        return result

    def fetchall(self):
        self._check_closed()
        result = [_row_as_tuple(r) for r in self._rows[self._index:]]
        self._index = len(self._rows)
        return result

    def close(self):
        self._closed = True

    def setinputsizes(self, sizes):
        pass  # no-op: sizing hints aren't meaningful over the HTTP transport

    def setoutputsize(self, size, column=None):
        pass  # no-op, same reason

    def _check_closed(self):
        if self._closed:
            raise exceptions.InterfaceError("Cursor is closed")

    def __iter__(self):
        return self

    def __next__(self):
        row = self.fetchone()
        if row is None:
            raise StopIteration
        return row

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()


def _row_as_tuple(row_dict):
    return tuple(row_dict.values())


def _build_description(rows):
    if not rows:
        return []
    first = rows[0]
    # (name, type_code, display_size, internal_size, precision, scale, null_ok)
    # SyntricDB's HTTP API doesn't carry column type metadata, so type_code is
    # inferred best-effort from the first row's Python value types.
    return [(name, type(value), None, None, None, None, True) for name, value in first.items()]


def _bind_params(sql, params):
    """Substitutes '?' placeholders with literal SQL, skipping '?' inside string
    literals and escaping embedded quotes the same way the JDBC driver does."""
    if not params:
        return sql
    result = []
    param_iter = iter(params)
    in_single = False
    for c in sql:
        if c == "'":
            in_single = not in_single
            result.append(c)
        elif c == "?" and not in_single:
            try:
                value = next(param_iter)
            except StopIteration:
                raise exceptions.ProgrammingError(
                    "Not enough parameters supplied for the '?' placeholders in this statement"
                )
            result.append(_literal(value))
        else:
            result.append(c)
    remaining = list(param_iter)
    if remaining:
        raise exceptions.ProgrammingError(
            "Too many parameters supplied for the '?' placeholders in this statement"
        )
    return "".join(result)


def _literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float)):
        return repr(value)
    if isinstance(value, (bytes, bytearray)):
        value = value.decode("utf-8", errors="replace")
    return "'" + str(value).replace("'", "''") + "'"
