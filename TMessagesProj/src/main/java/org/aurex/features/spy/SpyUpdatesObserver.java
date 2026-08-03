package org.aurex.features.spy;

import androidx.collection.LongSparseArray;

import org.aurex.core.AurexFeatures;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.List;

/**
 * Разбор потока апдейтов от сервера — единственная точка перехвата модуля «Шпион».
 *
 * Почему именно здесь, а не в MessagesController/MessagesStorage, как в AyuGram:
 *  - это самая ранняя точка, где видны события удаления и редактирования:
 *    сообщение ещё цело в базе Telegram, его можно спокойно прочитать;
 *  - требуется всего одна строка врезки в апстрим вместо правок в двух самых
 *    больших и чаще всего меняющихся файлах клиента — это резко упрощает
 *    обновление до новых версий Telegram.
 *
 * Порядок гарантирован: наше чтение ставится в очередь хранилища раньше, чем
 * Telegram обработает апдейт в своёй stage-очереди и только потом запишет изменения.
 */
public final class SpyUpdatesObserver {

    private SpyUpdatesObserver() {
    }

    public static void onUpdates(int accountId, TLRPC.Updates updates) {
        if (updates == null) {
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

        List<TLRPC.Update> list = flatten(updates);
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
                    SpyTelegramMessages.loadMessages(accountId, finalPlainDeleted,
                            (dialogId, message) -> SpyController.onMessageDeleted(accountId, dialogId, message));
                }
                if (finalChannelDeleted != null) {
                    for (int i = 0; i < finalChannelDeleted.size(); i++) {
                        long channelId = finalChannelDeleted.keyAt(i);
                        SpyTelegramMessages.loadChannelMessages(accountId, channelId, finalChannelDeleted.valueAt(i),
                                (dialogId, message) -> SpyController.onMessageDeleted(accountId, dialogId, message));
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
            return;
        }
        SpyController.onMessageEdited(accountId, dialogId, previous);
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
