# Node.js sample

Node.js 18 or newer. No dependencies: it uses the built-in `fetch`.

| File | What it is |
|---|---|
| `gamma-client.mjs` | The client. Copy it into your project |
| `example.mjs` | A command-line example of both flows |

## Run it

```bash
export GAMMA_INTEGRATION_TOKEN=GWINT_…

node example.mjs me                      # which business, which currency, days left on the token
node example.mjs bill TEST-1001 12.50    # declare a paid order; saves the QR as a PNG and waits for the claim
node example.mjs status <billId>         # look a bill up
node example.mjs credit TEST-1002 20.00  # store-credit request; saves the QR and waits for Paid or Expired
```

`bill` creates a real bill on the business. Use order numbers you can recognise.

## In your project

```js
import { GammaClient, GammaApiError } from './gamma-client.mjs';

const gamma = new GammaClient(process.env.GAMMA_INTEGRATION_TOKEN);
const me = await gamma.getConnection();
const bill = await gamma.createBill({ reference: 'ORD-1001', total: 42.5, currencyCode: me.currencyCode });
// show bill.qrImageUrl to the customer; keep bill.billId with the order

const now = await gamma.getBill(bill.billId);   // ask every 5 s until now.status === 'Claimed'
```

A route your page can poll, with Express:

```js
app.get('/orders/:id/gamma-status', async (req, res) => {
    const order = await orders.find(req.params.id);          // your own lookup
    const bill = await gamma.getBill(order.gammaBillId);       // the token stays on the server
    res.json({ status: bill.status });
});
```

Every failure is a `GammaApiError`: branch on `identifier`, retry when `isRetryable`, tell the owner when `isTokenProblem`.
