package org.aurex.ui;

import org.aurex.features.spy.SpyChatMerger;
import org.aurex.features.spy.SpyNotifications;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;

import java.util.List;

/**
 * Фасад «Режима шпиона» для ленты чата.
 *
 * Единственная точка, к которой обращаются врезки в {@code ChatActivity}.
 * Все методы безопасны: любая внутренняя ошибка логируется и проглатывается,
 * поведение штатного клиента при этом не меняется.
 */
public final class AurexSpyDeleted {

    private AurexSpyDeleted() {
    }

    /**
     * Подмешивает удалённые сообщения в загруженную порцию истории.
     *
     * @return true, если лента изменилась и её нужно перерисовать
     */
    public static boolean merge(int accountId, long dialogId, long topicId, List<MessageObject> messages) {
        try {
            return SpyChatMerger.merge(accountId, dialogId, topicId, messages) > 0;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * Восстановлено ли сообщение модулем (на сервере его больше нет).
     */
    public static boolean isRestored(MessageObject object) {
        try {
            return SpyChatMerger.isRestored(object);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * Подписка чата на события модуля: сохранена правка / сохранены удалённые.
     */
    public static void addObservers(int accountId, NotificationCenter.NotificationCenterDelegate delegate) {
        try {
            final NotificationCenter center = NotificationCenter.getInstance(accountId);
            center.addObserver(delegate, SpyNotifications.MESSAGE_EDITED);
            center.addObserver(delegate, SpyNotifications.MESSAGES_DELETED);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void removeObservers(int accountId, NotificationCenter.NotificationCenterDelegate delegate) {
        try {
            final NotificationCenter center = NotificationCenter.getInstance(accountId);
            center.removeObserver(delegate, SpyNotifications.MESSAGE_EDITED);
            center.removeObserver(delegate, SpyNotifications.MESSAGES_DELETED);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Относится ли уведомление к модулю. */
    public static boolean isSpyNotification(int id) {
        return id == SpyNotifications.MESSAGE_EDITED || id == SpyNotifications.MESSAGES_DELETED;
    }
}
