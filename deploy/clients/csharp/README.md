# 💜 SyntricDB Official ADO.NET Provider (`SyntricDB.Data`)

A real ADO.NET provider for **SyntricDB** — `DbConnection`/`DbCommand`/`DbDataReader`/
`DbTransaction`, not a hand-rolled `HttpClient` wrapper. Works with
[Dapper](https://github.com/DapperLib/Dapper), raw ADO.NET code, or anything built on
`System.Data.Common.DbProviderFactories`.

> Full EF Core provider support (query translation, migrations, LINQ) is a much larger,
> separate undertaking and isn't included here — this targets Dapper and direct ADO.NET
> usage, which is what most Dapper-style "real projects" actually want from a database
> client.

## Install

Reference the `SyntricDb.Data` project (or the `SyntricDB.Data` NuGet package once
published).

## Quick start

```csharp
using SyntricDB.Data;

await using var conn = new SyntricDbConnection("syntricdb://admin:syntricdb_secret_pass@localhost:8080/default");
await conn.OpenAsync();

using (var insert = conn.CreateCommand())
{
    insert.CommandText = "INSERT INTO developers (id, name, role) VALUES (?, ?, ?)";
    insert.Parameters.Add(new SyntricDbParameter("id", "dev_1"));
    insert.Parameters.Add(new SyntricDbParameter("name", "Alice"));
    insert.Parameters.Add(new SyntricDbParameter("role", "Senior Engineer"));
    await insert.ExecuteNonQueryAsync();
}

using var select = conn.CreateCommand();
select.CommandText = "SELECT id, name FROM developers WHERE role LIKE ?";
select.Parameters.Add(new SyntricDbParameter("role", "%Engineer%"));
using var reader = await select.ExecuteReaderAsync();
while (await reader.ReadAsync())
{
    Console.WriteLine($"{reader.GetString(0)}: {reader.GetString(1)}");
}
```

### With Dapper

```csharp
using Dapper;
using SyntricDB.Data;

await using var conn = new SyntricDbConnection("syntricdb://admin:syntricdb_secret_pass@localhost:8080/default");
await conn.OpenAsync();

var developers = await conn.QueryAsync(
    "SELECT id, name FROM developers WHERE role LIKE ?", new { });
```

### Connection strings

Either the `syntricdb://user:pass@host:port/database` format (matching the Java, Python,
and Go drivers), or a standard ADO.NET key=value string:

```
Host=localhost;Port=8080;Username=admin;Password=syntricdb_secret_pass;Database=default
```

## Transactions

```csharp
using var tx = await conn.BeginTransactionAsync();
using (var cmd = conn.CreateCommand())
{
    cmd.CommandText = "INSERT INTO accounts VALUES (?, ?)";
    cmd.Parameters.Add(new SyntricDbParameter("id", "a1"));
    cmd.Parameters.Add(new SyntricDbParameter("balance", 100.0));
    await cmd.ExecuteNonQueryAsync();
}
await tx.CommitAsync();
```

Wired to SyntricDB's `/api/transaction/begin|commit|rollback` endpoints: only
`INSERT`/`UPDATE`/`DELETE` participate — they're queued server-side and only applied
atomically on `Commit`; `Rollback` (or disposing the transaction without committing, or
closing the connection) discards them entirely. `CREATE`/`DROP`/`SELECT` run immediately
regardless of transaction state and only ever see already-committed data (no
read-your-own-writes while a transaction is open).

## Notes on the provider's contract

- **Placeholders**: `?`, matching the JDBC/Python/Go drivers. A `?` inside a
  single-quoted string literal is left alone, and embedded single quotes in string
  parameters are escaped by doubling.
- Parameter binding is **positional** (the order they're `Add`ed to
  `DbCommand.Parameters`), not by `ParameterName` — there's no server-side prepared
  statement to bind named parameters against.
- Row values are decoded with `System.Text.Json.JsonDocument`, not `Dictionary<string,
  object>`, specifically to preserve each row's column order as the server returned it.
- Arrays/objects (e.g. a vector embedding column) don't have a natural ADO.NET scalar
  type, so they come through `GetValue`/`GetString` as their raw JSON text.
- `DbProviderFactories` registration is opt-in — call
  `DbProviderFactories.RegisterFactory("SyntricDB.Data", SyntricDbProviderFactory.Instance)`
  yourself if you need factory-based tooling; Dapper and direct ADO.NET code don't need it.

## Running the driver's own tests

Requires a running SyntricDB server and NuGet access (xUnit + the .NET Test SDK):

```bash
cd SyntricDb.Data.Tests
SYNTRICDB_TEST_HOST=localhost SYNTRICDB_TEST_PORT=8080 dotnet test
```

### Troubleshooting: `dotnet restore` hangs / times out reaching nuget.org

If `dotnet restore` or `dotnet test` hangs for ~100s per package and then fails with
`NU1301: ... has timed out`, while `curl https://api.nuget.org/v3/index.json` succeeds
instantly, your network likely has IPv6 egress that's silently dropped (black-holed)
rather than refused — some sandboxes and corporate networks do this. .NET's HTTP client
doesn't fail over to IPv4 quickly in that case, so it burns the whole timeout. Force IPv4:

```bash
export DOTNET_SYSTEM_NET_DISABLEIPV6=1
```
