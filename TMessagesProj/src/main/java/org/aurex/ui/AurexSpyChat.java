package org.aurex.ui;

import org.aurex.features.spy.SpyStorage;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;

/**
 * Фасад между экраном чата Telegram и модулем «Шпион».
 *
 * Врезки в чужой код — самое дорогое при обновлении форка, поэтому вся логика
 * собрана здесь, а в ChatActivity останется два вызова: проверка условия и действие.
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
