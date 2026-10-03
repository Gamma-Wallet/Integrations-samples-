# C# sample

.NET 8 or newer. No packages beyond the framework.

| File | What it is |
|---|---|
| `GammaClient.cs` | The client. Copy it into your project |
| `Program.cs` | A command-line example of both flows |

## Run it

```bash
# Windows: set GAMMA_INTEGRATION_TOKEN=GWINT_…
export GAMMA_INTEGRATION_TOKEN=GWINT_…

dotnet run -- me                         # which business, which currency, days left on the token
dotnet run -- bill TEST-1001 12.50       # declare a paid order; saves the QR as a PNG and waits for the claim
dotnet run -- status <billId>            # look a bill up
dotnet run -- credit TEST-1002 20.00     # store-credit request; saves the QR and waits for Paid or Expired
```

`bill` creates a real bill on the business. Use order numbers you can recognise.

## In your project

```csharp
using var gamma = new GammaClient(Environment.GetEnvironmentVariable("GAMMA_INTEGRATION_TOKEN")!);

Connection me = await gamma.GetConnectionAsync();
Bill bill = await gamma.CreateBillAsync(new BillRequest("ORD-1001", 42.50m, me.CurrencyCode));
// show bill.QrImageUrl (or bill.QrPngBase64) to the customer; keep bill.BillId with the order

Bill now = await gamma.GetBillAsync(bill.BillId);   // ask every 5 s until now.Status == BillStatus.Claimed
```

- Create one `GammaClient` per token and reuse it (it holds one `HttpClient`). In ASP.NET Core, register it as a singleton per business.
- Every failure is a `GammaApiException`: branch on `Identifier`, retry when `IsRetryable`, tell the owner when `IsTokenProblem`.
- Amounts are `decimal`.
