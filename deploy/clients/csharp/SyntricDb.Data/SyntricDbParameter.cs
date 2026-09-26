using System.Data;
using System.Data.Common;
using System.Diagnostics.CodeAnalysis;

namespace SyntricDB.Data;

public sealed class SyntricDbParameter : DbParameter
{
    public override DbType DbType { get; set; } = DbType.Object;
    public override ParameterDirection Direction { get; set; } = ParameterDirection.Input;
    public override bool IsNullable { get; set; } = true;

    [AllowNull]
    public override string ParameterName { get; set; } = string.Empty;
    public override int Size { get; set; }

    [AllowNull]
    public override string SourceColumn { get; set; } = string.Empty;
    public override bool SourceColumnNullMapping { get; set; }
    public override DataRowVersion SourceVersion { get; set; } = DataRowVersion.Current;
    public override object? Value { get; set; }

    public SyntricDbParameter() { }

    public SyntricDbParameter(string name, object? value)
    {
        ParameterName = name;
        Value = value;
    }

    public override void ResetDbType() => DbType = DbType.Object;
}
