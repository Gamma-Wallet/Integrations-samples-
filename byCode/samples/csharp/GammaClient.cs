using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace GammaIntegration;

/// <summary>
/// A small client for the Gamma Integration API. Copy this file into your project.
///
/// Create one instance per business (one access token) and reuse it: it holds one HttpClient.
/// Use it on your server only. The access token must never reach a browser or an app.
/// </summary>
public sealed class GammaClient : IDisposable
{
    public const string DefaultBaseUrl = "https://integration.gamma-wallet.com";

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        Converters = { new JsonStringEnumConverter() }
    };

    private readonly HttpClient _http;

    public GammaClient(string accessToken, string baseUrl = DefaultBaseUrl)
    {
        if (string.IsNullOrWhiteSpace(accessToken) || !accessToken.StartsWith("GWINT_", StringComparison.Ordinal))
            throw new ArgumentException("The access token starts with GWINT_. Create one in Gamma Business → Integrations.", nameof(accessToken));

        _http = new HttpClient { BaseAddress = new Uri(baseUrl.TrimEnd('/') + "/"), Timeout = TimeSpan.FromSeconds(30) };
        _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", accessToken);
        _http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("gamma-integration-sample-csharp/1.0");
    }

    /// <summary>Which business the token belongs to, its currency, and how long the token has left.</summary>
    public Task<Connection> GetConnectionAsync(CancellationToken ct = default) =>
        SendAsync<Connection>(HttpMethod.Get, "api/Connection/Me", null, ct);

    /// <summary>Declares a paid order. Safe to repeat with the same reference.</summary>
    public Task<Bill> CreateBillAsync(BillRequest request, CancellationToken ct = default) =>
        SendAsync<Bill>(HttpMethod.Post, "api/Bill/Create", request, ct);

    public Task<Bill> GetBillAsync(string billId, CancellationToken ct = default) =>
        SendAsync<Bill>(HttpMethod.Get, $"api/Bill/Get/{Uri.EscapeDataString(billId)}", null, ct);

    public Task<Bill> GetBillByReferenceAsync(string reference, CancellationToken ct = default) =>
        SendAsync<Bill>(HttpMethod.Get, $"api/Bill/GetByReference?reference={Uri.EscapeDataString(reference)}", null, ct);

    /// <summary>Starts a store-credit request for the whole order. The QR is valid for 60 seconds.</summary>
    public Task<CreditRequest> StartCreditAsync(CreditStart request, CancellationToken ct = default) =>
        SendAsync<CreditRequest>(HttpMethod.Post, "api/Credit/Start", request, ct);

    public Task<CreditRequest> CheckCreditAsync(string creditRequest, CancellationToken ct = default) =>
        SendAsync<CreditRequest>(HttpMethod.Post, "api/Credit/Check", new { creditRequest }, ct);

    private async Task<T> SendAsync<T>(HttpMethod method, string path, object? body, CancellationToken ct) where T : class
    {
        using var message = new HttpRequestMessage(method, path);
        if (body is not null)
            message.Content = JsonContent.Create(body, options: Json);

        using HttpResponseMessage response = await _http.SendAsync(message, ct);
        Envelope<T>? envelope = null;
        try
        {
            envelope = await response.Content.ReadFromJsonAsync<Envelope<T>>(Json, ct);
        }
        catch (JsonException)
        {
            // Not our JSON (a proxy error page, for example): reported below by status code.
        }

        if (response.IsSuccessStatusCode && envelope?.Result is not null)
            return envelope.Result;

        throw new GammaApiException((int)response.StatusCode, envelope?.Error);
    }

    public void Dispose() => _http.Dispose();

    private sealed record Envelope<T>(int StatusCode, string? Message, T? Result, ApiError? Error) where T : class;
}

public sealed record ApiError(string? Message, string? Identifier, string? Code, JsonElement? OnObject);

/// <summary>
/// An answer other than success. <see cref="Identifier"/> is the stable number to branch on
/// (see the error table in docs/API-REFERENCE.md).
/// </summary>
public sealed class GammaApiException(int status, ApiError? error)
    : Exception($"Gamma answered HTTP {status}: {error?.Message ?? "no details"}{(error?.Identifier is null ? "" : $" ({error.Identifier})")}")
{
    public int Status { get; } = status;
    public string? Identifier { get; } = error?.Identifier;
    public string? ErrorName { get; } = error?.Message;
    /// <summary>For a 400: each invalid field and what is wrong with it.</summary>
    public JsonElement? Fields { get; } = error?.OnObject;

    /// <summary>Worth trying again later: too many requests, or a problem on Gamma's side.</summary>
    public bool IsRetryable => Status == 429 || Status >= 500;

    /// <summary>The token no longer works: the business owner must create a new one.</summary>
    public bool IsTokenProblem => Status == 401;
}

// ---------------------------------------------------------------- what is sent

public sealed record BillRequest(
    string Reference,
    decimal Total,
    string CurrencyCode,
    DateTimeOffset? IssuedOn = null,
    string? Platform = null,
    string? PluginVersion = null);

public sealed record CreditStart(string Reference, decimal Total, string CurrencyCode);

// ---------------------------------------------------------------- what comes back

public sealed record Connection(
    string BusinessId,
    string BusinessName,
    string CurrencyCode,
    string? CurrencyName,
    bool CanClaim,
    string? ActiveServiceType,
    TokenInfo Token);

public sealed record TokenInfo(string DisplayPrefix, string DisplaySuffix, DateTimeOffset ExpiresOn, int DaysLeft);

public enum BillStatus { Waiting, Claimed }

public sealed record Bill(
    string BillId,
    string Reference,
    decimal Total,
    string CurrencyCode,
    BillStatus Status,
    DateTimeOffset? ClaimedOn,
    DateTimeOffset IssuedOn,
    DateTimeOffset ReceivedOn,
    string Code,
    string Link,
    string QrImageUrl,
    string? QrPngBase64);

public enum CreditStatus { Waiting, Paid, Expired }

public sealed record CreditRequest(
    string RequestId,
    string Reference,
    decimal Total,
    string CurrencyCode,
    CreditStatus Status,
    DateTimeOffset? PaidOn,
    DateTimeOffset ExpiresOn,
    int SecondsLeft,
    // The API calls this field "creditRequest"; renamed here so it does not clash with the type name.
    [property: JsonPropertyName("creditRequest")] string CreditRequestToken,
    string Link,
    string? QrPngBase64);
