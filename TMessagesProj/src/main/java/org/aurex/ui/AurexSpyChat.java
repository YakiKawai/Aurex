package org.aurex.ui;

import org.aurex.features.spy.SpyStorage;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;

import java.util.List;

/**
 * Фасад между экраном чата Telegram и модулем «Шпион».
 *
 * Врезки в чужой код — самое дорогое при обновлении форка, поэтому вся логика
 * собрана здесь, а в ChatActivity остаются только вызовы.
 * Тот же принцип уже использован в {@code AurexHooks} для режима призрака.
 *
 * Любая ошибка здесь гасится: сбой в функции мода не должен ломать штатное меню чата.
 */
public final class AurexSpyChat {

    /**
     * Код пункта контекстного меню «История правок».
     *
     * Собственные коды держим заведомо далеко от диапазона Telegram (OPTION_* заняты
     * до 115), чтобы новые пункты апстрима никогда с нами не пересеклись.
     */
    public static final int OPTION_SPY_HISTORY = 1338;

    private AurexSpyChat() {
    }

    /**
     * Есть ли у сообщения сохранённые версии — показывать ли пункт меню.
     *
     * Намеренно НЕ смотрим на переключатель «Сохранять историю правок»: если его выключить,
     * уже накопленная история должна оставаться доступной, а не пропадать из меню.
     *
     * Запрос идёт по индексу idx_spy_message_lookup с LIMIT 1, поэтому его допустимо
     * выполнять прямо при построении меню: меню открывается по действию пользователя и редко.
     */
    public static boolean hasRevisions(int accountId, MessageObject messageObject) {
        try {
            if (messageObject == null || messageObject.messageOwner == null) {
                return false;
            }
            int messageId = messageObject.getId();
            // Отрицательные и нулевые идентификаторы — локальные и отложенные сообщения.
            if (messageId <= 0) {
                return false;
            }
            long dialogId = messageObject.getDialogId();
            long userId = UserConfig.getInstance(accountId).getClientUserId();
            return SpyStorage.getInstance().hasRevisions(userId, dialogId, messageId);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * Добавляет в готовое меню сообщения пункт «История правок» — сразу под «Удалить».
     *
     * Почему вставка, а не добавление в нужном месте кода: пункты меню Telegram
     * собирает в десятке независимых ветвлений (личный чат, канал, отложенные,
     * избранное, предложения постов), и «Удалить» появляется в каждой из них по
     * своим правилам. Привязка врезки к одному из этих блоков означала бы, что в
     * остальных случаях пункт либо пропадёт, либо встанет не туда. Поэтому модуль
     * получает уже собранное меню и сам находит позицию «Удалить».
     *
     * Если «Удалить» в меню нет (например, чужое сообщение в канале без прав),
     * пункт становится последним — это ожидаемо и не ломает порядок.
     *
     * @param items   подписи пунктов
     * @param options коды пунктов
     * @param icons   иконки пунктов
     */
    public static void addHistoryItem(int accountId, MessageObject messageObject,
                                      List<? super String> items, List<Integer> options, List<Integer> icons) {
        try {
            if (items == null || options == null || icons == null) {
                return;
            }
            // Три списка индексируются синхронно: если их длины разошлись, вставка по
            // индексу перепутала бы подписи и действия. Такого быть не должно, но
            // молча ничего не делать безопаснее, чем сломать чужое меню.
            if (items.size() != options.size() || icons.size() != options.size()) {
                return;
            }
            // Меню может собираться повторно, пункт обязан остаться один.
            if (options.contains(Integer.valueOf(OPTION_SPY_HISTORY))) {
                return;
            }
            if (!hasRevisions(accountId, messageObject)) {
                return;
            }
            int deleteIndex = options.indexOf(Integer.valueOf(ChatActivity.OPTION_DELETE));
            int at = deleteIndex >= 0 ? deleteIndex + 1 : options.size();
            items.add(at, LocaleController.getString(R.string.AurexSpyHistoryTitle));
            options.add(at, Integer.valueOf(OPTION_SPY_HISTORY));
            icons.add(at, Integer.valueOf(R.drawable.msg_edit));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Открывает экран сохранённых версий сообщения. */
    public static void openHistory(BaseFragment fragment, MessageObject messageObject) {
        try {
            if (fragment == null || messageObject == null) {
                return;
            }
            fragment.presentFragment(new AurexSpyHistoryActivity(messageObject));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
