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
 * Бэкенд — AmneziaWG: он понимает и обычный WireGuard (в том числе конфиг WARP),
 * и обфусцированный AmneziaWG, поэтому DPI видит не чистый WireGuard.
 */
public final class VpnManager implements Tunnel {

    public interface Callback {
        void onDone(boolean ok, String error);
    }

    private static VpnManager instance;

    private final GoBackend backend;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile Tunnel.State state = Tunnel.State.DOWN;

    private VpnManager(final Context appContext) {
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
        return state == Tunnel.State.UP;
    }

    public void connect(final Config config, final Callback callback) {
        io.execute(() -> {
            try {
                backend.setState(this, Tunnel.State.UP, config);
                callback.onDone(true, null);
            } catch (final Exception e) {
                callback.onDone(false, describe(e));
            }
        });
    }

    public void disconnect(final Callback callback) {
        io.execute(() -> {
            try {
                backend.setState(this, Tunnel.State.DOWN, null);
                if (callback != null) callback.onDone(true, null);
            } catch (final Exception e) {
                if (callback != null) callback.onDone(false, describe(e));
            }
        });
    }

    /** @return {получено байт, отправлено байт} */
    public long[] stats() {
        try {
            final Statistics statistics = backend.getStatistics(this);
            return new long[]{statistics.totalRx(), statistics.totalTx()};
        } catch (final Exception e) {
            return new long[]{0L, 0L};
        }
    }

    private static String describe(final Exception e) {
        final String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
