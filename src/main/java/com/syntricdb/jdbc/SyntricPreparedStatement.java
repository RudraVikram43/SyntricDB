package com.syntricdb.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SyntricPreparedStatement extends SyntricStatement implements PreparedStatement {

    private final String rawSql;
    private final Map<Integer, String> parameters = new HashMap<>();

    public SyntricPreparedStatement(SyntricConnection connection, String sql) {
        super(connection);
        this.rawSql = sql;
    }

    private String buildSql() {
        if (parameters.isEmpty()) {
            return rawSql;
        }

        String sql = rawSql;

        // Substitute ? in sequence or $1, $2 markers
        if (sql.contains("?")) {
            StringBuilder sb = new StringBuilder();
            int paramIndex = 1;
            boolean inString = false;
            for (int i = 0; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (c == '\'') {
                    inString = !inString;
                    sb.append(c);
                } else if (c == '?' && !inString) {
                    String val = parameters.getOrDefault(paramIndex++, "NULL");
                    sb.append(val);
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        // Replace longest markers first ($10 before $1) so a shorter marker can't
        // match as a substring prefix of a longer one.
        List<Integer> keys = new ArrayList<>(parameters.keySet());
        keys.sort(Comparator.<Integer>naturalOrder().reversed());
        for (Integer key : keys) {
            sql = sql.replace("$" + key, parameters.get(key));
        }

        return sql;
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        return executeQuery(buildSql());
    }

    @Override
    public int executeUpdate() throws SQLException {
        return executeUpdate(buildSql());
    }

    @Override
    public boolean execute() throws SQLException {
        return execute(buildSql());
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        parameters.put(parameterIndex, "NULL");
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        parameters.put(parameterIndex, String.valueOf(x));
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        parameters.put(parameterIndex, String.valueOf(x));
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        parameters.put(parameterIndex, String.valueOf(x));
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        parameters.put(parameterIndex, String.valueOf(x));
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        parameters.put(parameterIndex, String.valueOf(x));
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        parameters.put(parameterIndex, String.valueOf(x));
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        parameters.put(parameterIndex, String.valueOf(x));
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        parameters.put(parameterIndex, x != null ? x.toString() : "NULL");
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        if (x == null) {
            parameters.put(parameterIndex, "NULL");
        } else {
            parameters.put(parameterIndex, "'" + x.replace("'", "''") + "'");
        }
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        if (x == null) {
            parameters.put(parameterIndex, "NULL");
        } else {
            parameters.put(parameterIndex, "'" + new String(x).replace("'", "''") + "'");
        }
    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        if (x == null) {
            parameters.put(parameterIndex, "NULL");
        } else {
            parameters.put(parameterIndex, "'" + x.toString() + "'");
        }
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        if (x == null) {
            parameters.put(parameterIndex, "NULL");
        } else {
            parameters.put(parameterIndex, "'" + x.toString() + "'");
        }
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        if (x == null) {
            parameters.put(parameterIndex, "NULL");
        } else {
            parameters.put(parameterIndex, "'" + x.toString() + "'");
        }
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {}

    @Override
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {}

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {}

    @Override
    public void clearParameters() throws SQLException {
        parameters.clear();
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        if (x == null) {
            setNull(parameterIndex, Types.NULL);
        } else if (x instanceof String) {
            setString(parameterIndex, (String) x);
        } else if (x instanceof Number) {
            parameters.put(parameterIndex, x.toString());
        } else if (x instanceof Boolean) {
            setBoolean(parameterIndex, (Boolean) x);
        } else {
            setString(parameterIndex, x.toString());
        }
    }

    @Override
    public void addBatch() throws SQLException {}

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {}

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {}

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {}

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {}

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {}

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return new SyntricResultSetMetaData(List.of());
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException { setDate(parameterIndex, x); }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException { setTime(parameterIndex, x); }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException { setTimestamp(parameterIndex, x); }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException { setNull(parameterIndex, sqlType); }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        setString(parameterIndex, x != null ? x.toString() : null);
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException { return null; }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {}

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException { setString(parameterIndex, value); }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {}

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {}

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {}

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {}

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {}

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {}

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {}

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {}

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {}

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {}

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {}

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {}

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {}

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {}

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {}

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {}
}
