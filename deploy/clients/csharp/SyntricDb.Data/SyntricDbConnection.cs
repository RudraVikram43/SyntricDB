using System;
using System.Data;
using System.Data.Common;
using System.Diagnostics.CodeAnalysis;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace SyntricDB.Data;

/// <summary>
/// A real ADO.NET connection for SyntricDB, so it works with Dapper, raw
/// ADO.NET code, or anything built on <see cref="DbProviderFactories"/> — not
/// just direct HttpClient calls.
/// </summary>
public sealed class SyntricDbConnection : DbConnection
{
    private ParsedConnectionInfo _info;
    private SyntricDbTransport? _transport;
    private ConnectionState _state = ConnectionState.Closed;
    private long? _txnId;

    public SyntricDbConnection() : this(string.Empty) { }

    public SyntricDbConnection(string connectionString)
    {
        _info = SyntricDbConnectionStringParser.Parse(connectionString);
        ConnectionStringInternal = connectionString;
    }

    private string ConnectionStringInternal { get; set; }

    [AllowNull]
    public override string ConnectionString
    {
        get => ConnectionStringInternal;
        set
        {
            ConnectionStringInternal = value ?? string.Empty;
            _info = SyntricDbConnectionStringParser.Parse(ConnectionStringInternal);
        }
    }

    public override string Database => _info.Database;
    public override string DataSource => $"{_info.Host}:{_info.Port}";
    public override string ServerVersion => "1.0.0-PROD";
    public override ConnectionState State => _state;

    internal SyntricDbTransport Transport =>
        _transport ?? throw new InvalidOperationException("Connection is not open.");

    internal long? TransactionId => _txnId;

    public override void Open() => OpenAsync(CancellationToken.None).GetAwaiter().GetResult();

    public override async Task OpenAsync(CancellationToken cancellationToken)
    {
        if (_state == ConnectionState.Open) return;

        _transport = new SyntricDbTransport(_info);
        try
        {
            using JsonDocument doc = await _transport
                .RequestAsync(HttpMethod.Get, "/api/auth/verify", null, cancellationToken)
                .ConfigureAwait(false);
            bool success = doc.RootElement.TryGetProperty("success", out var successProp)
                            && successProp.ValueKind == JsonValueKind.True;
            if (!success)
            {
                throw new SyntricDbException($"SyntricDB authentication failed for user '{_info.User}'.", isAuthError: true);
            }
        }
        catch
        {
            _transport.Dispose();
            _transport = null;
            throw;
        }

        _state = ConnectionState.Open;
    }

    public override void Close()
    {
        if (_state == ConnectionState.Closed) return;
        if (_txnId is long id)
        {
            try
            {
                _transport?.RequestAsync(HttpMethod.Post, "/api/transaction/rollback",
                    new { txnId = id }, CancellationToken.None).GetAwaiter().GetResult();
            }
            catch (SyntricDbException)
            {
                // Best-effort: the connection is going away regardless.
            }
            _txnId = null;
        }
        _transport?.Dispose();
        _transport = null;
        _state = ConnectionState.Closed;
    }

    public override void ChangeDatabase(string databaseName) => _info.Database = databaseName;

    protected override DbCommand CreateDbCommand() => new SyntricDbCommand(this);

    protected override DbTransaction BeginDbTransaction(IsolationLevel isolationLevel) =>
        BeginTransactionAsync(CancellationToken.None).GetAwaiter().GetResult();

    public new SyntricDbTransaction BeginTransaction() =>
        BeginTransactionAsync(CancellationToken.None).GetAwaiter().GetResult();

    public new async Task<SyntricDbTransaction> BeginTransactionAsync(CancellationToken cancellationToken = default)
    {
        if (_state != ConnectionState.Open)
        {
            throw new InvalidOperationException("Connection must be open before starting a transaction.");
        }
        if (_txnId != null)
        {
            throw new InvalidOperationException("A transaction is already open on this connection.");
        }

        using JsonDocument doc = await Transport
            .RequestAsync(HttpMethod.Post, "/api/transaction/begin", new { }, cancellationToken)
            .ConfigureAwait(false);
        if (!doc.RootElement.TryGetProperty("txnId", out var idProp))
        {
            throw new SyntricDbException("Server did not return a transaction id for BEGIN.");
        }
        _txnId = idProp.GetInt64();
        return new SyntricDbTransaction(this);
    }

    internal async Task CommitTransactionAsync(CancellationToken cancellationToken)
    {
        if (_txnId is not long id) return;
        await Transport.RequestAsync(HttpMethod.Post, "/api/transaction/commit",
            new { txnId = id }, cancellationToken).ConfigureAwait(false);
        _txnId = null;
    }

    internal async Task RollbackTransactionAsync(CancellationToken cancellationToken)
    {
        if (_txnId is not long id) return;
        await Transport.RequestAsync(HttpMethod.Post, "/api/transaction/rollback",
            new { txnId = id }, cancellationToken).ConfigureAwait(false);
        _txnId = null;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) Close();
        base.Dispose(disposing);
    }
}
