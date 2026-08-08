package org.aurex.core;

import android.content.Context;

import org.aurex.features.paid.LocalPremium;
import org.aurex.features.paid.PaidReactions;
import org.aurex.features.spy.SpyUpdatesObserver;
import org.aurex.ui.AurexSettingsActivity;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.List;

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
     * Фильтр исходящих запросов (режим призрака и другие сетевые страховки).
     *
     * Вызывается из ConnectionsManager на каждый запрос, поэтому любое исключение
     * здесь гасится: в худшем случае запрос уйдёт как в обычном Telegram.
     *
     * @return true, если запрос отправлять не нужно.
     */
    public static boolean shouldDropRequest(int accountId, TLObject request) {
        try {
            return AurexRequestFilter.shouldDrop(accountId, request);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Блокировка платной реакции в точке нажатия (функция "Заблокировать реакции за звёзды").
     *
     * Вызывается ДО любых проверок баланса и до открытия оплаты. Если функция включена —
     * показывает предупреждение и возвращает true; вызывающий код обязан немедленно выйти.
     *
     * @return true, если действие нужно прервать.
     */
    public static boolean blockPaidReaction(Context context, Theme.ResourcesProvider resourcesProvider) {
        try {
            return PaidReactions.block(context, resourcesProvider);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Тихая проверка той же функции — без диалога.
     *
     * Нужна для страховочных точек в глубине апстрима, куда управление доходить не должно:
     * предупреждение там уже показано выше по стеку, а дублировать его нельзя.
     */
    public static boolean isPaidReactionBlocked() {
        try {
            return PaidReactions.isBlocked();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Включён ли локальный Telegram Premium (функция "Локальный Premium").
     *
     * Врезка в UserConfig.isPremium(): отвечает на вопрос "премиум ли текущий аккаунт".
     * Метод находится на очень горячем пути (вызывается при отрисовке списков),
     * поэтому внутри — только чтение кэшированного значения настройки.
     */
    public static boolean isLocalPremium() {
        try {
            return LocalPremium.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Считать ли конкретного пользователя премиумом из-за локального Premium.
     *
     * Врезка в MessagesController.isPremiumUser(User): часть экранов спрашивает не про
     * аккаунт, а про конкретного пользователя. Возвращает true только для себя.
     */
    public static boolean isLocalPremiumUser(int accountId, TLRPC.User user) {
        try {
            return LocalPremium.isLocalPremiumUser(accountId, user);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Апдейты, принятые из сети (режим шпиона).
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

    /**
     * Единая воронка апдейтов клиента (режим шпиона).
     *
     * В отличие от {@link #onUpdatesReceived}, видит не только push из сокета, но и
     * апдейты из ответов на собственные запросы и из difference — то есть всё, что
     * произошло, пока приложение было закрыто. Именно поэтому точки перехвата две.
     *
     * Метод находится на горячем пути и вызывается на каждый пакет апдейтов:
     * ранний выход по выключенным настройкам делает модуль, здесь только мост.
     */
    public static void onUpdateArray(int accountId, List<TLRPC.Update> updates) {
        try {
            SpyUpdatesObserver.onUpdateArray(accountId, updates);
        } catch (Throwable ignored) {
        }
    }
}
