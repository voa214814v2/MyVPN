package com.narezany.myvpn;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.wireguard.config.Config;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_VPN = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;

    private static final String PREFS = "vpn";
    private static final String KEY_MODE = "mode";
    private static final String KEY_WARP = "warp_conf";
    private static final String KEY_CUSTOM = "custom_conf";
    private static final String MODE_WARP = "warp";
    private static final String MODE_CUSTOM = "custom";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private TextView statusView;
    private TextView statsView;
    private TextView sourceView;
    private TextView toggleView;
    private TextView logView;
    private TextView warpChip;
    private TextView customChip;

    private boolean busy;

    private final Runnable statsTicker = new Runnable() {
        @Override
        public void run() {
            refreshStats();
            if (VpnManager.get(getApplicationContext()).isUp()) {
                ui.postDelayed(this, 2000);
            }
        }
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        statusView = findViewById(R.id.status);
        statsView = findViewById(R.id.stats);
        sourceView = findViewById(R.id.source);
        toggleView = findViewById(R.id.toggle);
        logView = findViewById(R.id.log);
        warpChip = findViewById(R.id.mode_warp);
        customChip = findViewById(R.id.mode_custom);

        toggleView.setOnClickListener(v -> toggle());
        warpChip.setOnClickListener(v -> selectMode(MODE_WARP));
        customChip.setOnClickListener(v -> showConfigDialog());

        askForNotifications();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
        ui.removeCallbacks(statsTicker);
        ui.post(statsTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(statsTicker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                startTunnel();
            } else {
                log(getString(R.string.log_vpn_denied));
            }
        }
    }

    private void toggle() {
        if (busy) return;
        if (VpnManager.get(getApplicationContext()).isUp()) {
            stopTunnel();
            return;
        }
        final Intent prepare = android.net.VpnService.prepare(this);
        if (prepare != null) {
            startActivityForResult(prepare, REQ_VPN);
        } else {
            startTunnel();
        }
    }

    private void startTunnel() {
        setBusy(true, getString(R.string.status_connecting));
        io.execute(() -> {
            try {
                final String conf = ensureConfig();
                final Config config = Config.parse(new BufferedReader(new StringReader(conf)));
                VpnManager.get(getApplicationContext()).connect(config, (ok, error) -> ui.post(() -> {
                    setBusy(false, null);
                    if (ok) {
                        startKeepAlive();
                        log(getString(R.string.log_connected));
                        ui.removeCallbacks(statsTicker);
                        ui.post(statsTicker);
                    } else {
                        log(getString(R.string.log_error) + " " + error);
                    }
                    refreshUi();
                }));
            } catch (final Exception e) {
                ui.post(() -> {
                    setBusy(false, null);
                    log(getString(R.string.log_error) + " " + describe(e));
                    refreshUi();
                });
            }
        });
    }

    private void stopTunnel() {
        setBusy(true, getString(R.string.status_disconnecting));
        VpnManager.get(getApplicationContext()).disconnect((ok, error) -> ui.post(() -> {
            stopService(new Intent(this, KeepAliveService.class));
            setBusy(false, null);
            refreshUi();
        }));
    }

    private String ensureConfig() throws Exception {
        if (MODE_CUSTOM.equals(prefs.getString(KEY_MODE, MODE_WARP))) {
            final String custom = prefs.getString(KEY_CUSTOM, "");
            if (custom.trim().isEmpty()) throw new IllegalStateException(getString(R.string.err_no_config));
            return custom;
        }
        String warp = prefs.getString(KEY_WARP, "");
        if (warp.trim().isEmpty()) {
            ui.post(() -> log(getString(R.string.log_registering)));
            warp = WarpClient.register();
            prefs.edit().putString(KEY_WARP, warp).apply();
        }
        return warp;
    }

    private void startKeepAlive() {
        final Intent intent = new Intent(this, KeepAliveService.class);
        intent.setAction(KeepAliveService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void selectMode(final String mode) {
        prefs.edit().putString(KEY_MODE, mode).apply();
        refreshUi();
    }

    private void showConfigDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        input.setMinLines(8);
        input.setText(prefs.getString(KEY_CUSTOM, ""));
        input.setHint(R.string.config_hint);

        final int pad = (int) (16 * getResources().getDisplayMetrics().density);
        final FrameLayout container = new FrameLayout(this);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.config_title)
                .setView(container)
                .setPositiveButton(R.string.save, (dialog, which) -> saveCustomConfig(input.getText().toString()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void saveCustomConfig(final String text) {
        try {
            Config.parse(new BufferedReader(new StringReader(text)));
        } catch (final Exception e) {
            log(getString(R.string.log_bad_config) + " " + describe(e));
            return;
        }
        prefs.edit().putString(KEY_CUSTOM, text).putString(KEY_MODE, MODE_CUSTOM).apply();
        log(getString(R.string.log_config_saved));
        refreshUi();
    }

    private void refreshUi() {
        final boolean up = VpnManager.get(getApplicationContext()).isUp();
        final boolean warp = !MODE_CUSTOM.equals(prefs.getString(KEY_MODE, MODE_WARP));

        sourceView.setText(warp ? R.string.source_warp : R.string.source_custom);
        warpChip.setBackgroundResource(warp ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        customChip.setBackgroundResource(warp ? R.drawable.bg_chip : R.drawable.bg_chip_on);

        if (busy) {
            statusView.setText(R.string.status_working);
            statusView.setTextColor(getColor(R.color.muted));
        } else if (up) {
            statusView.setText(R.string.status_on);
            statusView.setTextColor(getColor(R.color.good));
        } else {
            statusView.setText(R.string.status_off);
            statusView.setTextColor(getColor(R.color.text));
        }

        toggleView.setText(up ? R.string.btn_off : R.string.btn_on);
        toggleView.setBackgroundResource(up ? R.drawable.bg_btn_off : R.drawable.bg_btn_on);
        toggleView.setAlpha(busy ? 0.5f : 1f);

        refreshStats();
    }

    private void setBusy(final boolean value, final String message) {
        busy = value;
        if (message != null) {
            statusView.setText(message);
            statusView.setTextColor(getColor(R.color.muted));
        }
        toggleView.setAlpha(value ? 0.5f : 1f);
    }

    private void refreshStats() {
        final VpnManager manager = VpnManager.get(getApplicationContext());
        if (!manager.isUp()) {
            statsView.setText(R.string.stats_zero);
            return;
        }
        final long[] values = manager.stats();
        statsView.setText(getString(R.string.stats_fmt, human(values[0]), human(values[1])));
    }

    private void log(final String line) {
        final String current = logView.getText().toString();
        final String[] lines = current.isEmpty() ? new String[0] : current.split("\n");
        final StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - 3); i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        sb.append(line);
        logView.setText(sb.toString());
    }

    private void askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private static String describe(final Exception e) {
        final String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static String human(final long bytes) {
        if (bytes < 1024) return bytes + " Б";
        final double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f КБ", kb);
        final double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f МБ", mb);
        return String.format(Locale.US, "%.2f ГБ", mb / 1024.0);
    }
}
