using System;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace SyntricDB.Data;

/// <summary>
/// Shared HTTP calling code. Responses are parsed with <see cref="JsonDocument"/>
/// (not into a Dictionary), because a row's column order must be preserved
/// exactly as the server returned it, and .NET's JsonDocument/JsonElement DOM
/// keeps object property order from the source text — a plain dictionary
/// would not.
/// </summary>
internal sealed class SyntricDbTransport : IDisposable
{
    private readonly HttpClient _client;
    private readonly string _baseUrl;

    public SyntricDbTransport(ParsedConnectionInfo info)
    {
        _baseUrl = $"http://{info.Host}:{info.Port}";
        _client = new HttpClient();
        string raw = $"{info.User}:{info.Password}";
        _client.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Basic", Convert.ToBase64String(Encoding.UTF8.GetBytes(raw)));
        _client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
    }

    public async Task<JsonDocument> RequestAsync(HttpMethod method, string path, object? body, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(method, _baseUrl + path);
        if (body != null)
        {
            string json = JsonSerializer.Serialize(body);
            request.Content = new StringContent(json, Encoding.UTF8, "application/json");
        }

        HttpResponseMessage response;
        try
        {
            response = await _client.SendAsync(request, ct).ConfigureAwait(false);
        }
        catch (HttpRequestException ex)
        {
            throw new SyntricDbException($"Could not reach SyntricDB server at {_baseUrl}: {ex.Message}", ex);
        }

        using (response)
        {
            string content = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            JsonDocument? doc = null;
            if (!string.IsNullOrEmpty(content))
            {
                try { doc = JsonDocument.Parse(content); }
                catch (JsonException) { /* fall through with doc == null */ }
            }

            if (response.StatusCode == HttpStatusCode.Unauthorized || response.StatusCode == HttpStatusCode.Forbidden)
            {
                throw new SyntricDbException(ErrorMessage(doc, $"HTTP {(int)response.StatusCode}"), isAuthError: true);
            }
            if (!response.IsSuccessStatusCode)
            {
                throw new SyntricDbException(ErrorMessage(doc, $"HTTP {(int)response.StatusCode}"));
            }
            return doc ?? JsonDocument.Parse("{}");
        }
    }

    private static string ErrorMessage(JsonDocument? doc, string fallback)
    {
        if (doc != null
            && doc.RootElement.ValueKind == JsonValueKind.Object
            && doc.RootElement.TryGetProperty("error", out var errProp)
            && errProp.ValueKind == JsonValueKind.String)
        {
            return errProp.GetString() ?? fallback;
        }
        return fallback;
    }

    public void Dispose() => _client.Dispose();
}
