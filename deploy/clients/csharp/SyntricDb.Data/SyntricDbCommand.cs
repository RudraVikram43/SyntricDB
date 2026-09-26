using System;
using System.Data;
using System.Data.Common;
using System.Globalization;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace SyntricDB.Data;

public sealed class SyntricDbCommand : DbCommand
{
    private SyntricDbConnection? _connection;
    private readonly SyntricDbParameterCollection _parameters = new();

    internal SyntricDbCommand(SyntricDbConnection connection)
    {
        _connection = connection;
    }

    public SyntricDbCommand() { }

    [System.Diagnostics.CodeAnalysis.AllowNull]
    public override string CommandText { get; set; } = string.Empty;
    public override int CommandTimeout { get; set; } = 15;
    public override CommandType CommandType { get; set; } = CommandType.Text;
    public override bool DesignTimeVisible { get; set; }
    public override UpdateRowSource UpdatedRowSource { get; set; } = UpdateRowSource.None;

    protected override DbConnection? DbConnection
    {
        get => _connection;
        set => _connection = value as SyntricDbConnection;
    }

    protected override DbParameterCollection DbParameterCollection => _parameters;

    protected override DbTransaction? DbTransaction { get; set; }

    public override void Cancel() { /* no-op: statements execute as a single blocking HTTP call */ }

    protected override DbParameter CreateDbParameter() => new SyntricDbParameter();

    public override void Prepare() { /* no-op: SyntricDB has no server-side prepared-statement cache */ }

    public override int ExecuteNonQuery() => ExecuteNonQueryAsync(CancellationToken.None).GetAwaiter().GetResult();

    public override async Task<int> ExecuteNonQueryAsync(CancellationToken cancellationToken)
    {
        using JsonDocument doc = await RunAsync(cancellationToken).ConfigureAwait(false);
        return doc.RootElement.TryGetProperty("affectedRows", out var affected) ? affected.GetInt32() : -1;
    }

    public override object? ExecuteScalar() => ExecuteScalarAsync(CancellationToken.None).GetAwaiter().GetResult();

    public override async Task<object?> ExecuteScalarAsync(CancellationToken cancellationToken)
    {
        using var reader = (SyntricDbDataReader)await ExecuteDbDataReaderAsync(CommandBehavior.Default, cancellationToken)
            .ConfigureAwait(false);
        if (!reader.Read() || reader.FieldCount == 0) return null;
        return reader.IsDBNull(0) ? null : reader.GetValue(0);
    }

    protected override DbDataReader ExecuteDbDataReader(CommandBehavior behavior) =>
        ExecuteDbDataReaderAsync(behavior, CancellationToken.None).GetAwaiter().GetResult();

    protected override async Task<DbDataReader> ExecuteDbDataReaderAsync(CommandBehavior behavior, CancellationToken cancellationToken)
    {
        using JsonDocument doc = await RunAsync(cancellationToken).ConfigureAwait(false);
        int recordsAffected = doc.RootElement.TryGetProperty("affectedRows", out var affected) ? affected.GetInt32() : -1;
        // SyntricDbDataReader materializes every value out of the document in
        // its constructor, so it's safe to dispose doc as soon as this returns.
        return new SyntricDbDataReader(doc, recordsAffected);
    }

    private async Task<JsonDocument> RunAsync(CancellationToken cancellationToken)
    {
        if (_connection is null)
        {
            throw new InvalidOperationException("Command has no connection assigned.");
        }
        string sql = BindParameters(CommandText, _parameters);

        var body = new System.Collections.Generic.Dictionary<string, object?>
        {
            ["sql"] = sql,
            ["database"] = _connection.Database,
        };
        if (_connection.TransactionId is long txnId)
        {
            body["txnId"] = txnId;
        }

        JsonDocument doc = await _connection.Transport
            .RequestAsync(HttpMethod.Post, "/api/sql", body, cancellationToken)
            .ConfigureAwait(false);

        bool success = doc.RootElement.TryGetProperty("success", out var successProp)
                        && successProp.ValueKind == JsonValueKind.True;
        if (!success)
        {
            string message = doc.RootElement.TryGetProperty("error", out var errProp)
                ? errProp.GetString() ?? "Unknown SyntricDB error"
                : "Unknown SyntricDB error";
            doc.Dispose();
            throw new SyntricDbException(message);
        }
        return doc;
    }

    /// <summary>
    /// Substitutes '?' placeholders in <paramref name="sql"/> with literal SQL
    /// built from <paramref name="parameters"/>, in Add() order. A '?' inside
    /// a single-quoted string literal is left alone, and embedded single
    /// quotes in string values are escaped by doubling — the same rules the
    /// JDBC, Python, and Go drivers use.
    /// </summary>
    private static string BindParameters(string sql, SyntricDbParameterCollection parameters)
    {
        if (parameters.Count == 0) return sql;

        var sb = new StringBuilder();
        int argIndex = 0;
        bool inSingle = false;
        foreach (char c in sql)
        {
            if (c == '\'')
            {
                inSingle = !inSingle;
                sb.Append(c);
            }
            else if (c == '?' && !inSingle)
            {
                if (argIndex >= parameters.Count)
                {
                    throw new SyntricDbException("Not enough parameters supplied for the '?' placeholders in this statement.");
                }
                sb.Append(Literal(parameters.Ordered[argIndex].Value));
                argIndex++;
            }
            else
            {
                sb.Append(c);
            }
        }
        if (argIndex != parameters.Count)
        {
            throw new SyntricDbException("Too many parameters supplied for the '?' placeholders in this statement.");
        }
        return sb.ToString();
    }

    private static string Literal(object? value) => value switch
    {
        null or DBNull => "NULL",
        bool b => b ? "TRUE" : "FALSE",
        byte or sbyte or short or ushort or int or uint or long or ulong =>
            Convert.ToString(value, CultureInfo.InvariantCulture)!,
        float or double or decimal =>
            Convert.ToString(value, CultureInfo.InvariantCulture)!,
        byte[] bytes => Quote(Encoding.UTF8.GetString(bytes)),
        DateTime dt => Quote(dt.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture)),
        _ => Quote(Convert.ToString(value, CultureInfo.InvariantCulture) ?? string.Empty),
    };

    private static string Quote(string s) => "'" + s.Replace("'", "''") + "'";
}
