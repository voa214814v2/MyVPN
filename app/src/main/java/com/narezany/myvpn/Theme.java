package com.narezany.myvpn;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

/** Пресеты цвета и генерация drawable под выбранный акцент. */
public final class Theme {

    public static final int[] PRESETS = {
            0xFF7C5CFF, 0xFF4E7BFF, 0xFF00C2D1, 0xFF3DDC97,
            0xFFB6E64A, 0xFFFF8A3D, 0xFFFF5C8A, 0xFFE5484D
    };

    public static final String[] NAMES = {
            "Фиолетовый", "Синий", "Голубой", "Зелёный",
            "Лаймовый", "Оранжевый", "Розовый", "Красный"
    };

    private static final String PREFS = "vpn";
    private static final String KEY = "accent";

    private Theme() {
    }

    public static int accent(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, PRESETS[0]);
    }

    public static void setAccent(final Context context, final int color) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY, color).apply();
    }

    public static String nameOf(final int color) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i] == color) return NAMES[i];
        }
        return "Свой";
    }

    public static GradientDrawable circle(final Context context, final int color) {
        final GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{blend(color, 0xFFFFFFFF, 0.18f), blend(color, 0xFF000000, 0.12f)});
        drawable.setShape(GradientDrawable.OVAL);
        return drawable;
    }

    public static GradientDrawable chip(final Context context, final boolean selected, final int accent) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(context, 12));
        drawable.setColor(selected ? blend(accent, 0xFF000000, 0.76f) : 0xFF1B1F27);
        drawable.setStroke(dp(context, 1), selected ? accent : 0xFF232733);
        return drawable;
    }

    public static GradientDrawable banner(final Context context, final int accent) {
        final GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{blend(accent, 0xFF000000, 0.55f), blend(accent, 0xFF000000, 0.82f)});
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(context, 14));
        drawable.setStroke(dp(context, 1), blend(accent, 0xFFFFFFFF, 0.25f));
        return drawable;
    }

    public static int dp(final Context context, final int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }

    public static int blend(final int from, final int to, final float amount) {
        final int fr = (from >> 16) & 0xFF;
        final int fg = (from >> 8) & 0xFF;
        final int fb = from & 0xFF;
        final int tr = (to >> 16) & 0xFF;
        final int tg = (to >> 8) & 0xFF;
        final int tb = to & 0xFF;
        final int r = (int) (fr + (tr - fr) * amount);
        final int g = (int) (fg + (tg - fg) * amount);
        final int b = (int) (fb + (tb - fb) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
