# PHP sample

PHP 8.1 or newer, with the `curl` and `json` extensions. No Composer packages.

| File | What it is |
|---|---|
| `GammaClient.php` | The client. Copy it into your project |
| `example.php` | A command-line example of both flows |

## Run it

```bash
export GAMMA_INTEGRATION_TOKEN=GWINT_…

php example.php me                       # which business, which currency, days left on the token
php example.php bill TEST-1001 12.50     # declare a paid order; saves the QR as a PNG and waits for the claim
php example.php status <billId>          # look a bill up
php example.php credit TEST-1002 20.00   # store-credit request; saves the QR and waits for Paid or Expired
```

`bill` creates a real bill on the business. Use order numbers you can recognise.

**On Windows**, PHP's cURL may not know where the trusted certificates are and answer "unable to get local issuer certificate". Point `curl.cainfo` in `php.ini` to a CA bundle (for example the one from <https://curl.se/docs/caextract.html>). Do not switch certificate checking off.

## In your project

```php
require 'GammaClient.php';

$gamma = new GammaClient(getenv('GAMMA_INTEGRATION_TOKEN'));
$me = $gamma->getConnection();
$bill = $gamma->createBill(['reference' => 'ORD-1001', 'total' => 42.50, 'currencyCode' => $me['currencyCode']]);
// show $bill['qrImageUrl'] to the customer; keep $bill['billId'] with the order

$now = $gamma->getBill($bill['billId']);   // ask every 5 s until $now['status'] === 'Claimed'
```

A script your page can poll:

```php
// gamma-status.php?order=1001 — the token stays on the server
$order = findOrder((int) $_GET['order']);               // your own lookup
$bill = $gamma->getBill($order['gamma_bill_id']);
header('Content-Type: application/json');
echo json_encode(['status' => $bill['status']]);
```

Every failure is a `GammaApiException`: branch on `$e->identifier`, retry when `isRetryable()`, tell the owner when `isTokenProblem()`.
