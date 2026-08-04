package org.aurex.ui;

import org.aurex.features.spy.SpyChatMerger;
import org.aurex.features.spy.SpyNotifications;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

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
     * Чат открыт: подписываемся на события модуля и начинаем учёт подмешанных
     * сообщений заново.
     */
    public static void onChatOpen(int accountId, long dialogId, NotificationCenter.NotificationCenterDelegate delegate) {
        try {
            SpyChatMerger.openSession(dialogId);
            final NotificationCenter center = NotificationCenter.getInstance(accountId);
            center.addObserver(delegate, SpyNotifications.MESSAGE_EDITED);
            center.addObserver(delegate, SpyNotifications.MESSAGES_DELETED);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Чат закрыт: снимаем подписку и освобождаем учёт. */
    public static void onChatClose(int accountId, long dialogId, NotificationCenter.NotificationCenterDelegate delegate) {
        try {
            final NotificationCenter center = NotificationCenter.getInstance(accountId);
            center.removeObserver(delegate, SpyNotifications.MESSAGE_EDITED);
            center.removeObserver(delegate, SpyNotifications.MESSAGES_DELETED);
            SpyChatMerger.closeSession(dialogId);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Подмешивает удалённые сообщения в список сообщений чата.
     *
     * Вызывается и для только что загруженной порции истории, и для живой
     * ленты, когда сообщение удалили при открытом чате. Всю дальнейшую
     * бухгалтерию (словари сообщений, группы, даты, верстка) делает штатный
     * код Telegram.
     *
     * @return true, если было добавлено хотя бы одно сообщение
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
     * Прозрачность сообщения в ленте: восстановленное рисуется приглушённым,
     * чтобы его было видно с первого взгляда.
     */
    public static float alphaFor(MessageObject object) {
        try {
            return isRestored(object) ? SpyChatMerger.RESTORED_ALPHA : 1f;
        } catch (Throwable e) {
            FileLog.e(e);
            return 1f;
        }
    }

    /**
     * Нужно ли заблокировать действие контекстного меню.
     *
     * Сообщения, восстановленные из локальной базы, на сервере не существуют.
     * Любое сетевое действие над ними (ответить, переслать, закрепить,
     * реакция, удалить) вернуло бы ошибку или сработало бы непредсказуемо,
     * поэтому вместо ошибки сервера пользователь видит понятное объяснение.
     *
     * @return true, если вызывающая сторона должна прервать обработку
     */
    public static boolean blockAction(BaseFragment fragment, MessageObject object, int option) {
        try {
            if (!isRestored(object) || isLocalOption(option)) {
                return false;
            }
            if (fragment != null) {
                BulletinFactory.of(fragment)
                        .createErrorBulletin(LocaleController.getString(R.string.AurexSpyDeletedActionUnavailable))
                        .show();
            }
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * Действия, которые полностью локальны и потому разрешены.
     */
    private static boolean isLocalOption(int option) {
        return option == ChatActivity.OPTION_COPY
                || option == ChatActivity.OPTION_SHARE
                || option == ChatActivity.OPTION_SAVE_TO_GALLERY
                || option == ChatActivity.OPTION_SAVE_TO_GALLERY2
                || option == ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC
                || option == ChatActivity.OPTION_TRANSLATE
                || option == AurexSpyChat.OPTION_SPY_HISTORY;
    }

    /** Уведомление о том, что модуль сохранил удалённые сообщения диалога. */
    public static boolean isDeletedNotification(int id) {
        return id == SpyNotifications.MESSAGES_DELETED;
    }

    /** Относится ли уведомление к модулю. */
    public static boolean isSpyNotification(int id) {
        return id == SpyNotifications.MESSAGE_EDITED || id == SpyNotifications.MESSAGES_DELETED;
    }
}
