package org.aurex.features.paid;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Показ локальных подарков в ленте чата.
 *
 * ПОЧЕМУ ИМЕННО ТАК. Подарок в Telegram — это не сообщение с картинкой, а
 * служебное сообщение с действием {@code messageActionStarGift}, которое рисует
 * штатная ячейка {@code ChatActionCell} (карточка, анимация, цена, кнопка).
 * Ровно такое сообщение апстрим собирает сам, без сервера, когда показывает
 * предпросмотр подарка в {@code SendGiftSheet}. Мод переиспользует этот приём:
 * собственной вёрстки подарка у нас нет вообще, а значит локальный подарок
 * выглядит и анимируется точно как настоящий.
 *
 * ПОЧЕМУ НЕ ПИШЕМ В БАЗУ TELEGRAM. Запись в {@code messages_v2} означала бы
 * борьбу за id с сервером и риск, что локальная запись переживёт функцию и
 * попадёт в синхронизацию. Лента дополняется на лету — так же, как это делает
 * режим шпиона для удалённых сообщений, — а источником правды остаётся своё
 * изолированное хранилище.
 */
public final class LocalGiftsFeed {

    /**
     * Объекты, созданные модом.
     *
     * Слабые ключи: как только лента отпускает сообщение, запись исчезает сама.
     */
    private static final Set<MessageObject> LOCAL =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<MessageObject, Boolean>()));

    private LocalGiftsFeed() {
    }

    /**
     * Дополняет ленту чата локальными подарками, а при выключенной функции —
     * наоборот, убирает их из неё.
     *
     * @param messages лента чата (новые сообщения в начале списка)
     * @return сколько записей добавлено или удалено
     */
    public static int merge(int accountId, long dialogId, long topicId, List<MessageObject> messages) {
        if (messages == null) {
            return 0;
        }
        try {
            if (!LocalGifts.isEnabled()) {
                // Функцию выключили при открытом чате: подарки должны исчезнуть
                // сразу, не дожидаясь перезахода.
                return purge(messages);
            }
            final List<LocalGiftsStore.Entry> entries = LocalGiftsStore.list(accountId);
            if (entries.isEmpty()) {
                return 0;
            }
            // Тема/комментарии: подарок отправляется в диалог целиком, в ветках
            // обсуждения ему не место.
            if (topicId != 0) {
                return 0;
            }

            final HashSet<Integer> present = new HashSet<>();
            for (int a = 0, N = messages.size(); a < N; a++) {
                final MessageObject object = messages.get(a);
                if (object != null && object.messageOwner != null) {
                    present.add(object.getId());
                }
            }

            int added = 0;
            for (int a = 0, N = entries.size(); a < N; a++) {
                final LocalGiftsStore.Entry entry = entries.get(a);
                if (entry == null || entry.dialogId != dialogId || present.contains(entry.messageId)) {
                    continue;
                }
                final MessageObject object = build(accountId, entry);
                if (object == null) {
                    continue;
                }
                present.add(entry.messageId);
                insertByDate(messages, object);
                added++;
            }
            return added;
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    /** Создано ли сообщение модом: на сервере такого подарка не существует. */
    public static boolean isLocal(MessageObject object) {
        if (object == null) {
            return false;
        }
        if (LOCAL.contains(object)) {
            return true;
        }
        // Страховка на случай, если объект пересоздали из нашего же сообщения:
        // id локальных подарков лежат в заведомо своём диапазоне.
        return object.messageOwner != null && LocalGiftsStore.isLocalMessageId(object.getId());
    }

    private static int purge(List<MessageObject> messages) {
        int removed = 0;
        for (int a = messages.size() - 1; a >= 0; a--) {
            if (isLocal(messages.get(a))) {
                messages.remove(a);
                removed++;
            }
        }
        return removed;
    }

    private static MessageObject build(int accountId, LocalGiftsStore.Entry entry) {
        try {
            final MessagesController controller = MessagesController.getInstance(accountId);
            final TLRPC.TL_messageService message = new TLRPC.TL_messageService();
            message.id = entry.messageId;
            message.date = entry.date;
            message.dialog_id = entry.dialogId;
            message.out = true;
            message.from_id = controller.getPeer(UserConfig.getInstance(accountId).getClientUserId());
            message.peer_id = controller.getPeer(entry.dialogId);
            message.action = buildAction(entry);

            final MessageObject object = new MessageObject(accountId, message, false, false);
            LOCAL.add(object);
            return object;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Действие собирается ровно теми же полями, что и предпросмотр подарка в
     * штатном {@code SendGiftSheet}, включая клиентский флаг {@code forceIn}:
     * без него ячейка считает подарок исходящим и не рисует карточку.
     */
    private static TLRPC.MessageAction buildAction(LocalGiftsStore.Entry entry) {
        final TLRPC.TL_messageActionStarGift action = new TLRPC.TL_messageActionStarGift();
        action.gift = entry.gift;
        action.flags |= 2;
        action.message = new TLRPC.TL_textWithEntities();
        action.message.text = entry.text != null ? entry.text : "";
        if (action.message.entities == null) {
            action.message.entities = new ArrayList<>();
        }
        action.name_hidden = entry.anonymous;
        action.can_upgrade = entry.upgraded;
        action.upgrade_stars = entry.upgraded ? entry.gift.upgrade_stars : 0;
        action.convert_stars = entry.upgraded ? 0 : entry.gift.convert_stars;
        action.forceIn = true;
        return action;
    }

    /**
     * Вставка по дате, а не по id.
     *
     * Id локальных подарков лежат выше любых серверных, поэтому сортировка по id
     * всегда выбрасывала бы подарок в самый низ чата. По дате он встаёт туда,
     * где его действительно отправили.
     */
    private static void insertByDate(List<MessageObject> messages, MessageObject object) {
        final int date = object.messageOwner.date;
        for (int a = 0, N = messages.size(); a < N; a++) {
            final MessageObject existing = messages.get(a);
            if (existing == null || existing.messageOwner == null) {
                continue;
            }
            if (existing.messageOwner.date <= date) {
                messages.add(a, object);
                return;
            }
        }
        messages.add(object);
    }
}
