package com.narezany.myvpn;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Обновление без переустановки вручную: читает version.json из ветки updates,
 * скачивает APK и открывает системный установщик. Подпись постоянная, поэтому
 * новая версия ставится поверх старой.
 */
public final class UpdateChecker {

    private static final String VERSION_URL =
            "https://raw.githubusercontent.com/voa214814v2/MyVPN/updates/version.json";
    private static final String APK_FALLBACK =
            "https://raw.githubusercontent.com/voa214814v2/MyVPN/updates/myvpn.apk";
    private static final String AUTHORITY_SUFFIX = ".fileprovider";

    public interface InfoCallback {
        void onInfo(Info info, String error);
    }

    public interface ApkCallback {
        void onApk(File apk, String error);
    }

    public static final class Info {
        public final int versionCode;
        public final String versionName;
        public final String url;
        public final String notes;

        Info(final int versionCode, final String versionName,
             final String url, final String notes) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.url = url;
            this.notes = notes;
        }
    }

    private UpdateChecker() {
    }

    public static void check(final Context context, final InfoCallback callback) {
        new Thread(() -> {
            try {
                final String body = read(VERSION_URL + "?t=" + System.currentTimeMillis());
                final JSONObject json = new JSONObject(body);
                final int code = json.optInt("versionCode", 0);
                final String name = json.optString("versionName", "?");
                final String notes = json.optString("notes", "");
                String url = json.optString("url", "");
                if (url.trim().isEmpty()) url = APK_FALLBACK;
                if (code <= 0) throw new IllegalStateException("в version.json нет versionCode");
                callback.onInfo(new Info(code, name, url, notes), null);
            } catch (final Exception e) {
                callback.onInfo(null, describe(e));
            }
        }, "update-check").start();
    }

    public static void download(final Context context, final Info info,
                                final ApkCallback callback) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            HttpURLConnection connection = null;
            FileOutputStream out = null;
            try {
                final File dir = new File(app.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("не создать папку обновления");
                }
                final File target = new File(dir, "myvpn.apk");
                final File temp = new File(dir, "myvpn.apk.part");
                if (temp.exists()) temp.delete();

                connection = (HttpURLConnection) new URL(info.url).openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "MyVPN");
                connection.setRequestProperty("Cache-Control", "no-cache");

                final int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("HTTP " + code);
                }
                final long total = connection.getContentLengthLong();

                out = new FileOutputStream(temp);
                try (InputStream in = connection.getInputStream()) {
                    final byte[] buffer = new byte[16384];
                    long done = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        done += read;
                    }
                    out.flush();
                    if (done < 100 * 1024) {
                        throw new IllegalStateException("файл слишком маленький");
                    }
                    if (total > 0 && done != total) {
                        throw new IllegalStateException("скачалось не всё");
                    }
                } finally {
                    out.close();
                    out = null;
                }

                if (target.exists() && !target.delete()) {
                    throw new IllegalStateException("не заменить старый файл");
                }
                if (!temp.renameTo(target)) {
                    throw new IllegalStateException("не сохранить файл");
                }
                callback.onApk(target, null);
            } catch (final Exception e) {
                if (out != null) {
                    try {
                        out.close();
                    } catch (final Exception ignored) {
                        // ничего
                    }
                }
                callback.onApk(null, describe(e));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "update-download").start();
    }

    public static boolean canInstall(final Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || context.getPackageManager().canRequestPackageInstalls();
    }

    public static void openInstallSettings(final Context context) {
        final Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (final Exception ignored) {
            try {
                context.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (final Exception ignored2) {
                // на некоторых прошивках этого экрана нет
            }
        }
    }

    public static boolean install(final Context context, final File apk) {
        try {
            final Uri uri = FileProvider.getUriForFile(
                    context, context.getPackageName() + AUTHORITY_SUFFIX, apk);
            final Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setClipData(ClipData.newRawUri("apk", uri));
            context.startActivity(intent);
            return true;
        } catch (final Exception e) {
            return false;
        }
    }

    private static String read(final String address) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "MyVPN");
            connection.setRequestProperty("Cache-Control", "no-cache");

            final int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + code);
            }
            try (InputStream in = connection.getInputStream()) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return out.toString("UTF-8");
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String describe(final Exception e) {
        final String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
