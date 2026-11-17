package com.narezany.myvpn;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.amnezia.awg.config.Config;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_VPN = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final int REQ_IMPORT_VLESS = 1003;
    private static final int REQ_IMPORT_CONF = 1004;

    private static final int TAB_VPN = 0;
    private static final int TAB_THEMES = 1;
    private static final int TAB_LOGS = 2;
    private static final int TAB_HELP = 3;

    private static final String PREFS = "vpn";
    private static final String KEY_MODE = "mode";
    private static final String KEY_WARP = "warp_conf";
    private static final String KEY_CUSTOM = "custom_conf";
    private static final String KEY_VLESS = "vless_conf";
    private static final String MODE_WARP = "warp";
    private static final String MODE_CUSTOM = "custom";
    private static final String MODE_VLESS = "vless";

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
    private TextView miniLog;
    private TextView warpChip;
    private TextView customChip;
    private TextView vlessChip;

    private FrameLayout keyContainer;
    private KeyClickerView keyView;
    private TextView keysCount;
    private TextView keysLevel;
    private TextView keysProgressText;
    private ProgressBar keysProgress;
    private TextView boostStatus;
    private TextView boostSmall;
    private TextView boostBig;
    private TextView boostMega;
    private TextView upgradeMult;
    private TextView upgradeAuto;
    private TextView disconnectView;

    private TextView updateBanner;
    private TextView versionRow;

    private LinearLayout swatches;
    private TextView themeName;
    private TextView logsText;
    private LinearLayout helpContainer;

    private boolean busy;
    private int busyMessage;
    private int tab = TAB_VPN;
    private int lastAccent;
    private int lastClicks;
    private int lastStatusColor;
    private String lastStatusText = "";

    private UpdateChecker.Info pendingUpdate;
    private boolean updateBusy;
    private boolean updateChecked;
    private int loggedUpdateCode;

    private int serverCount;
    private AlertDialog vlessMenuDialog;
    private ArrayAdapter<String> vlessAdapter;
    private final List<String> vlessLabels = new ArrayList<>();
    private final List<String> vlessLinks = new ArrayList<>();
    private final List<String> vlessStatuses = new ArrayList<>();
    private TextView vlessMenuHint;

    private final Runnable autoTicker = new Runnable() {
        @Override
        public void run() {
            final Clicker.Tap tap = Clicker.tick(MainActivity.this);
            updateClicker(false);
            if (tap.levelUp) {
                keyView.celebrate();
                final String line = getString(R.string.log_level_up, tap.level, Clicker.rank(tap.level));
                Toast.makeText(MainActivity.this, line, Toast.LENGTH_SHORT).show();
                log(line);
            }
            ui.postDelayed(this, 1000);
        }
    };

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
        lastAccent = Theme.accent(this);
        lastClicks = Clicker.clicks(this);
        serverCount = ServerCatalog.servers(this).size();
        migrateOldConfig();

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
        miniLog = findViewById(R.id.mini_log);
        warpChip = findViewById(R.id.mode_warp);
        customChip = findViewById(R.id.mode_custom);
        vlessChip = findViewById(R.id.mode_vless);

        keyContainer = findViewById(R.id.key_container);
        keyView = findViewById(R.id.key_view);
        keysCount = findViewById(R.id.keys_count);
        keysLevel = findViewById(R.id.keys_level);
        keysProgressText = findViewById(R.id.keys_progress_text);
        keysProgress = findViewById(R.id.keys_progress);
        boostStatus = findViewById(R.id.boost_status);
        boostSmall = findViewById(R.id.boost_small);
        boostBig = findViewById(R.id.boost_big);
        boostMega = findViewById(R.id.boost_mega);
        upgradeMult = findViewById(R.id.upgrade_mult);
        upgradeAuto = findViewById(R.id.upgrade_auto);
        disconnectView = findViewById(R.id.disconnect);

        updateBanner = findViewById(R.id.update_banner);
        versionRow = findViewById(R.id.version_row);

        swatches = findViewById(R.id.swatches);
        themeName = findViewById(R.id.theme_name);
        logsText = findViewById(R.id.logs_text);
        helpContainer = findViewById(R.id.help_container);

        keyView.setOnClickListener(v -> onKeyTap());
        setPressed(disconnectView, () -> {
            if (!busy) stopTunnel();
        });
        setPressed(updateBanner, this::onUpdateBannerClick);
        setPressed(versionRow, () -> checkUpdates(true));

        setPressed(warpChip, () -> selectMode(MODE_WARP));
        setPressed(customChip, this::showConfigMenu);
        setPressed(vlessChip, this::showVlessMenu);

        setPressed(boostSmall, () -> buyBoost(Clicker.BOOST_SMALL));
        setPressed(boostBig, () -> buyBoost(Clicker.BOOST_BIG));
        setPressed(boostMega, () -> buyBoost(Clicker.BOOST_MEGA));
        setPressed(upgradeMult, this::buyMultiplier);
        setPressed(upgradeAuto, this::buyAuto);

        setPressed(tabVpn, () -> showTab(TAB_VPN));
        setPressed(tabThemes, () -> showTab(TAB_THEMES));
        setPressed(tabLogs, () -> showTab(TAB_LOGS));
        setPressed(tabHelp, () -> showTab(TAB_HELP));

        setPressed(findViewById(R.id.logs_refresh), this::refreshLogs);
        setPressed(findViewById(R.id.logs_copy), this::copyLogs);
        setPressed(findViewById(R.id.logs_clear), () -> {
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
        ui.removeCallbacks(autoTicker);
        ui.post(autoTicker);
        if (!updateChecked) {
            updateChecked = true;
            ui.postDelayed(() -> checkUpdates(false), 1500);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(statsTicker);
        ui.removeCallbacks(autoTicker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (vlessMenuDialog != null && vlessMenuDialog.isShowing()) vlessMenuDialog.dismiss();
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
            return;
        }
        if (requestCode == REQ_IMPORT_VLESS || requestCode == REQ_IMPORT_CONF) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            final String text = readFile(data.getData());
            if (text == null || text.trim().isEmpty()) {
                Toast.makeText(this, R.string.config_import_error, Toast.LENGTH_SHORT).show();
                return;
            }
            if (requestCode == REQ_IMPORT_VLESS) {
                importVlessFile(text);
            } else {
                importConfigFile(text);
            }
        }
    }

    // ---------- вкладки ----------

    private void showTab(final int index) {
        final int previous = tab;
        tab = index;

        final View[] panels = {panelVpn, panelThemes, panelLogs, panelHelp};
        for (int i = 0; i < panels.length; i++) {
            final View panel = panels[i];
            final int panelIndex = i;
            panel.animate().cancel();
            if (i == index) {
                panel.setVisibility(View.VISIBLE);
                if (previous != index) {
                    panel.setAlpha(0f);
                    panel.setTranslationY(Theme.dp(this, 14));
                    panel.animate().alpha(1f).translationY(0f).setDuration(220)
                            .setInterpolator(new DecelerateInterpolator()).start();
                } else {
                    panel.setAlpha(1f);
                    panel.setTranslationY(0f);
                }
            } else if (panel.getVisibility() == View.VISIBLE) {
                panel.animate().alpha(0f).translationY(Theme.dp(this, -8))
                        .setDuration(120).withEndAction(() -> {
                            if (tab != panelIndex) {
                                panel.setVisibility(View.GONE);
                                panel.setAlpha(1f);
                                panel.setTranslationY(0f);
                            }
                        }).start();
            }
        }

        animateTabColors(Theme.accent(this));

        if (index == TAB_THEMES) buildSwatches();
        if (index == TAB_LOGS) refreshLogs();
        if (index == TAB_HELP) buildHelp();
    }

    private void animateTabColors(final int accent) {
        final TextView[] tabs = {tabVpn, tabThemes, tabLogs, tabHelp};
        final int muted = getColor(R.color.muted);
        for (int i = 0; i < tabs.length; i++) {
            final TextView view = tabs[i];
            final int from = view.getCurrentTextColor();
            final int to = i == tab ? accent : muted;
            if (from == to) continue;
            final ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
            animator.setDuration(220);
            animator.addUpdateListener(animation ->
                    view.setTextColor((Integer) animation.getAnimatedValue()));
            animator.start();
        }
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
            swatch.setAlpha(selected ? 1f : 0.9f);

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

    // ---------- кликер ----------

    private void onKeyTap() {
        final Clicker.Tap tap = Clicker.add(this, 1);
        lastClicks = tap.total;
        updateClicker(true);
        showFlyingKeys(tap.gained);

        if (tap.levelUp) {
            keyView.celebrate();
            final String line = getString(R.string.log_level_up, tap.level, Clicker.rank(tap.level));
            Toast.makeText(this, line, Toast.LENGTH_SHORT).show();
            log(line);
        }

        if (!busy && !VpnManager.get(getApplicationContext()).isUp()) {
            toggle();
        }
    }

    private void updateClicker(final boolean bounce) {
        final int total = Clicker.clicks(this);
        final int level = Clicker.level(total);
        final int inLevel = Clicker.inLevel(total);
        final int accent = Theme.accent(this);

        keysCount.setText(getString(R.string.keys_count, Clicker.balance(this)));
        keysLevel.setText(getString(R.string.keys_total, total,
                getString(R.string.keys_level, level, Clicker.rank(level))));
        keysProgress.setMax(Clicker.PER_LEVEL);
        keysProgress.setProgress(inLevel);
        keysProgressText.setText(getString(
                R.string.keys_progress, inLevel, Clicker.PER_LEVEL, level + 1));
        keysProgress.getProgressDrawable().setTint(accent);

        final int left = Clicker.boostSecondsLeft(this);
        String status = left > 0
                ? getString(R.string.boost_active, Clicker.boostMultiplier(this),
                        left, Clicker.multiplier(this))
                : getString(R.string.boost_none, Clicker.multiplier(this));
        if (Clicker.vpnBonus(this)) {
            status += " · " + getString(R.string.vpn_bonus);
        }
        boostStatus.setText(status);

        boostSmall.setText(getString(R.string.boost_small, Clicker.priceBoost(Clicker.BOOST_SMALL)));
        boostBig.setText(getString(R.string.boost_big, Clicker.priceBoost(Clicker.BOOST_BIG)));
        boostMega.setText(getString(R.string.boost_mega, Clicker.priceBoost(Clicker.BOOST_MEGA)));
        upgradeMult.setText(getString(R.string.upgrade_mult,
                Clicker.baseMultiplier(this), Clicker.priceMultiplier(this)));
        upgradeAuto.setText(getString(R.string.upgrade_auto,
                Clicker.autoPerSecond(this), Clicker.priceAuto(this)));

        boostSmall.setBackground(Theme.chip(this,
                Clicker.balance(this) >= Clicker.priceBoost(Clicker.BOOST_SMALL), accent));
        boostBig.setBackground(Theme.chip(this,
                Clicker.balance(this) >= Clicker.priceBoost(Clicker.BOOST_BIG), accent));
        boostMega.setBackground(Theme.chip(this,
                Clicker.balance(this) >= Clicker.priceBoost(Clicker.BOOST_MEGA), accent));
        upgradeMult.setBackground(Theme.chip(this,
                Clicker.balance(this) >= Clicker.priceMultiplier(this), accent));
        upgradeAuto.setBackground(Theme.chip(this,
                Clicker.balance(this) >= Clicker.priceAuto(this), accent));

        keyView.setMultiplier(Clicker.multiplier(this));

        if (bounce) {
            keysCount.animate().cancel();
            keysCount.setScaleX(1.25f);
            keysCount.setScaleY(1.25f);
            keysCount.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private void showFlyingKeys(final int gained) {
        final TextView fly = new TextView(this);
        fly.setText(getString(R.string.fly_key, gained));
        fly.setTextColor(Theme.accent(this));
        fly.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        fly.setTypeface(null, Typeface.BOLD);
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER;
        fly.setLayoutParams(params);
        keyContainer.addView(fly);
        fly.animate().translationY(-Theme.dp(this, 80)).alpha(0f)
                .setDuration(750).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> keyContainer.removeView(fly)).start();
    }

    private void buyBoost(final int kind) {
        if (Clicker.buyBoost(this, kind)) {
            keyView.celebrate();
            Toast.makeText(this,
                    getString(R.string.boost_bought, kind, Clicker.boostDuration(kind)),
                    Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.boost_no_keys, Toast.LENGTH_SHORT).show();
        }
        updateClicker(true);
    }

    private void buyMultiplier() {
        if (Clicker.buyMultiplier(this)) {
            keyView.celebrate();
            Toast.makeText(this, R.string.upgrade_bought, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.boost_no_keys, Toast.LENGTH_SHORT).show();
        }
        updateClicker(true);
    }

    private void buyAuto() {
        if (Clicker.buyAuto(this)) {
            Toast.makeText(this, R.string.upgrade_bought, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.boost_no_keys, Toast.LENGTH_SHORT).show();
        }
        updateClicker(true);
    }

    /** Небольшая пружинка при нажатии на любую кнопку-плашку. */
    private void setPressed(final View view, final Runnable action) {
        view.setOnClickListener(v -> {
            v.animate().cancel();
            v.setScaleX(0.94f);
            v.setScaleY(0.94f);
            v.animate().scaleX(1f).scaleY(1f).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator()).start();
            action.run();
        });
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
        if (MODE_VLESS.equals(mode())) {
            startVlessTunnel();
            return;
        }
        setBusy(true, R.string.status_connecting);
        io.execute(() -> {
            try {
                final String conf = ensureConfig();
                final Config config = Config.parse(new BufferedReader(new StringReader(conf)));
                VpnManager.get(getApplicationContext()).connect(config, this::onTunnelDone);
            } catch (final Exception e) {
                ui.post(() -> {
                    setBusy(false, 0);
                    log(getString(R.string.log_error) + " " + describe(e));
                    refreshUi();
                });
            }
        });
    }

    private void startVlessTunnel() {
        final String text = prefs.getString(KEY_VLESS, "");
        if (text.trim().isEmpty()) {
            setBusy(false, 0);
            log(getString(R.string.log_error) + " " + getString(R.string.err_no_servers));
            showVlessDialog();
            return;
        }
        // Несколько ссылок — сначала проверяем, какая отвечает.
        setBusy(true, text.contains("\n") ? R.string.status_testing : R.string.status_connecting);
        VpnManager.get(getApplicationContext()).connectVless(text, this::onTunnelDone);
    }

    private void onTunnelDone(final boolean ok, final String error) {
        ui.post(() -> {
            setBusy(false, 0);
            if (ok) {
                startKeepAlive();
                log(getString(R.string.log_connected));
                ui.removeCallbacks(statsTicker);
                ui.post(statsTicker);
            } else {
                log(getString(R.string.log_error) + " " + error);
            }
            refreshUi();
        });
    }

    private void stopTunnel() {
        setBusy(true, R.string.status_disconnecting);
        VpnManager.get(getApplicationContext()).disconnect((ok, error) -> ui.post(() -> {
            stopService(new Intent(this, KeepAliveService.class));
            setBusy(false, 0);
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
        if (mode().equals(mode)) return;
        if (!busy && VpnManager.get(getApplicationContext()).isUp()) stopTunnel();
        prefs.edit().putString(KEY_MODE, mode).apply();
        refreshUi();
    }

    private String mode() {
        return prefs.getString(KEY_MODE, MODE_WARP);
    }

    // ---------- конфиг ----------

    /** Конфиг из старых версий переносим в библиотеку, чтобы он не потерялся. */
    private void migrateOldConfig() {
        final String old = prefs.getString(KEY_CUSTOM, "");
        if (!old.trim().isEmpty() && ConfigLibrary.all(this).isEmpty()) {
            ConfigLibrary.add(this, "Мой конфиг", old);
        }
    }

    private void showConfigMenu() {
        final List<ConfigLibrary.Entry> entries = ConfigLibrary.all(this);
        final List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.menu_warp));
        labels.add(getString(R.string.menu_add_config));
        labels.add(getString(R.string.menu_file_config));
        for (final ConfigLibrary.Entry entry : entries) {
            labels.add("🔹 " + entry.name);
        }

        showListMenu(getString(R.string.menu_config_title), labels,
                getString(R.string.menu_delete_hint), 0, null,
                (parent, view, position, id) -> {
                    if (position == 0) {
                        selectMode(MODE_WARP);
                        Toast.makeText(this, R.string.menu_warp, Toast.LENGTH_SHORT).show();
                    } else if (position == 1) {
                        showConfigDialog();
                    } else if (position == 2) {
                        pickFile(REQ_IMPORT_CONF);
                    } else if (position - 3 < entries.size()) {
                        final ConfigLibrary.Entry entry = entries.get(position - 3);
                        saveCustomConfig(entry.text, entry.name);
                    }
                },
                (parent, view, position, id) -> {
                    if (position >= 3 && position - 3 < entries.size()) {
                        ConfigLibrary.remove(this, position - 3);
                        Toast.makeText(this, R.string.config_deleted, Toast.LENGTH_SHORT).show();
                        showConfigMenu();
                    }
                    return true;
                });
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

        final int pad = Theme.dp(this, 16);
        final FrameLayout container = new FrameLayout(this);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.config_title)
                .setView(container)
                .setPositiveButton(R.string.save,
                        (dialog, which) -> saveCustomConfig(input.getText().toString(), null))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void saveCustomConfig(final String text, final String name) {
        String config;
        try {
            config = text.trim();
            Config.parse(new BufferedReader(new StringReader(config)));
        } catch (final Exception e) {
            log(getString(R.string.log_bad_config) + " " + describe(e));
            return;
        }

        final List<ConfigLibrary.Entry> entries = ConfigLibrary.all(this);
        String label = name;
        boolean known = false;
        for (final ConfigLibrary.Entry entry : entries) {
            if (entry.text.equals(config)) {
                label = entry.name;
                known = true;
                break;
            }
        }
        if (!known) {
            label = getString(R.string.config_name, entries.size() + 1);
            ConfigLibrary.add(this, label, config);
        }

        prefs.edit().putString(KEY_CUSTOM, config).putString(KEY_MODE, MODE_CUSTOM).apply();
        log(getString(R.string.config_saved_as, label));
        refreshUi();
    }

    // ---------- VLESS ----------

    private void showVlessMenu() {
        vlessLinks.clear();
        vlessLinks.addAll(ServerCatalog.servers(this));
        vlessStatuses.clear();
        for (int i = 0; i < vlessLinks.size(); i++) vlessStatuses.add("");
        fillVlessLabels();

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = Theme.dp(this, 12);
        box.setPadding(pad, pad / 2, pad, 0);

        vlessMenuHint = new TextView(this);
        vlessMenuHint.setText(R.string.menu_vless_hint);
        vlessMenuHint.setTextColor(getColor(R.color.muted));
        vlessMenuHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        vlessMenuHint.setPadding(0, 0, 0, Theme.dp(this, 6));
        box.addView(vlessMenuHint);

        vlessAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, vlessLabels);
        final ListView list = new ListView(this);
        list.setDividerHeight(0);
        list.setAdapter(vlessAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position == 0) {
                if (vlessLinks.isEmpty()) {
                    Toast.makeText(this, R.string.err_no_servers, Toast.LENGTH_SHORT).show();
                    return;
                }
                final String all = join(vlessLinks);
                applyVless(all, getString(R.string.menu_vless_auto, vlessLinks.size()));
            } else if (position == 1) {
                if (vlessMenuDialog != null) vlessMenuDialog.dismiss();
                showVlessDialog();
                return;
            } else if (position == 2) {
                if (vlessMenuDialog != null) vlessMenuDialog.dismiss();
                pickFile(REQ_IMPORT_VLESS);
                return;
            } else if (position - 3 < vlessLinks.size()) {
                final String link = vlessLinks.get(position - 3);
                applyVless(link, ServerCatalog.nameOf(link));
            }
            if (vlessMenuDialog != null) vlessMenuDialog.dismiss();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= 3 && position - 3 < vlessLinks.size()) {
                pingVless(position - 3);
            }
            return true;
        });
        box.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(this, 320)));

        vlessMenuDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.menu_vless_title)
                .setView(box)
                .setNeutralButton(R.string.menu_vless_refresh, (dialog, which) -> refreshCatalog())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void fillVlessLabels() {
        vlessLabels.clear();
        vlessLabels.add(getString(R.string.menu_vless_auto, vlessLinks.size()));
        vlessLabels.add(getString(R.string.menu_vless_paste));
        vlessLabels.add(getString(R.string.menu_vless_file));
        for (int i = 0; i < vlessLinks.size(); i++) {
            vlessLabels.add(serverLabel(i));
        }
        if (vlessAdapter != null) vlessAdapter.notifyDataSetChanged();
    }

    private String serverLabel(final int index) {
        String label = ServerCatalog.hostOf(vlessLinks.get(index))
                + " · " + ServerCatalog.nameOf(vlessLinks.get(index));
        final String status = vlessStatuses.get(index);
        if (!status.isEmpty()) label = status + " " + label;
        return label.length() > 64 ? label.substring(0, 64) + "…" : label;
    }

    private void pingVless(final int index) {
        final String link = vlessLinks.get(index);
        io.execute(() -> {
            String status;
            String message;
            try {
                final long delay = VlessTunnel.ping(getApplicationContext(), link);
                status = delay >= 0 ? "✓ " + delay + " мс" : "✖";
                message = delay >= 0
                        ? getString(R.string.vless_server_ok, ServerCatalog.nameOf(link), (int) delay)
                        : getString(R.string.vless_server_bad, ServerCatalog.nameOf(link));
            } catch (final Exception e) {
                status = "✖";
                message = getString(R.string.vless_server_bad, ServerCatalog.nameOf(link));
            }
            final String resultStatus = status;
            final String resultMessage = message;
            ui.post(() -> {
                if (index < vlessStatuses.size()) {
                    vlessStatuses.set(index, resultStatus);
                    vlessLabels.set(index + 3, serverLabel(index));
                    if (vlessAdapter != null) vlessAdapter.notifyDataSetChanged();
                }
                Toast.makeText(this, resultMessage, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void refreshCatalog() {
        if (vlessMenuDialog != null && vlessMenuDialog.isShowing()) vlessMenuDialog.dismiss();
        final TextView wait = new TextView(this);
        wait.setText(R.string.menu_vless_updating);
        wait.setTextColor(getColor(R.color.text));
        wait.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        wait.setPadding(Theme.dp(this, 20), Theme.dp(this, 24),
                Theme.dp(this, 20), Theme.dp(this, 24));
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle(R.string.menu_vless_title)
                .setView(wait)
                .setCancelable(false)
                .show();

        ServerCatalog.fetch(this, (text, error) -> ui.post(() -> {
            progress.dismiss();
            serverCount = ServerCatalog.servers(this).size();
            if (error != null) {
                Toast.makeText(this,
                        getString(R.string.vless_catalog_error, error), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this,
                        getString(R.string.vless_catalog_updated, serverCount),
                        Toast.LENGTH_SHORT).show();
            }
            showVlessMenu();
        }));
    }

    private void showVlessDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        input.setMinLines(7);
        input.setText(prefs.getString(KEY_VLESS, ""));
        input.setHint(R.string.config_vless_hint);

        final TextView note = new TextView(this);
        note.setText(R.string.config_vless_note);
        note.setTextColor(getColor(R.color.muted));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        note.setLineSpacing(Theme.dp(this, 2), 1f);
        note.setPadding(0, Theme.dp(this, 10), 0, 0);

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = Theme.dp(this, 16);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        box.addView(note);

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle(R.string.config_vless_title)
                .setView(scroll)
                .setPositiveButton(R.string.save,
                        (dialog, which) -> saveVlessConfig(input.getText().toString()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void saveVlessConfig(final String text) {
        final String trimmed = text.trim();
        // Разбор ссылок делает ядро Xray — это долго, поэтому не в главном потоке.
        io.execute(() -> {
            int count = 0;
            String failure = null;
            try {
                count = VlessTunnel.countServers(trimmed);
            } catch (final Throwable e) {
                for (final String line : trimmed.split("\n")) {
                    if (line.trim().startsWith("vless://")) count++;
                }
                if (count == 0) failure = describe(e);
            }

            final int total = count;
            final String error = failure;
            ui.post(() -> {
                if (total == 0) {
                    log(getString(R.string.log_bad_vless) + " "
                            + (error == null ? getString(R.string.err_no_servers) : error));
                    return;
                }
                final List<String> links = ServerCatalog.lines(trimmed);
                final String joined = links.isEmpty() ? trimmed : join(links);
                ServerCatalog.save(this, joined);
                serverCount = Math.max(1, links.size());
                applyVless(joined, getString(R.string.log_vless_saved, total));
                log(getString(R.string.log_vless_saved, total));
            });
        });
    }

    private void applyVless(final String text, final String label) {
        prefs.edit().putString(KEY_VLESS, text).putString(KEY_MODE, MODE_VLESS).apply();
        serverCount = Math.max(1, ServerCatalog.lines(text).size());
        log(getString(R.string.vless_chosen, label));
        refreshUi();
    }

    // ---------- меню и файлы ----------

    private void showListMenu(final String title, final List<String> labels, final String hint,
                              final int neutralRes, final Runnable neutral,
                              final AdapterView.OnItemClickListener click,
                              final AdapterView.OnItemLongClickListener longClick) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = Theme.dp(this, 12);
        box.setPadding(pad, pad / 2, pad, 0);

        final TextView note = new TextView(this);
        note.setText(hint);
        note.setTextColor(getColor(R.color.muted));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        note.setPadding(0, 0, 0, Theme.dp(this, 6));
        box.addView(note);

        final ListView list = new ListView(this);
        list.setDividerHeight(0);
        list.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, labels));
        list.setOnItemClickListener(click);
        if (longClick != null) list.setOnItemLongClickListener(longClick);
        box.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Theme.dp(this, 320)));

        final AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setNegativeButton(R.string.cancel, null);
        if (neutralRes != 0) {
            builder.setNeutralButton(neutralRes, (dialog, which) -> {
                if (neutral != null) neutral.run();
            });
        }
        builder.show();
    }

    private void pickFile(final int requestCode) {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, requestCode);
    }

    private String readFile(final Uri uri) {
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) return null;
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (final Exception e) {
            return null;
        }
    }

    private void importVlessFile(final String text) {
        final List<String> links = ServerCatalog.lines(text);
        if (links.isEmpty()) {
            Toast.makeText(this, R.string.err_no_servers, Toast.LENGTH_SHORT).show();
            return;
        }
        final String joined = join(links);
        ServerCatalog.save(this, joined);
        applyVless(joined, getString(R.string.vless_catalog_updated, links.size()));
    }

    private void importConfigFile(final String text) {
        try {
            Config.parse(new BufferedReader(new StringReader(text.trim())));
        } catch (final Exception e) {
            log(getString(R.string.log_bad_config) + " " + describe(e));
            Toast.makeText(this, R.string.log_bad_config + " " + describe(e),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        saveCustomConfig(text, null);
    }

    private static String join(final List<String> lines) {
        final StringBuilder result = new StringBuilder();
        for (final String line : lines) result.append(line).append('\n');
        return result.toString().trim();
    }

    // ---------- обновления ----------

    private void checkUpdates(final boolean manual) {
        if (updateBusy) return;
        updateBusy = true;
        final String version = versionName();
        if (manual) versionRow.setText(getString(R.string.update_checking, version));

        UpdateChecker.check(this, (info, error) -> ui.post(() -> {
            updateBusy = false;
            if (error != null) {
                versionRow.setText(getString(R.string.update_failed, version));
                if (manual) {
                    Toast.makeText(this, R.string.update_toast_failed, Toast.LENGTH_SHORT).show();
                }
                return;
            }

            if (info.versionCode > versionCode()) {
                pendingUpdate = info;
                updateBanner.setText(getString(R.string.update_ready, info.versionName));
                updateBanner.setVisibility(View.VISIBLE);
                updateBanner.setAlpha(0f);
                updateBanner.setScaleX(0.96f);
                updateBanner.setScaleY(0.96f);
                updateBanner.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
                versionRow.setText(getString(R.string.update_check, version));
                if (loggedUpdateCode != info.versionCode) {
                    loggedUpdateCode = info.versionCode;
                    log(getString(R.string.log_update_available, info.versionName));
                }
            } else {
                pendingUpdate = null;
                updateBanner.setVisibility(View.GONE);
                versionRow.setText(getString(R.string.update_latest, version));
                if (manual) {
                    Toast.makeText(this,
                            getString(R.string.update_toast_latest, version),
                            Toast.LENGTH_SHORT).show();
                }
            }
        }));
    }

    private void onUpdateBannerClick() {
        if (pendingUpdate == null || updateBusy) return;

        if (!UpdateChecker.canInstall(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.update_perm_title)
                    .setMessage(R.string.update_perm_text)
                    .setPositiveButton(R.string.update_open_settings,
                            (dialog, which) -> UpdateChecker.openInstallSettings(this))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }

        updateBusy = true;
        updateBanner.setText(R.string.update_downloading);

        UpdateChecker.download(this, pendingUpdate, (apk, error) -> ui.post(() -> {
            updateBusy = false;
            if (error != null) {
                updateBanner.setText(getString(R.string.update_ready, pendingUpdate.versionName));
                log(getString(R.string.log_update_error) + error);
                Toast.makeText(this,
                        getString(R.string.log_update_error) + error,
                        Toast.LENGTH_LONG).show();
                return;
            }

            log(getString(R.string.log_update_downloaded));
            updateBanner.setText(R.string.update_installing);
            if (!UpdateChecker.install(this, apk)) {
                Toast.makeText(this, R.string.update_toast_failed, Toast.LENGTH_LONG).show();
                updateBanner.setText(getString(R.string.update_ready, pendingUpdate.versionName));
            }
        }));
    }

    private int versionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (final Exception e) {
            return 0;
        }
    }

    private String versionName() {
        try {
            final PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "?" : info.versionName;
        } catch (final Exception e) {
            return "?";
        }
    }

    // ---------- отрисовка ----------

    private void refreshUi() {
        final int accent = Theme.accent(this);
        final boolean up = VpnManager.get(getApplicationContext()).isUp();
        final String mode = mode();
        final boolean warp = MODE_WARP.equals(mode);
        final boolean custom = MODE_CUSTOM.equals(mode);
        final boolean vless = !warp && !custom;

        sourceView.setText(warp ? getString(R.string.source_warp)
                : custom ? getString(R.string.source_custom)
                        : getString(R.string.source_vless_count, serverCount));
        warpChip.setBackground(Theme.chip(this, warp, accent));
        customChip.setBackground(Theme.chip(this, custom, accent));
        vlessChip.setBackground(Theme.chip(this, vless, accent));
        customChip.setText(getString(R.string.mode_custom_count, ConfigLibrary.all(this).size()));
        vlessChip.setText(getString(R.string.mode_vless_count, serverCount));
        updateBanner.setBackground(Theme.banner(this, accent));

        if (busy) {
            applyStatus(busyMessage != 0 ? busyMessage : R.string.status_working,
                    getColor(R.color.muted));
        } else if (up) {
            applyStatus(R.string.status_on, getColor(R.color.good));
        } else {
            applyStatus(R.string.status_off, getColor(R.color.text));
        }

        if (lastAccent != accent) {
            keyView.animateAccent(lastAccent, accent);
            lastAccent = accent;
        } else {
            keyView.setAccent(accent);
        }
        keyView.setConnected(up);
        keyView.setAlpha(busy ? 0.72f : 1f);
        Clicker.setVpnBonus(this, up);

        disconnectView.setVisibility(up ? View.VISIBLE : View.GONE);
        disconnectView.setAlpha(busy ? 0.5f : 1f);

        updateClicker(false);
        miniLog.setText(Logs.recent(this, 2));
        animateTabColors(accent);
        refreshStats();

        if (versionRow.getText().length() == 0) {
            versionRow.setText(getString(R.string.update_check, versionName()));
        }
    }

    private void applyStatus(final int textRes, final int color) {
        final String text = getString(textRes);
        if (text.equals(lastStatusText) && color == lastStatusColor) return;
        lastStatusText = text;
        lastStatusColor = color;

        statusView.animate().cancel();
        statusView.animate().alpha(0f).translationY(-Theme.dp(this, 4))
                .setDuration(100).withEndAction(() -> {
                    statusView.setText(textRes);
                    statusView.setTextColor(color);
                    statusView.setTranslationY(Theme.dp(this, 6));
                    statusView.animate().alpha(1f).translationY(0f).setDuration(200)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }).start();
    }

    private void setBusy(final boolean value, final int messageRes) {
        busy = value;
        busyMessage = messageRes;
        refreshUi();
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

    private static String describe(final Throwable e) {
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
