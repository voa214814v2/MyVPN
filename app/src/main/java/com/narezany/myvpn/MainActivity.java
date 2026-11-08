package com.narezany.myvpn;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.wireguard.config.Config;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_VPN = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;

    private static final int TAB_VPN = 0;
    private static final int TAB_THEMES = 1;
    private static final int TAB_LOGS = 2;
    private static final int TAB_HELP = 3;

    private static final String PREFS = "vpn";
    private static final String KEY_MODE = "mode";
    private static final String KEY_WARP = "warp_conf";
    private static final String KEY_CUSTOM = "custom_conf";
    private static final String MODE_WARP = "warp";
    private static final String MODE_CUSTOM = "custom";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;

    private View panelVpn;
    private View panelThemes;
    private View panelLogs;
    private View panelHelp;

    private TextView tabVpn;
    private TextView tabThemes;
    private TextView tabLogs;
    private TextView tabHelp;

    private TextView statusView;
    private TextView statsView;
    private TextView sourceView;
    private TextView toggleView;
    private TextView miniLog;
    private TextView warpChip;
    private TextView customChip;

    private LinearLayout swatches;
    private TextView themeName;
    private TextView logsText;
    private LinearLayout helpContainer;

    private boolean busy;
    private int tab = TAB_VPN;

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

        panelVpn = findViewById(R.id.panel_vpn);
        panelThemes = findViewById(R.id.panel_themes);
        panelLogs = findViewById(R.id.panel_logs);
        panelHelp = findViewById(R.id.panel_help);

        tabVpn = findViewById(R.id.tab_vpn);
        tabThemes = findViewById(R.id.tab_themes);
        tabLogs = findViewById(R.id.tab_logs);
        tabHelp = findViewById(R.id.tab_help);

        statusView = findViewById(R.id.status);
        statsView = findViewById(R.id.stats);
        sourceView = findViewById(R.id.source);
        toggleView = findViewById(R.id.toggle);
        miniLog = findViewById(R.id.mini_log);
        warpChip = findViewById(R.id.mode_warp);
        customChip = findViewById(R.id.mode_custom);

        swatches = findViewById(R.id.swatches);
        themeName = findViewById(R.id.theme_name);
        logsText = findViewById(R.id.logs_text);
        helpContainer = findViewById(R.id.help_container);

        toggleView.setOnClickListener(v -> toggle());
        warpChip.setOnClickListener(v -> selectMode(MODE_WARP));
        customChip.setOnClickListener(v -> showConfigDialog());

        tabVpn.setOnClickListener(v -> showTab(TAB_VPN));
        tabThemes.setOnClickListener(v -> showTab(TAB_THEMES));
        tabLogs.setOnClickListener(v -> showTab(TAB_LOGS));
        tabHelp.setOnClickListener(v -> showTab(TAB_HELP));

        findViewById(R.id.logs_refresh).setOnClickListener(v -> refreshLogs());
        findViewById(R.id.logs_copy).setOnClickListener(v -> copyLogs());
        findViewById(R.id.logs_clear).setOnClickListener(v -> {
            Logs.clear(this);
            refreshLogs();
            Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show();
        });

        askForNotifications();
        log(getString(R.string.log_app_start));
        buildSwatches();
        buildHelp();
        showTab(TAB_VPN);
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

    // ---------- вкладки ----------

    private void showTab(final int index) {
        tab = index;
        panelVpn.setVisibility(index == TAB_VPN ? View.VISIBLE : View.GONE);
        panelThemes.setVisibility(index == TAB_THEMES ? View.VISIBLE : View.GONE);
        panelLogs.setVisibility(index == TAB_LOGS ? View.VISIBLE : View.GONE);
        panelHelp.setVisibility(index == TAB_HELP ? View.VISIBLE : View.GONE);

        refreshTabColors(Theme.accent(this));

        if (index == TAB_THEMES) buildSwatches();
        if (index == TAB_LOGS) refreshLogs();
        if (index == TAB_HELP) buildHelp();
    }

    private void refreshTabColors(final int accent) {
        final int muted = getColor(R.color.muted);
        tabVpn.setTextColor(tab == TAB_VPN ? accent : muted);
        tabThemes.setTextColor(tab == TAB_THEMES ? accent : muted);
        tabLogs.setTextColor(tab == TAB_LOGS ? accent : muted);
        tabHelp.setTextColor(tab == TAB_HELP ? accent : muted);
    }

    private void buildSwatches() {
        swatches.removeAllViews();
        final int accent = Theme.accent(this);
        LinearLayout row = null;

        for (int i = 0; i < Theme.PRESETS.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                swatches.addView(row);
            }
            final int color = Theme.PRESETS[i];
            final boolean selected = color == accent;

            final View swatch = new View(this);
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    Theme.dp(this, 54), Theme.dp(this, 54));
            params.setMargins(Theme.dp(this, 5), Theme.dp(this, 5), Theme.dp(this, 5), Theme.dp(this, 5));
            swatch.setLayoutParams(params);

            final GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.OVAL);
            drawable.setColor(color);
            drawable.setStroke(Theme.dp(this, selected ? 3 : 1),
                    selected ? 0xFFFFFFFF : 0x33FFFFFF);
            swatch.setBackground(drawable);

            swatch.setOnClickListener(v -> {
                Theme.setAccent(this, color);
                buildSwatches();
                buildHelp();
                refreshUi();
            });
            row.addView(swatch);
        }

        themeName.setText(getString(R.string.theme_current, Theme.nameOf(accent)));
    }

    private void buildHelp() {
        helpContainer.removeAllViews();
        final int accent = Theme.accent(this);

        for (final String[] section : Help.SECTIONS) {
            final TextView title = new TextView(this);
            title.setText(section[0]);
            title.setTextColor(accent);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            title.setTypeface(null, Typeface.BOLD);
            final LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            titleParams.topMargin = Theme.dp(this, 18);
            title.setLayoutParams(titleParams);
            helpContainer.addView(title);

            final TextView body = new TextView(this);
            body.setText(section[1]);
            body.setTextColor(getColor(R.color.muted));
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            body.setLineSpacing(Theme.dp(this, 3), 1f);
            final LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bodyParams.topMargin = Theme.dp(this, 6);
            body.setLayoutParams(bodyParams);
            helpContainer.addView(body);
        }
    }

    // ---------- туннель ----------

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
            log(getString(R.string.log_disconnected));
            refreshUi();
        }));
    }

    private String ensureConfig() throws Exception {
        if (MODE_CUSTOM.equals(prefs.getString(KEY_MODE, MODE_WARP))) {
            final String custom = prefs.getString(KEY_CUSTOM, "");
            if (custom.trim().isEmpty()) {
                throw new IllegalStateException(getString(R.string.err_no_config));
            }
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

    // ---------- конфиг ----------

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

        final int pad = Theme.dp(this, 16);
        final FrameLayout container = new FrameLayout(this);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.config_title)
                .setView(container)
                .setPositiveButton(R.string.save,
                        (dialog, which) -> saveCustomConfig(input.getText().toString()))
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

    // ---------- отрисовка ----------

    private void refreshUi() {
        final int accent = Theme.accent(this);
        final boolean up = VpnManager.get(getApplicationContext()).isUp();
        final boolean warp = !MODE_CUSTOM.equals(prefs.getString(KEY_MODE, MODE_WARP));

        sourceView.setText(warp ? R.string.source_warp : R.string.source_custom);
        warpChip.setBackground(Theme.chip(this, warp, accent));
        customChip.setBackground(Theme.chip(this, !warp, accent));

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
        toggleView.setBackground(Theme.circle(this, up ? getColor(R.color.danger) : accent));
        toggleView.setAlpha(busy ? 0.5f : 1f);

        miniLog.setText(Logs.recent(this, 2));
        refreshTabColors(accent);
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

    private void refreshLogs() {
        final String text = Logs.text(this);
        logsText.setText(text.isEmpty() ? getString(R.string.logs_empty) : text);
    }

    private void copyLogs() {
        String text = Logs.text(this);
        if (text.isEmpty()) text = getString(R.string.logs_empty);
        final ClipboardManager manager =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return;
        manager.setPrimaryClip(ClipData.newPlainText("vpn logs", text));
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show();
    }

    private void log(final String line) {
        Logs.add(this, line);
        miniLog.setText(Logs.recent(this, 2));
        if (tab == TAB_LOGS) refreshLogs();
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
