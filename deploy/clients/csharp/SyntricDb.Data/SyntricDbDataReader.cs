using System;
using System.Collections;
using System.Collections.Generic;
using System.Data;
using System.Data.Common;
using System.Text.Json;

namespace SyntricDB.Data;

public sealed class SyntricDbDataReader : DbDataReader
{
    private readonly string[] _columns;
    private readonly List<object?[]> _rows;
    private readonly int _recordsAffected;
    private int _index = -1;
    private bool _closed;

    internal SyntricDbDataReader(JsonDocument document, int recordsAffected)
    {
        _recordsAffected = recordsAffected;
        _columns = Array.Empty<string>();
        _rows = new List<object?[]>();

        if (document.RootElement.TryGetProperty("data", out var dataElement)
            && dataElement.ValueKind == JsonValueKind.Array)
        {
            var columns = new List<string>();
            foreach (var rowElement in dataElement.EnumerateArray())
            {
                var values = new List<object?>();
                int i = 0;
                foreach (var property in rowElement.EnumerateObject())
                {
                    if (_rows.Count == 0)
                    {
                        // First row establishes column order; every row in a
                        // single result set has the same projection, so later
                        // rows are assumed to share it.
                        if (i >= columns.Count) columns.Add(property.Name);
                    }
                    values.Add(ConvertValue(property.Value));
                    i++;
                }
                _rows.Add(values.ToArray());
            }
            _columns = columns.ToArray();
        }
    }

    private static object? ConvertValue(JsonElement element) => element.ValueKind switch
    {
        JsonValueKind.Null or JsonValueKind.Undefined => null,
        JsonValueKind.String => element.GetString(),
        JsonValueKind.True => true,
        JsonValueKind.False => false,
        JsonValueKind.Number => element.TryGetInt64(out long l) ? l : element.GetDouble(),
        // Arrays/objects (e.g. a vector embedding column) don't have a natural
        // scalar ADO.NET representation, so they're exposed as their JSON text.
        _ => element.GetRawText(),
    };

    public override int FieldCount => _columns.Length;
    public override bool HasRows => _rows.Count > 0;
    public override bool IsClosed => _closed;
    public override int RecordsAffected => _recordsAffected;
    public override int Depth => 0;

    public override object this[int ordinal] => GetValue(ordinal);
    public override object this[string name] => GetValue(GetOrdinal(name));

    public override bool Read()
    {
        if (_closed) return false;
        if (_index + 1 >= _rows.Count) return false;
        _index++;
        return true;
    }

    public override bool NextResult() => false;

    public override void Close() => _closed = true;

    public override string GetName(int ordinal) => _columns[ordinal];

    public override int GetOrdinal(string name)
    {
        for (int i = 0; i < _columns.Length; i++)
        {
            if (string.Equals(_columns[i], name, StringComparison.OrdinalIgnoreCase)) return i;
        }
        throw new IndexOutOfRangeException($"Column '{name}' not found in result set.");
    }

    private object?[] CurrentRow()
    {
        if (_index < 0 || _index >= _rows.Count)
        {
            throw new InvalidOperationException("No current row. Call Read() before accessing column values.");
        }
        return _rows[_index];
    }

    public override object GetValue(int ordinal)
    {
        object? v = CurrentRow()[ordinal];
        return v ?? DBNull.Value;
    }

    public override int GetValues(object[] values)
    {
        var row = CurrentRow();
        int count = Math.Min(values.Length, row.Length);
        for (int i = 0; i < count; i++) values[i] = row[i] ?? DBNull.Value;
        return count;
    }

    public override bool IsDBNull(int ordinal) => CurrentRow()[ordinal] is null;

    public override Type GetFieldType(int ordinal)
    {
        object? v = _rows.Count > 0 ? _rows[0][ordinal] : null;
        return v?.GetType() ?? typeof(string);
    }

    public override string GetDataTypeName(int ordinal) => GetFieldType(ordinal).Name;

    public override string GetString(int ordinal) => Convert.ToString(GetValue(ordinal)) ?? string.Empty;
    public override bool GetBoolean(int ordinal) => Convert.ToBoolean(GetValue(ordinal));
    public override byte GetByte(int ordinal) => Convert.ToByte(GetValue(ordinal));
    public override char GetChar(int ordinal) => Convert.ToChar(GetValue(ordinal));
    public override short GetInt16(int ordinal) => Convert.ToInt16(GetValue(ordinal));
    public override int GetInt32(int ordinal) => Convert.ToInt32(GetValue(ordinal));
    public override long GetInt64(int ordinal) => Convert.ToInt64(GetValue(ordinal));
    public override float GetFloat(int ordinal) => Convert.ToSingle(GetValue(ordinal));
    public override double GetDouble(int ordinal) => Convert.ToDouble(GetValue(ordinal));
    public override decimal GetDecimal(int ordinal) => Convert.ToDecimal(GetValue(ordinal));
    public override DateTime GetDateTime(int ordinal) => Convert.ToDateTime(GetValue(ordinal));
    public override Guid GetGuid(int ordinal) => Guid.Parse(GetString(ordinal));

    public override long GetBytes(int ordinal, long dataOffset, byte[]? buffer, int bufferOffset, int length)
    {
        byte[] bytes = System.Text.Encoding.UTF8.GetBytes(GetString(ordinal));
        if (buffer is null) return bytes.LongLength;
        int count = Math.Min(length, bytes.Length - (int)dataOffset);
        Array.Copy(bytes, dataOffset, buffer, bufferOffset, count);
        return count;
    }

    public override long GetChars(int ordinal, long dataOffset, char[]? buffer, int bufferOffset, int length)
    {
        string s = GetString(ordinal);
        if (buffer is null) return s.Length;
        int count = Math.Min(length, s.Length - (int)dataOffset);
        s.CopyTo((int)dataOffset, buffer, bufferOffset, count);
        return count;
    }

    public override IEnumerator GetEnumerator() => new DbEnumerator(this);

    public override DataTable? GetSchemaTable() => null;
}
