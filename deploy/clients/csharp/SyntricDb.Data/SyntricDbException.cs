using System;
using System.Data.Common;

namespace SyntricDB.Data;

/// <summary>
/// Raised for SyntricDB API/auth failures. <see cref="IsAuthError"/> is true
/// for HTTP 401/403 responses (bad credentials), which callers may want to
/// distinguish from an ordinary rejected statement.
/// </summary>
public sealed class SyntricDbException : DbException
{
    public bool IsAuthError { get; }

    public SyntricDbException(string message, bool isAuthError = false)
        : base(message)
    {
        IsAuthError = isAuthError;
    }

    public SyntricDbException(string message, Exception innerException)
        : base(message, innerException)
    {
    }
}
