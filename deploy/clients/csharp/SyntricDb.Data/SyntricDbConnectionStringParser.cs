using System;

namespace SyntricDB.Data;

internal sealed class ParsedConnectionInfo
{
    public string Host { get; set; } = "localhost";
    public int Port { get; set; } = 8080;
    public string User { get; set; } = "admin";
    public string Password { get; set; } = "syntricdb_secret_pass";
    public string Database { get; set; } = "default";
}

internal static class SyntricDbConnectionStringParser
{
    /// <summary>
    /// Accepts either a "syntricdb://user:pass@host:port/database" connection
    /// string (matching the Java/Python/Go drivers) or a standard ADO.NET
    /// key=value connection string ("Host=localhost;Port=8080;Username=admin;
    /// Password=secret;Database=default").
    /// </summary>
    public static ParsedConnectionInfo Parse(string? connectionString)
    {
        var info = new ParsedConnectionInfo();
        if (string.IsNullOrWhiteSpace(connectionString))
        {
            return info;
        }

        string trimmed = connectionString.Trim();
        if (trimmed.Contains("://"))
        {
            ParseDsn(trimmed, info);
        }
        else
        {
            ParseKeyValue(trimmed, info);
        }
        return info;
    }

    private static void ParseDsn(string dsn, ParsedConnectionInfo info)
    {
        string clean = dsn
            .Replace("jdbc:syntricdb://", "http://", StringComparison.OrdinalIgnoreCase)
            .Replace("syntricdb://", "http://", StringComparison.OrdinalIgnoreCase);

        var uri = new Uri(clean);
        info.Host = uri.Host;
        if (uri.Port > 0)
        {
            info.Port = uri.Port;
        }

        if (!string.IsNullOrEmpty(uri.UserInfo))
        {
            string[] parts = uri.UserInfo.Split(':', 2);
            info.User = Uri.UnescapeDataString(parts[0]);
            if (parts.Length > 1)
            {
                info.Password = Uri.UnescapeDataString(parts[1]);
            }
        }

        // Trim both ends: a trailing slash (".../default/") must not become
        // part of the database name.
        string db = uri.AbsolutePath.Trim('/');
        if (!string.IsNullOrEmpty(db))
        {
            info.Database = db;
        }
    }

    private static void ParseKeyValue(string connectionString, ParsedConnectionInfo info)
    {
        foreach (string rawPair in connectionString.Split(';', StringSplitOptions.RemoveEmptyEntries))
        {
            int eq = rawPair.IndexOf('=');
            if (eq < 0) continue;

            string key = rawPair[..eq].Trim();
            string value = rawPair[(eq + 1)..].Trim();

            switch (key.ToLowerInvariant())
            {
                case "host":
                case "server":
                case "data source":
                case "datasource":
                    info.Host = value;
                    break;
                case "port":
                    if (int.TryParse(value, out int port)) info.Port = port;
                    break;
                case "user":
                case "username":
                case "user id":
                case "uid":
                    info.User = value;
                    break;
                case "password":
                case "pwd":
                    info.Password = value;
                    break;
                case "database":
                case "initial catalog":
                    info.Database = value;
                    break;
            }
        }
    }
}
