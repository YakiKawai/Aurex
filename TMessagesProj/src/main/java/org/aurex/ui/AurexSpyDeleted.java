package org.aurex.ui;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.List;

/**
 * Точка входа для врезок в {@code ChatActivity}.
 *
 * ЛОГИКИ ЗДЕСЬ БОЛЬШЕ НЕТ — всё переехало в {@link AurexChatFeed}, который
 * сводит в ленте чата все функции мода сразу (режим шпиона и локальные
 * подарки).
 *
 * ПОЧЕМУ КЛАСС НЕ ПЕРЕИМЕНОВАН И НЕ УДАЛЁН. На его имя ссылаются шесть
 * уже существующих врезок в {@code ChatActivity} (см. docs/PATCHES-SPY-CHAT.md).
 * Переименование означало бы правку шести мест в самом часто обновляемом
 * файле Telegram без единого выигрыша для пользователя, а каждая правка
 * апстрима — это будущий конфликт при обновлении на новую версию Telegram.
 *
 * Имена методов тоже сохранены дословно и по той же причине, хотя два из них
 * теперы шире своего названия (см. комментарии к ним).
 */
public final class AurexSpyDeleted {

    private AurexSpyDeleted() {
    }

    /** Врезка в {@code onFragmentCreate()}. */
    public static void onChatOpen(int accountId, long dialogId, NotificationCenter.NotificationCenterDelegate delegate) {
        AurexChatFeed.onChatOpen(accountId, dialogId, delegate);
    }

    /** Врезка в {@code onFragmentDestroy()}. */
    public static void onChatClose(int accountId, long dialogId, NotificationCenter.NotificationCenterDelegate delegate) {
        AurexChatFeed.onChatClose(accountId, dialogId, delegate);
    }

    /** Врезки в {@code messagesDidLoad} и {@code didReceivedNotification}. */
    public static boolean merge(int accountId, long dialogId, long topicId, List<MessageObject> messages) {
        return AurexChatFeed.merge(accountId, dialogId, topicId, messages);
    }

    /** Восстановлено ли сообщение режимом шпиона. */
    public static boolean isRestored(MessageObject object) {
        return AurexChatFeed.isRestored(object);
    }

    /** Врезка в {@code ChatActivityAdapter.onBindViewHolder}. */
    public static float alphaFor(MessageObject object) {
        return AurexChatFeed.alphaFor(object);
    }

    /** Врезка в {@code processSelectedOption}. */
    public static boolean blockAction(BaseFragment fragment, MessageObject object, int option) {
        return AurexChatFeed.blockAction(fragment, object, option);
    }

    /**
     * Требует ли уведомление пересборки ленты чата.
     *
     * Имя осталось с тех времён, когда такие уведомления приходили только об
     * удалённых сообщениях. Сейчас сюда же попадает изменение состава
     * локальных подарков: врезка в апстриме в обоих случаях делает ровно то,
     * что нужно — пересобирает ленту своего диалога.
     */
    public static boolean isDeletedNotification(int id) {
        return AurexChatFeed.isRebuildNotification(id);
    }

    /**
     * Наше ли это уведомление (апстриму его обрабатывать не надо).
     *
     * Как и выше, имя шире смысла: сюда входят все уведомления мода, а не
     * только события режима шпиона.
     */
    public static boolean isSpyNotification(int id) {
        return AurexChatFeed.isFeedNotification(id);
    }
}
