package org.aurex.features.spy;

import android.text.TextUtils;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Преобразование TLRPC.Message в запись базы и обратно.
 *
 * В AyuGram эта часть (AyuMessageUtils) лежит в закрытом сабмодуле, поэтому логика
 * написана с нуля поверх штатной сериализации Telegram (SerializedData +
 * serializeToStream/TLdeserialize). Собственного бинарного формата у мода нет:
 * всё, что мы пишем в BLOB, умеет читать сам Telegram.
 */
public final class SpyMessageMapper {

    /** Магическое число TL-вектора. */
    private static final int VECTOR_MAGIC = 0x1cb5c415;

    private SpyMessageMapper() {
    }

    // ~ TLRPC.Message -> запись

    public static SpyMessage map(int accountId, long dialogId, TLRPC.Message msg) {
        SpyMessage saved = new SpyMessage();
        saved.userId = UserConfig.getInstance(accountId).getClientUserId();
        saved.dialogId = dialogId;
        saved.topicId = MessageObject.getTopicId(accountId, msg, false);
        saved.groupedId = msg.grouped_id;
        saved.peerId = msg.peer_id != null ? DialogObject.getPeerDialogId(msg.peer_id) : dialogId;
        saved.fromId = msg.from_id != null ? DialogObject.getPeerDialogId(msg.from_id) : 0;
        saved.messageId = msg.id;
        saved.date = msg.date;
        saved.flags = msg.flags;
        saved.editDate = msg.edit_date;
        saved.views = msg.views;
        saved.entityCreateDate = (int) (System.currentTimeMillis() / 1000L);

        if (msg.fwd_from != null) {
            saved.fwdFlags = msg.fwd_from.flags;
            saved.fwdFromId = msg.fwd_from.from_id != null
                    ? DialogObject.getPeerDialogId(msg.fwd_from.from_id)
                    : 0;
            saved.fwdName = msg.fwd_from.from_name;
            saved.fwdDate = msg.fwd_from.date;
            saved.fwdPostAuthor = msg.fwd_from.post_author;
        }

        if (msg.reply_to != null) {
            saved.replyFlags = msg.reply_to.flags;
            saved.replyMessageId = msg.reply_to.reply_to_msg_id;
            saved.replyPeerId = msg.reply_to.reply_to_peer_id != null
                    ? DialogObject.getPeerDialogId(msg.reply_to.reply_to_peer_id)
                    : 0;
            saved.replyTopId = msg.reply_to.reply_to_top_id;
            saved.replyForumTopic = msg.reply_to.forum_topic;
        }

        saved.text = msg.message;
        if (SpyConfig.saveFormatting() && msg.entities != null && !msg.entities.isEmpty()) {
            saved.textEntities = serializeEntities(msg.entities);
        }

        saved.documentType = documentType(msg.media);
        if (saved.documentType != SpyAttachments.DOCUMENT_TYPE_NONE) {
            saved.media = serializeMedia(msg.media);
        }
        return saved;
    }

    /** Тип вложения в терминах AyuConstants.DOCUMENT_TYPE_*. */
    public static int documentType(TLRPC.MessageMedia media) {
        if (media == null || media instanceof TLRPC.TL_messageMediaEmpty) {
            return SpyAttachments.DOCUMENT_TYPE_NONE;
        }
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            return SpyAttachments.DOCUMENT_TYPE_PHOTO;
        }
        if (media instanceof TLRPC.TL_messageMediaDocument) {
            TLRPC.Document document = ((TLRPC.TL_messageMediaDocument) media).document;
            if (document != null && document.attributes != null) {
                for (int i = 0; i < document.attributes.size(); i++) {
                    if (document.attributes.get(i) instanceof TLRPC.TL_documentAttributeSticker) {
                        return SpyAttachments.DOCUMENT_TYPE_STICKER;
                    }
                }
            }
            return SpyAttachments.DOCUMENT_TYPE_FILE;
        }
        return SpyAttachments.DOCUMENT_TYPE_NONE;
    }

    // ~ запись -> TLRPC.Message

    /**
     * Собирает из записи обычное сообщение Telegram, которое можно отдать в MessageObject
     * и отрисовать штатной ячейкой чата.
     */
    public static TLRPC.Message toMessage(int accountId, SpyMessage saved) {
        TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.id = saved.messageId;
        msg.date = saved.date;
        msg.flags = saved.flags;
        msg.edit_date = saved.editDate;
        msg.views = saved.views;
        msg.grouped_id = saved.groupedId;
        msg.dialog_id = saved.dialogId;
        msg.peer_id = peerOf(accountId, saved.peerId != 0 ? saved.peerId : saved.dialogId);
        if (saved.fromId != 0) {
            msg.from_id = peerOf(accountId, saved.fromId);
        }
        msg.message = saved.text != null ? saved.text : "";

        if (saved.fwdFlags != 0 || saved.fwdFromId != 0 || !TextUtils.isEmpty(saved.fwdName)) {
            TLRPC.TL_messageFwdHeader fwd = new TLRPC.TL_messageFwdHeader();
            fwd.flags = saved.fwdFlags;
            if (saved.fwdFromId != 0) {
                fwd.from_id = peerOf(accountId, saved.fwdFromId);
            }
            fwd.from_name = saved.fwdName;
            fwd.date = saved.fwdDate;
            fwd.post_author = saved.fwdPostAuthor;
            msg.fwd_from = fwd;
        }

        if (saved.replyMessageId != 0 || saved.replyTopId != 0) {
            TLRPC.TL_messageReplyHeader reply = new TLRPC.TL_messageReplyHeader();
            reply.flags = saved.replyFlags;
            reply.reply_to_msg_id = saved.replyMessageId;
            if (saved.replyPeerId != 0) {
                reply.reply_to_peer_id = peerOf(accountId, saved.replyPeerId);
            }
            reply.reply_to_top_id = saved.replyTopId;
            reply.forum_topic = saved.replyForumTopic;
            msg.reply_to = reply;
        }

        ArrayList<TLRPC.MessageEntity> entities = deserializeEntities(saved.textEntities);
        if (entities != null) {
            msg.entities = entities;
        }

        TLRPC.MessageMedia media = deserializeMedia(saved.media);
        if (media != null) {
            msg.media = media;
        }
        if (!TextUtils.isEmpty(saved.mediaPath)) {
            msg.attachPath = saved.mediaPath;
        }
        return msg;
    }

    /**
     * Собирает TLRPC.Peer по идентификатору диалога: положительный — пользователь,
     * отрицательный — группа или канал (различаем по типу чата).
     */
    private static TLRPC.Peer peerOf(int accountId, long id) {
        if (id >= 0) {
            TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
            peer.user_id = id;
            return peer;
        }
        long chatId = -id;
        TLRPC.Chat chat = MessagesController.getInstance(accountId).getChat(chatId);
        if (chat != null && ChatObject.isChannel(chat)) {
            TLRPC.TL_peerChannel peer = new TLRPC.TL_peerChannel();
            peer.channel_id = chatId;
            return peer;
        }
        TLRPC.TL_peerChat peer = new TLRPC.TL_peerChat();
        peer.chat_id = chatId;
        return peer;
    }

    // ~ Сериализация

    private static byte[] serializeEntities(ArrayList<TLRPC.MessageEntity> entities) {
        SerializedData data = new SerializedData();
        try {
            data.writeInt32(VECTOR_MAGIC);
            data.writeInt32(entities.size());
            for (int i = 0; i < entities.size(); i++) {
                entities.get(i).serializeToStream(data);
            }
            return data.toByteArray();
        } finally {
            data.cleanup();
        }
    }

    private static ArrayList<TLRPC.MessageEntity> deserializeEntities(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        SerializedData data = new SerializedData(bytes);
        try {
            if (data.readInt32(false) != VECTOR_MAGIC) {
                return null;
            }
            int count = data.readInt32(false);
            if (count <= 0) {
                return null;
            }
            ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                TLRPC.MessageEntity entity =
                        TLRPC.MessageEntity.TLdeserialize(data, data.readInt32(false), false);
                if (entity == null) {
                    break;
                }
                entities.add(entity);
            }
            return entities.isEmpty() ? null : entities;
        } finally {
            data.cleanup();
        }
    }

    private static byte[] serializeMedia(TLRPC.MessageMedia media) {
        if (media == null) {
            return null;
        }
        SerializedData data = new SerializedData();
        try {
            media.serializeToStream(data);
            return data.toByteArray();
        } finally {
            data.cleanup();
        }
    }

    private static TLRPC.MessageMedia deserializeMedia(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        SerializedData data = new SerializedData(bytes);
        try {
            return TLRPC.MessageMedia.TLdeserialize(data, data.readInt32(false), false);
        } finally {
            data.cleanup();
        }
    }
}
