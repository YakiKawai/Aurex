package org.aurex.features.ghost;

import org.aurex.core.AurexFeatures;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;

/**
 * Фильтр исходящих запросов к серверу Telegram — ядро режима призрака.
 *
 * ПОЧЕМУ ТАК. AyuGram глушит активность в десятках мест внутри MessagesController,
 * SendMessagesHelper, StoriesController и т.д. Каждая такая правка — потенциальный
 * конфликт при каждом обновлении Telegram и риск пропустить новое место, откуда
 * утекает активность.
 *
 * Любая активность всё равно уходит на сервер одним и тем же путём — через
 * ConnectionsManager. Поэтому мы ставим ОДИН фильтр на выходе: одна врезка в
 * апстрим вместо десятков, и ни один пакет не проскользнёт мимо, даже если
 * Telegram добавит новое место отправки прочтений.
 *
 * Локальное состояние клиента при этом не меняется: чат у вас по-прежнему
 * помечается прочитанным, просто собеседник об этом не узнаёт.
 */
public final class GhostRequestFilter {

    /**
     * Окно, в течение которого прочтения разрешены после вашего собственного
     * действия (отправка сообщения, реакция, пересылка) — настройка "Читать при действиях".
     */
    private static final long READ_BYPASS_WINDOW_MS = 5000L;

    private static volatile long readBypassUntil;

    private GhostRequestFilter() {
    }

    /**
     * @return true, если запрос не должен уйти на сервер.
     */
    public static boolean shouldDrop(int accountId, TLObject request) {
        if (request == null) {
            return false;
        }

        // Ваше собственное действие открывает окно для прочтений.
        if (isOwnAction(request)) {
            if (AurexFeatures.READ_AFTER_ACTION.get()) {
                readBypassUntil = System.currentTimeMillis() + READ_BYPASS_WINDOW_MS;
            }
            return false;
        }

        if (isReadRequest(request)) {
            if (AurexFeatures.SEND_READ_PACKETS.get()) {
                return false;
            }
            return System.currentTimeMillis() >= readBypassUntil;
        }

        if (isStoryReadRequest(request)) {
            return !AurexFeatures.SEND_READ_STORIES.get();
        }

        if (request instanceof TLRPC.TL_account_updateStatus) {
            TLRPC.TL_account_updateStatus status = (TLRPC.TL_account_updateStatus) request;
            if (status.offline) {
                // Офлайн-пакет никогда не блокируется: он не раскрывает активность.
                return false;
            }
            if (AurexFeatures.SEND_ONLINE_PACKETS.get()) {
                return false;
            }
            if (AurexFeatures.AUTO_OFFLINE.get()) {
                sendOfflinePacket(accountId);
            }
            return true;
        }

        if (request instanceof TLRPC.TL_messages_setTyping) {
            TLRPC.SendMessageAction action = ((TLRPC.TL_messages_setTyping) request).action;
            if (action == null || action instanceof TLRPC.TL_sendMessageCancelAction) {
                // Отмена активности разрешена всегда, иначе у собеседника может
                // зависнуть статус "печатает".
                return false;
            }
            if (isUploadAction(action)) {
                return !AurexFeatures.SEND_UPLOAD_PROGRESS.get();
            }
            return !AurexFeatures.SEND_TYPING_PACKETS.get();
        }

        return false;
    }

    /** Автоматический офлайн: вместо заблокированного "онлайн" отправляем "офлайн". */
    private static void sendOfflinePacket(int accountId) {
        try {
            TLRPC.TL_account_updateStatus offline = new TLRPC.TL_account_updateStatus();
            offline.offline = true;
            // Рекурсии не будет: офлайн-пакет фильтр пропускает безусловно.
            ConnectionsManager.getInstance(accountId).sendRequest(offline, null);
        } catch (Throwable ignored) {
        }
    }

    /** Отметки о прочтении сообщений. */
    private static boolean isReadRequest(TLObject request) {
        return request instanceof TLRPC.TL_messages_readHistory
                || request instanceof TLRPC.TL_channels_readHistory
                || request instanceof TLRPC.TL_messages_readDiscussion
                || request instanceof TLRPC.TL_messages_readMentions
                || request instanceof TLRPC.TL_messages_readReactions
                || request instanceof TLRPC.TL_messages_readMessageContents
                || request instanceof TLRPC.TL_channels_readMessageContents;
    }

    /** Просмотры историй. */
    private static boolean isStoryReadRequest(TLObject request) {
        return request instanceof TL_stories.TL_stories_readStories
                || request instanceof TL_stories.TL_stories_incrementStoryViews;
    }

    /** Собственное активное действие пользователя в чате. */
    private static boolean isOwnAction(TLObject request) {
        return request instanceof TLRPC.TL_messages_sendMessage
                || request instanceof TLRPC.TL_messages_sendMedia
                || request instanceof TLRPC.TL_messages_sendMultiMedia
                || request instanceof TLRPC.TL_messages_forwardMessages
                || request instanceof TLRPC.TL_messages_sendReaction
                || request instanceof TLRPC.TL_messages_sendInlineBotResult;
    }

    /** Статусы вида "отправляет фото", "загружает видео" и т.д. */
    private static boolean isUploadAction(TLRPC.SendMessageAction action) {
        return action instanceof TLRPC.TL_sendMessageUploadPhotoAction
                || action instanceof TLRPC.TL_sendMessageUploadVideoAction
                || action instanceof TLRPC.TL_sendMessageUploadDocumentAction
                || action instanceof TLRPC.TL_sendMessageUploadAudioAction
                || action instanceof TLRPC.TL_sendMessageUploadRoundAction;
    }
}
