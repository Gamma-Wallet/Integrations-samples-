// A small client for the Gamma Integration API. Node.js 18 or newer, no dependencies.
// Copy this file into your project.
//
// Use it on your server only: the access token must never reach a browser or an app.

export const DEFAULT_BASE_URL = 'https://integration.gamma-wallet.com';

/** An answer other than success. `identifier` is the stable number to branch on (see docs/API-REFERENCE.md). */
export class GammaApiError extends Error {
    constructor(status, error) {
        super(`Gamma answered HTTP ${status}: ${error?.message ?? 'no details'}${error?.identifier ? ` (${error.identifier})` : ''}`);
        this.name = 'GammaApiError';
        this.status = status;
        this.identifier = error?.identifier ?? null;
        this.errorName = error?.message ?? null;
        /** For a 400: each invalid field and what is wrong with it. */
        this.fields = error?.onObject ?? null;
    }

    /** Worth trying again later: too many requests, or a problem on Gamma's side. */
    get isRetryable() {
        return this.status === 429 || this.status >= 500;
    }

    /** The token no longer works: the business owner must create a new one. */
    get isTokenProblem() {
        return this.status === 401;
    }
}

export class GammaClient {
    /**
     * @param {string} accessToken the business's token, starting with GWINT_
     * @param {string} [baseUrl]
     */
    constructor(accessToken, baseUrl = DEFAULT_BASE_URL) {
        if (!accessToken?.startsWith('GWINT_')) {
            throw new Error('The access token starts with GWINT_. Create one in Gamma Business → Integrations.');
        }
        this.token = accessToken;
        this.baseUrl = baseUrl.replace(/\/+$/, '');
    }

    /** Which business the token belongs to, its currency, and how long the token has left. */
    getConnection() {
        return this.#send('GET', '/api/Connection/Me');
    }

    /**
     * Declares a paid order. Safe to repeat with the same reference.
     * @param {{reference: string, total: number, currencyCode: string, issuedOn?: string, platform?: string, pluginVersion?: string}} bill
     */
    createBill(bill) {
        return this.#send('POST', '/api/Bill/Create', bill);
    }

    getBill(billId) {
        return this.#send('GET', `/api/Bill/Get/${encodeURIComponent(billId)}`);
    }

    getBillByReference(reference) {
        return this.#send('GET', `/api/Bill/GetByReference?reference=${encodeURIComponent(reference)}`);
    }

    /**
     * Starts a store-credit request for the whole order. The QR is valid for 60 seconds.
     * @param {{reference: string, total: number, currencyCode: string}} order
     */
    startCredit(order) {
        return this.#send('POST', '/api/Credit/Start', order);
    }

    /** @param {string} creditRequest the value returned by startCredit */
    checkCredit(creditRequest) {
        return this.#send('POST', '/api/Credit/Check', { creditRequest });
    }

    async #send(method, path, body) {
        const response = await fetch(this.baseUrl + path, {
            method,
            headers: {
                Authorization: `Bearer ${this.token}`,
                Accept: 'application/json',
                'User-Agent': 'gamma-integration-sample-node/1.0',
                ...(body ? { 'Content-Type': 'application/json' } : {})
            },
            body: body ? JSON.stringify(body) : undefined,
            signal: AbortSignal.timeout(30_000)
        });

        let envelope = null;
        try {
            envelope = await response.json();
        } catch {
            // Not our JSON (a proxy error page, for example): reported below by status code.
        }
        if (response.ok && envelope?.result) return envelope.result;
        throw new GammaApiError(response.status, envelope?.error);
    }
}
