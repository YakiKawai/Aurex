package org.aurex.features.spy;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;

/**
 * База данных модуля «Шпион»: удалённые сообщения, ревизии правок и реакции.
 *
 * Почему не Room, как в AyuGram:
 *  - Room тянет annotation processor и правки build.gradle, которые придётся
 *    переносить при каждом обновлении апстрима;
 *  - AyuGram включает allowMainThreadQueries(), то есть ходит в базу из UI-потока;
 *  - Telegram и так работает с SQLite напрямую, отдельный фреймворк здесь лишний.
 * Набор запросов при этом повторяет DAO AyuGram один в один.
 *
 * Все методы этого класса блокирующие: вызывать их следует из фонового потока
 * (см. {@link SpyController}), а не из UI.
 */
public final class SpyStorage {

    private static final String DATABASE_NAME = "aurex_spy.db";
    /** v2: вместо document/thumbs/document_attributes/mime_type — одно поле media. */
    private static final int DATABASE_VERSION = 2;

    private static final String TABLE_MESSAGE = "spy_message";
    private static final String TABLE_REACTION = "spy_reaction";

    private static volatile SpyStorage instance;

    private final Helper helper;

    private SpyStorage(Context context) {
        helper = new Helper(context);
    }

    public static SpyStorage getInstance() {
        SpyStorage local = instance;
        if (local == null) {
            synchronized (SpyStorage.class) {
                local = instance;
                if (local == null) {
                    local = new SpyStorage(ApplicationLoader.applicationContext);
                    instance = local;
                }
            }
        }
        return local;
    }

    // ~ Удалённые сообщения

    /** @return rowId сохранённой записи или 0 при ошибке. */
    public long insertDeleted(SpyMessage message) {
        message.kind = SpyMessage.KIND_DELETED;
        return insert(message);
    }

    public boolean deletedExists(long userId, long dialogId, long topicId, int messageId) {
        String[] args = {
                String.valueOf(SpyMessage.KIND_DELETED),
                String.valueOf(userId),
                String.valueOf(dialogId),
                String.valueOf(topicId),
                String.valueOf(messageId)
        };
        try (Cursor cursor = helper.getReadableDatabase().rawQuery(
                "SELECT 1 FROM " + TABLE_MESSAGE
                        + " WHERE kind = ? AND user_id = ? AND dialog_id = ? AND topic_id = ? AND message_id = ? LIMIT 1",
                args)) {
            return cursor.moveToFirst();
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public SpyMessage getDeleted(long userId, long dialogId, int messageId) {
        List<SpyMessage> result = query(
                "kind = ? AND user_id = ? AND dialog_id = ? AND message_id = ?",
                new String[]{
                        String.valueOf(SpyMessage.KIND_DELETED),
                        String.valueOf(userId),
                        String.valueOf(dialogId),
                        String.valueOf(messageId)
                },
                "message_id", "1");
        return result.isEmpty() ? null : result.get(0);
    }

    /**
     * Удалённые сообщения диалога начиная с указанного идентификатора —
     * для подмешивания в историю чата.
     *
     * Диапазон ограничен только снизу. Ограничение сверху выглядит логичным,
     * но ломает главный сценарий: сообщение, удалённое последним в чате, имеет
     * id больше всех оставшихся и в такую выборку не попадает.
     *
     * Сортировка по убыванию: если сохранённых сообщений больше лимита,
     * показать нужно самые свежие.
     */
    public List<SpyMessage> getDeletedFrom(long userId, long dialogId, int startId, int limit) {
        return query(
                "kind = ? AND user_id = ? AND dialog_id = ? AND message_id >= ?",
                new String[]{
                        String.valueOf(SpyMessage.KIND_DELETED),
                        String.valueOf(userId),
                        String.valueOf(dialogId),
                        String.valueOf(startId)
                },
                "message_id DESC", String.valueOf(limit));
    }

    /** Все части удалённого альбома. */
    public List<SpyMessage> getDeletedGrouped(long userId, long dialogId, long groupedId) {
        return query(
                "kind = ? AND user_id = ? AND dialog_id = ? AND grouped_id = ?",
                new String[]{
                        String.valueOf(SpyMessage.KIND_DELETED),
                        String.valueOf(userId),
                        String.valueOf(dialogId),
                        String.valueOf(groupedId)
                },
                "message_id", null);
    }

    /** Удаляет сохранённое сообщение вместе с его реакциями и вложением. */
    public void deleteSaved(long userId, long dialogId, int messageId) {
        SpyMessage message = getDeleted(userId, dialogId, messageId);
        if (message == null) {
            return;
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_REACTION, "message_row_id = ?", new String[]{String.valueOf(message.rowId)});
            db.delete(TABLE_MESSAGE, "id = ?", new String[]{String.valueOf(message.rowId)});
            db.setTransactionSuccessful();
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            db.endTransaction();
        }
        SpyAttachments.remove(message.mediaPath);
        SpyAttachments.remove(message.thumbPath);
    }

    // ~ История правок

    public long insertRevision(SpyMessage message) {
        message.kind = SpyMessage.KIND_REVISION;
        return insert(message);
    }

    public List<SpyMessage> getRevisions(long userId, long dialogId, int messageId) {
        return query(
                "kind = ? AND user_id = ? AND dialog_id = ? AND message_id = ?",
                new String[]{
                        String.valueOf(SpyMessage.KIND_REVISION),
                        String.valueOf(userId),
                        String.valueOf(dialogId),
                        String.valueOf(messageId)
                },
                "entity_create_date", null);
    }

    public SpyMessage getLastRevision(long userId, long dialogId, int messageId) {
        List<SpyMessage> result = query(
                "kind = ? AND user_id = ? AND dialog_id = ? AND message_id = ?",
                new String[]{
                        String.valueOf(SpyMessage.KIND_REVISION),
                        String.valueOf(userId),
                        String.valueOf(dialogId),
                        String.valueOf(messageId)
                },
                "entity_create_date DESC", "1");
        return result.isEmpty() ? null : result.get(0);
    }

    public boolean hasRevisions(long userId, long dialogId, int messageId) {
        String[] args = {
                String.valueOf(SpyMessage.KIND_REVISION),
                String.valueOf(userId),
                String.valueOf(dialogId),
                String.valueOf(messageId)
        };
        try (Cursor cursor = helper.getReadableDatabase().rawQuery(
                "SELECT 1 FROM " + TABLE_MESSAGE
                        + " WHERE kind = ? AND user_id = ? AND dialog_id = ? AND message_id = ? LIMIT 1",
                args)) {
            return cursor.moveToFirst();
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * Проставляет ранним ревизиям путь к скопированному вложению.
     *
     * Нужно, когда медиа сообщения заменили: у старых ревизий записан путь к файлу
     * Telegram, который уже перезаписан новым содержимым. Логика повторяет
     * AyuGram (EditedMessageDao.updateAttachmentForRevisionsBetweenDates).
     */
    public void replaceRevisionMedia(long userId, long dialogId, int messageId, String oldPath, String newPath) {
        if (TextUtils.isEmpty(oldPath) || TextUtils.isEmpty(newPath)) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("media_path", newPath);
        try {
            helper.getWritableDatabase().update(TABLE_MESSAGE, values,
                    "kind = ? AND user_id = ? AND dialog_id = ? AND message_id = ? AND media_path = ?",
                    new String[]{
                            String.valueOf(SpyMessage.KIND_REVISION),
                            String.valueOf(userId),
                            String.valueOf(dialogId),
                            String.valueOf(messageId),
                            oldPath
                    });
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // ~ Реакции

    public void insertReaction(SpyReaction reaction) {
        ContentValues values = new ContentValues();
        values.put("message_row_id", reaction.messageRowId);
        values.put("count", reaction.count);
        values.put("self_selected", reaction.selfSelected ? 1 : 0);
        values.put("emoticon", reaction.emoticon);
        values.put("document_id", reaction.documentId);
        values.put("is_custom", reaction.custom ? 1 : 0);
        try {
            helper.getWritableDatabase().insert(TABLE_REACTION, null, values);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public List<SpyReaction> getReactions(long messageRowId) {
        List<SpyReaction> result = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(TABLE_REACTION, null,
                "message_row_id = ?", new String[]{String.valueOf(messageRowId)},
                null, null, "id")) {
            while (cursor.moveToNext()) {
                SpyReaction reaction = new SpyReaction();
                reaction.rowId = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
                reaction.messageRowId = messageRowId;
                reaction.count = cursor.getInt(cursor.getColumnIndexOrThrow("count"));
                reaction.selfSelected = cursor.getInt(cursor.getColumnIndexOrThrow("self_selected")) != 0;
                reaction.emoticon = cursor.getString(cursor.getColumnIndexOrThrow("emoticon"));
                reaction.documentId = cursor.getLong(cursor.getColumnIndexOrThrow("document_id"));
                reaction.custom = cursor.getInt(cursor.getColumnIndexOrThrow("is_custom")) != 0;
                result.add(reaction);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    // ~ Обслуживание

    public int count(int kind) {
        try (Cursor cursor = helper.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_MESSAGE + " WHERE kind = ?",
                new String[]{String.valueOf(kind)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }

    /** Полностью очищает базу и хранилище вложений. */
    public void clear() {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_REACTION, null, null);
            db.delete(TABLE_MESSAGE, null, null);
            db.setTransactionSuccessful();
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            db.endTransaction();
        }
        SpyAttachments.clear();
    }

    // ~ Внутреннее

    private long insert(SpyMessage message) {
        ContentValues values = new ContentValues();
        values.put("kind", message.kind);
        values.put("user_id", message.userId);
        values.put("dialog_id", message.dialogId);
        values.put("topic_id", message.topicId);
        values.put("grouped_id", message.groupedId);
        values.put("peer_id", message.peerId);
        values.put("from_id", message.fromId);
        values.put("message_id", message.messageId);
        values.put("date", message.date);
        values.put("flags", message.flags);
        values.put("edit_date", message.editDate);
        values.put("views", message.views);
        values.put("fwd_flags", message.fwdFlags);
        values.put("fwd_from_id", message.fwdFromId);
        values.put("fwd_name", message.fwdName);
        values.put("fwd_date", message.fwdDate);
        values.put("fwd_post_author", message.fwdPostAuthor);
        values.put("reply_flags", message.replyFlags);
        values.put("reply_message_id", message.replyMessageId);
        values.put("reply_peer_id", message.replyPeerId);
        values.put("reply_top_id", message.replyTopId);
        values.put("reply_forum_topic", message.replyForumTopic ? 1 : 0);
        values.put("entity_create_date", message.entityCreateDate);
        values.put("text", message.text);
        values.put("text_entities", message.textEntities);
        values.put("media_path", message.mediaPath);
        values.put("thumb_path", message.thumbPath);
        values.put("document_type", message.documentType);
        values.put("media", message.media);
        try {
            long rowId = helper.getWritableDatabase().insert(TABLE_MESSAGE, null, values);
            message.rowId = rowId;
            return rowId;
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }

    private List<SpyMessage> query(String selection, String[] args, String orderBy, String limit) {
        List<SpyMessage> result = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(TABLE_MESSAGE, null,
                selection, args, null, null, orderBy, limit)) {
            while (cursor.moveToNext()) {
                result.add(read(cursor));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    private static SpyMessage read(Cursor cursor) {
        SpyMessage message = new SpyMessage();
        message.rowId = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        message.kind = cursor.getInt(cursor.getColumnIndexOrThrow("kind"));
        message.userId = cursor.getLong(cursor.getColumnIndexOrThrow("user_id"));
        message.dialogId = cursor.getLong(cursor.getColumnIndexOrThrow("dialog_id"));
        message.topicId = cursor.getLong(cursor.getColumnIndexOrThrow("topic_id"));
        message.groupedId = cursor.getLong(cursor.getColumnIndexOrThrow("grouped_id"));
        message.peerId = cursor.getLong(cursor.getColumnIndexOrThrow("peer_id"));
        message.fromId = cursor.getLong(cursor.getColumnIndexOrThrow("from_id"));
        message.messageId = cursor.getInt(cursor.getColumnIndexOrThrow("message_id"));
        message.date = cursor.getInt(cursor.getColumnIndexOrThrow("date"));
        message.flags = cursor.getInt(cursor.getColumnIndexOrThrow("flags"));
        message.editDate = cursor.getInt(cursor.getColumnIndexOrThrow("edit_date"));
        message.views = cursor.getInt(cursor.getColumnIndexOrThrow("views"));
        message.fwdFlags = cursor.getInt(cursor.getColumnIndexOrThrow("fwd_flags"));
        message.fwdFromId = cursor.getLong(cursor.getColumnIndexOrThrow("fwd_from_id"));
        message.fwdName = cursor.getString(cursor.getColumnIndexOrThrow("fwd_name"));
        message.fwdDate = cursor.getInt(cursor.getColumnIndexOrThrow("fwd_date"));
        message.fwdPostAuthor = cursor.getString(cursor.getColumnIndexOrThrow("fwd_post_author"));
        message.replyFlags = cursor.getInt(cursor.getColumnIndexOrThrow("reply_flags"));
        message.replyMessageId = cursor.getInt(cursor.getColumnIndexOrThrow("reply_message_id"));
        message.replyPeerId = cursor.getLong(cursor.getColumnIndexOrThrow("reply_peer_id"));
        message.replyTopId = cursor.getInt(cursor.getColumnIndexOrThrow("reply_top_id"));
        message.replyForumTopic = cursor.getInt(cursor.getColumnIndexOrThrow("reply_forum_topic")) != 0;
        message.entityCreateDate = cursor.getInt(cursor.getColumnIndexOrThrow("entity_create_date"));
        message.text = cursor.getString(cursor.getColumnIndexOrThrow("text"));
        message.textEntities = cursor.getBlob(cursor.getColumnIndexOrThrow("text_entities"));
        message.mediaPath = cursor.getString(cursor.getColumnIndexOrThrow("media_path"));
        message.thumbPath = cursor.getString(cursor.getColumnIndexOrThrow("thumb_path"));
        message.documentType = cursor.getInt(cursor.getColumnIndexOrThrow("document_type"));
        message.media = cursor.getBlob(cursor.getColumnIndexOrThrow("media"));
        return message;
    }

    private static final class Helper extends SQLiteOpenHelper {

        Helper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onConfigure(SQLiteDatabase db) {
            super.onConfigure(db);
            // WAL заметно ускоряет одиночные вставки, которых у модуля большинство.
            db.enableWriteAheadLogging();
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_MESSAGE + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "kind INTEGER NOT NULL,"
                    + "user_id INTEGER NOT NULL,"
                    + "dialog_id INTEGER NOT NULL,"
                    + "topic_id INTEGER NOT NULL DEFAULT 0,"
                    + "grouped_id INTEGER NOT NULL DEFAULT 0,"
                    + "peer_id INTEGER NOT NULL DEFAULT 0,"
                    + "from_id INTEGER NOT NULL DEFAULT 0,"
                    + "message_id INTEGER NOT NULL,"
                    + "date INTEGER NOT NULL DEFAULT 0,"
                    + "flags INTEGER NOT NULL DEFAULT 0,"
                    + "edit_date INTEGER NOT NULL DEFAULT 0,"
                    + "views INTEGER NOT NULL DEFAULT 0,"
                    + "fwd_flags INTEGER NOT NULL DEFAULT 0,"
                    + "fwd_from_id INTEGER NOT NULL DEFAULT 0,"
                    + "fwd_name TEXT,"
                    + "fwd_date INTEGER NOT NULL DEFAULT 0,"
                    + "fwd_post_author TEXT,"
                    + "reply_flags INTEGER NOT NULL DEFAULT 0,"
                    + "reply_message_id INTEGER NOT NULL DEFAULT 0,"
                    + "reply_peer_id INTEGER NOT NULL DEFAULT 0,"
                    + "reply_top_id INTEGER NOT NULL DEFAULT 0,"
                    + "reply_forum_topic INTEGER NOT NULL DEFAULT 0,"
                    + "entity_create_date INTEGER NOT NULL DEFAULT 0,"
                    + "text TEXT,"
                    + "text_entities BLOB,"
                    + "media_path TEXT,"
                    + "thumb_path TEXT,"
                    + "document_type INTEGER NOT NULL DEFAULT 0,"
                    + "media BLOB)");
            db.execSQL("CREATE INDEX idx_spy_message_lookup ON " + TABLE_MESSAGE
                    + " (kind, user_id, dialog_id, message_id)");
            db.execSQL("CREATE INDEX idx_spy_message_range ON " + TABLE_MESSAGE
                    + " (kind, user_id, dialog_id, topic_id, message_id)");
            db.execSQL("CREATE INDEX idx_spy_message_grouped ON " + TABLE_MESSAGE
                    + " (kind, user_id, dialog_id, grouped_id)");

            db.execSQL("CREATE TABLE " + TABLE_REACTION + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "message_row_id INTEGER NOT NULL,"
                    + "count INTEGER NOT NULL DEFAULT 0,"
                    + "self_selected INTEGER NOT NULL DEFAULT 0,"
                    + "emoticon TEXT,"
                    + "document_id INTEGER NOT NULL DEFAULT 0,"
                    + "is_custom INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX idx_spy_reaction_message ON " + TABLE_REACTION + " (message_row_id)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                // Схема v1 существовала только в ветке разработки и никогда ничего не хранила:
                // перехват сообщений ещё не был подключён, поэтому терять нечего.
                db.execSQL("DROP TABLE IF EXISTS " + TABLE_REACTION);
                db.execSQL("DROP TABLE IF EXISTS " + TABLE_MESSAGE);
                onCreate(db);
            }
        }
    }
}
