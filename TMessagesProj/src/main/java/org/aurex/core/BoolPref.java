package org.aurex.core;

/**
 * Типизированная обёртка над одной булевой настройкой мода.
 *
 * Код фич никогда не работает со строковыми ключами напрямую — только через
 * константы из {@link AurexFeatures}. Это исключает опечатки в ключах и даёт
 * одно место, где видно все настройки мода.
 */
public final class BoolPref {

    public final String key;
    public final boolean defaultValue;

    public BoolPref(String key, boolean defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    public boolean get() {
        return AurexConfig.getBoolean(key, defaultValue);
    }

    public void set(boolean value) {
        AurexConfig.putBoolean(key, value);
    }

    public boolean toggle() {
        boolean value = !get();
        set(value);
        return value;
    }

    public void reset() {
        AurexConfig.remove(key);
    }
}
