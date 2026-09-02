package com.shizuku.rwmiao.module.proxy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ProxyPool {
    public static final String DEFAULT_COUNTRY = "CN";
    public static final int DEFAULT_MAX_LATENCY_MS = 1000;
    public static final String DEFAULT_ANONYMITY = "all";
    public static final int MAX_RESULTS = 200;
    public static final String PROVIDER_NAME = "HProxy";

    private static final String API =
            "https://hproxy.com/api/proxy-list?format=json&protocol=socks5";
    private static final int MIN_UPTIME_PERCENT = 50;

    private ProxyPool() {
    }

    public static String buildApiUrl(String country, int maxLatencyMs, String anonymity) {
        String normalizedCountry = normalizeFilter(country, DEFAULT_COUNTRY);
        int maxLatency = maxLatencyMs <= 0 ? DEFAULT_MAX_LATENCY_MS
                : Math.min(maxLatencyMs, 30_000);
        StringBuilder url = new StringBuilder(API);
        if (!"ALL".equalsIgnoreCase(normalizedCountry)) {
            url.append("&country=").append(normalizedCountry.toUpperCase(Locale.US));
        }
        url.append("&max_latency_ms=").append(maxLatency)
                .append("&min_uptime_pct=").append(MIN_UPTIME_PERCENT)
                .append("&sort=uptime")
                .append("&limit=").append(MAX_RESULTS);
        if (anonymity != null && !anonymity.isEmpty()
                && !"all".equalsIgnoreCase(anonymity)) {
            url.append("&anonymity=").append(normalizeFilter(anonymity, DEFAULT_ANONYMITY));
        }
        return url.toString();
    }

    public static List<Endpoint> fetch(
            String country,
            int maxLatencyMs,
            String anonymity
    ) throws IOException {
        String normalizedCountry = normalizeFilter(country, DEFAULT_COUNTRY);
        try {
            return parseMetadata(
                    fetchText(buildApiUrl(country, maxLatencyMs, anonymity)),
                    normalizedCountry,
                    maxLatencyMs,
                    anonymity
            );
        } catch (Throwable ignored) {
        }
        String text = fetchText(buildApiUrl(country, maxLatencyMs, anonymity)
                .replace("format=json", "format=txt"));
        List<Endpoint> fallback = parse(text);
        for (int i = 0; i < fallback.size(); i++) {
            Endpoint item = fallback.get(i);
            fallback.set(i, item.withMetadata(
                    "ALL".equalsIgnoreCase(normalizedCountry) ? "" : normalizedCountry,
                    "",
                    -1,
                    "unknown"));
        }
        return fallback;
    }

    private static String fetchText(String address) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(address);
            connection = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(8_000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json,text/plain");
            connection.setRequestProperty("User-Agent", "RWmiao/ProxyPool");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("Proxy list HTTP " + code);
            try (InputStream stream = connection.getInputStream()) {
                return readBody(stream);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static List<Endpoint> parseMetadata(
            String body,
            String country,
            int maxLatencyMs,
            String anonymity
    ) throws Exception {
        Map<String, Endpoint> unique = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) return new ArrayList<>();
        JSONArray records = new JSONArray(body);
        for (int i = 0; i < records.length() && unique.size() < MAX_RESULTS; i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null) continue;
            if (!supportsSocks5(record)) continue;
            String status = text(record, "status");
            if (!status.isEmpty() && !"alive".equalsIgnoreCase(status)) continue;

            String host = text(record, "ip");
            int port = integer(record, "port", -1);
            if (host.isEmpty() || port <= 0 || port > 65_535) continue;

            JSONObject geo = record.optJSONObject("geolocation");
            String countryCode = firstText(record, "country_code", "countryCode");
            String countryName = text(record, "country");
            if (geo != null) {
                if (countryCode.isEmpty()) {
                    countryCode = firstText(geo, "country_code", "countryCode");
                }
                if (countryName.isEmpty()) countryName = text(geo, "country");
            }
            if (countryCode.isEmpty() && countryName.length() == 2) {
                countryCode = countryName;
                countryName = "";
            }
            if (!"ALL".equalsIgnoreCase(country)
                    && !country.equalsIgnoreCase(countryCode)
                    && !country.equalsIgnoreCase(countryName)) {
                continue;
            }

            double latency = number(record, "latency_ms", number(record, "latency", -1.0));
            if (latency < 0) continue;
            if (maxLatencyMs > 0 && latency > maxLatencyMs) continue;
            String anonymityValue = text(record, "anonymity");
            if (anonymityValue.isEmpty()) anonymityValue = "unknown";
            if (!"all".equalsIgnoreCase(anonymity)
                    && !anonymity.equalsIgnoreCase(anonymityValue)) {
                continue;
            }
            Endpoint endpoint = new Endpoint(
                    host,
                    port,
                    countryCode.toUpperCase(Locale.US),
                    countryName,
                    (int) Math.round(latency),
                    anonymityValue
            );
            unique.put(endpoint.key(), endpoint);
        }
        return new ArrayList<>(unique.values());
    }

    private static boolean supportsSocks5(JSONObject record) {
        String protocol = text(record, "protocol");
        if ("socks5".equalsIgnoreCase(protocol)) return true;
        Object protocols = record.opt("protocols");
        if (protocols instanceof JSONArray) {
            JSONArray values = (JSONArray) protocols;
            for (int i = 0; i < values.length(); i++) {
                if ("socks5".equalsIgnoreCase(values.optString(i, ""))) return true;
            }
            return false;
        }
        String value = protocols == null || protocols == JSONObject.NULL
                ? "" : String.valueOf(protocols);
        for (String item : value.split("[,\\s]+")) {
            if ("socks5".equalsIgnoreCase(item)) return true;
        }
        return false;
    }

    private static String firstText(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = text(object, key);
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static String text(JSONObject object, String key) {
        if (object == null) return "";
        Object value = object.opt(key);
        return value == null || value == JSONObject.NULL ? "" : String.valueOf(value).trim();
    }

    private static int integer(JSONObject object, String key, int fallback) {
        Object value = object == null ? null : object.opt(key);
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return value == null || value == JSONObject.NULL
                    ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static double number(JSONObject object, String key, double fallback) {
        Object value = object == null ? null : object.opt(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return value == null || value == JSONObject.NULL
                    ? fallback : Double.parseDouble(String.valueOf(value).trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static List<Endpoint> parse(String body) {
        Map<String, Endpoint> unique = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) return new ArrayList<>();
        String[] lines = body.replace('\r', '\n').split("\\n");
        for (String line : lines) {
            Endpoint endpoint = Endpoint.parse(line);
            if (endpoint != null) unique.put(endpoint.key(), endpoint);
            if (unique.size() >= MAX_RESULTS) break;
        }
        return new ArrayList<>(unique.values());
    }

    public static String serialize(List<Endpoint> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (Endpoint endpoint : endpoints) {
            if (endpoint == null) continue;
            if (count++ > 0) result.append('\n');
            result.append(endpoint.storageLine());
            if (count >= MAX_RESULTS) break;
        }
        return result.toString();
    }

    public static List<Endpoint> deserialize(String serialized) {
        return parse(serialized == null ? "" : serialized);
    }

    private static String readBody(InputStream stream) throws IOException {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            int size = 0;
            while ((line = reader.readLine()) != null && size < 1_000_000) {
                if (body.length() > 0) body.append('\n');
                body.append(line);
                size += line.length() + 1;
            }
        }
        return body.toString();
    }

    private static String normalizeFilter(String value, String fallback) {
        if (value == null || value.isEmpty()) return fallback;
        String normalized = value.trim();
        if (normalized.isEmpty()) return fallback;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return fallback;
        }
        return normalized;
    }

    public static final class Endpoint {
        public final String host;
        public final int port;
        public final String countryCode;
        public final String countryName;
        public final int latencyMs;
        public final String anonymity;

        public Endpoint(String host, int port) {
            this(host, port, "", "", -1, "unknown");
        }

        public Endpoint(
                String host,
                int port,
                String countryCode,
                String countryName,
                int latencyMs,
                String anonymity
        ) {
            this.host = host;
            this.port = port;
            this.countryCode = countryCode == null ? "" : countryCode;
            this.countryName = countryName == null ? "" : countryName;
            this.latencyMs = latencyMs;
            this.anonymity = anonymity == null || anonymity.isEmpty() ? "unknown" : anonymity;
        }

        public String key() {
            return host + ':' + port;
        }

        public String display() {
            return host.indexOf(':') >= 0 ? "[" + host + "]:" + port : key();
        }

        public String storageLine() {
            return display() + "|" + clean(countryCode) + "|" + clean(countryName)
                    + "|" + latencyMs + "|" + clean(anonymity);
        }

        public Endpoint withMetadata(
                String countryCode,
                String countryName,
                int latencyMs,
                String anonymity
        ) {
            return new Endpoint(host, port, countryCode, countryName, latencyMs, anonymity);
        }

        public static Endpoint parse(String raw) {
            if (raw == null) return null;
            String value = raw.trim();
            if (value.isEmpty() || value.startsWith("#")) return null;
            String[] metadata = value.split("\\|", -1);
            value = metadata[0].trim();
            int comment = value.indexOf('#');
            if (comment >= 0) value = value.substring(0, comment).trim();
            String lower = value.toLowerCase(Locale.US);
            if (lower.startsWith("socks5://")) value = value.substring(9);
            else if (lower.startsWith("socks://")) value = value.substring(8);
            else if (lower.startsWith("http://")) value = value.substring(7);
            else if (lower.startsWith("https://")) value = value.substring(8);
            value = value.trim();

            String host;
            String portText;
            if (value.startsWith("[")) {
                int closing = value.indexOf(']');
                if (closing < 0 || closing + 1 >= value.length()
                        || value.charAt(closing + 1) != ':') return null;
                host = value.substring(1, closing);
                portText = value.substring(closing + 2);
            } else {
                int separator = value.lastIndexOf(':');
                if (separator <= 0 || separator == value.length() - 1) return null;
                host = value.substring(0, separator);
                portText = value.substring(separator + 1);
                if (host.indexOf(':') >= 0) return null;
            }
            if (!isValidHost(host)) return null;
            final int port;
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException ignored) {
                return null;
            }
            if (port <= 0 || port > 65_535) return null;
            String countryCode = metadata.length > 1 ? metadata[1] : "";
            String countryName = metadata.length > 2 ? metadata[2] : "";
            int latency = -1;
            if (metadata.length > 3) {
                try {
                    latency = Integer.parseInt(metadata[3]);
                } catch (NumberFormatException ignored) {
                }
            }
            String anonymity = metadata.length > 4 ? metadata[4] : "unknown";
            return new Endpoint(host, port, countryCode, countryName, latency, anonymity);
        }

        private static String clean(String value) {
            return value == null ? "" : value.replace("|", " ").replace("\n", " ");
        }

        private static boolean isValidHost(String host) {
            if (host == null || host.isEmpty() || host.length() > 255) return false;
            for (int i = 0; i < host.length(); i++) {
                char c = host.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == ':')) {
                    return false;
                }
            }
            return true;
        }
    }
}
