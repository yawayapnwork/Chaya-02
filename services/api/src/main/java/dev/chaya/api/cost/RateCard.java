package dev.chaya.api.cost;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The configured infrastructure rates (chaya.cost.rates). Every figure here is an operator's statement, with its source:
 * a cloud list price, a negotiated rate, or the operator's own estimate of what a local machine costs to run. None of it
 * is a bill.
 *
 * <pre>
 * {"currency": "USD",
 *  "workerClasses": {"gpu-g5-xlarge": {"hourly": 1.006, "kind": "CLOUD_LIST_PRICE", "source": "AWS on-demand, us-east-1, 2026-10"},
 *                    "local-rtx4090": {"hourly": 0.12, "kind": "LOCAL_HARDWARE", "source": "450 W at 0.27/kWh, no amortisation"}},
 *  "storagePerGbMonth": {"rate": 0.023, "source": "S3 Standard, us-east-1, 2026-10"}}
 * </pre>
 *
 * A worker names its class (WORKER_RESOURCE_CLASS) in each stage report; an attempt is priced at its class's hourly rate
 * times its measured wall time, since a provider bills a worker machine for the hours it is held, whether its GPU is
 * busy or not. A class missing from the card is not priced.
 */
public record RateCard(String currency, Map<String, WorkerRate> workerClasses, StorageRate storage, String canonicalJson, String sha256) {

    /** CLOUD_LIST_PRICE: a provider's published price; CLOUD_CONTRACT_RATE: a negotiated or reserved rate;
     * LOCAL_HARDWARE: an operator's estimate for a machine it runs itself (power, amortisation), not a provider's price. */
    public static final Set<String> KINDS = Set.of("CLOUD_LIST_PRICE", "CLOUD_CONTRACT_RATE", "LOCAL_HARDWARE");
    static final Pattern CLASS = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public record WorkerRate(BigDecimal hourly, String kind, String source) {}

    public record StorageRate(BigDecimal perGbMonth, String source) {}

    /** The card in `json`, or null when it is blank. IllegalStateException names what is wrong with an invalid one. */
    public static RateCard parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw invalid("it is not valid JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw invalid("it must be a JSON object");
        }
        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (!Set.of("currency", "workerClasses", "storagePerGbMonth").contains(f)) {
                throw invalid("unknown field " + f);
            }
        }
        String currency = root.path("currency").asText("");
        if (!CURRENCY.matcher(currency).matches()) {
            throw invalid("currency must be an ISO 4217 code such as USD");
        }
        JsonNode classes = root.path("workerClasses");
        if (!classes.isObject() || classes.isEmpty()) {
            throw invalid("workerClasses must name at least one worker class");
        }
        Map<String, WorkerRate> rates = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = classes.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String name = e.getKey();
            JsonNode r = e.getValue();
            if (!CLASS.matcher(name).matches()) {
                throw invalid("worker class '" + name + "' must be 1-100 of A-Z a-z 0-9 . _ : -");
            }
            String kind = r.path("kind").asText("");
            if (!KINDS.contains(kind)) {
                throw invalid("worker class " + name + ": kind must be one of " + new java.util.TreeSet<>(KINDS));
            }
            rates.put(name, new WorkerRate(rate(r.path("hourly"), name + ".hourly"), kind, source(r, name)));
        }
        StorageRate storage = null;
        JsonNode s = root.path("storagePerGbMonth");
        if (!s.isMissingNode() && !s.isNull()) {
            storage = new StorageRate(rate(s.path("rate"), "storagePerGbMonth.rate"), source(s, "storagePerGbMonth"));
        }
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("currency", currency);
        Map<String, Object> cls = new TreeMap<>();
        rates.forEach((k, v) -> cls.put(k, new TreeMap<>(Map.of("hourly", v.hourly(), "kind", v.kind(), "source", v.source()))));
        canonical.put("workerClasses", cls);
        if (storage != null) {
            canonical.put("storagePerGbMonth", new TreeMap<>(Map.of("rate", storage.perGbMonth(), "source", storage.source())));
        }
        String text;
        try {
            text = mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).writeValueAsString(canonical);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return new RateCard(currency, Map.copyOf(rates), storage, text, sha256(text));
    }

    /** The view of this card shown on the dashboard: everything it states, nothing more. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", currency);
        out.put("sha256", sha256);
        Map<String, Object> cls = new TreeMap<>();
        workerClasses.forEach((k, v) -> cls.put(k, Map.of("hourly", v.hourly(), "kind", v.kind(), "source", v.source())));
        out.put("workerClasses", cls);
        out.put("storagePerGbMonth", storage == null ? null : Map.of("rate", storage.perGbMonth(), "source", storage.source()));
        return out;
    }

    private static BigDecimal rate(JsonNode n, String what) {
        if (!n.isNumber()) {
            throw invalid(what + " must be a number");
        }
        BigDecimal v = n.decimalValue();
        if (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(1_000_000)) > 0) {
            throw invalid(what + " must be between 0 and 1000000");
        }
        return v.stripTrailingZeros();
    }

    private static String source(JsonNode n, String what) {
        String s = n.path("source").asText("").trim();
        if (s.isEmpty() || s.length() > 300) {
            throw invalid(what + ": source (where the rate comes from) is required, at most 300 characters");
        }
        return s;
    }

    private static IllegalStateException invalid(String why) {
        return new IllegalStateException("chaya.cost.rates (CHAYA_COST_RATES) is invalid: " + why);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
