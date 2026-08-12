package org.aurex.ui;

import org.aurex.core.AurexNotifications;
import org.aurex.features.paid.LocalGiftsFeed;
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
 * Общая лента чата для всех функций мода, которые добавляют в чат собственные
 * сообщения.
 *
 * ПОЧЕМУ ЭТОТ КЛАСС ПОЯВИЛСЯ. Сначала такая функция была одна (восстановление
 * удалённых сообщений), и врезки в {@code ChatActivity} звали её фасад
 * {@link AurexSpyDeleted} напрямую. Локальные подарки — вторая такая функция, и
 * варианты были такие: либо добавить в апстрим второй комплект тех же врезок,
 * либо свести все функции в одной точке внутри кода мода. Выбран второй
 * вариант: чем меньше врезок в {@code ChatActivity}, тем дешевле обновление до
 * новой версии Telegram — это самый часто меняющийся файл апстрима.
 *
 * Итог: врезки в апстриме не изменились вообще — ни одной новой строки в
 * {@code ChatActivity} ради локальных подарков добавлять не потребовалось.
 * {@link AurexSpyDeleted} остался точкой входа для апстрима и теперь только
 * делегирует вызовы сюда.
 *
 * Все методы безопасны: ошибка одной функции не ломает ни вторую, ни штатное
 * поведение чата.
 */
public final class AurexChatFeed {

    private AurexChatFeed() {
    }

    /**
     * Чат открыт: подписываемся на события всех функций и начинаем учёт
     * подмешанных сообщений заново.
     */
    public static void onChatOpen(int accountId, long dialogId, NotificationCenter.NotificationCenterDelegate delegate) {
        try {
            SpyChatMerger.openSession(dialogId);
            final NotificationCenter center = NotificationCenter.getInstance(accountId);
            center.addObserver(delegate, SpyNotifications.MESSAGE_EDITED);
            center.addObserver(delegate, SpyNotifications.MESSAGES_DELETED);
            center.addObserver(delegate, AurexNotifications.LOCAL_GIFTS_CHANGED);
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
            center.removeObserver(delegate, AurexNotifications.LOCAL_GIFTS_CHANGED);
            SpyChatMerger.closeSession(dialogId);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Дополняет ленту сообщениями мода и убирает те, которые уже не должны
     * там быть (функцию выключили при открытом чате).
     *
     * Каждая функция обрабатывается в своём try: если одна упадёт, вторая всё
     * равно отработает.
     *
     * @return true, если состав ленты изменился и её надо перерисовать
     */
    public static boolean merge(int accountId, long dialogId, long topicId, List<MessageObject> messages) {
        boolean changed = false;
        try {
            changed = SpyChatMerger.merge(accountId, dialogId, topicId, messages) > 0;
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            // Отличается от режима шпиона: тут лента может не только дополниться, но и
            // похудеть — поэтому сравнение с нулём по модулю, а не «больше нуля».
            changed |= LocalGiftsFeed.merge(accountId, dialogId, topicId, messages) != 0;
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return changed;
    }

    /**
     * Наше ли это уведомление.
     *
     * Апстриму они не нужны, поэтому врезка в {@code didReceivedNotification}
     * обрабатывает своё и выходит, не прогоняя его через ветки Telegram.
     */
    public static boolean isFeedNotification(int id) {
        return id == SpyNotifications.MESSAGE_EDITED
                || id == SpyNotifications.MESSAGES_DELETED
                || id == AurexNotifications.LOCAL_GIFTS_CHANGED;
    }

    /**
     * Требует ли уведомление пересборки ленты.
     *
     * Первым аргументом такие уведомления обязаны нести {@code Long dialogId}:
     * врезка в чате проверяет его и реагирует только на свой диалог.
     *
     * Правка сообщения сюда не входит сознательно: новая версия текста уже
     * пришла через штатный апдейт Telegram, а мод лишь сохраняет старую.
     */
    public static boolean isRebuildNotification(int id) {
        return id == SpyNotifications.MESSAGES_DELETED
                || id == AurexNotifications.LOCAL_GIFTS_CHANGED;
    }

    /** Восстановлено ли сообщение режимом шпиона (на сервере его больше нет). */
    public static boolean isRestored(MessageObject object) {
        try {
            return SpyChatMerger.isRestored(object);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Локальный ли это подарок: на сервере такого сообщения не существует. */
    public static boolean isLocalGift(MessageObject object) {
        try {
            return LocalGiftsFeed.isLocal(object);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * Прозрачность сообщения в ленте.
     *
     * Восстановленное сообщение рисуется приглушённым — пользователь должен
     * сразу видеть, что его удалили. Локальный подарок, наоборот, по требованию
     * функции должен выглядеть как настоящий, поэтому рисуется без изменений.
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
     * Сообщения мода на сервере не существуют, поэтому любое сетевое действие
     * над ними (ответить, переслать, закрепить, реакция, удалить) вернуло бы
     * ошибку сервера либо сработало непредсказуемо. Вместо этого пользователь
     * видит понятное объяснение в штатном bulletin.
     *
     * Для локального подарка это ещё и защита кошелька: действия над подарком
     * в апстриме могут вести к операциям со звёздами (улучшение, продажа,
     * конвертация), а у локального подарка нет ни владельца на сервере, ни
     * реальной стоимости.
     *
     * @return true, если вызывающая сторона должна прервать обработку
     */
    public static boolean blockAction(BaseFragment fragment, MessageObject object, int option) {
        try {
            final boolean restored = isRestored(object);
            final boolean localGift = isLocalGift(object);
            if (!restored && !localGift) {
                return false;
            }
            if (isLocalOption(option)) {
                return false;
            }
            if (fragment != null) {
                final int textRes = localGift
                        ? R.string.AurexLocalGiftsLocalOnly
                        : R.string.AurexSpyDeletedActionUnavailable;
                BulletinFactory.of(fragment)
                        .createErrorBulletin(LocaleController.getString(textRes))
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
}
