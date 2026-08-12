package org.aurex.core;

/**
 * Идентификаторы событий мода в NotificationCenter, не привязанные к одной функции.
 *
 * NotificationCenter хранит наблюдателей в SparseArray, поэтому собственные
 * идентификаторы работают без правок апстрима — достаточно взять заведомо
 * свободный диапазон. Режим шпиона занял 7968–7969
 * ({@code org.aurex.features.spy.SpyNotifications}), поэтому нумерация
 * продолжается с 7970.
 */
public final class AurexNotifications {

    /**
     * Список локальных подарков изменился.
     *
     * Аргументы: {@code long dialogId} — диалог, которого касается изменение,
     * либо 0, если затронуты все диалоги (например, функцию выключили).
     */
    public static final int LOCAL_GIFTS_CHANGED = 7970;

    private AurexNotifications() {
    }
}
