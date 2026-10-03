// Gamma Integration API — Node.js sample.
//
//   node example.mjs me
//   node example.mjs bill   <orderNumber> <total>     declare a paid order, wait for the reward to be claimed
//   node example.mjs status <billId>                  look a bill up
//   node example.mjs credit <orderNumber> <total>     settle an order with store credits (60-second QR)
//
// The access token comes from the GAMMA_INTEGRATION_TOKEN environment variable.
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';
import { GammaClient, GammaApiError, DEFAULT_BASE_URL } from './gamma-client.mjs';

const token = process.env.GAMMA_INTEGRATION_TOKEN ?? '';
const [command, arg1, arg2] = process.argv.slice(2);
if (!command || !token) {
    console.error('Set GAMMA_INTEGRATION_TOKEN, then run: me | bill <orderNumber> <total> | status <billId> | credit <orderNumber> <total>');
    process.exit(2);
}
const gamma = new GammaClient(token, process.env.GAMMA_INTEGRATION_URL ?? DEFAULT_BASE_URL);

async function showConnection() {
    const me = await gamma.getConnection();
    console.log(`Business:  ${me.businessName} (${me.businessId})`);
    console.log(`Currency:  ${me.currencyCode}`);
    console.log(`Rewards:   ${me.canClaim ? 'customers can claim rewards' : 'no reward programme is running'}`);
    console.log(`Token:     ${me.token.displayPrefix}…${me.token.displaySuffix}, ${me.token.daysLeft} day(s) left`);
    if (me.token.daysLeft < 14) console.log('           Ask the business owner for a new token soon.');
}

// Flow 1: the order is paid. Declare it, show the QR, and wait until the customer claims the reward.
async function rewardForPaidOrder(orderNumber, total) {
    const me = await gamma.getConnection();
    let bill = await gamma.createBill({ reference: orderNumber, total, currencyCode: me.currencyCode, platform: 'node-sample', pluginVersion: '1.0' });

    const file = saveQr(bill.qrPngBase64, `qr-bill-${safe(orderNumber)}.png`);
    console.log(`Bill ${bill.billId} for ${bill.total} ${bill.currencyCode} is ${bill.status}.`);
    console.log(`Show this QR to the customer: ${file}`);
    console.log(`Or give them the link: ${bill.link}`);
    console.log('Waiting for the customer to claim the reward (Ctrl+C to stop)…');

    // In a real shop your page asks your server, and your server makes this call.
    const giveUpAt = Date.now() + 10 * 60_000;
    while (bill.status === 'Waiting' && Date.now() < giveUpAt) {
        await sleep(5_000);
        bill = await withRetry(() => gamma.getBill(bill.billId));
    }

    console.log(bill.status === 'Claimed'
        ? `Reward claimed on ${bill.claimedOn}.`
        : 'Not claimed yet. The bill stays valid; the customer can still use the link later.');
}

// Flow 2: the customer settles the whole order with store credits. The QR is valid for 60 seconds.
async function settleWithStoreCredits(orderNumber, total) {
    const me = await gamma.getConnection();
    let request = await gamma.startCredit({ reference: orderNumber, total, currencyCode: me.currencyCode });

    const file = saveQr(request.qrPngBase64, `qr-credit-${safe(orderNumber)}.png`);
    console.log(`Ask the customer to scan within ${request.secondsLeft} s: ${file}`);
    console.log(`On a phone, give them the link instead: ${request.link}`);

    // Keep asking until the answer is final. "Expired" comes a few seconds after the countdown.
    const creditRequest = request.creditRequest;
    while (request.status === 'Waiting') {
        await sleep(5_000);
        request = await withRetry(() => gamma.checkCredit(creditRequest));
        if (request.status === 'Waiting') console.log(`  waiting… ${request.secondsLeft} s left`);
    }

    console.log(request.status === 'Paid'
        ? `Settled with store credits on ${request.paidOn}. Complete the order. (Do not declare a bill for it.)`
        : 'The code expired. Offer the customer to try again: start a new request.');
}

// Too many requests or a problem on Gamma's side: wait and try again, a few times.
async function withRetry(call) {
    for (let attempt = 1; ; attempt++) {
        try {
            return await call();
        } catch (e) {
            if (!(e instanceof GammaApiError) || !e.isRetryable || attempt >= 4) throw e;
            await sleep(e.status === 429 ? 60_000 : 5_000 * attempt);
        }
    }
}

function saveQr(base64Png, file) {
    if (!base64Png) return '(no image in this answer)';
    writeFileSync(file, Buffer.from(base64Png, 'base64'));
    return resolve(file);
}

const safe = (text) => text.replace(/[^A-Za-z0-9_-]/g, '_');
const amount = (text) => {
    const n = Number(text);
    if (!Number.isFinite(n) || n <= 0) throw new Error(`Not an amount: ${text}`);
    return n;
};

try {
    if (command === 'me') await showConnection();
    else if (command === 'bill' && arg1 && arg2) await rewardForPaidOrder(arg1, amount(arg2));
    else if (command === 'status' && arg1) {
        const bill = await gamma.getBill(arg1);
        console.log(`${bill.reference}: ${bill.status}${bill.claimedOn ? ` on ${bill.claimedOn}` : ''}`);
    } else if (command === 'credit' && arg1 && arg2) await settleWithStoreCredits(arg1, amount(arg2));
    else {
        console.error('Unknown command.');
        process.exit(2);
    }
} catch (e) {
    console.error(e.message);
    if (e instanceof GammaApiError) {
        if (e.fields) console.error('Fields:', JSON.stringify(e.fields));
        if (e.isTokenProblem) console.error('The business owner must create a new token in Gamma Business → Integrations.');
    }
    process.exit(1);
}
