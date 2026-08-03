package org.aurex.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Единое хранилище настроек мода Aurex.
 *
 * ВАЖНО: мод НИКОГДА не пишет в SharedPreferences Telegram (mainconfig, userconfig и т.д.).
 * Все наши настройки живут в отдельном файле "aurex", поэтому обновление апстрима
 * физически не может сломать наши данные, а наши данные не могут сломать Telegram.
 */
public final class AurexConfig {

    public static final String PREFS_NAME = "aurex";

    private static volatile SharedPreferences prefs;
    private static final List<ChangeListener> listeners = new ArrayList<>();

    private AurexConfig() {
    }

    public interface ChangeListener {
        void onAurexConfigChanged(String key);
    }

    public static SharedPreferences prefs() {
        SharedPreferences local = prefs;
        if (local == null) {
            synchronized (AurexConfig.class) {
                local = prefs;
                if (local == null) {
                    Context context = ApplicationLoader.applicationContext;
                    local = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    prefs = local;
                }
            }
        }
        return local;
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        return prefs().getBoolean(key, defaultValue);
    }

    public static void putBoolean(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
        notifyChanged(key);
    }

    public static int getInt(String key, int defaultValue) {
        return prefs().getInt(key, defaultValue);
    }

    public static void putInt(String key, int value) {
        prefs().edit().putInt(key, value).apply();
        notifyChanged(key);
    }

    public static long getLong(String key, long defaultValue) {
        return prefs().getLong(key, defaultValue);
    }

    public static void putLong(String key, long value) {
        prefs().edit().putLong(key, value).apply();
        notifyChanged(key);
    }

    public static String getString(String key, String defaultValue) {
        return prefs().getString(key, defaultValue);
    }

    public static void putString(String key, String value) {
        prefs().edit().putString(key, value).apply();
        notifyChanged(key);
    }

    public static void remove(String key) {
        prefs().edit().remove(key).apply();
        notifyChanged(key);
    }

    /** Сброс всех настроек мода к значениям по умолчанию. Настройки Telegram не трогаются. */
    public static void resetAll() {
        prefs().edit().clear().apply();
        notifyChanged(null);
    }

    public static void addListener(ChangeListener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public static void removeListener(ChangeListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private static void notifyChanged(String key) {
        List<ChangeListener> copy;
        synchronized (listeners) {
            copy = new ArrayList<>(listeners);
        }
        for (int i = 0; i < copy.size(); i++) {
            copy.get(i).onAurexConfigChanged(key);
        }
    }
}
