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
 * Чтение сообщений из штатного кэша Telegram.
 *
 * Благодаря этому моду не нужно врезаться в MessagesStorage: мы читаем содержимое
 * сообщения тем же способом, как это делает сам Telegram в ProfileChannelCell
 * и других местах.
 *
 * Источников два, и они дополняют друг друга:
 *  - {@link #TABLE_HISTORY} — обычная история чатов. Есть всё, что клиент успел
 *    получить и сохранить, пока работал;
 *  - {@link #TABLE_PUSH} — очередь уведомлений. Единственное место, где лежит
 *    сообщение, доставленное push-уведомлением при выключенном приложении: в
 *    историю чата оно попадёт только после запуска клиента, а если к этому
 *    моменту его успели удалить, сервер его больше не отдаст.
 *
 * Формат BLOB в обеих таблицах одинаковый — результат
 * {@code TLRPC.Message.serializeToStream}, поэтому обе читаются одним кодом.
 *
 * ВСЕ методы обязаны вызываться в очереди хранилища
 * ({@code MessagesStorage.getStorageQueue()}): только так гарантируется безопасный доступ
 * к базе и правильный порядок относительно записей самого Telegram.
 */
final class SpyTelegramMessages {

    private static final String TABLE_HISTORY = "messages_v2";
    private static final String TABLE_PUSH = "unread_push_messages";

    interface Consumer {
        void accept(long dialogId, TLRPC.Message message);
    }

    private SpyTelegramMessages() {
    }

    /** Сообщения канала или супергруппы из истории чата. */
    static void loadChannelMessages(int accountId, long channelId, List<Integer> messageIds, Consumer consumer) {
        readChannel(accountId, TABLE_HISTORY, channelId, messageIds, consumer);
    }

    /** То же, но из очереди уведомлений: клиент был выключен и историю ещё не догружал. */
    static void loadChannelPushMessages(int accountId, long channelId, List<Integer> messageIds, Consumer consumer) {
        readChannel(accountId, TABLE_PUSH, channelId, messageIds, consumer);
    }

    /**
     * Сообщения личных чатов и обычных групп из истории чата.
     *
     * В этом случае сервер не сообщает диалог: идентификаторы сообщений уникальны
     * в пределах аккаунта, поэтому диалог берётся из самой таблицы.
     */
    static void loadMessages(int accountId, List<Integer> messageIds, Consumer consumer) {
        readPlain(accountId, TABLE_HISTORY, messageIds, consumer);
    }

    /** То же, но из очереди уведомлений. */
    static void loadPushMessages(int accountId, List<Integer> messageIds, Consumer consumer) {
        readPlain(accountId, TABLE_PUSH, messageIds, consumer);
    }

    /** Одно сообщение конкретного диалога — версия до применения правки. */
    static TLRPC.Message loadMessage(int accountId, long dialogId, int messageId) {
        return readSingle(accountId, TABLE_HISTORY, dialogId, messageId);
    }

    /** Одно сообщение из очереди уведомлений. */
    static TLRPC.Message loadPushMessage(int accountId, long dialogId, int messageId) {
        return readSingle(accountId, TABLE_PUSH, dialogId, messageId);
    }

    private static void readChannel(int accountId, String table, long channelId, List<Integer> messageIds, Consumer consumer) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        long dialogId = -channelId;
        String sql = String.format(Locale.US,
                "SELECT data, mid FROM %s WHERE uid = %d AND mid IN (%s)",
                table, dialogId, join(messageIds));
        read(accountId, sql, false, dialogId, consumer);
    }

    private static void readPlain(int accountId, String table, List<Integer> messageIds, Consumer consumer) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        String sql = String.format(Locale.US,
                "SELECT data, mid, uid FROM %s WHERE mid IN (%s)",
                table, join(messageIds));
        read(accountId, sql, true, 0, consumer);
    }

    private static TLRPC.Message readSingle(int accountId, String table, long dialogId, int messageId) {
        final TLRPC.Message[] result = new TLRPC.Message[1];
        String sql = String.format(Locale.US,
                "SELECT data, mid FROM %s WHERE uid = %d AND mid = %d",
                table, dialogId, messageId);
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
