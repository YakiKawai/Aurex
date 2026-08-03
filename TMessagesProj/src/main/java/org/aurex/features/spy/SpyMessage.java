package org.aurex.features.spy;

/**
 * Снимок одного сообщения, сохранённый модулем «Шпион».
 *
 * Одна и та же структура используется и для удалённого сообщения, и для ревизии
 * правки — различает их только {@link #kind}. Так устроено и в AyuGram
 * (AyuMessageBase -> DeletedMessage / EditedMessage), но у нас это одна таблица
 * с индексом по kind: меньше кода, меньше дублирующихся запросов.
 *
 * Поля намеренно повторяют набор полей TLRPC.Message, который нужен, чтобы
 * восстановить сообщение обратно: всё, что нельзя разложить по колонкам
 * (сущности форматирования, документ, миниатюры, атрибуты), хранится в BLOB
 * в штатной TL-сериализации Telegram.
 */
public final class SpyMessage {

    /** Удалённое сообщение. */
    public static final int KIND_DELETED = 0;
    /** Ревизия отредактированного сообщения. */
    public static final int KIND_REVISION = 1;

    /** Идентификатор строки в базе. 0 — запись ещё не сохранена. */
    public long rowId;
    public int kind;

    /** Идентификатор аккаунта-владельца (clientUserId), а не индекс аккаунта. */
    public long userId;
    public long dialogId;
    public long topicId;
    public long groupedId;
    public long peerId;
    public long fromId;
    public int messageId;
    public int date;
    public int flags;

    public int editDate;
    public int views;

    public int fwdFlags;
    public long fwdFromId;
    public String fwdName;
    public int fwdDate;
    public String fwdPostAuthor;

    public int replyFlags;
    public int replyMessageId;
    public long replyPeerId;
    public int replyTopId;
    public boolean replyForumTopic;

    /** Момент, когда мод поймал событие (unix time, секунды). */
    public int entityCreateDate;

    public String text;
    /** TL-сериализованный список TLRPC.MessageEntity. */
    public byte[] textEntities;

    /** Абсолютный путь к скопированному вложению внутри хранилища мода. */
    public String mediaPath;
    /** Абсолютный путь к сохранённой миниатюре. */
    public String thumbPath;
    public int documentType;
    /** TL-сериализованный TLRPC.Document. */
    public byte[] document;
    /** TL-сериализованный список TLRPC.PhotoSize. */
    public byte[] thumbs;
    /** TL-сериализованный список TLRPC.DocumentAttribute. */
    public byte[] documentAttributes;
    public String mimeType;

    public boolean hasMedia() {
        return documentType != SpyAttachments.DOCUMENT_TYPE_NONE;
    }
}
