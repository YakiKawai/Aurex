package org.aurex.core;

import org.aurex.features.ghost.GhostRequestFilter;
import org.aurex.features.spy.SpyUpdatesObserver;
import org.aurex.ui.AurexSettingsActivity;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * Единственный мост между кодом официального Telegram и кодом мода.
 *
 * Правило проекта: любая врезка в файлы апстрима вызывает ТОЛЬКО методы этого
 * класса и не содержит никакой логики. Все методы здесь обязаны быть
 * абсолютно отказоустойчивыми: ошибка в коде мода не имеет права уронить клиент.
 *
 * Полный список врезок ведётся в docs/PATCHES.md.
 */
public final class AurexHooks {

    /**
     * ID пункта "Aurex" в списке настроек приложения.
     * Апстрим использует небольшие id (1..23), поэтому мод занимает диапазон от 1000.
     */
    public static final int SETTINGS_ITEM_ID = 1000;

    private AurexHooks() {
    }

    /** Открывает корневой экран настроек мода. */
    public static void openSettings(BaseFragment fragment) {
        if (fragment == null) {
            return;
        }
        fragment.presentFragment(new AurexSettingsActivity());
    }

    /**
     * Фильтр исходящих запросов (режим призрака).
     *
     * Вызывается из ConnectionsManager на каждый запрос, поэтому любое исключение
     * здесь гасится: в худшем случае запрос уйдёт как в обычном Telegram.
     *
     * @return true, если запрос отправлять не нужно.
     */
    public static boolean shouldDropRequest(int accountId, TLObject request) {
        try {
            return GhostRequestFilter.shouldDrop(accountId, request);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Поток апдейтов от сервера (режим шпиона).
     *
     * Вызывается до того, как Telegram применит обновление, поэтому удаляемое
     * или редактируемое сообщение ещё доступно в штатном кэше клиента.
     * Сам апдейт никак не модифицируется.
     */
    public static void onUpdatesReceived(int accountId, TLObject updates) {
        try {
            if (updates instanceof TLRPC.Updates) {
                SpyUpdatesObserver.onUpdates(accountId, (TLRPC.Updates) updates);
            }
        } catch (Throwable ignored) {
        }
    }
}
