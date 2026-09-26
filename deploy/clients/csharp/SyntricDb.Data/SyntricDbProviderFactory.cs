using System.Data.Common;

namespace SyntricDB.Data;

/// <summary>
/// Enables generic ADO.NET tooling built on <see cref="DbProviderFactories"/>.
/// Register it explicitly at startup if you need that:
/// <code>
/// DbProviderFactories.RegisterFactory("SyntricDB.Data", SyntricDbProviderFactory.Instance);
/// </code>
/// Dapper and hand-written ADO.NET code don't need this — they work directly
/// against <see cref="SyntricDbConnection"/>/<see cref="SyntricDbCommand"/>.
/// </summary>
public sealed class SyntricDbProviderFactory : DbProviderFactory
{
    public static readonly SyntricDbProviderFactory Instance = new();

    private SyntricDbProviderFactory() { }

    public override DbConnection CreateConnection() => new SyntricDbConnection();

    public override DbCommand CreateCommand() => new SyntricDbCommand();

    public override DbParameter CreateParameter() => new SyntricDbParameter();

    public override DbConnectionStringBuilder CreateConnectionStringBuilder() => new();
}
