using System.Data;
using System.Data.Common;
using System.Threading;
using System.Threading.Tasks;

namespace SyntricDB.Data;

/// <summary>
/// Only INSERT/UPDATE/DELETE participate in this transaction — they're queued
/// server-side and applied atomically on <see cref="Commit"/>, or discarded
/// entirely on <see cref="Rollback"/> (or an implicit rollback if the
/// connection is closed first). DDL/SELECT run immediately regardless of
/// transaction state and only ever see already-committed data.
/// </summary>
public sealed class SyntricDbTransaction : DbTransaction
{
    private readonly SyntricDbConnection _connection;
    private bool _completed;

    internal SyntricDbTransaction(SyntricDbConnection connection)
    {
        _connection = connection;
    }

    protected override DbConnection DbConnection => _connection;
    public override IsolationLevel IsolationLevel => IsolationLevel.Unspecified;

    public override void Commit() => CommitAsync(CancellationToken.None).GetAwaiter().GetResult();

    public override async Task CommitAsync(CancellationToken cancellationToken = default)
    {
        if (_completed) return;
        await _connection.CommitTransactionAsync(cancellationToken).ConfigureAwait(false);
        _completed = true;
    }

    public override void Rollback() => RollbackAsync(CancellationToken.None).GetAwaiter().GetResult();

    public override async Task RollbackAsync(CancellationToken cancellationToken = default)
    {
        if (_completed) return;
        await _connection.RollbackTransactionAsync(cancellationToken).ConfigureAwait(false);
        _completed = true;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing && !_completed)
        {
            Rollback();
        }
        base.Dispose(disposing);
    }
}
