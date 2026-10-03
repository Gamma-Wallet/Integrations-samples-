<?php
declare(strict_types=1);

/**
 * A small client for the Gamma Integration API. PHP 8.1 or newer, with the cURL and JSON
 * extensions. Copy this file into your project.
 *
 * Use it on your server only: the access token must never reach a browser or an app.
 */

/** An answer other than success. $identifier is the stable number to branch on (see docs/API-REFERENCE.md). */
final class GammaApiException extends RuntimeException
{
    public function __construct(
        public readonly int $status,
        public readonly ?string $identifier,
        public readonly ?string $errorName,
        /** For a 400: each invalid field and what is wrong with it. */
        public readonly ?array $fields = null,
    ) {
        parent::__construct(sprintf(
            'Gamma answered HTTP %d: %s%s',
            $status,
            $errorName ?? 'no details',
            $identifier !== null ? " ($identifier)" : ''
        ));
    }

    /** Worth trying again later: too many requests, or a problem on Gamma's side. */
    public function isRetryable(): bool
    {
        return $this->status === 429 || $this->status >= 500;
    }

    /** The token no longer works: the business owner must create a new one. */
    public function isTokenProblem(): bool
    {
        return $this->status === 401;
    }
}

final class GammaClient
{
    public const DEFAULT_BASE_URL = 'https://integration.gamma-wallet.com';

    private string $baseUrl;

    public function __construct(private readonly string $accessToken, string $baseUrl = self::DEFAULT_BASE_URL)
    {
        if (!str_starts_with($accessToken, 'GWINT_')) {
            throw new InvalidArgumentException('The access token starts with GWINT_. Create one in Gamma Business → Integrations.');
        }
        $this->baseUrl = rtrim($baseUrl, '/');
    }

    /** Which business the token belongs to, its currency, and how long the token has left. */
    public function getConnection(): array
    {
        return $this->send('GET', '/api/Connection/Me');
    }

    /**
     * Declares a paid order. Safe to repeat with the same reference.
     *
     * @param array{reference: string, total: float|int|string, currencyCode: string, issuedOn?: string, platform?: string, pluginVersion?: string} $bill
     */
    public function createBill(array $bill): array
    {
        return $this->send('POST', '/api/Bill/Create', $bill);
    }

    public function getBill(string $billId): array
    {
        return $this->send('GET', '/api/Bill/Get/' . rawurlencode($billId));
    }

    public function getBillByReference(string $reference): array
    {
        return $this->send('GET', '/api/Bill/GetByReference?reference=' . rawurlencode($reference));
    }

    /**
     * Starts a store-credit request for the whole order. The QR is valid for 60 seconds.
     *
     * @param array{reference: string, total: float|int|string, currencyCode: string} $order
     */
    public function startCredit(array $order): array
    {
        return $this->send('POST', '/api/Credit/Start', $order);
    }

    /** @param string $creditRequest the value returned by startCredit() */
    public function checkCredit(string $creditRequest): array
    {
        return $this->send('POST', '/api/Credit/Check', ['creditRequest' => $creditRequest]);
    }

    private function send(string $method, string $path, ?array $body = null): array
    {
        $headers = [
            'Authorization: Bearer ' . $this->accessToken,
            'Accept: application/json',
            'User-Agent: gamma-integration-sample-php/1.0',
        ];
        $curl = curl_init($this->baseUrl . $path);
        $options = [
            CURLOPT_CUSTOMREQUEST => $method,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => 30,
            CURLOPT_CONNECTTIMEOUT => 10,
        ];
        if ($body !== null) {
            // Amounts go as JSON numbers; JSON_PRESERVE_ZERO_FRACTION keeps 20.0 as 20.0.
            $options[CURLOPT_POSTFIELDS] = json_encode($body, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE | JSON_PRESERVE_ZERO_FRACTION);
            $headers[] = 'Content-Type: application/json';
        }
        $options[CURLOPT_HTTPHEADER] = $headers;
        curl_setopt_array($curl, $options);

        $raw = curl_exec($curl);
        $status = (int) curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
        $transportError = curl_error($curl);
        curl_close($curl);

        if ($raw === false) {
            throw new GammaApiException(0, null, 'Gamma could not be reached: ' . $transportError);
        }
        $envelope = json_decode((string) $raw, true);
        if ($status >= 200 && $status < 300 && is_array($envelope['result'] ?? null)) {
            return $envelope['result'];
        }
        $error = is_array($envelope['error'] ?? null) ? $envelope['error'] : [];
        throw new GammaApiException(
            $status,
            $error['identifier'] ?? null,
            $error['message'] ?? null,
            is_array($error['onObject'] ?? null) ? $error['onObject'] : null,
        );
    }
}
