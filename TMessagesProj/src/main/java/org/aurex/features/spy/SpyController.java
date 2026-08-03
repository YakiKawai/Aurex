package org.aurex.features.spy;

import android.text.TextUtils;

import org.aurex.core.AurexFeatures;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.List;

/**
 * Ядро модуля «Шпион»: единая точка входа для перехвата событий.
 *
 * Врезки в код Telegram вызывают только методы этого класса и всегда одной строкой:
 * чем меньше чужого кода мы трогаем, тем проще обновлять апстрим.
 *
 * Вся работа с базой и файлами выполняется в фоновой очереди (AyuGram делает это
 * в UI-потоке через allowMainThreadQueries — повторять это мы не стали).
 */
public final class SpyController {

    private SpyController() {
    }

    /**
     * Сообщение удалено на сервере или собеседником.
     * Вызывать ДО того, как Telegram удалит его из своей базы.
     */
    public static void onMessageDeleted(int accountId, long dialogId, TLRPC.Message msg) {
        if (msg == null || !SpyConfig.saveDeletedFor(accountId, dialogId)) {
            return;
        }
        final TLRPC.Message copy = msg;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                saveDeleted(accountId, dialogId, copy);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * Сообщение отредактировано. Передавать ПРЕДЫДУЩУЮ версию сообщения:
     * именно она и составляет историю правок.
     */
    public static void onMessageEdited(int accountId, long dialogId, TLRPC.Message previous) {
        if (previous == null || !SpyConfig.saveEditsFor(accountId, dialogId)) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                saveRevision(accountId, dialogId, previous);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    // ~ Внутреннее (всегда фоновый поток)

    private static void saveDeleted(int accountId, long dialogId, TLRPC.Message msg) {
        SpyStorage storage = SpyStorage.getInstance();
        long userId = UserConfig.getInstance(accountId).getClientUserId();
        SpyMessage saved = SpyMessageMapper.map(accountId, dialogId, msg);
        if (storage.deletedExists(userId, dialogId, saved.topicId, saved.messageId)) {
            return;
        }
        attachMedia(accountId, dialogId, msg, saved);

        long rowId = storage.insertDeleted(saved);
        if (rowId == 0) {
            return;
        }
        if (AurexFeatures.SPY_SAVE_REACTIONS.get()) {
            saveReactions(storage, rowId, msg);
        }
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(accountId)
                .postNotificationName(SpyNotifications.MESSAGES_DELETED, dialogId));
    }

    private static void saveRevision(int accountId, long dialogId, TLRPC.Message msg) {
        SpyStorage storage = SpyStorage.getInstance();
        long userId = UserConfig.getInstance(accountId).getClientUserId();
        SpyMessage saved = SpyMessageMapper.map(accountId, dialogId, msg);

        SpyMessage last = storage.getLastRevision(userId, dialogId, saved.messageId);
        if (last != null && sameContent(last, saved)) {
            return;
        }
        attachMedia(accountId, dialogId, msg, saved);
        // Если медиа заменили, у старых ревизий остался путь к файлу Telegram, который
        // уже перезаписан новым содержимым — переводим их на нашу копию.
        if (last != null && !TextUtils.isEmpty(saved.mediaPath)
                && !TextUtils.isEmpty(last.mediaPath)
                && !TextUtils.equals(last.mediaPath, saved.mediaPath)) {
            storage.replaceRevisionMedia(userId, dialogId, saved.messageId, last.mediaPath, saved.mediaPath);
        }

        if (storage.insertRevision(saved) == 0) {
            return;
        }
        final int messageId = saved.messageId;
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(accountId)
                .postNotificationName(SpyNotifications.MESSAGE_EDITED, dialogId, messageId));
    }

    /** Ревизию не пишем, если ни текст, ни вложение не изменились (как в AyuGram). */
    private static boolean sameContent(SpyMessage previous, SpyMessage current) {
        if (!TextUtils.equals(previous.text, current.text)) {
            return false;
        }
        if (previous.documentType != current.documentType) {
            return false;
        }
        byte[] a = previous.media;
        byte[] b = current.media;
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    /** Копирует вложение в хранилище мода, если это разрешено для данного типа чата. */
    private static void attachMedia(int accountId, long dialogId, TLRPC.Message msg, SpyMessage saved) {
        if (!saved.hasMedia() || !SpyMediaScope.allowed(accountId, dialogId)) {
            return;
        }
        File source = null;
        if (!TextUtils.isEmpty(msg.attachPath)) {
            File attach = new File(msg.attachPath);
            if (attach.exists()) {
                source = attach;
            }
        }
        if (source == null) {
            source = FileLoader.getInstance(accountId).getPathToMessage(msg);
        }
        if (source == null || !source.exists()) {
            return;
        }
        String suffix = extensionOf(source.getName());
        saved.mediaPath = SpyAttachments.store(source, saved.userId, dialogId, saved.messageId, suffix);
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot) : "";
    }

    private static void saveReactions(SpyStorage storage, long rowId, TLRPC.Message msg) {
        if (msg.reactions == null || msg.reactions.results == null) {
            return;
        }
        List<TLRPC.ReactionCount> results = msg.reactions.results;
        for (int i = 0; i < results.size(); i++) {
            TLRPC.ReactionCount source = results.get(i);
            if (source == null || source.reaction == null) {
                continue;
            }
            SpyReaction reaction = new SpyReaction();
            reaction.messageRowId = rowId;
            reaction.count = source.count;
            reaction.selfSelected = source.chosen_order >= 0;
            if (source.reaction instanceof TLRPC.TL_reactionEmoji) {
                reaction.emoticon = ((TLRPC.TL_reactionEmoji) source.reaction).emoticon;
            } else if (source.reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                reaction.documentId = ((TLRPC.TL_reactionCustomEmoji) source.reaction).document_id;
                reaction.custom = true;
            } else {
                continue;
            }
            storage.insertReaction(reaction);
        }
    }
}
