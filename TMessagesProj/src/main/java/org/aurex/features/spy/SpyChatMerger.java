package org.aurex.features.spy;

import android.util.LongSparseArray;

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
 * Логика полностью изолирована здесь: врезки в {@code ChatActivity} состоят из
 * вызовов фасада {@code org.aurex.ui.AurexSpyDeleted}.
 *
 * Правила поиска повторяют AyuGram (AyuMessagesController.getMessages +
 * AyuUtils.getMinRealId), но дедупликация сделана строже: AyuGram полагается
 * на то, что порция истории приходит один раз, а Telegram может прислать её
 * повторно (обновление кеша, прыжок к сообщению, возврат к последнему
 * прочитанному). Поэтому мы помним, какие id уже подмешали за сессию чата.
 *
 * Текст сообщения при восстановлении не изменяется: за визуальное отличие
 * отвечают прозрачность ({@link #RESTORED_ALPHA}) и иконка
 * {@code org.aurex.ui.AurexSpyMark}. AyuGram вместо этого дописывает в текст
 * символ, но тогда сохранённое сообщение перестаёт совпадать с оригиналом —
 * это заметно при копировании и в истории правок.
 */
public final class SpyChatMerger {

    /** Прозрачность восстановленного сообщения: сразу видно, что его больше нет. */
    public static final float RESTORED_ALPHA = 0.6f;

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

    /**
     * Идентификаторы, уже подмешанные в открытый чат.
     *
     * Живёт от открытия чата до его закрытия. Без этого одно и то же
     * восстановленное сообщение попадало в ленту повторно при подгрузке
     * следующей порции истории.
     */
    private static final LongSparseArray<HashSet<Integer>> INJECTED = new LongSparseArray<>();

    private SpyChatMerger() {
    }

    /** Чат открыт: начинаем учёт подмешанных сообщений с чистого листа. */
    public static void openSession(long dialogId) {
        synchronized (INJECTED) {
            INJECTED.put(dialogId, new HashSet<Integer>());
        }
    }

    /** Чат закрыт: учёт больше не нужен. */
    public static void closeSession(long dialogId) {
        synchronized (INJECTED) {
            INJECTED.remove(dialogId);
        }
    }

    /**
     * Подмешивает удалённые сообщения в список сообщений чата.
     *
     * Годится и для только что загруженной порции истории, и для живой ленты
     * (когда сообщение удалили при открытом чате).
     *
     * Диапазон ограничен только снизу: сверху ограничивать нельзя, иначе
     * сообщение, удалённое последним в чате, никогда не попадёт в выборку.
     * Пустой список означает, что историю очистили — тогда ищем с начала,
     * ведь факт удаления от очистки истории не зависит.
     *
     * @param messages лента чата, отсортированная по убыванию id (новые в начале)
     * @return сколько сообщений было добавлено
     */
    public static int merge(int accountId, long dialogId, long topicId, List<MessageObject> messages) {
        try {
            if (messages == null) {
                return 0;
            }
            if (!SpyConfig.saveDeletedFor(accountId, dialogId)) {
                return 0;
            }

            final HashSet<Integer> present = new HashSet<>();
            int minId = Integer.MAX_VALUE;
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
            }
            final int startId = minId == Integer.MAX_VALUE ? 1 : minId;

            final List<SpyMessage> deleted = SpyStorage.getInstance()
                    .getDeletedFrom(UserConfig.getInstance(accountId).getClientUserId(),
                            dialogId, startId, RANGE_LIMIT);
            if (deleted == null || deleted.isEmpty()) {
                return 0;
            }

            int added = 0;
            for (int a = 0, N = deleted.size(); a < N; a++) {
                final SpyMessage saved = deleted.get(a);
                if (saved == null || saved.messageId <= 0) {
                    continue;
                }
                if (!sameTopic(saved.topicId, topicId)) {
                    continue;
                }
                if (present.contains(saved.messageId)) {
                    continue;
                }
                if (!claim(dialogId, saved.messageId)) {
                    continue;
                }
                final MessageObject object = build(accountId, saved);
                if (object == null) {
                    release(dialogId, saved.messageId);
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
     * (ответить, переслать, закрепить) на объекте, которого на сервере уже нет,
     * и чтобы отрисовать его приглушённым и с иконкой.
     */
    public static boolean isRestored(MessageObject object) {
        return object != null && RESTORED.contains(object);
    }

    /**
     * Щадящее сравнение тем.
     *
     * Тема на записи и тема на экране вычисляются в разных местах Telegram и
     * для форумов, комментариев и monoforum могут не совпасть. Жёсткое
     * равенство приводило к тому, что сообщение просто не находилось.
     * Отбрасываем только тогда, когда тема заведомо известна с обеих сторон
     * и заведомо разная.
     */
    private static boolean sameTopic(long savedTopicId, long topicId) {
        return savedTopicId == topicId || savedTopicId == 0 || topicId == 0;
    }

    /** @return true, если этот id ещё не подмешивали в открытый чат. */
    private static boolean claim(long dialogId, int messageId) {
        synchronized (INJECTED) {
            HashSet<Integer> injected = INJECTED.get(dialogId);
            if (injected == null) {
                // Чат не сообщил об открытии: работаем без учёта, но не падаем.
                injected = new HashSet<>();
                INJECTED.put(dialogId, injected);
            }
            return injected.add(messageId);
        }
    }

    private static void release(long dialogId, int messageId) {
        synchronized (INJECTED) {
            final HashSet<Integer> injected = INJECTED.get(dialogId);
            if (injected != null) {
                injected.remove(messageId);
            }
        }
    }

    private static MessageObject build(int accountId, SpyMessage saved) {
        try {
            final TLRPC.Message message = SpyMessageMapper.toMessage(accountId, saved);
            if (message == null) {
                return null;
            }
            message.date = saved.date > 0 ? saved.date : message.date;

            final MessageObject object = new MessageObject(accountId, message, true, true);
            object.generateThumbs(false);
            RESTORED.add(object);
            return object;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
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
