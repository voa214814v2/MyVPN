package com.narezany.myvpn;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Прогресс кликера: ключи, уровни, звания, бусты и улучшения.
 * Всё хранится на телефоне: очки за тапы, баланс для покупок,
 * постоянный множитель, автокликер и временные бусты.
 */
public final class Clicker {

    public static final int PER_LEVEL = 25;

    public static final int BOOST_SMALL = 2;   // x2 на 30 секунд
    public static final int BOOST_BIG = 3;     // x3 на 60 секунд
    public static final int BOOST_MEGA = 5;    // x5 на 20 секунд

    private static final String PREFS = "clicker";
    private static final String KEY_TOTAL = "total";
    private static final String KEY_BALANCE = "balance";
    private static final String KEY_MULT = "mult_level";
    private static final String KEY_AUTO = "auto_level";
    private static final String KEY_BOOST_UNTIL = "boost_until";
    private static final String KEY_BOOST_MULT = "boost_mult";
    private static final String KEY_VPN_BONUS = "vpn_bonus";

    private static final String[] RANKS = {
            "Новичок", "Любитель", "Знаток", "Мастер", "Гуру", "Легенда"
    };

    private Clicker() {
    }

    /** Результат одного начисления — его показываем всплывающей цифрой. */
    public static final class Tap {
        public final int gained;
        public final int total;
        public final int balance;
        public final int level;
        public final boolean levelUp;
        public final int multiplier;

        Tap(final int gained, final int total, final int balance,
            final int level, final boolean levelUp, final int multiplier) {
            this.gained = gained;
            this.total = total;
            this.balance = balance;
            this.level = level;
            this.levelUp = levelUp;
            this.multiplier = multiplier;
        }
    }

    private static SharedPreferences prefs(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Всего заработано ключей (уровни считаются от этой цифры). */
    public static int clicks(final Context context) {
        return prefs(context).getInt(KEY_TOTAL, 0);
    }

    /** Ключи, которые можно потратить на бусты и улучшения. */
    public static int balance(final Context context) {
        return prefs(context).getInt(KEY_BALANCE, 0);
    }

    public static int multiplierLevel(final Context context) {
        return prefs(context).getInt(KEY_MULT, 0);
    }

    public static int autoLevel(final Context context) {
        return prefs(context).getInt(KEY_AUTO, 0);
    }

    public static void setVpnBonus(final Context context, final boolean up) {
        prefs(context).edit().putBoolean(KEY_VPN_BONUS, up).apply();
    }

    /** Множитель от постоянных улучшений. */
    public static int baseMultiplier(final Context context) {
        return 1 + multiplierLevel(context);
    }

    /** Оставшийся множитель временного буста (1, если буст закончился). */
    public static int boostMultiplier(final Context context) {
        final SharedPreferences p = prefs(context);
        final long until = p.getLong(KEY_BOOST_UNTIL, 0L);
        if (until <= System.currentTimeMillis()) return 1;
        return Math.max(1, p.getInt(KEY_BOOST_MULT, 1));
    }

    public static int boostSecondsLeft(final Context context) {
        final long left = prefs(context).getLong(KEY_BOOST_UNTIL, 0L) - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) (left / 1000L) + 1;
    }

    /** Общий множитель: улучшения × буст × бонус за включённый VPN. */
    public static int multiplier(final Context context) {
        int value = baseMultiplier(context) * boostMultiplier(context);
        if (prefs(context).getBoolean(KEY_VPN_BONUS, false)) value *= 2;
        return Math.min(value, 100);
    }

    /** Ключей в секунду от автокликера (без учёта множителя). */
    public static int autoPerSecond(final Context context) {
        return autoLevel(context);
    }

    /** Начисляет ключи и возвращает подробности для анимации. */
    public static Tap add(final Context context, final int amount) {
        if (amount <= 0) return snapshot(context, 0);
        final SharedPreferences p = prefs(context);
        final int oldTotal = p.getInt(KEY_TOTAL, 0);
        final int gained = amount * multiplier(context);
        final int total = oldTotal + gained;
        final int balance = p.getInt(KEY_BALANCE, 0) + gained;
        p.edit().putInt(KEY_TOTAL, total).putInt(KEY_BALANCE, balance).apply();
        return new Tap(gained, total, balance, level(total),
                level(total) > level(oldTotal), multiplier(context));
    }

    /** Один тик автокликера. */
    public static Tap tick(final Context context) {
        return add(context, autoPerSecond(context));
    }

    public static int priceBoost(final int kind) {
        switch (kind) {
            case BOOST_BIG:
                return 100;
            case BOOST_MEGA:
                return 180;
            default:
                return 40;
        }
    }

    public static boolean vpnBonus(final Context context) {
        return prefs(context).getBoolean(KEY_VPN_BONUS, false);
    }

    public static int boostDuration(final int kind) {
        switch (kind) {
            case BOOST_BIG:
                return 60;
            case BOOST_MEGA:
                return 20;
            default:
                return 30;
        }
    }

    public static int priceMultiplier(final Context context) {
        return 60 * (multiplierLevel(context) + 1);
    }

    public static int priceAuto(final Context context) {
        return 80 * (autoLevel(context) + 1);
    }

    public static boolean buyBoost(final Context context, final int kind) {
        final SharedPreferences p = prefs(context);
        final int price = priceBoost(kind);
        if (p.getInt(KEY_BALANCE, 0) < price) return false;

        final long until = Math.max(p.getLong(KEY_BOOST_UNTIL, 0L), System.currentTimeMillis());
        p.edit()
                .putInt(KEY_BALANCE, p.getInt(KEY_BALANCE, 0) - price)
                .putLong(KEY_BOOST_UNTIL, until + boostDuration(kind) * 1000L)
                .putInt(KEY_BOOST_MULT, Math.max(p.getInt(KEY_BOOST_MULT, 1), kind))
                .apply();
        return true;
    }

    public static boolean buyMultiplier(final Context context) {
        final SharedPreferences p = prefs(context);
        final int price = priceMultiplier(context);
        if (p.getInt(KEY_BALANCE, 0) < price) return false;
        p.edit()
                .putInt(KEY_BALANCE, p.getInt(KEY_BALANCE, 0) - price)
                .putInt(KEY_MULT, multiplierLevel(context) + 1)
                .apply();
        return true;
    }

    public static boolean buyAuto(final Context context) {
        final SharedPreferences p = prefs(context);
        final int price = priceAuto(context);
        if (p.getInt(KEY_BALANCE, 0) < price) return false;
        p.edit()
                .putInt(KEY_BALANCE, p.getInt(KEY_BALANCE, 0) - price)
                .putInt(KEY_AUTO, autoLevel(context) + 1)
                .apply();
        return true;
    }

    public static int level(final int clicks) {
        return clicks / PER_LEVEL + 1;
    }

    /** Сколько ключей набрано внутри текущего уровня. */
    public static int inLevel(final int clicks) {
        return clicks % PER_LEVEL;
    }

    public static int levelStart(final int clicks) {
        return clicks - clicks % PER_LEVEL;
    }

    public static String rank(final int level) {
        final int index = Math.min(RANKS.length - 1, Math.max(0, level - 1));
        return RANKS[index];
    }

    private static Tap snapshot(final Context context, final int gained) {
        final int total = clicks(context);
        return new Tap(gained, total, balance(context), level(total), false, multiplier(context));
    }
}
