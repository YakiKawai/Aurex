package org.aurex.features.spy;

import android.text.TextUtils;

import org.aurex.core.AurexFeatures;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

/**
 * Решает, нужно ли сохранять вложения для конкретного диалога.
 *
 * Разбиение на пять типов чатов и значения по умолчанию — как в AyuGram
 * (MessageSavingPreferencesActivity): публичные каналы и группы по умолчанию выключены,
 * чтобы не забивать память устройства общедоступным контентом.
 */
public final class SpyMediaScope {

    private SpyMediaScope() {
    }

    public static boolean allowed(int accountId, long dialogId) {
        if (!AurexFeatures.SPY_SAVE_MEDIA.get()) {
            return false;
        }
        if (dialogId >= 0) {
            return AurexFeatures.SPY_SAVE_MEDIA_PRIVATE_CHATS.get();
        }
        TLRPC.Chat chat = MessagesController.getInstance(accountId).getChat(-dialogId);
        if (chat == null) {
            // Неизвестный чат считаем приватной группой: поведение по умолчанию совпадает
            // с AyuGram — там такой диалог тоже попадает в ветку для групп.
            return AurexFeatures.SPY_SAVE_MEDIA_PRIVATE_GROUPS.get();
        }
        boolean isPublic = !TextUtils.isEmpty(ChatObject.getPublicUsername(chat));
        boolean isBroadcast = ChatObject.isChannel(chat) && !chat.megagroup;
        if (isBroadcast) {
            return isPublic
                    ? AurexFeatures.SPY_SAVE_MEDIA_PUBLIC_CHANNELS.get()
                    : AurexFeatures.SPY_SAVE_MEDIA_PRIVATE_CHANNELS.get();
        }
        return isPublic
                ? AurexFeatures.SPY_SAVE_MEDIA_PUBLIC_GROUPS.get()
                : AurexFeatures.SPY_SAVE_MEDIA_PRIVATE_GROUPS.get();
    }
}
