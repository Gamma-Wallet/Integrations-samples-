# API reference

**Base address:** `https://integration.gamma-wallet.com`

All calls are made from your server, over HTTPS, with JSON bodies (`Content-Type: application/json`).

## Authentication

Send the business's access token on every call:

```
Authorization: Bearer GWINT_…
```

- The business owner creates the token in **Gamma Business → Integrations** (<https://business.gamma-wallet.com>). It is shown once.
- It starts with `GWINT_` and is 49 characters long.
- A business has **one** active token. Creating a new one disables the previous one at once. A new token can be created once every 24 hours.
- The owner chooses how long it is valid (up to a year) and can disable it at any time.
- The token identifies the business: no call takes a business id.

## Responses

Every answer, success or error, has the same envelope:

```json
{
  "version": "1.0",
  "statusCode": 200,
  "message": "OK",
  "result": { … },
  "error": null
}
```

On an error, `result` is absent and `error` says what went wrong:

```json
{
  "version": "1.0",
  "statusCode": 404,
  "message": "NotFound",
  "error": { "message": "BillNotFound", "identifier": "0394", "code": "404" }
}
```

- `error.identifier` is a stable number you can branch on. `error.message` is its name.
- A `400` (the body itself is invalid) has no `identifier`; `error.onObject` lists the fields and what is wrong with each.

Dates are UTC, in ISO 8601 (`2026-10-02T14:34:31.8376830Z`). Amounts are JSON numbers.

---

## Connection/Me

Confirms the token and tells you which business it belongs to. Call it when you set up the connection, and from time to time to watch the token's expiry.

```
GET /api/Connection/Me
```

**Answer**

```json
{
  "result": {
    "businessId": "65f0c1a2b3c4d5e6f7a8b9c0",
    "businessName": "Demo Café",
    "currencyCode": "EUR",
    "currencyName": "Euro",
    "canClaim": true,
    "activeServiceType": "PoolFixedPercentage",
    "token": {
      "displayPrefix": "GWINT_Ab12Cd3",
      "displaySuffix": "x9Yz",
      "expiresOn": "2026-10-31T13:23:00.427Z",
      "daysLeft": 28
    }
  }
}
```

| Field | Meaning |
|---|---|
| `currencyCode` | The currency every amount you send must be in |
| `canClaim` | `true` when customers can claim rewards right now (the business has a running reward programme). If `false`, bills are still recorded, but a claim would be refused until the business starts one |
| `token.daysLeft` | Whole days until the token stops working. Warn the business owner when it gets low |

---

## Bill/Create

Declares an order that **is paid**, and returns the QR code the customer scans to claim their reward.

```
POST /api/Bill/Create
```

**Body**

```json
{
  "reference": "ORD-1001",
  "total": 42.50,
  "currencyCode": "EUR",
  "issuedOn": "2026-10-02T14:30:00Z",
  "platform": "my-shop",
  "pluginVersion": "1.0.0"
}
```

| Field | Required | Meaning |
|---|---|---|
| `reference` | yes | Your order number. Up to 128 characters. Unique per business |
| `total` | yes | The order total. Greater than 0 |
| `currencyCode` | yes | The business's currency, as `Connection/Me` returns it |
| `issuedOn` | no | Your date and time for the order. Defaults to now |
| `platform` | no | Name of your system, up to 40 characters. Helps support |
| `pluginVersion` | no | Your integration's version, up to 40 characters |

**Answer**

```json
{
  "result": {
    "billId": "6abfc0f7d779356b0c4f7941",
    "reference": "ORD-1001",
    "total": 42.5,
    "currencyCode": "EUR",
    "status": "Waiting",
    "claimedOn": null,
    "issuedOn": "2026-10-02T14:30:00Z",
    "receivedOn": "2026-10-02T14:34:31.837683Z",
    "code": "j9Idlm0voGTbU5X0UhsFXQ",
    "link": "https://integration.gamma-wallet.com/b/j9Idlm0voGTbU5X0UhsFXQ",
    "qrImageUrl": "https://integration.gamma-wallet.com/b/j9Idlm0voGTbU5X0UhsFXQ.png",
    "qrPngBase64": "iVBORw0KGgoAAAANSUhEUgAA…"
  }
}
```

| Field | Meaning |
|---|---|
| `billId` | Gamma's id for the bill. Keep it with the order |
| `status` | `Waiting` until a customer claims the reward, then `Claimed` |
| `link` | What the QR code holds. Give it to a customer on a phone as a button |
| `qrImageUrl` | A public address of the QR image. Use it in a page or an email |
| `qrPngBase64` | The QR image itself (PNG, base64). Only in this answer |

**Retrying:** sending the same `reference` with the same `total` and `currencyCode` again returns the same bill, with the same code. A different total under a used `reference` is refused (`409`).

**Do not** call it for an order settled with store credits.

---

## Bill/Get

The bill, and whether the reward was claimed. This is what you poll.

```
GET /api/Bill/Get/{billId}
```

**Answer:** the same object as `Bill/Create`, without `qrPngBase64`. When claimed:

```json
{ "result": { "billId": "6abfc0f7d779356b0c4f7941", "status": "Claimed", "claimedOn": "2026-10-02T14:35:58.455Z", … } }
```

## Bill/GetByReference

The same, by your own order number. Useful if you did not keep the `billId`.

```
GET /api/Bill/GetByReference?reference=ORD-1001
```

The order number goes in the query string because order numbers can contain a `/`. Encode it (`encodeURIComponent` or equivalent).

---

## Credit/Start

Starts a request for the customer to settle the **whole** order with their store credits, and returns a QR code that is valid for 60 seconds. Nothing is stored until the customer settles.

```
POST /api/Credit/Start
```

**Body**

```json
{ "reference": "ORD-1002", "total": 20.00, "currencyCode": "EUR" }
```

| Field | Required | Meaning |
|---|---|---|
| `reference` | yes | Your order number, up to 128 characters |
| `total` | yes | The whole order total. Greater than 0 |
| `currencyCode` | yes | The business's currency |

**Answer**

```json
{
  "result": {
    "requestId": "wiefYSNluQnw-rRBisothg",
    "reference": "ORD-1002",
    "total": 20,
    "currencyCode": "EUR",
    "status": "Waiting",
    "paidOn": null,
    "expiresOn": "2026-10-02T14:39:53Z",
    "secondsLeft": 60,
    "creditRequest": "eyJpZCI6IndpZWZZU05sdVFu…OTMzfQ.B-6fxI8Rh_5clmMwYVIRy-Ysipemlaw18U_APH6f2BQ",
    "link": "https://integration.gamma-wallet.com/c/eyJpZCI6IndpZWZZU05sdVFu…",
    "qrPngBase64": "iVBORw0KGgoAAAANSUhEUgAA…"
  }
}
```

| Field | Meaning |
|---|---|
| `creditRequest` | Keep it on your server for this order; send it to `Credit/Check` |
| `expiresOn`, `secondsLeft` | For the countdown next to the QR |
| `link` | What the QR code holds. On a phone, offer it as a button |
| `qrPngBase64` | The QR image (PNG, base64). Only in this answer. There is no public image address for a credit request |

The QR code is denser than a bill's, because it carries the signed request. Show it at least 220 × 220 pixels.

Each call starts a new, independent request, even for the same order.

---

## Credit/Check

Whether the request was settled. This is what you poll.

```
POST /api/Credit/Check
```

**Body**

```json
{ "creditRequest": "eyJpZCI6IndpZWZZU05sdVFu…" }
```

**Answer**

```json
{ "result": { "requestId": "wiefYSNluQnw-rRBisothg", "status": "Waiting", "secondsLeft": 41, … } }
```

| `status` | What to do |
|---|---|
| `Waiting` | Keep showing the QR and the countdown. Ask again in 5 seconds |
| `Paid` | The customer settled the whole order with store credits. Complete the order. `paidOn` says when |
| `Expired` | Nobody settled it in time. Remove the QR; offer to start again with `Credit/Start` |

`Expired` comes a few seconds after the countdown reaches 0, once no settlement can still arrive. Until then the answer is `Waiting` with `secondsLeft: 0`; keep asking.

---

## Public addresses (no token)

These are what customers and email clients open. You do not call them from your server.

| Address | Is |
|---|---|
| `GET /b/{code}` | The link inside a bill's QR code |
| `GET /b/{code}.png` | A bill's QR image (cacheable) |
| `GET /c/{creditRequest}` | The link inside a store-credit QR code |

---

## Errors

| HTTP | `identifier` | Name | What to do |
|---|---|---|---|
| 400 | — | Missing or Invalid parameters | Fix the body; `error.onObject` lists each field |
| 401 | 0392 | IntegrationTokenMissing | Send `Authorization: Bearer GWINT_…` |
| 401 | 0388 | IntegrationTokenInvalid | The token is wrong or incomplete; check how it was copied |
| 401 | 0389 | IntegrationTokenDisabled | The owner disabled or replaced it; they create a new one in Gamma Business |
| 401 | 0390 | IntegrationTokenExpired | Its validity ended; the owner creates a new one |
| 403 | 0393 | IntegrationBusinessNotAvailable | The business behind the token is not available |
| 404 | 0394 | BillNotFound | No bill with that id or reference for this business |
| 409 | 0398 | BillReferenceUsedForAnotherBill | This order number was already declared with a different amount or currency |
| 422 | 0395 | BillTotalInvalid | The total must be greater than 0 |
| 422 | 0396 | BillCurrencyNotOfBusiness | Use the currency `Connection/Me` returns |
| 422 | 0399 | CreditRequestInvalid | The `creditRequest` sent to `Credit/Check` is not one this business started |
| 429 | 0403 | TooManyRequests | Too many calls in a minute. Wait a minute and retry |
| 5xx | — | — | A problem on Gamma's side. Retry later, with a growing pause |

## Limits

- **600 calls a minute per token.** One waiting order polled every 5 seconds costs 12 calls a minute.
- **1,200 calls a minute per address**, across all tokens. Relevant if one server serves many businesses.
- Request bodies are small; keep `reference` under 128 characters.

## Versioning

This is version 1. Fields may be added to answers; existing fields keep their meaning. Ignore fields you do not know.
