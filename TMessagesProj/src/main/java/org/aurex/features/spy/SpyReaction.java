package org.aurex.features.spy;

/**
 * Реакция, сохранённая вместе с удалённым сообщением.
 *
 * Обычная реакция описывается эмодзи, кастомная — идентификатором документа.
 */
public final class SpyReaction {

    public long rowId;
    /** {@link SpyMessage#rowId} сообщения, которому принадлежит реакция. */
    public long messageRowId;
    public int count;
    public boolean selfSelected;
    public String emoticon;
    public long documentId;
    public boolean custom;
}
