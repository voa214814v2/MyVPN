package com.narezany.myvpn;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import libXray.DialerController;
import libXray.LibXray;

/**
 * Режим VLESS: Xray поднимает локальный SOCKS5 и ходит на сервер по ссылке
 * vless://, а VlessVpnService заворачивает в него весь трафик телефона.
 *
 * Если вставлен список серверов (подписка), перед подключением проверяем
 * несколько штук и берём тот, что реально отвечает.
 */
final class VlessTunnel {

    private static final int API_VERSION = 3;
    private static final int PING_BATCH = 5;
    private static final int PING_TRIES = 40;
    private static final int PING_TIMEOUT = 4;
    private static final String PING_URL = "https://www.gstatic.com/generate_204";

    private VlessTunnel() {
    }

    static void start(final Context context, final String text) throws Exception {
        final List<JSONObject> servers = parse(text);
        if (servers.isEmpty()) {
            throw new IllegalStateException(context.getString(R.string.err_no_servers));
        }

        stopCore();

        JSONObject chosen = servers.get(0);
        if (servers.size() > 1) {
            chosen = pick(context, servers);
            if (chosen == null) {
                throw new IllegalStateException(context.getString(
                        R.string.err_no_working, Math.min(servers.size(), PING_TRIES)));
            }
        } else {
            Logs.add(context, context.getString(R.string.log_vless_server_one, address(chosen)));
        }

        final int port = freePort();

        final Intent intent = new Intent(context, VlessVpnService.class);
        intent.setAction(VlessVpnService.ACTION_START);
        context.startService(intent);
        if (!VlessVpnService.awaitTun(8000)) {
            context.stopService(intent);
            throw new IllegalStateException(context.getString(R.string.err_no_tun));
        }

        try {
            final VlessVpnService service = VlessVpnService.instance();
            if (service != null) {
                final DialerController controller = fd -> service.protect((int) fd);
                LibXray.registerDialerController(controller);
                // Go-резолвер должен смотреть на внешний DNS, а не на петлю VPN.
                LibXray.setDNS(controller, "1.1.1.1:53");
            }

            invoke("runXray", new JSONObject().put("xrayJson", buildConfig(chosen, port)));

            final JSONObject state = invoke("getXrayState", null).optJSONObject("data");
            if (state == null || !state.optBoolean("running")) {
                throw new IllegalStateException(context.getString(R.string.err_xray_dead));
            }

            if (service != null) service.startHev(port);
        } catch (final Throwable e) {
            stop(context);
            if (e instanceof Exception) throw (Exception) e;
            throw new IllegalStateException(e.toString());
        }
    }

    static void stop(final Context context) {
        stopCore();
        try {
            context.stopService(new Intent(context, VlessVpnService.class));
        } catch (final Exception ignored) {
            // сервис мог не запуститься
        }
    }

    static long[] stats() {
        try {
            final long[] values = org.amnezia.awg.hevtunnel.TProxyService.TProxyGetStats();
            if (values == null || values.length < 4) return new long[]{0L, 0L};
            // [tx_packets, tx_bytes, rx_packets, rx_bytes]: tx — от нас, rx — к нам.
            return new long[]{values[3], values[1]};
        } catch (final Throwable e) {
            return new long[]{0L, 0L};
        }
    }

    /** Сколько серверов в ссылке или подписке — для проверки при сохранении. */
    static int countServers(final String text) throws Exception {
        return parse(text).size();
    }

    /**
     * Проверяет одну ссылку из меню. @return задержка в мс или -1, если сервер не ответил.
     * Вызывать только из фонового потока.
     */
    static long ping(final Context context, final String link) throws Exception {
        final List<JSONObject> servers = parse(link);
        if (servers.isEmpty()) return -1;
        final JSONArray configs = new JSONArray().put(new JSONObject().put("xrayJson",
                new JSONObject().put("outbounds", new JSONArray().put(servers.get(0))).toString()));
        final JSONObject data = invoke("pingBatch", new JSONObject()
                .put("configs", configs)
                .put("timeout", PING_TIMEOUT)
                .put("url", PING_URL)).optJSONObject("data");
        if (data == null) return -1;
        final JSONArray results = data.optJSONArray("results");
        if (results == null || results.length() == 0) return -1;
        final JSONObject result = results.optJSONObject(0);
        if (result == null || !result.optBoolean("success")) return -1;
        return result.optLong("delay", -1);
    }

    // ---------- Xray ----------

    private static synchronized JSONObject invoke(final String method, final JSONObject payload)
            throws Exception {
        final JSONObject request = new JSONObject();
        request.put("apiVersion", API_VERSION);
        request.put("method", method);
        if (payload != null) request.put("payload", payload);

        final String raw;
        try {
            raw = LibXray.invoke(request.toString());
        } catch (final Throwable e) {
            throw new IllegalStateException("ядро Xray не загрузилось: " + e);
        }
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("ядро Xray не ответило");
        }

        final JSONObject response = new JSONObject(raw);
        if (!response.optBoolean("success")) {
            final String error = response.optString("error", "");
            throw new IllegalStateException(error.isEmpty() ? "ошибка ядра Xray" : error);
        }
        return response;
    }

    private static void stopCore() {
        try {
            invoke("stopXray", null);
        } catch (final Exception ignored) {
            // ядро и так не запущено
        }
        try {
            LibXray.resetDNS();
        } catch (final Throwable ignored) {
        }
    }

    private static List<JSONObject> parse(final String text) throws Exception {
        final JSONObject data = invoke("convertShareLinksToXrayJson",
                new JSONObject().put("text", text.trim())).optJSONObject("data");
        if (data == null) {
            throw new IllegalStateException("не удалось разобрать ссылку");
        }
        final JSONArray array = data.optJSONArray("outbounds");
        final List<JSONObject> servers = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                final JSONObject server = array.optJSONObject(i);
                if (server != null) servers.add(server);
            }
        }
        return servers;
    }

    /** Проверяет пачки серверов и возвращает самый быстрый из ответивших. */
    private static JSONObject pick(final Context context, final List<JSONObject> servers)
            throws Exception {
        final List<JSONObject> list = new ArrayList<>(servers);
        Collections.shuffle(list);

        final int limit = Math.min(list.size(), PING_TRIES);
        for (int start = 0; start < limit; start += PING_BATCH) {
            final int end = Math.min(start + PING_BATCH, limit);
            final JSONArray configs = new JSONArray();
            for (int i = start; i < end; i++) {
                final JSONObject one = new JSONObject()
                        .put("outbounds", new JSONArray().put(list.get(i)));
                configs.put(new JSONObject().put("xrayJson", one.toString()));
            }

            final JSONObject data = invoke("pingBatch", new JSONObject()
                    .put("configs", configs)
                    .put("timeout", PING_TIMEOUT)
                    .put("url", PING_URL)).optJSONObject("data");
            if (data == null) continue;

            final JSONArray results = data.optJSONArray("results");
            if (results == null) continue;

            int best = -1;
            long bestDelay = Long.MAX_VALUE;
            for (int i = 0; i < results.length(); i++) {
                final JSONObject result = results.optJSONObject(i);
                if (result == null || !result.optBoolean("success")) continue;
                final long delay = result.optLong("delay", Long.MAX_VALUE);
                if (delay < bestDelay) {
                    bestDelay = delay;
                    best = i;
                }
            }
            if (best >= 0 && start + best < list.size()) {
                final JSONObject chosen = list.get(start + best);
                Logs.add(context, context.getString(
                        R.string.log_vless_server, address(chosen), bestDelay));
                return chosen;
            }
        }
        return null;
    }

    /** "server:port" из outbound-а VLESS — только для лога. */
    private static String address(final JSONObject server) {
        try {
            final JSONObject vnext = server.getJSONObject("settings")
                    .getJSONArray("vnext").getJSONObject(0);
            return vnext.getString("address") + ":" + vnext.getInt("port");
        } catch (final Exception e) {
            return "?";
        }
    }

    private static String buildConfig(final JSONObject server, final int port) throws Exception {
        final JSONObject proxy = new JSONObject(server.toString());
        proxy.put("tag", "proxy");

        final JSONObject inbound = new JSONObject()
                .put("tag", "socks-in")
                .put("listen", "127.0.0.1")
                .put("port", port)
                .put("protocol", "socks")
                .put("settings", new JSONObject().put("auth", "noauth").put("udp", true));

        // Локальную сеть не гоняем через сервер.
        final JSONObject localNetwork = new JSONObject()
                .put("type", "field")
                .put("outboundTag", "direct")
                .put("ip", new JSONArray()
                        .put("127.0.0.0/8")
                        .put("10.0.0.0/8")
                        .put("172.16.0.0/12")
                        .put("192.168.0.0/16")
                        .put("169.254.0.0/16"));

        return new JSONObject()
                .put("log", new JSONObject().put("loglevel", "warning"))
                .put("inbounds", new JSONArray().put(inbound))
                .put("outbounds", new JSONArray()
                        .put(proxy)
                        .put(new JSONObject().put("protocol", "freedom").put("tag", "direct"))
                        .put(new JSONObject().put("protocol", "blackhole").put("tag", "block")))
                .put("routing", new JSONObject()
                        .put("domainStrategy", "AsIs")
                        .put("rules", new JSONArray().put(localNetwork)))
                .toString();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
