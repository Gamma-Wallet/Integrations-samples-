# Java sample

Java 11 or newer. One file, no dependencies.

`GammaExample.java` holds the example and three small classes you can copy:

| Class | What it is |
|---|---|
| `GammaClient` | The client, on the built-in `java.net.http.HttpClient` |
| `GammaApiException` | Every failure: `identifier`, `isRetryable()`, `isTokenProblem()` |
| `Json` | Just enough JSON for the sample to run on its own. **Replace it with Jackson or Gson** in your project |

## Run it

No build step: Java 11 runs a single source file directly.

```bash
export GAMMA_INTEGRATION_TOKEN=GWINT_…

java GammaExample.java me                       # which business, which currency, days left on the token
java GammaExample.java bill TEST-1001 12.50     # declare a paid order; saves the QR as a PNG and waits for the claim
java GammaExample.java status <billId>          # look a bill up
java GammaExample.java credit TEST-1002 20.00   # store-credit request; saves the QR and waits for Paid or Expired
```

`bill` creates a real bill on the business. Use order numbers you can recognise.

## In your project

```java
GammaClient gamma = new GammaClient(System.getenv("GAMMA_INTEGRATION_TOKEN"), GammaClient.DEFAULT_BASE_URL);

String currency = (String) gamma.getConnection().get("currencyCode");
Map<String, Object> bill = gamma.createBill(Map.of("reference", "ORD-1001", "total", new BigDecimal("42.50"), "currencyCode", currency));
// show bill.get("qrImageUrl") to the customer; keep bill.get("billId") with the order

Map<String, Object> now = gamma.getBill((String) bill.get("billId"));   // ask every 5 s until "Claimed"
```

- The sample uses `double` for brevity. Use `BigDecimal` for amounts in real code.
- With Jackson, map the answers to classes; the field names are in [the API reference](../../docs/API-REFERENCE.md).
- One `GammaClient` per token; it is safe to share between threads.
