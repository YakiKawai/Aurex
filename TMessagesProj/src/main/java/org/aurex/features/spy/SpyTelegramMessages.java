package org.aurex.features.spy;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Чтение сообщений из штатного кэша Telegram (таблица messages_v2).
 *
 * Благодаря этому моду не нужно врезаться в MessagesStorage: мы читаем содержимое
 * сообщения тем же способом, как это делает сам Telegram в ProfileChannelCell
 * и других местах.
 *
 * ВСЕ методы обязаны вызываться в очереди хранилища
 * ({@code MessagesStorage.getStorageQueue()}): только так гарантируется безопасный доступ
 * к базе и правильный порядок относительно записей самого Telegram.
 */
final class SpyTelegramMessages {

    interface Consumer {
        void accept(long dialogId, TLRPC.Message message);
    }

    private SpyTelegramMessages() {
    }

    /**
     * Загружает сообщения канала или супергруппы по списку идентификаторов.
     */
    static void loadChannelMessages(int accountId, long channelId, List<Integer> messageIds, Consumer consumer) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        long dialogId = -channelId;
        String sql = String.format(Locale.US,
                "SELECT data, mid FROM messages_v2 WHERE uid = %d AND mid IN (%s)",
                dialogId, join(messageIds));
        read(accountId, sql, false, dialogId, consumer);
    }

    /**
     * Загружает сообщения личных чатов и обычных групп.
     *
     * В этом случае сервер не сообщает диалог: идентификаторы сообщений уникальны
     * в пределах аккаунта, поэтому диалог берётся из самой таблицы.
     */
    static void loadMessages(int accountId, List<Integer> messageIds, Consumer consumer) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        String sql = String.format(Locale.US,
                "SELECT data, mid, uid FROM messages_v2 WHERE mid IN (%s)",
                join(messageIds));
        read(accountId, sql, true, 0, consumer);
    }

    /** Одно сообщение конкретного диалога — версия до применения правки. */
    static TLRPC.Message loadMessage(int accountId, long dialogId, int messageId) {
        final TLRPC.Message[] result = new TLRPC.Message[1];
        String sql = String.format(Locale.US,
                "SELECT data, mid FROM messages_v2 WHERE uid = %d AND mid = %d",
                dialogId, messageId);
        read(accountId, sql, false, dialogId, (did, message) -> result[0] = message);
        return result[0];
    }

    private static void read(int accountId, String sql, boolean readDialogId, long dialogId, Consumer consumer) {
        long selfId = UserConfig.getInstance(accountId).getClientUserId();
        SQLiteCursor cursor = null;
        try {
            cursor = MessagesStorage.getInstance(accountId).getDatabase().queryFinalized(sql);
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                if (message == null) {
                    data.reuse();
                    continue;
                }
                message.readAttachPath(data, selfId);
                data.reuse();

                message.id = cursor.intValue(1);
                long messageDialogId = readDialogId ? cursor.longValue(2) : dialogId;
                message.dialog_id = messageDialogId;
                consumer.accept(messageDialogId, message);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    private static String join(List<Integer> ids) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            // Значения — целые числа из TL-структуры, подстановка безопасна.
            builder.append(ids.get(i).intValue());
        }
        return builder.toString();
    }

    static List<Integer> copyIds(List<Integer> source) {
        return new ArrayList<>(source);
    }
}
