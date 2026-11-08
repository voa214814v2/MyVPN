package com.narezany.myvpn;

import android.content.Context;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Единственный туннель приложения: держит GoBackend и текущее состояние.
 * Все вызовы к backend блокирующие, поэтому выполняются в отдельном потоке.
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
        backend = new GoBackend(appContext);
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
