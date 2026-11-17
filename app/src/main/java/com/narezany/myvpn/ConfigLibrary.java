package com.narezany.myvpn;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Небольшая библиотека конфигов WireGuard / AmneziaWG.
 * Хранится вместе с настройками приложения, чтобы конфиг не нужно было
 * искать по телефону каждый раз: выбрал имя из меню — и подключился.
 */
final class ConfigLibrary {

    static final class Entry {
        final String name;
        final String text;

        Entry(final String name, final String text) {
            this.name = name;
            this.text = text;
        }
    }

    private static final String PREFS = "vpn";
    private static final String KEY_LIST = "config_library";

    private ConfigLibrary() {
    }

    static List<Entry> all(final Context context) {
        final List<Entry> result = new ArrayList<>();
        final String raw = prefs(context).getString(KEY_LIST, "");
        if (raw.isEmpty()) return result;
        try {
            final JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                final JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                final String name = item.optString("name", "");
                final String text = item.optString("text", "");
                if (!name.isEmpty() && !text.isEmpty()) result.add(new Entry(name, text));
            }
        } catch (final Exception ignored) {
            // Библиотека пуста или повреждена — покажем пустой список.
        }
        return result;
    }

    static void add(final Context context, final String name, final String text) {
        final List<Entry> entries = all(context);
        entries.add(new Entry(name, text));
        save(context, entries);
    }

    static void remove(final Context context, final int index) {
        final List<Entry> entries = all(context);
        if (index < 0 || index >= entries.size()) return;
        entries.remove(index);
        save(context, entries);
    }

    private static void save(final Context context, final List<Entry> entries) {
        final JSONArray array = new JSONArray();
        try {
            for (final Entry entry : entries) {
                array.put(new JSONObject().put("name", entry.name).put("text", entry.text));
            }
            prefs(context).edit().putString(KEY_LIST, array.toString()).apply();
        } catch (final Exception ignored) {
            // Не должны оказаться здесь: строки всегда валидны для JSON.
        }
    }

    private static SharedPreferences prefs(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
