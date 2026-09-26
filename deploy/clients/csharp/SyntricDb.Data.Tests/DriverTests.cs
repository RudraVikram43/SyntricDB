using System;
using System.Threading.Tasks;
using SyntricDB.Data;
using Xunit;

namespace SyntricDb.Data.Tests;

/// <summary>
/// End-to-end tests against a live SyntricDB server. Requires a running
/// server; point at it with SYNTRICDB_TEST_HOST (default localhost) and
/// SYNTRICDB_TEST_PORT (default 8080).
/// </summary>
public class DriverTests
{
    private static string Host => Environment.GetEnvironmentVariable("SYNTRICDB_TEST_HOST") ?? "localhost";
    private static int Port => int.TryParse(Environment.GetEnvironmentVariable("SYNTRICDB_TEST_PORT"), out int p) ? p : 8080;

    private static string Dsn(string user = "admin", string password = "syntricdb_secret_pass") =>
        $"syntricdb://{user}:{password}@{Host}:{Port}/default";

    private static async Task<SyntricDbConnection> OpenAsync()
    {
        var conn = new SyntricDbConnection(Dsn());
        await conn.OpenAsync();
        return conn;
    }

    private static string UniqueTable() => $"csharp_dbapi_test_{DateTime.UtcNow.Ticks}_{Guid.NewGuid():N}"[..40];

    private static async Task<string> CreateTableAsync(SyntricDbConnection conn)
    {
        string table = UniqueTable();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = $"CREATE TABLE {table} (id VARCHAR PRIMARY KEY, name VARCHAR, city VARCHAR, age INT, bio VARCHAR)";
        await cmd.ExecuteNonQueryAsync();
        return table;
    }

    [Fact]
    public async Task InsertAndSelect()
    {
        await using var conn = await OpenAsync();
        string table = await CreateTableAsync(conn);

        using (var insert = conn.CreateCommand())
        {
            insert.CommandText = $"INSERT INTO {table} VALUES ('u1', 'Alice', 'Hyderabad', 30, 'Java Engineer')";
            int affected = await insert.ExecuteNonQueryAsync();
            Assert.Equal(1, affected);
        }

        using var select = conn.CreateCommand();
        select.CommandText = $"SELECT id, name, city FROM {table} WHERE id = 'u1'";
        using var reader = await select.ExecuteReaderAsync();
        Assert.True(await reader.ReadAsync());
        Assert.Equal("u1", reader.GetString(0));
        Assert.Equal("Alice", reader.GetString(1));
        Assert.Equal("Hyderabad", reader.GetString(2));
        Assert.False(await reader.ReadAsync());
    }

    [Fact]
    public async Task ParameterizedQueryWithEmbeddedQuote()
    {
        await using var conn = await OpenAsync();
        string table = await CreateTableAsync(conn);

        using (var insert = conn.CreateCommand())
        {
            insert.CommandText = $"INSERT INTO {table} (id, name, city, age) VALUES (?, ?, ?, ?)";
            insert.Parameters.Add(new SyntricDbParameter("id", "u2"));
            insert.Parameters.Add(new SyntricDbParameter("name", "O'Brien"));
            insert.Parameters.Add(new SyntricDbParameter("city", "London"));
            insert.Parameters.Add(new SyntricDbParameter("age", 40));
            await insert.ExecuteNonQueryAsync();
        }

        using var select = conn.CreateCommand();
        select.CommandText = $"SELECT name FROM {table} WHERE id = ?";
        select.Parameters.Add(new SyntricDbParameter("id", "u2"));
        object? name = await select.ExecuteScalarAsync();
        Assert.Equal("O'Brien", name);
    }

    [Fact]
    public async Task UpdateAndDeleteRowsAffected()
    {
        await using var conn = await OpenAsync();
        string table = await CreateTableAsync(conn);

        await ExecAsync(conn, $"INSERT INTO {table} VALUES ('u1', 'Alice', 'Hyderabad', 30, null)");
        await ExecAsync(conn, $"INSERT INTO {table} VALUES ('u2', 'Bob', 'London', 40, null)");

        int updated = await ExecAsync(conn, $"UPDATE {table} SET age = 99 WHERE city = 'Hyderabad'");
        Assert.Equal(1, updated);

        int deleted = await ExecAsync(conn, $"DELETE FROM {table} WHERE age = 99");
        Assert.Equal(1, deleted);

        var ids = await SelectIdsAsync(conn, $"SELECT id FROM {table}");
        Assert.Equal(new[] { "u2" }, ids);
    }

    [Fact]
    public async Task AdvancedWhereClauseOperators()
    {
        await using var conn = await OpenAsync();
        string table = await CreateTableAsync(conn);

        await ExecAsync(conn, $"INSERT INTO {table} VALUES ('u1', 'Alice', 'Hyderabad', 30, 'Engineer')");
        await ExecAsync(conn, $"INSERT INTO {table} VALUES ('u2', 'Bob', 'London', 40, null)");
        await ExecAsync(conn, $"INSERT INTO {table} VALUES ('u3', 'Carol', 'Berlin', 25, 'Scientist')");

        Assert.Equal(new[] { "u2", "u3" }, await SelectIdsAsync(conn,
            $"SELECT id FROM {table} WHERE city = 'London' OR city = 'Berlin' ORDER BY id"));
        Assert.Equal(new[] { "u1" }, await SelectIdsAsync(conn, $"SELECT id FROM {table} WHERE name LIKE 'A%'"));
        Assert.Equal(new[] { "u1" }, await SelectIdsAsync(conn, $"SELECT id FROM {table} WHERE age BETWEEN 26 AND 35"));
        Assert.Equal(new[] { "u2" }, await SelectIdsAsync(conn, $"SELECT id FROM {table} WHERE bio IS NULL"));
        Assert.Equal(new[] { "u2", "u3" }, await SelectIdsAsync(conn,
            $"SELECT id FROM {table} WHERE city IN ('London', 'Berlin') ORDER BY id"));
    }

    [Fact]
    public async Task TransactionCommitVisibleAfterCommit()
    {
        await using var writer = await OpenAsync();
        string table = await CreateTableAsync(writer);

        using var tx = await writer.BeginTransactionAsync();
        await ExecAsync(writer, $"INSERT INTO {table} VALUES ('u1', 'Alice', 'Hyderabad', 30, null)");

        await using (var reader1 = await OpenAsync())
        {
            Assert.Empty(await SelectIdsAsync(reader1, $"SELECT id FROM {table}"));
        }

        await tx.CommitAsync();

        await using var reader2 = await OpenAsync();
        Assert.Equal(new[] { "u1" }, await SelectIdsAsync(reader2, $"SELECT id FROM {table}"));
    }

    [Fact]
    public async Task TransactionRollbackDiscardsWrites()
    {
        await using var conn = await OpenAsync();
        string table = await CreateTableAsync(conn);

        using (var tx = await conn.BeginTransactionAsync())
        {
            await ExecAsync(conn, $"INSERT INTO {table} VALUES ('u1', 'Alice', 'Hyderabad', 30, null)");
            await tx.RollbackAsync();
        }

        await using var reader = await OpenAsync();
        Assert.Empty(await SelectIdsAsync(reader, $"SELECT id FROM {table}"));
    }

    [Fact]
    public async Task ProgrammingErrorOnBadSql()
    {
        await using var conn = await OpenAsync();
        string table = await CreateTableAsync(conn);

        using var cmd = conn.CreateCommand();
        cmd.CommandText = $"SELECT * FROM {table} WHERE age BETWEEN";
        await Assert.ThrowsAsync<SyntricDbException>(() => cmd.ExecuteReaderAsync());
    }

    [Fact]
    public async Task AuthErrorOnBadCredentials()
    {
        await using var conn = new SyntricDbConnection(Dsn(password: "definitely-wrong"));
        var ex = await Assert.ThrowsAsync<SyntricDbException>(() => conn.OpenAsync());
        Assert.True(ex.IsAuthError);
    }

    private static async Task<int> ExecAsync(SyntricDbConnection conn, string sql)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        return await cmd.ExecuteNonQueryAsync();
    }

    private static async Task<System.Collections.Generic.List<string>> SelectIdsAsync(SyntricDbConnection conn, string sql)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        using var reader = await cmd.ExecuteReaderAsync();
        var ids = new System.Collections.Generic.List<string>();
        while (await reader.ReadAsync())
        {
            ids.Add(reader.GetString(0));
        }
        return ids;
    }
}
