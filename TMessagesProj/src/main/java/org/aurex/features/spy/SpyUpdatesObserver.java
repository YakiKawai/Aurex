package org.aurex.features.spy;

import androidx.collection.LongSparseArray;

import org.aurex.core.AurexFeatures;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Разбор потока апдейтов от сервера — точка входа модуля «Шпион».
 *
 * Апдейты доходят до клиента тремя разными путями, и модуль обязан видеть все три:
 *  1. push из сокета — приложение запущено и соединение живо;
 *  2. ответ на собственный запрос — например, своё редактирование сообщения
 *     возвращает результат в виде набора апдейтов;
 *  3. getDifference / getChannelDifference — всё, что произошло, пока клиент был
 *     офлайн или выключен.
 *
 * Пункты 2 и 3 в сокет не попадают, поэтому одной врезки на приёме из сети
 * недостаточно: без второй точки перехвата не сохранялись ни правки, сделанные
 * самим пользователем, ни любые удаления за время, пока приложение было закрыто.
 *
 * Повторная обработка одного события не опасна: удаления гасит
 * {@code SpyStorage.deletedExists}, ревизии — сравнение с последней сохранённой
 * версией, а обе точки перехвата пишут через одну и ту же очередь.
 */
public final class SpyUpdatesObserver {

    private SpyUpdatesObserver() {
    }

    /**
     * Апдейты, принятые из сети до передачи в MessagesController.
     *
     * Самая ранняя из возможных точек: сообщение ещё гарантированно цело в кэше.
     */
    public static void onUpdates(int accountId, TLRPC.Updates updates) {
        if (updates == null) {
            return;
        }
        process(accountId, flatten(updates));
    }

    /**
     * Единая воронка апдейтов клиента.
     *
     * Через неё проходит абсолютно всё: сокет, ответы на запросы, difference и
     * channel difference. Вызывается до применения апдейтов, поэтому предыдущее
     * состояние сообщения ещё доступно.
     */
    public static void onUpdateArray(int accountId, List<TLRPC.Update> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        process(accountId, updates);
    }

    private static void process(int accountId, List<TLRPC.Update> list) {
        if (list.isEmpty()) {
            return;
        }
        boolean saveDeleted = AurexFeatures.SPY_SAVE_DELETED.get();
        boolean saveEdits = AurexFeatures.SPY_SAVE_EDITS.get();
        if (!saveDeleted && !saveEdits) {
            return;
        }

        List<Integer> plainDeleted = null;
        LongSparseArray<List<Integer>> channelDeleted = null;
        List<TLRPC.Message> edited = null;

        for (int i = 0; i < list.size(); i++) {
            TLRPC.Update update = list.get(i);
            if (saveDeleted && update instanceof TL_update.TL_updateDeleteMessages) {
                TL_update.TL_updateDeleteMessages delete = (TL_update.TL_updateDeleteMessages) update;
                if (delete.messages != null && !delete.messages.isEmpty()) {
                    if (plainDeleted == null) {
                        plainDeleted = new ArrayList<>();
                    }
                    plainDeleted.addAll(delete.messages);
                }
            } else if (saveDeleted && update instanceof TL_update.TL_updateDeleteChannelMessages) {
                TL_update.TL_updateDeleteChannelMessages delete = (TL_update.TL_updateDeleteChannelMessages) update;
                if (delete.messages != null && !delete.messages.isEmpty()) {
                    if (channelDeleted == null) {
                        channelDeleted = new LongSparseArray<>();
                    }
                    List<Integer> ids = channelDeleted.get(delete.channel_id);
                    if (ids == null) {
                        ids = new ArrayList<>();
                        channelDeleted.put(delete.channel_id, ids);
                    }
                    ids.addAll(delete.messages);
                }
            } else if (saveEdits && update instanceof TL_update.TL_updateEditMessage) {
                TLRPC.Message message = ((TL_update.TL_updateEditMessage) update).message;
                if (message != null) {
                    if (edited == null) {
                        edited = new ArrayList<>();
                    }
                    edited.add(message);
                }
            } else if (saveEdits && update instanceof TL_update.TL_updateEditChannelMessage) {
                TLRPC.Message message = ((TL_update.TL_updateEditChannelMessage) update).message;
                if (message != null) {
                    if (edited == null) {
                        edited = new ArrayList<>();
                    }
                    edited.add(message);
                }
            }
        }

        if (plainDeleted == null && channelDeleted == null && edited == null) {
            return;
        }

        final List<Integer> finalPlainDeleted = plainDeleted;
        final LongSparseArray<List<Integer>> finalChannelDeleted = channelDeleted;
        final List<TLRPC.Message> finalEdited = edited;
        MessagesStorage.getInstance(accountId).getStorageQueue().postRunnable(() -> {
            try {
                if (finalPlainDeleted != null) {
                    captureDeleted(accountId, finalPlainDeleted);
                }
                if (finalChannelDeleted != null) {
                    for (int i = 0; i < finalChannelDeleted.size(); i++) {
                        captureChannelDeleted(accountId, finalChannelDeleted.keyAt(i), finalChannelDeleted.valueAt(i));
                    }
                }
                if (finalEdited != null) {
                    for (int i = 0; i < finalEdited.size(); i++) {
                        captureRevision(accountId, finalEdited.get(i));
                    }
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
    }

    /**
     * Удаления в личных чатах и обычных группах.
     *
     * Сначала история чата, затем — для того, чего в истории нет — очередь
     * уведомлений. Второй проход закрывает главный пробел: сообщение пришло
     * push-уведомлением при выключенном приложении и было удалено до того, как
     * клиент успел запуститься и догрузить историю.
     */
    private static void captureDeleted(int accountId, List<Integer> messageIds) {
        Set<Integer> found = new HashSet<>();
        SpyTelegramMessages.loadMessages(accountId, messageIds, (dialogId, message) -> {
            found.add(message.id);
            SpyController.onMessageDeleted(accountId, dialogId, message);
        });
        List<Integer> missing = missing(messageIds, found);
        if (missing.isEmpty()) {
            return;
        }
        SpyTelegramMessages.loadPushMessages(accountId, missing,
                (dialogId, message) -> SpyController.onMessageDeleted(accountId, dialogId, message));
    }

    /** То же для каналов и супергрупп: там сервер сообщает диалог. */
    private static void captureChannelDeleted(int accountId, long channelId, List<Integer> messageIds) {
        Set<Integer> found = new HashSet<>();
        SpyTelegramMessages.loadChannelMessages(accountId, channelId, messageIds, (dialogId, message) -> {
            found.add(message.id);
            SpyController.onMessageDeleted(accountId, dialogId, message);
        });
        List<Integer> missing = missing(messageIds, found);
        if (missing.isEmpty()) {
            return;
        }
        SpyTelegramMessages.loadChannelPushMessages(accountId, channelId, missing,
                (dialogId, message) -> SpyController.onMessageDeleted(accountId, dialogId, message));
    }

    /**
     * Сохраняет версию сообщения, которая была до правки.
     *
     * Новая версия остаётся в самом Telegram, поэтому мод хранит только старые —
     * точно так же устроена история правок в AyuGram.
     */
    private static void captureRevision(int accountId, TLRPC.Message edited) {
        long dialogId = SpyDialogs.dialogIdOf(edited);
        if (dialogId == 0) {
            return;
        }
        TLRPC.Message previous = SpyTelegramMessages.loadMessage(accountId, dialogId, edited.id);
        if (previous == null) {
            previous = SpyTelegramMessages.loadPushMessage(accountId, dialogId, edited.id);
        }
        if (previous == null) {
            return;
        }
        SpyController.onMessageEdited(accountId, dialogId, previous);
    }

    /** Идентификаторы, которых не оказалось в первом источнике, без повторов. */
    private static List<Integer> missing(List<Integer> messageIds, Set<Integer> found) {
        List<Integer> result = new ArrayList<>();
        Set<Integer> unique = new HashSet<>();
        for (int i = 0; i < messageIds.size(); i++) {
            Integer id = messageIds.get(i);
            if (id == null || found.contains(id)) {
                continue;
            }
            if (unique.add(id)) {
                result.add(id);
            }
        }
        return result;
    }

    /** Раскрывает контейнер апдейтов в плоский список. */
    private static List<TLRPC.Update> flatten(TLRPC.Updates updates) {
        List<TLRPC.Update> list = new ArrayList<>();
        if (updates.updates != null && !updates.updates.isEmpty()) {
            list.addAll(updates.updates);
        }
        if (updates instanceof TLRPC.TL_updateShort) {
            TLRPC.Update single = ((TLRPC.TL_updateShort) updates).update;
            if (single != null) {
                list.add(single);
            }
        }
        return list;
    }
}
