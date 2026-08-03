package org.aurex.features.spy;

import org.aurex.core.AurexFeatures;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

/**
 * Правила модуля «Шпион»: что именно сохранять.
 *
 * Вся проверка условий собрана здесь, чтобы места перехвата событий оставались
 * одной строкой и не обрастали логикой.
 */
public final class SpyConfig {

    private SpyConfig() {
    }

    /** Сохранять ли удалённые сообщения этого диалога. */
    public static boolean saveDeletedFor(int accountId, long dialogId) {
        return AurexFeatures.SPY_SAVE_DELETED.get() && allowedDialog(accountId, dialogId);
    }

    /** Сохранять ли историю правок этого диалога. */
    public static boolean saveEditsFor(int accountId, long dialogId) {
        return AurexFeatures.SPY_SAVE_EDITS.get() && allowedDialog(accountId, dialogId);
    }

    /**
     * Диалоги с ботами исключаются, если выключен соответствующий переключатель.
     * Логика повторяет AyuConfig.saveDeletedMessageFor / saveEditedMessageFor.
     */
    private static boolean allowedDialog(int accountId, long dialogId) {
        if (AurexFeatures.SPY_SAVE_FOR_BOTS.get()) {
            return true;
        }
        TLRPC.User user = MessagesController.getInstance(accountId).getUser(Math.abs(dialogId));
        return user == null || !user.bot;
    }

    public static boolean saveFormatting() {
        return AurexFeatures.SPY_SAVE_FORMATTING.get();
    }

    public static boolean saveReactions() {
        return AurexFeatures.SPY_SAVE_REACTIONS.get();
    }

    /** Сколько типов чатов выбрано для сохранения медиа — для счётчика "N/M" в настройках. */
    public static int selectedMediaScopes() {
        int count = 0;
        if (AurexFeatures.SPY_SAVE_MEDIA_PRIVATE_CHATS.get()) count++;
        if (AurexFeatures.SPY_SAVE_MEDIA_PUBLIC_CHANNELS.get()) count++;
        if (AurexFeatures.SPY_SAVE_MEDIA_PRIVATE_CHANNELS.get()) count++;
        if (AurexFeatures.SPY_SAVE_MEDIA_PUBLIC_GROUPS.get()) count++;
        if (AurexFeatures.SPY_SAVE_MEDIA_PRIVATE_GROUPS.get()) count++;
        return count;
    }
}
