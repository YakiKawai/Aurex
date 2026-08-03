package org.aurex.features.spy;

/**
 * Снимок одного сообщения, сохранённый модулем «Шпион».
 *
 * Одна и та же структура используется и для удалённого сообщения, и для ревизии
 * правки — различает их только {@link #kind}. В AyuGram это две почти одинаковые
 * сущности (DeletedMessage и EditedMessage) с дублирующимися DAO.
 *
 * Неразложимые части сообщения (сущности форматирования и вложение) хранятся в BLOB
 * в штатной TL-сериализации Telegram — тогда при обновлении апстрима не нужно
 * сопровождать собственный формат.
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
    /** См. константы DOCUMENT_TYPE_* в {@link SpyAttachments}. */
    public int documentType;
    /**
     * TL-сериализованный TLRPC.MessageMedia целиком.
     *
     * AyuGram разбирает его на три отдельных BLOB (документ, миниатюры, атрибуты)
     * и потом собирает обратно вручную. Хранить медиа целиком проще и надёжнее:
     * сериализацией занимается сам Telegram, а поведение для пользователя то же самое.
     */
    public byte[] media;

    public boolean hasMedia() {
        return documentType != SpyAttachments.DOCUMENT_TYPE_NONE;
    }
}
