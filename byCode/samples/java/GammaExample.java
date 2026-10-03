import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gamma Integration API — Java sample. Java 11 or newer, no dependencies: run it as is with
 *
 *   java GammaExample.java me
 *   java GammaExample.java bill   <orderNumber> <total>     declare a paid order, wait for the reward to be claimed
 *   java GammaExample.java status <billId>                  look a bill up
 *   java GammaExample.java credit <orderNumber> <total>     settle an order with store credits (60-second QR)
 *
 * The access token comes from the GAMMA_INTEGRATION_TOKEN environment variable.
 *
 * In your project, copy {@link GammaClient} and {@link GammaApiException}, and replace the small
 * {@link Json} helper at the bottom with the JSON library you already use (Jackson, Gson, …).
 */
public class GammaExample {

    public static void main(String[] args) throws Exception {
        String token = System.getenv().getOrDefault("GAMMA_INTEGRATION_TOKEN", "");
        String baseUrl = System.getenv().getOrDefault("GAMMA_INTEGRATION_URL", GammaClient.DEFAULT_BASE_URL);
        if (args.length == 0 || token.isEmpty()) {
            System.err.println("Set GAMMA_INTEGRATION_TOKEN, then run: me | bill <orderNumber> <total> | status <billId> | credit <orderNumber> <total>");
            System.exit(2);
        }
        GammaClient gamma = new GammaClient(token, baseUrl);
        try {
            switch (args[0]) {
                case "me":
                    showConnection(gamma);
                    break;
                case "bill":
                    requireArgs(args, 3);
                    rewardForPaidOrder(gamma, args[1], amount(args[2]));
                    break;
                case "status":
                    requireArgs(args, 2);
                    Map<String, Object> bill = gamma.getBill(args[1]);
                    System.out.println(bill.get("reference") + ": " + bill.get("status") + (bill.get("claimedOn") != null ? " on " + bill.get("claimedOn") : ""));
                    break;
                case "credit":
                    requireArgs(args, 3);
                    settleWithStoreCredits(gamma, args[1], amount(args[2]));
                    break;
                default:
                    System.err.println("Unknown command.");
                    System.exit(2);
            }
        } catch (GammaApiException e) {
            System.err.println(e.getMessage());
            if (e.fields != null) System.err.println("Fields: " + e.fields);
            if (e.isTokenProblem()) System.err.println("The business owner must create a new token in Gamma Business → Integrations.");
            System.exit(1);
        }
    }

    static void showConnection(GammaClient gamma) throws IOException, InterruptedException {
        Map<String, Object> me = gamma.getConnection();
        @SuppressWarnings("unchecked")
        Map<String, Object> token = (Map<String, Object>) me.get("token");
        long daysLeft = ((Number) token.get("daysLeft")).longValue();
        System.out.println("Business:  " + me.get("businessName") + " (" + me.get("businessId") + ")");
        System.out.println("Currency:  " + me.get("currencyCode"));
        System.out.println("Rewards:   " + (Boolean.TRUE.equals(me.get("canClaim")) ? "customers can claim rewards" : "no reward programme is running"));
        System.out.println("Token:     " + token.get("displayPrefix") + "…" + token.get("displaySuffix") + ", " + daysLeft + " day(s) left");
        if (daysLeft < 14) System.out.println("           Ask the business owner for a new token soon.");
    }

    /** Flow 1: the order is paid. Declare it, show the QR, and wait until the customer claims the reward. */
    static void rewardForPaidOrder(GammaClient gamma, String orderNumber, double total) throws Exception {
        String currency = (String) gamma.getConnection().get("currencyCode");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("reference", orderNumber);
        request.put("total", total);
        request.put("currencyCode", currency);
        request.put("platform", "java-sample");
        request.put("pluginVersion", "1.0");
        Map<String, Object> bill = gamma.createBill(request);

        String file = saveQr((String) bill.get("qrPngBase64"), "qr-bill-" + safe(orderNumber) + ".png");
        System.out.println("Bill " + bill.get("billId") + " for " + bill.get("total") + " " + bill.get("currencyCode") + " is " + bill.get("status") + ".");
        System.out.println("Show this QR to the customer: " + file);
        System.out.println("Or give them the link: " + bill.get("link"));
        System.out.println("Waiting for the customer to claim the reward (Ctrl+C to stop)…");

        // In a real shop your page asks your server, and your server makes this call.
        String billId = (String) bill.get("billId");
        long giveUpAt = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
        while ("Waiting".equals(bill.get("status")) && System.currentTimeMillis() < giveUpAt) {
            Thread.sleep(5_000);
            bill = withRetry(() -> gamma.getBill(billId));
        }

        System.out.println("Claimed".equals(bill.get("status"))
                ? "Reward claimed on " + bill.get("claimedOn") + "."
                : "Not claimed yet. The bill stays valid; the customer can still use the link later.");
    }

    /** Flow 2: the customer settles the whole order with store credits. The QR is valid for 60 seconds. */
    static void settleWithStoreCredits(GammaClient gamma, String orderNumber, double total) throws Exception {
        String currency = (String) gamma.getConnection().get("currencyCode");
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("reference", orderNumber);
        order.put("total", total);
        order.put("currencyCode", currency);
        Map<String, Object> request = gamma.startCredit(order);

        String file = saveQr((String) request.get("qrPngBase64"), "qr-credit-" + safe(orderNumber) + ".png");
        System.out.println("Ask the customer to scan within " + request.get("secondsLeft") + " s: " + file);
        System.out.println("On a phone, give them the link instead: " + request.get("link"));

        // Keep asking until the answer is final. "Expired" comes a few seconds after the countdown.
        String creditRequest = (String) request.get("creditRequest");
        while ("Waiting".equals(request.get("status"))) {
            Thread.sleep(5_000);
            request = withRetry(() -> gamma.checkCredit(creditRequest));
            if ("Waiting".equals(request.get("status"))) System.out.println("  waiting… " + request.get("secondsLeft") + " s left");
        }

        System.out.println("Paid".equals(request.get("status"))
                ? "Settled with store credits on " + request.get("paidOn") + ". Complete the order. (Do not declare a bill for it.)"
                : "The code expired. Offer the customer to try again: start a new request.");
    }

    interface Call {
        Map<String, Object> run() throws IOException, InterruptedException;
    }

    /** Too many requests or a problem on Gamma's side: wait and try again, a few times. */
    static Map<String, Object> withRetry(Call call) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.run();
            } catch (GammaApiException e) {
                if (!e.isRetryable() || attempt >= 4) throw e;
                Thread.sleep(e.status == 429 ? 60_000 : 5_000L * attempt);
            }
        }
    }

    static String saveQr(String base64Png, String file) throws IOException {
        if (base64Png == null || base64Png.isEmpty()) return "(no image in this answer)";
        Path path = Path.of(file);
        Files.write(path, Base64.getDecoder().decode(base64Png));
        return path.toAbsolutePath().toString();
    }

    static String safe(String text) {
        return text.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    static double amount(String text) {
        double value = Double.parseDouble(text);
        if (value <= 0) throw new IllegalArgumentException("Not an amount: " + text);
        return value;
    }

    static void requireArgs(String[] args, int count) {
        if (args.length < count) {
            System.err.println("Missing arguments.");
            System.exit(2);
        }
    }
}

/**
 * A small client for the Gamma Integration API. Create one per business (one access token) and
 * reuse it. Use it on your server only: the access token must never reach a browser or an app.
 */
class GammaClient {
    static final String DEFAULT_BASE_URL = "https://integration.gamma-wallet.com";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String token;
    private final String baseUrl;

    GammaClient(String accessToken, String baseUrl) {
        if (accessToken == null || !accessToken.startsWith("GWINT_")) {
            throw new IllegalArgumentException("The access token starts with GWINT_. Create one in Gamma Business → Integrations.");
        }
        this.token = accessToken;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** Which business the token belongs to, its currency, and how long the token has left. */
    Map<String, Object> getConnection() throws IOException, InterruptedException {
        return send("GET", "/api/Connection/Me", null);
    }

    /** Declares a paid order. Safe to repeat with the same reference. */
    Map<String, Object> createBill(Map<String, Object> bill) throws IOException, InterruptedException {
        return send("POST", "/api/Bill/Create", bill);
    }

    Map<String, Object> getBill(String billId) throws IOException, InterruptedException {
        return send("GET", "/api/Bill/Get/" + encode(billId), null);
    }

    Map<String, Object> getBillByReference(String reference) throws IOException, InterruptedException {
        return send("GET", "/api/Bill/GetByReference?reference=" + encode(reference), null);
    }

    /** Starts a store-credit request for the whole order. The QR is valid for 60 seconds. */
    Map<String, Object> startCredit(Map<String, Object> order) throws IOException, InterruptedException {
        return send("POST", "/api/Credit/Start", order);
    }

    Map<String, Object> checkCredit(String creditRequest) throws IOException, InterruptedException {
        return send("POST", "/api/Credit/Check", Map.of("creditRequest", creditRequest));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(String method, String path, Map<String, Object> body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .header("User-Agent", "gamma-integration-sample-java/1.0");
        if (body != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, Object> envelope = null;
        try {
            Object parsed = Json.read(response.body());
            if (parsed instanceof Map) envelope = (Map<String, Object>) parsed;
        } catch (IllegalArgumentException notOurJson) {
            // A proxy error page, for example: reported below by status code.
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300 && envelope != null && envelope.get("result") instanceof Map) {
            return (Map<String, Object>) envelope.get("result");
        }
        Map<String, Object> error = envelope != null && envelope.get("error") instanceof Map ? (Map<String, Object>) envelope.get("error") : Map.of();
        throw new GammaApiException(status, (String) error.get("identifier"), (String) error.get("message"), error.get("onObject"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

/** An answer other than success. {@code identifier} is the stable number to branch on (see docs/API-REFERENCE.md). */
class GammaApiException extends IOException {
    final int status;
    final String identifier;
    final String errorName;
    /** For a 400: each invalid field and what is wrong with it. */
    final Object fields;

    GammaApiException(int status, String identifier, String errorName, Object fields) {
        super("Gamma answered HTTP " + status + ": " + (errorName != null ? errorName : "no details") + (identifier != null ? " (" + identifier + ")" : ""));
        this.status = status;
        this.identifier = identifier;
        this.errorName = errorName;
        this.fields = fields;
    }

    /** Worth trying again later: too many requests, or a problem on Gamma's side. */
    boolean isRetryable() {
        return status == 429 || status >= 500;
    }

    /** The token no longer works: the business owner must create a new one. */
    boolean isTokenProblem() {
        return status == 401;
    }
}

/**
 * Just enough JSON for this sample, so it runs without dependencies. Objects become Maps, arrays
 * Lists, numbers Double or Long. Replace it with Jackson or Gson in a real project.
 */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object read(String text) {
        Json p = new Json(text == null ? "" : text);
        p.space();
        Object value = p.value();
        p.space();
        if (p.i != p.s.length()) throw new IllegalArgumentException("Unexpected text after the JSON value");
        return value;
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeTo(out, value);
        return out.toString();
    }

    private static void writeTo(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            out.append('"');
            for (char c : ((String) value).toCharArray()) {
                if (c == '"' || c == '\\') out.append('\\').append(c);
                else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                else out.append(c);
            }
            out.append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeTo(out, String.valueOf(e.getKey()));
                out.append(':');
                writeTo(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) out.append(',');
                first = false;
                writeTo(out, item);
            }
            out.append(']');
        } else {
            writeTo(out, value.toString());
        }
    }

    private Object value() {
        if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        i++;
        space();
        if (peek('}')) { i++; return map; }
        while (true) {
            space();
            String key = string();
            space();
            expect(':');
            space();
            map.put(key, value());
            space();
            if (peek(',')) { i++; continue; }
            expect('}');
            return map;
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++;
        space();
        if (peek(']')) { i++; return list; }
        while (true) {
            space();
            list.add(value());
            space();
            if (peek(',')) { i++; continue; }
            expect(']');
            return list;
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': out.append('\n'); break;
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'u': out.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: out.append(e);
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    private Number number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String text = s.substring(start, i);
        if (text.isEmpty()) throw new IllegalArgumentException("Not JSON at position " + start);
        return text.contains(".") || text.contains("e") || text.contains("E") ? (Number) Double.valueOf(text) : (Number) Long.valueOf(text);
    }

    private void space() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) throw new IllegalArgumentException("Expected '" + c + "' at position " + i);
        i++;
    }
}
