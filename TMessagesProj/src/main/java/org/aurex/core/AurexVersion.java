package org.aurex.core;

/**
 * Версия мода и версия базового Telegram, на которой он собран.
 *
 * BASE_TELEGRAM обновляется вручную при каждом мерже апстрима — это делает
 * очевидным, на какой версии официального клиента основана сборка.
 */
public final class AurexVersion {

    public static final String MOD_NAME = "Aurex";
    public static final String MOD_VERSION = "0.1.0";
    public static final String BASE_TELEGRAM = "12.9.2";

    private AurexVersion() {
    }

    /** Например: "Aurex 0.1.0 (Telegram 12.9.2)". */
    public static String getFullVersion() {
        return MOD_NAME + " " + MOD_VERSION + " (Telegram " + BASE_TELEGRAM + ")";
    }
}
