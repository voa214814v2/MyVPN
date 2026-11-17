package com.narezany.myvpn;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Каталог VLESS-серверов. Умеет подтягивать публичные подписки,
 * хранить последний удачно загруженный список и читать запасной список
 * из assets — чтобы меню выбора всегда было не пустым.
 */
final class ServerCatalog {

    interface Callback {
        void onDone(String text, String error);
    }

    private static final String[] SOURCES = {
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt"
    };

    private static final String PREFS = "vpn";
    private static final String KEY_LIST = "vless_catalog";
    private static final String KEY_TIME = "vless_catalog_time";
    private static final String ASSET = "vless_default.txt";
    private static final int MAX_SERVERS = 120;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private ServerCatalog() {
    }

    /** Ссылки из последней загрузки (или из запасного файла). */
    static List<String> servers(final Context context) {
        final String saved = prefs(context).getString(KEY_LIST, "");
        final String text = saved.isEmpty() ? fallback(context) : saved;
        return lines(text);
    }

    static String raw(final Context context) {
        final String saved = prefs(context).getString(KEY_LIST, "");
        return saved.isEmpty() ? fallback(context) : saved;
    }

    static long updatedAt(final Context context) {
        return prefs(context).getLong(KEY_TIME, 0L);
    }

    static void save(final Context context, final String text) {
        prefs(context).edit()
                .putString(KEY_LIST, text)
                .putLong(KEY_TIME, System.currentTimeMillis())
                .apply();
    }

    /** Ставит задачу обновления списка: сеть — вне главного потока. */
    static void fetch(final Context context, final Callback callback) {
        IO.execute(() -> {
            final StringBuilder all = new StringBuilder();
            String firstError = null;
            for (final String source : SOURCES) {
                try {
                    all.append(download(source)).append('\n');
                } catch (final Exception e) {
                    if (firstError == null) firstError = describe(e);
                }
            }
            final List<String> found = lines(all.toString());
            if (found.isEmpty()) {
                callback.onDone(null, firstError == null ? "список серверов пуст" : firstError);
                return;
            }
            final StringBuilder text = new StringBuilder();
            for (final String line : found) text.append(line).append('\n');
            save(context, text.toString());
            callback.onDone(text.toString(), null);
        });
    }

    /** Только строки vless://, без дублей. */
    static List<String> lines(final String text) {
        final Set<String> unique = new LinkedHashSet<>();
        if (text == null) return new ArrayList<>();
        final BufferedReader reader = new BufferedReader(new StringReader(text));
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                final String trimmed = line.trim();
                // Некоторые подписки приходят base64 — Xray их не поймёт, пропускаем.
                if (trimmed.startsWith("vless://") && !unique.contains(trimmed)) {
                    unique.add(trimmed);
                    if (unique.size() >= MAX_SERVERS) break;
                }
            }
        } catch (final IOException ignored) {
        }
        return new ArrayList<>(unique);
    }

    /** Человекочитаемое имя из фрагмента ссылки. */
    static String nameOf(final String link) {
        try {
            final int hash = link.lastIndexOf('#');
            if (hash < 0) return hostOf(link);
            final String fragment = java.net.URLDecoder.decode(
                    link.substring(hash + 1), StandardCharsets.UTF_8.name()).replace('+', ' ');
            String name = fragment.split("\\|")[0].split("—")[0].trim();
            if (name.isEmpty()) name = fragment.trim();
            return name.length() > 26 ? name.substring(0, 26) + "…" : name;
        } catch (final Exception e) {
            return hostOf(link);
        }
    }

    /** server:port для короткой подписи в списке. */
    static String hostOf(final String link) {
        try {
            final int from = link.indexOf('@') + 1;
            final int to = link.indexOf('?', from);
            if (from <= 0) return "сервер";
            final String host = to > from ? link.substring(from, to) : link.substring(from);
            return host.length() > 30 ? host.substring(0, 30) + "…" : host;
        } catch (final Exception e) {
            return "сервер";
        }
    }

    private static String fallback(final Context context) {
        try (InputStream in = context.getAssets().open(ASSET)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (final Exception e) {
            return "";
        }
    }

    private static String download(final String address) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "MyVPN/1.5");
            final int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            final StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line).append('\n');
            }
            return body.toString();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static SharedPreferences prefs(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String describe(final Throwable e) {
        final String message = e.getMessage();
        if (message == null || message.isEmpty()) return e.getClass().getSimpleName();
        return String.format(Locale.US, "%s", message);
    }
}
