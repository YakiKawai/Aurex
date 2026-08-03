package org.aurex.features.spy;

import org.telegram.messenger.DialogObject;
import org.telegram.tgnet.TLRPC;

/**
 * Мелкие вспомогательные вычисления по диалогам.
 */
final class SpyDialogs {

    private SpyDialogs() {
    }

    /**
     * Идентификатор диалога сообщения, пришедшего в апдейте.
     *
     * Для исходящих сообщений в личной переписке peer_id — это собеседник,
     * что и требуется: именно этот идентификатор используется в таблице messages_v2.
     */
    static long dialogIdOf(TLRPC.Message message) {
        if (message == null) {
            return 0;
        }
        if (message.dialog_id != 0) {
            return message.dialog_id;
        }
        if (message.peer_id == null) {
            return 0;
        }
        return DialogObject.getPeerDialogId(message.peer_id);
    }
}
