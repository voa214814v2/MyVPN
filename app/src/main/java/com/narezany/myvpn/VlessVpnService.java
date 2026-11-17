package com.narezany.myvpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.amnezia.awg.hevtunnel.TProxyService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * VPN-интерфейс для режима VLESS.
 *
 * Сам VLESS-протокол умеет только Xray, а он отдаёт трафик через локальный
 * SOCKS5. Чтобы телефонные приложения ходили через него, нужен tun:
 * этот сервис поднимает tun и гонит из него пакеты в SOCKS5 через
 * hev-socks5-tunnel (та же нативная библиотека, что в AmneziaWG).
 */
public final class VlessVpnService extends VpnService {

    private static final String TAG = "MyVpn/Vless";

    static final String ACTION_START = "com.narezany.myvpn.VLESS_START";
    static final String ACTION_STOP = "com.narezany.myvpn.VLESS_STOP";

    private static final String ADDRESS_V4 = "10.111.222.1";
    private static final String ADDRESS_V6 = "2001:db8::1";
    private static final int MTU = 1280;

    private static final Object TUN_LOCK = new Object();
    private static volatile VlessVpnService current;
    private static boolean tunUp;

    private ParcelFileDescriptor fd;
    private Thread hevThread;

    /** Ждёт, пока сервис поднимет tun. */
    static boolean awaitTun(final long millis) {
        synchronized (TUN_LOCK) {
            final long deadline = System.currentTimeMillis() + millis;
            while (!tunUp) {
                final long left = deadline - System.currentTimeMillis();
                if (left <= 0) return false;
                try {
                    TUN_LOCK.wait(left);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    static VlessVpnService instance() {
        return current;
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (fd == null) {
            try {
                establish();
            } catch (final Exception e) {
                Log.e(TAG, "не удалось поднять tun", e);
                Logs.add(this, "VLESS: VPN не поднялся: " + e);
                shutdown();
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_NOT_STICKY;
    }

    private void establish() throws Exception {
        final Builder builder = new Builder();
        builder.setSession(getString(R.string.app_name));
        builder.addAddress(ADDRESS_V4, 32);
        builder.addAddress(ADDRESS_V6, 128);
        builder.addRoute("0.0.0.0", 0);
        builder.addRoute("::", 0);
        builder.addDnsServer("1.1.1.1");
        builder.addDnsServer("8.8.8.8");
        builder.setMtu(MTU);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }
        // Само приложение (Xray) не заворачиваем в туннель, иначе будет петля.
        builder.addDisallowedApplication(getPackageName());

        fd = builder.establish();
        if (fd == null) {
            throw new IllegalStateException("нет разрешения на VPN");
        }
        current = this;
        synchronized (TUN_LOCK) {
            tunUp = true;
            TUN_LOCK.notifyAll();
        }
        Log.d(TAG, "tun поднят");
    }

    /** Запускает hev-socks5-tunnel на уже поднятом tun. */
    void startHev(final int port) {
        if (hevThread != null) return;
        hevThread = new Thread(() -> {
            try {
                final File config = writeHevConfig(port);
                // Ждём, пока Xray начнёт принимать соединения на SOCKS5-порту.
                final long deadline = System.currentTimeMillis() + 4000;
                while (System.currentTimeMillis() < deadline) {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", port), 400);
                        break;
                    } catch (final IOException e) {
                        Thread.sleep(120);
                    }
                }
                final ParcelFileDescriptor descriptor = fd;
                if (descriptor == null) return;
                TProxyService.TProxyStartService(config.getAbsolutePath(), descriptor.getFd());
                Log.d(TAG, "hev-туннель запущен");
            } catch (final Throwable e) {
                Log.e(TAG, "hev-туннель не запустился", e);
                Logs.add(getApplicationContext(), "VLESS: туннель не запустился: " + e);
            }
        }, "vless-hev");
        hevThread.setDaemon(true);
        hevThread.start();
    }

    private File writeHevConfig(final int port) throws IOException {
        final File file = new File(getCacheDir(), "vless-tproxy.conf");
        final String yaml = "misc:\n"
                + "  task-stack-size: 24576\n"
                + "tunnel:\n"
                + "  mtu: " + MTU + "\n"
                + "  ipv4: '" + ADDRESS_V4 + "'\n"
                + "  ipv6: '" + ADDRESS_V6 + "'\n"
                + "socks5:\n"
                + "  address: '127.0.0.1'\n"
                + "  port: " + port + "\n"
                + "  udp: 'udp'\n";
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(yaml.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private void shutdown() {
        try {
            TProxyService.TProxyStopService();
        } catch (final Throwable ignored) {
            // библиотека могла ещё не загрузиться
        }
        if (hevThread != null) {
            hevThread.interrupt();
            hevThread = null;
        }
        if (fd != null) {
            try {
                fd.close();
            } catch (final IOException ignored) {
            }
            fd = null;
        }
        current = null;
        synchronized (TUN_LOCK) {
            tunUp = false;
            TUN_LOCK.notifyAll();
        }
        Log.d(TAG, "tun опущен");
    }

    @Override
    public void onRevoke() {
        shutdown();
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }
}
