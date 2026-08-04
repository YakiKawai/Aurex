package org.aurex.features.spy;

import android.text.TextUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Подмешивает сохранённые удалённые сообщения в ленту чата.
 *
 * Логика полностью изолирована здесь: врезка в {@code ChatActivity} состоит из
 * одного вызова фасада {@code org.aurex.ui.AurexSpyDeleted}.
 */
public final class SpyChatMerger {

    /** Метка, которой помечается восстановленное сообщение. */
    public static final String DELETED_MARK = "\uD83E\uDDF9";

    /** Предохранитель: сколько удалённых сообщений максимум подмешиваем за один проход. */
    private static final int RANGE_LIMIT = 500;

    /**
     * Объекты, созданные модулем, а не полученные от сервера.
     *
     * Слабые ключи: как только лента чата отпускает сообщение, запись исчезает
     * сама, утечки памяти при долгом скролле нет.
     */
    private static final Set<MessageObject> RESTORED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<MessageObject, Boolean>()));

    private SpyChatMerger() {
    }

    /**
     * Подмешивает удалённые сообщения в уже загруженный список.
     *
     * Диапазон определяется по самому старому и самому новому id в списке, так
     * что при подгрузке истории каждая новая порция дополняется своими
     * удалёнными сообщениями и ничего не дублируется.
     *
     * @param messages лента чата, отсортированная по убыванию id (новые в начале)
     * @return сколько сообщений было добавлено
     */
    public static int merge(int accountId, long dialogId, long topicId, List<MessageObject> messages) {
        try {
            if (messages == null || messages.isEmpty()) {
                return 0;
            }
            if (!SpyConfig.saveDeletedFor(accountId, dialogId)) {
                return 0;
            }

            final HashSet<Integer> present = new HashSet<>();
            int minId = Integer.MAX_VALUE;
            int maxId = Integer.MIN_VALUE;
            for (int a = 0, N = messages.size(); a < N; a++) {
                final MessageObject object = messages.get(a);
                if (object == null || object.messageOwner == null) {
                    continue;
                }
                final int id = object.getId();
                if (id <= 0) {
                    continue;
                }
                present.add(id);
                if (id < minId) {
                    minId = id;
                }
                if (id > maxId) {
                    maxId = id;
                }
            }
            if (minId > maxId) {
                return 0;
            }

            final List<SpyMessage> deleted = SpyStorage.getInstance()
                    .getDeletedRange(UserConfig.getInstance(accountId).getClientUserId(),
                            dialogId, topicId, minId, maxId, RANGE_LIMIT);
            if (deleted == null || deleted.isEmpty()) {
                return 0;
            }

            int added = 0;
            for (int a = 0, N = deleted.size(); a < N; a++) {
                final SpyMessage saved = deleted.get(a);
                if (saved == null || saved.messageId <= 0 || present.contains(saved.messageId)) {
                    continue;
                }
                final MessageObject object = build(accountId, saved);
                if (object == null) {
                    continue;
                }
                present.add(saved.messageId);
                insert(messages, object);
                added++;
            }
            return added;
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    /**
     * Создано ли сообщение модулем. Нужно, чтобы не давать серверных действий
     * (ответить, переслать, закрепить) на объекте, которого на сервере уже нет.
     */
    public static boolean isRestored(MessageObject object) {
        return object != null && RESTORED.contains(object);
    }

    private static MessageObject build(int accountId, SpyMessage saved) {
        try {
            final TLRPC.Message message = SpyMessageMapper.toMessage(accountId, saved);
            if (message == null) {
                return null;
            }
            message.date = saved.date > 0 ? saved.date : message.date;
            message.message = mark(message.message);

            final MessageObject object = new MessageObject(accountId, message, true, true);
            object.generateThumbs(false);
            RESTORED.add(object);
            return object;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Метка ставится в конец текста, а не в начало: иначе сместились бы все
     * offset у сохранённого форматирования (жирный, ссылки, эмодзи).
     */
    private static String mark(String text) {
        if (TextUtils.isEmpty(text)) {
            return DELETED_MARK;
        }
        if (text.endsWith(DELETED_MARK)) {
            return text;
        }
        return text + " " + DELETED_MARK;
    }

    /** Вставка с сохранением порядка по убыванию id. */
    private static void insert(List<MessageObject> messages, MessageObject object) {
        final int id = object.getId();
        for (int a = 0, N = messages.size(); a < N; a++) {
            final MessageObject existing = messages.get(a);
            if (existing == null || existing.messageOwner == null) {
                continue;
            }
            if (existing.getId() < id) {
                messages.add(a, object);
                return;
            }
        }
        messages.add(object);
    }
}
