package com.narezany.myvpn;

import android.content.Context;

/** Прогресс кликера: ключи, уровни и звания. Всё хранится на телефоне. */
public final class Clicker {

    public static final int PER_LEVEL = 25;

    private static final String PREFS = "clicker";
    private static final String KEY_CLICKS = "clicks";

    private static final String[] RANKS = {
            "Новичок", "Любитель", "Знаток", "Мастер", "Гуру", "Легенда"
    };

    private Clicker() {
    }

    public static int clicks(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_CLICKS, 0);
    }

    /** Добавляет один ключ и возвращает новое количество. */
    public static int add(final Context context) {
        final int next = clicks(context) + 1;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_CLICKS, next).apply();
        return next;
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
}
