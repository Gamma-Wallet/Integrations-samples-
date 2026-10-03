# Workflows

The diagrams below use [Mermaid](https://mermaid.js.org/), which GitHub and most documentation tools draw automatically.

## Who talks to whom

```mermaid
flowchart TB
    subgraph yours["Your side"]
        P["Your page / till screen"]
        S["Your server<br/>(holds the access token)"]
    end
    subgraph gamma["Gamma"]
        G["Integration API<br/>integration.gamma-wallet.com"]
        R["Gamma Wallet services"]
    end
    C["Customer's phone<br/>Gamma Wallet app"]

    P -->|"1 · asks for a QR"| S
    S -->|"2 · Bill/Create or Credit/Start"| G
    G -->|"3 · QR code + link"| S
    S -->|"4 · QR code"| P
    C -->|"5 · scans the QR, confirms"| R
    P -->|"6 · every 5 s: done yet?"| S
    S -->|"7 · Bill/Get or Credit/Check"| G
```

Your page never calls Gamma, and Gamma never calls your server. Everything is a plain HTTPS request from your server, and your server finds out the result by asking.

## Flow 1 — a reward for a paid order

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    participant P as Your page
    participant S as Your server
    participant G as Gamma Integration API
    participant W as Gamma Wallet

    C->>P: Pays the order (card, cash, your usual payment)
    P->>S: Order paid
    S->>G: POST Bill/Create {reference, total, currencyCode}
    G-->>S: billId, code, link, QR image, status "Waiting"
    S->>S: Store billId with the order
    S-->>P: QR image (+ link)
    P-->>C: "Scan to collect your reward"
    C->>W: Scans the QR with Gamma Wallet, confirms
    W-->>C: Reward added to the wallet
    loop every 5 seconds while the page is open
        P->>S: Claimed yet?
        S->>G: GET Bill/Get/{billId}
        G-->>S: status "Waiting" or "Claimed"
        S-->>P: status
    end
    P-->>C: "Reward collected"
```

Things to know:

- **The bill does not expire.** If the customer leaves without scanning, they can still use the link or QR from their confirmation email later.
- **Retrying is safe.** If your call to `Bill/Create` times out, send it again with the same `reference`: you get the same bill back. Sending a different total under the same `reference` is refused with `409`.
- **One claim per bill.** After a customer claims it, nobody else can.
- **You do not need to wait for the claim** to complete the order. Polling is only there so your page can say "reward collected".

### The bill's status

```mermaid
stateDiagram-v2
    [*] --> Waiting: Bill/Create
    Waiting --> Claimed: a customer claims the reward
    Claimed --> [*]
```

There is no cancel and no expiry: a bill stays `Waiting` until someone claims it.

## Flow 2 — the whole order settled with store credits

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer
    participant P as Your page
    participant S as Your server
    participant G as Gamma Integration API
    participant W as Gamma Wallet

    C->>P: Chooses "Use Store Credits with Gamma"
    P->>S: Start a credit request for order #1001
    S->>G: POST Credit/Start {reference, total, currencyCode}
    G-->>S: creditRequest, link, QR image, expiresOn, secondsLeft = 60
    S->>S: Keep creditRequest with the order
    S-->>P: QR image + expiresOn
    P-->>C: QR code with a 60 → 0 countdown
    C->>W: Scans, sees shop + total, confirms
    W-->>C: Credits redeemed
    loop every 5 seconds
        P->>S: Settled yet?
        S->>G: POST Credit/Check {creditRequest}
        G-->>S: status "Waiting" / "Paid" / "Expired"
        S-->>P: status
    end
    alt Paid
        S->>S: Mark the order as settled
        P-->>C: Order confirmed
    else Expired
        P-->>C: "The code expired" + button to try again
    end
```

Things to know:

- **Whole order or nothing.** The amount is fixed by your request; the customer cannot change it and credits never cover part of an order.
- **60 seconds.** Show the countdown from `secondsLeft` (or `expiresOn`). When it reaches 0, keep asking: `Credit/Check` answers `Expired` only a few seconds later, once no payment can still arrive. Until then it answers `Waiting` with `secondsLeft: 0`.
- **One request settles once.** A second scan of the same QR is refused on the customer's side.
- **Nothing is stored until the customer settles.** An expired request leaves no trace; just start a new one.
- **No reward for this order.** Do not call `Bill/Create` for an order settled with store credits.
- **Keep `creditRequest` on your server.** It is what you send to `Credit/Check`. It is not secret, but it belongs to this one order.

### The credit request's status

```mermaid
stateDiagram-v2
    [*] --> Waiting: Credit/Start
    Waiting --> Paid: the customer settles the order
    Waiting --> Expired: 60 seconds pass with no settlement
    Paid --> [*]
    Expired --> [*]
    Expired --> Waiting: you call Credit/Start again (new request)
```

## When the customer shops on their phone

A customer browsing your shop on their phone cannot scan a QR code on the same screen. Show the `link` as a button as well:

```mermaid
flowchart LR
    A{"Is the page open<br/>on a phone?"}
    A -- "no (desktop, till)" --> Q["Show the QR code"]
    A -- "yes" --> B["Show a button<br/>'Open in Gamma Wallet'<br/>(href = link)"]
    B --> Q2["Show the QR code too,<br/>smaller"]
```

The link opens Gamma Wallet on the phone. If the app is not installed, it opens a page telling the customer how to get it.

## Your page and your server

Your page polls **your** server, not Gamma. A typical shape:

```mermaid
sequenceDiagram
    participant P as Your page (browser)
    participant S as Your server
    participant G as Gamma

    P->>S: GET /orders/1001/gamma-status
    S->>G: GET Bill/Get/{billId}  (token added here)
    G-->>S: {status: "Claimed"}
    S-->>P: {status: "Claimed"}
```

This keeps the token out of the browser and lets you decide what your page sees. The API refuses calls made from a web page on purpose.

## Choosing what to show

| Your order is… | Call | Show | Ask every 5 s with | Stop when |
|---|---|---|---|---|
| Paid normally | `Bill/Create` | QR "collect your reward" (no time limit) | `Bill/Get` | `Claimed`, or the customer leaves |
| To be settled with store credits | `Credit/Start` | QR "use your store credits" with a 60 s countdown | `Credit/Check` | `Paid` or `Expired` |
