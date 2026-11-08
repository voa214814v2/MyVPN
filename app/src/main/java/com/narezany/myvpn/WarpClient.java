package com.narezany.myvpn;

import com.wireguard.crypto.KeyPair;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Регистрация бесплатного аккаунта Cloudflare WARP и сборка обычного WireGuard-конфига.
 * Ключи генерируются на телефоне, наружу уходит только публичный ключ.
 *
 * Важно: у некоторых операторов имя api.cloudflareclient.com режется по SNI
 * (TCP открывается, а TLS-рукопожатие молча висит). Поэтому пробуем несколько
 * хостов Cloudflare с одним и тем же API — регистрация проходит через первый живой.
 */
public final class WarpClient {

    private static final String[] REG_URLS = {
            "https://zero-trust-client.cloudflareclient.com/v0a2158/reg",
            "https://api.cloudflareclient.com/v0a2158/reg"
    };

    private static final String ENDPOINT_HOST = "engage.cloudflareclient.com";
    private static final int ENDPOINT_PORT = 2408;

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;

    private WarpClient() {
    }

    public static String register() throws Exception {
        final KeyPair keyPair = new KeyPair();
        final String publicKey = keyPair.getPublicKey().toBase64();
        final String privateKey = keyPair.getPrivateKey().toBase64();

        final JSONObject body = new JSONObject();
        body.put("key", publicKey);
        body.put("install_id", "");
        body.put("fcm_token", "");
        body.put("tos", "2021-09-14T17:00:00.000+00:00");
        body.put("model", "PC");
        body.put("serial_number", "");
        body.put("locale", "en_US");
        body.put("type", "Android");
        body.put("warp_enabled", true);

        final String response = request(body.toString());
        final JSONObject root = new JSONObject(response);
        final JSONObject config = root.getJSONObject("config");
        final JSONObject addresses = config.getJSONObject("interface").getJSONObject("addresses");
        final String addressV4 = addresses.optString("v4", "");
        final String addressV6 = addresses.optString("v6", "");

        final JSONArray peers = config.getJSONArray("peers");
        if (peers.length() == 0) throw new IOException("WARP не выдал peer");
        final String peerKey = peers.getJSONObject(0).getString("public_key");

        final String resolved = resolveV4(ENDPOINT_HOST);
        final String endpoint = (resolved != null ? resolved : ENDPOINT_HOST) + ":" + ENDPOINT_PORT;

        final StringBuilder conf = new StringBuilder();
        conf.append("[Interface]\n");
        conf.append("PrivateKey = ").append(privateKey).append('\n');
        if (!addressV4.isEmpty()) conf.append("Address = ").append(addressV4).append("/32\n");
        if (!addressV6.isEmpty()) conf.append("Address = ").append(addressV6).append("/128\n");
        conf.append("DNS = 1.1.1.1\n");
        conf.append("MTU = 1280\n");
        conf.append('\n');
        conf.append("[Peer]\n");
        conf.append("PublicKey = ").append(peerKey).append('\n');
        conf.append("AllowedIPs = 0.0.0.0/0\n");
        if (!addressV6.isEmpty()) conf.append("AllowedIPs = ::/0\n");
        conf.append("Endpoint = ").append(endpoint).append('\n');
        return conf.toString();
    }

    /** Пробуем хосты по очереди: первый, который реально ответил, и выигрывает. */
    private static String request(final String payload) throws Exception {
        final StringBuilder problems = new StringBuilder();
        IOException apiError = null;
        for (final String url : REG_URLS) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "okhttp/3.12.1");
                conn.setRequestProperty("CF-Client-Version", "a-6.10-2158");

                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload.getBytes(StandardCharsets.UTF_8));
                }

                final int code = conn.getResponseCode();
                final String response = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());

                if (code / 100 == 2) return response;
                // Это ответ самого API, значит хост доступен — запоминаем и идём дальше.
                apiError = new IOException("WARP API " + code + ": " + response);
                problems.append(url).append(" → HTTP ").append(code).append("; ");
            } catch (final IOException e) {
                problems.append(url).append(" → ").append(describe(e)).append("; ");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        if (apiError != null) throw apiError;
        throw new IOException(
                "Не удалось связаться с Cloudflare: похоже, оператор блокирует WARP. "
                        + "Попробуй Wi-Fi или другую сеть. (" + problems + ")");
    }

    /** Резолвим endpoint через DoH, чтобы не зависеть от системного DNS. */
    private static String resolveV4(final String host) {
        final String[] doh = {
                "https://cloudflare-dns.com/dns-query?name=" + host + "&type=A",
                "https://dns.google/resolve?name=" + host + "&type=A"
        };
        for (final String url : doh) {
            try {
                final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("accept", "application/dns-json");
                conn.setRequestProperty("User-Agent", "myvpn/1.0");
                final JSONObject root = new JSONObject(readAll(conn.getInputStream()));
                final JSONArray answers = root.optJSONArray("Answer");
                if (answers != null) {
                    for (int i = 0; i < answers.length(); i++) {
                        final JSONObject answer = answers.optJSONObject(i);
                        if (answer != null && answer.optInt("type") == 1) {
                            final String ip = answer.optString("data", "");
                            if (!ip.isEmpty()) return ip;
                        }
                    }
                }
            } catch (final Exception ignored) {
                // пробуем следующий DoH
            }
        }
        try {
            for (final InetAddress address : InetAddress.getAllByName(host)) {
                if (address instanceof Inet4Address) return address.getHostAddress();
            }
        } catch (final Exception ignored) {
            // не смогли — вернём null
        }
        return null;
    }

    private static String describe(final Exception e) {
        final String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static String readAll(final InputStream stream) throws IOException {
        if (stream == null) return "";
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
