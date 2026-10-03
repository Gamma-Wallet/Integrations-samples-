<?php
declare(strict_types=1);

// Gamma Integration API — PHP sample.
//
//   php example.php me
//   php example.php bill   <orderNumber> <total>     declare a paid order, wait for the reward to be claimed
//   php example.php status <billId>                  look a bill up
//   php example.php credit <orderNumber> <total>     settle an order with store credits (60-second QR)
//
// The access token comes from the GAMMA_INTEGRATION_TOKEN environment variable.

require __DIR__ . '/GammaClient.php';

$token = getenv('GAMMA_INTEGRATION_TOKEN') ?: '';
$command = $argv[1] ?? '';
if ($command === '' || $token === '') {
    fwrite(STDERR, "Set GAMMA_INTEGRATION_TOKEN, then run: me | bill <orderNumber> <total> | status <billId> | credit <orderNumber> <total>\n");
    exit(2);
}
$gamma = new GammaClient($token, getenv('GAMMA_INTEGRATION_URL') ?: GammaClient::DEFAULT_BASE_URL);

function showConnection(GammaClient $gamma): void
{
    $me = $gamma->getConnection();
    echo "Business:  {$me['businessName']} ({$me['businessId']})\n";
    echo "Currency:  {$me['currencyCode']}\n";
    echo 'Rewards:   ' . ($me['canClaim'] ? 'customers can claim rewards' : 'no reward programme is running') . "\n";
    echo "Token:     {$me['token']['displayPrefix']}…{$me['token']['displaySuffix']}, {$me['token']['daysLeft']} day(s) left\n";
    if ($me['token']['daysLeft'] < 14) {
        echo "           Ask the business owner for a new token soon.\n";
    }
}

// Flow 1: the order is paid. Declare it, show the QR, and wait until the customer claims the reward.
function rewardForPaidOrder(GammaClient $gamma, string $orderNumber, float $total): void
{
    $me = $gamma->getConnection();
    $bill = $gamma->createBill([
        'reference' => $orderNumber,
        'total' => $total,
        'currencyCode' => $me['currencyCode'],
        'platform' => 'php-sample',
        'pluginVersion' => '1.0',
    ]);

    $file = saveQr($bill['qrPngBase64'] ?? null, 'qr-bill-' . safe($orderNumber) . '.png');
    echo "Bill {$bill['billId']} for {$bill['total']} {$bill['currencyCode']} is {$bill['status']}.\n";
    echo "Show this QR to the customer: $file\n";
    echo "Or give them the link: {$bill['link']}\n";
    echo "Waiting for the customer to claim the reward (Ctrl+C to stop)…\n";

    // In a real shop your page asks your server, and your server makes this call.
    $giveUpAt = time() + 10 * 60;
    while ($bill['status'] === 'Waiting' && time() < $giveUpAt) {
        sleep(5);
        $billId = $bill['billId'];
        $bill = withRetry(fn () => $gamma->getBill($billId));
    }

    echo $bill['status'] === 'Claimed'
        ? "Reward claimed on {$bill['claimedOn']}.\n"
        : "Not claimed yet. The bill stays valid; the customer can still use the link later.\n";
}

// Flow 2: the customer settles the whole order with store credits. The QR is valid for 60 seconds.
function settleWithStoreCredits(GammaClient $gamma, string $orderNumber, float $total): void
{
    $me = $gamma->getConnection();
    $request = $gamma->startCredit(['reference' => $orderNumber, 'total' => $total, 'currencyCode' => $me['currencyCode']]);

    $file = saveQr($request['qrPngBase64'] ?? null, 'qr-credit-' . safe($orderNumber) . '.png');
    echo "Ask the customer to scan within {$request['secondsLeft']} s: $file\n";
    echo "On a phone, give them the link instead: {$request['link']}\n";

    // Keep asking until the answer is final. "Expired" comes a few seconds after the countdown.
    $creditRequest = $request['creditRequest'];
    while ($request['status'] === 'Waiting') {
        sleep(5);
        $request = withRetry(fn () => $gamma->checkCredit($creditRequest));
        if ($request['status'] === 'Waiting') {
            echo "  waiting… {$request['secondsLeft']} s left\n";
        }
    }

    echo $request['status'] === 'Paid'
        ? "Settled with store credits on {$request['paidOn']}. Complete the order. (Do not declare a bill for it.)\n"
        : "The code expired. Offer the customer to try again: start a new request.\n";
}

/** Too many requests or a problem on Gamma's side: wait and try again, a few times. */
function withRetry(callable $call): array
{
    for ($attempt = 1; ; $attempt++) {
        try {
            return $call();
        } catch (GammaApiException $e) {
            if (!$e->isRetryable() || $attempt >= 4) {
                throw $e;
            }
            sleep($e->status === 429 ? 60 : 5 * $attempt);
        }
    }
}

function saveQr(?string $base64Png, string $file): string
{
    if ($base64Png === null || $base64Png === '') {
        return '(no image in this answer)';
    }
    file_put_contents($file, base64_decode($base64Png, true));
    return realpath($file) ?: $file;
}

function safe(string $text): string
{
    return preg_replace('/[^A-Za-z0-9_-]/', '_', $text);
}

function amount(string $text): float
{
    if (!is_numeric($text) || (float) $text <= 0) {
        throw new InvalidArgumentException("Not an amount: $text");
    }
    return (float) $text;
}

try {
    match (true) {
        $command === 'me' => showConnection($gamma),
        $command === 'bill' && isset($argv[2], $argv[3]) => rewardForPaidOrder($gamma, $argv[2], amount($argv[3])),
        $command === 'status' && isset($argv[2]) => (function () use ($gamma, $argv) {
            $bill = $gamma->getBill($argv[2]);
            echo "{$bill['reference']}: {$bill['status']}" . ($bill['claimedOn'] ? " on {$bill['claimedOn']}" : '') . "\n";
        })(),
        $command === 'credit' && isset($argv[2], $argv[3]) => settleWithStoreCredits($gamma, $argv[2], amount($argv[3])),
        default => throw new InvalidArgumentException('Unknown command.'),
    };
} catch (GammaApiException $e) {
    fwrite(STDERR, $e->getMessage() . "\n");
    if ($e->fields !== null) {
        fwrite(STDERR, 'Fields: ' . json_encode($e->fields) . "\n");
    }
    if ($e->isTokenProblem()) {
        fwrite(STDERR, "The business owner must create a new token in Gamma Business → Integrations.\n");
    }
    exit(1);
} catch (InvalidArgumentException $e) {
    fwrite(STDERR, $e->getMessage() . "\n");
    exit(2);
}
