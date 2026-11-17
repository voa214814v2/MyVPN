package com.narezany.myvpn;

import android.content.Context;

import org.amnezia.awg.backend.GoBackend;
import org.amnezia.awg.backend.NoopTunnelActionHandler;
import org.amnezia.awg.backend.Statistics;
import org.amnezia.awg.backend.Tunnel;
import org.amnezia.awg.config.Config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Единственный туннель приложения: держит GoBackend и текущее состояние.
 * Все вызовы к backend блокирующие, поэтому выполняются в отдельном потоке.
 *
 * Два режима:
 *  - AmneziaWG/WireGuard (WARP и свой конфиг) — через GoBackend;
 *  - VLESS/Reality — через Xray и собственный VpnService.
 */
public final class VpnManager implements Tunnel {

    public interface Callback {
        void onDone(boolean ok, String error);
    }

    private static VpnManager instance;

    private final Context appContext;
    private final GoBackend backend;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile Tunnel.State state = Tunnel.State.DOWN;
    private volatile boolean vlessUp;

    private VpnManager(final Context appContext) {
        this.appContext = appContext;
        backend = new GoBackend(appContext, new NoopTunnelActionHandler());
    }

    public static synchronized VpnManager get(final Context context) {
        if (instance == null) {
            instance = new VpnManager(context.getApplicationContext());
        }
        return instance;
    }

    @Override
    public String getName() {
        return "myvpn";
    }

    @Override
    public void onStateChange(final Tunnel.State newState) {
        state = newState;
    }

    /** false — резолвить имена в IPv4 (обычный случай для мобильных сетей). */
    @Override
    public Boolean isIpv4ResolutionPreferred() {
        return Boolean.FALSE;
    }

    /** Сеть считаем не лимитированной, чтобы Android не резал фоновый трафик. */
    @Override
    public Boolean isMetered() {
        return Boolean.FALSE;
    }

    public boolean isUp() {
        return vlessUp || state == Tunnel.State.UP;
    }

    public void connect(final Config config, final Callback callback) {
        io.execute(() -> {
            try {
                backend.setState(this, Tunnel.State.UP, config);
                callback.onDone(true, null);
            } catch (final Throwable e) {
                callback.onDone(false, describe(e));
            }
        });
    }

    /** Подключение по ссылке vless:// или списку таких ссылок. */
    public void connectVless(final String text, final Callback callback) {
        io.execute(() -> {
            try {
                VlessTunnel.start(appContext, text);
                vlessUp = true;
                callback.onDone(true, null);
            } catch (final Throwable e) {
                vlessUp = false;
                callback.onDone(false, describe(e));
            }
        });
    }

    public void disconnect(final Callback callback) {
        io.execute(() -> {
            try {
                if (vlessUp) {
                    VlessTunnel.stop(appContext);
                    vlessUp = false;
                    if (callback != null) callback.onDone(true, null);
                    return;
                }
                backend.setState(this, Tunnel.State.DOWN, null);
                if (callback != null) callback.onDone(true, null);
            } catch (final Throwable e) {
                if (callback != null) callback.onDone(false, describe(e));
            }
        });
    }

    /** @return {получено байт, отправлено байт} */
    public long[] stats() {
        if (vlessUp) return VlessTunnel.stats();
        try {
            final Statistics statistics = backend.getStatistics(this);
            return new long[]{statistics.totalRx(), statistics.totalTx()};
        } catch (final Throwable e) {
            return new long[]{0L, 0L};
        }
    }

    private static String describe(final Throwable e) {
        final String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
