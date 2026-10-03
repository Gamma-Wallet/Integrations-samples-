using GammaIntegration;

// Gamma Integration API — C# sample.
//
//   dotnet run -- me
//   dotnet run -- bill   <orderNumber> <total>     declare a paid order, wait for the reward to be claimed
//   dotnet run -- status <billId>                  look a bill up
//   dotnet run -- credit <orderNumber> <total>     settle an order with store credits (60-second QR)
//
// The access token comes from the GAMMA_INTEGRATION_TOKEN environment variable.

string token = Environment.GetEnvironmentVariable("GAMMA_INTEGRATION_TOKEN") ?? "";
string baseUrl = Environment.GetEnvironmentVariable("GAMMA_INTEGRATION_URL") ?? GammaClient.DefaultBaseUrl;
if (args.Length == 0 || string.IsNullOrWhiteSpace(token))
{
    Console.Error.WriteLine("Set GAMMA_INTEGRATION_TOKEN, then run: me | bill <orderNumber> <total> | status <billId> | credit <orderNumber> <total>");
    return 2;
}

using var gamma = new GammaClient(token, baseUrl);
using var cancel = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; cancel.Cancel(); };

try
{
    switch (args[0])
    {
        case "me":
            await ShowConnection();
            break;
        case "bill" when args.Length == 3:
            await RewardForPaidOrder(args[1], decimal.Parse(args[2], System.Globalization.CultureInfo.InvariantCulture));
            break;
        case "status" when args.Length == 2:
            Bill bill = await gamma.GetBillAsync(args[1], cancel.Token);
            Console.WriteLine($"{bill.Reference}: {bill.Status}{(bill.ClaimedOn is null ? "" : $" on {bill.ClaimedOn:u}")}");
            break;
        case "credit" when args.Length == 3:
            await SettleWithStoreCredits(args[1], decimal.Parse(args[2], System.Globalization.CultureInfo.InvariantCulture));
            break;
        default:
            Console.Error.WriteLine("Unknown command.");
            return 2;
    }
    return 0;
}
catch (GammaApiException e)
{
    Console.Error.WriteLine(e.Message);
    if (e.Fields is not null) Console.Error.WriteLine($"Fields: {e.Fields}");
    if (e.IsTokenProblem) Console.Error.WriteLine("The business owner must create a new token in Gamma Business → Integrations.");
    return 1;
}
catch (OperationCanceledException)
{
    Console.Error.WriteLine("Stopped.");
    return 1;
}

async Task ShowConnection()
{
    Connection me = await gamma.GetConnectionAsync(cancel.Token);
    Console.WriteLine($"Business:  {me.BusinessName} ({me.BusinessId})");
    Console.WriteLine($"Currency:  {me.CurrencyCode}");
    Console.WriteLine($"Rewards:   {(me.CanClaim ? "customers can claim rewards" : "no reward programme is running")}");
    Console.WriteLine($"Token:     {me.Token.DisplayPrefix}…{me.Token.DisplaySuffix}, {me.Token.DaysLeft} day(s) left");
    if (me.Token.DaysLeft < 14) Console.WriteLine("           Ask the business owner for a new token soon.");
}

// Flow 1: the order is paid. Declare it, show the QR, and wait until the customer claims the reward.
async Task RewardForPaidOrder(string orderNumber, decimal total)
{
    Connection me = await gamma.GetConnectionAsync(cancel.Token);
    Bill bill = await gamma.CreateBillAsync(new BillRequest(orderNumber, total, me.CurrencyCode, Platform: "csharp-sample", PluginVersion: "1.0"), cancel.Token);

    string file = SaveQr(bill.QrPngBase64, $"qr-bill-{Safe(orderNumber)}.png");
    Console.WriteLine($"Bill {bill.BillId} for {bill.Total} {bill.CurrencyCode} is {bill.Status}.");
    Console.WriteLine($"Show this QR to the customer: {file}");
    Console.WriteLine($"Or give them the link: {bill.Link}");
    Console.WriteLine("Waiting for the customer to claim the reward (Ctrl+C to stop)…");

    // In a real shop your page asks your server, and your server makes this call.
    DateTime giveUpAt = DateTime.UtcNow.AddMinutes(10);
    while (bill.Status == BillStatus.Waiting && DateTime.UtcNow < giveUpAt)
    {
        await Task.Delay(TimeSpan.FromSeconds(5), cancel.Token);
        bill = await WithRetry(() => gamma.GetBillAsync(bill.BillId, cancel.Token));
    }

    Console.WriteLine(bill.Status == BillStatus.Claimed
        ? $"Reward claimed on {bill.ClaimedOn:u}."
        : "Not claimed yet. The bill stays valid; the customer can still use the link later.");
}

// Flow 2: the customer settles the whole order with store credits. The QR is valid for 60 seconds.
async Task SettleWithStoreCredits(string orderNumber, decimal total)
{
    Connection me = await gamma.GetConnectionAsync(cancel.Token);
    CreditRequest request = await gamma.StartCreditAsync(new CreditStart(orderNumber, total, me.CurrencyCode), cancel.Token);

    string file = SaveQr(request.QrPngBase64, $"qr-credit-{Safe(orderNumber)}.png");
    Console.WriteLine($"Ask the customer to scan within {request.SecondsLeft} s: {file}");
    Console.WriteLine($"On a phone, give them the link instead: {request.Link}");

    // Keep asking until the answer is final. "Expired" comes a few seconds after the countdown.
    while (request.Status == CreditStatus.Waiting)
    {
        await Task.Delay(TimeSpan.FromSeconds(5), cancel.Token);
        request = await WithRetry(() => gamma.CheckCreditAsync(request.CreditRequestToken, cancel.Token));
        if (request.Status == CreditStatus.Waiting) Console.WriteLine($"  waiting… {request.SecondsLeft} s left");
    }

    if (request.Status == CreditStatus.Paid)
        Console.WriteLine($"Settled with store credits on {request.PaidOn:u}. Complete the order. (Do not declare a bill for it.)");
    else
        Console.WriteLine("The code expired. Offer the customer to try again: start a new request.");
}

// Too many requests or a problem on Gamma's side: wait and try again, a few times.
async Task<T> WithRetry<T>(Func<Task<T>> call)
{
    for (int attempt = 1; ; attempt++)
    {
        try
        {
            return await call();
        }
        catch (GammaApiException e) when (e.IsRetryable && attempt < 4)
        {
            await Task.Delay(TimeSpan.FromSeconds(e.Status == 429 ? 60 : 5 * attempt), cancel.Token);
        }
    }
}

static string SaveQr(string? base64Png, string file)
{
    if (string.IsNullOrEmpty(base64Png)) return "(no image in this answer)";
    File.WriteAllBytes(file, Convert.FromBase64String(base64Png));
    return Path.GetFullPath(file);
}

static string Safe(string text) => string.Concat(text.Select(c => char.IsLetterOrDigit(c) || c is '-' or '_' ? c : '_'));
