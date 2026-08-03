package org.aurex.features.spy;

/**
 * Идентификаторы событий модуля в NotificationCenter.
 *
 * NotificationCenter хранит наблюдателей в SparseArray, поэтому собственные идентификаторы
 * работают без правок в апстриме — достаточно взять заведомо свободный диапазон.
 * Тот же приём использует AyuGram (AyuConstants).
 */
public final class SpyNotifications {

    /** Аргументы: long dialogId, int messageId. */
    public static final int MESSAGE_EDITED = 7968;
    /** Аргументы: long dialogId. */
    public static final int MESSAGES_DELETED = 7969;

    private SpyNotifications() {
    }
}
