package com.narezany.myvpn;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Логи приложения: в памяти и в файле, чтобы пережить перезапуск. */
public final class Logs {

    private static final String FILE_NAME = "vpn.log";
    private static final int MAX_LINES = 300;

    private static final List<String> MEMORY = new ArrayList<>();
    private static boolean loaded;

    private Logs() {
    }

    public static synchronized void add(final Context context, final String message) {
        ensureLoaded(context);
        final String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        MEMORY.add(time + "  " + message);
        trim();
        save(context);
    }

    public static synchronized String text(final Context context) {
        ensureLoaded(context);
        final StringBuilder sb = new StringBuilder();
        for (final String line : MEMORY) sb.append(line).append('\n');
        return sb.toString().trim();
    }

    public static synchronized String recent(final Context context, final int count) {
        ensureLoaded(context);
        final StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, MEMORY.size() - count); i < MEMORY.size(); i++) {
            sb.append(MEMORY.get(i)).append('\n');
        }
        return sb.toString().trim();
    }

    public static synchronized void clear(final Context context) {
        MEMORY.clear();
        loaded = true;
        final File file = new File(context.getFilesDir(), FILE_NAME);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static void ensureLoaded(final Context context) {
        if (loaded) return;
        loaded = true;
        final File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) MEMORY.add(line);
            trim();
        } catch (final Exception ignored) {
            // битый файл — начинаем заново
        }
    }

    private static void save(final Context context) {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(new File(context.getFilesDir(), FILE_NAME)), "UTF-8")) {
            for (final String line : MEMORY) writer.write(line + "\n");
        } catch (final Exception ignored) {
            // не смогли сохранить — не критично
        }
    }

    private static void trim() {
        while (MEMORY.size() > MAX_LINES) MEMORY.remove(0);
    }
}
