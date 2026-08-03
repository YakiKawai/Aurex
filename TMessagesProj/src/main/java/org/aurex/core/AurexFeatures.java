package org.aurex.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Реестр всех переключателей мода.
 *
 * Правило проекта: каждая новая функция добавляет РОВНО одну константу сюда.
 * Код фич никогда не работает со строковыми ключами напрямую.
 *
 * Значения по умолчанию совпадают с AyuGram: по умолчанию клиент ведёт себя как
 * обычный Telegram, режим призрака выключен.
 */
public final class AurexFeatures {

    private AurexFeatures() {
    }

    // ~ Режим призрака
    // Храним настройки в положительной логике ("отправлять"), а не в отрицательной
    // ("не отправлять"), чтобы в коде не было двойных отрицаний вида !dontSend.
    // Инверсия для UI делается только в одном месте — на экране настроек.
    public static final BoolPref SEND_READ_PACKETS = new BoolPref("ghost_send_read_packets", true);
    public static final BoolPref SEND_READ_STORIES = new BoolPref("ghost_send_read_stories", true);
    public static final BoolPref SEND_ONLINE_PACKETS = new BoolPref("ghost_send_online_packets", true);
    public static final BoolPref SEND_TYPING_PACKETS = new BoolPref("ghost_send_typing_packets", true);
    public static final BoolPref SEND_UPLOAD_PROGRESS = new BoolPref("ghost_send_upload_progress", true);
    public static final BoolPref AUTO_OFFLINE = new BoolPref("ghost_auto_offline", false);
    public static final BoolPref READ_AFTER_ACTION = new BoolPref("ghost_read_after_action", true);

    // ~ Режим шпиона
    // Значения по умолчанию — как в AyuGram (AyuConfig.loadConfig).
    public static final BoolPref SPY_SAVE_DELETED = new BoolPref("spy_save_deleted", true);
    public static final BoolPref SPY_SAVE_EDITS = new BoolPref("spy_save_edits", true);
    public static final BoolPref SPY_SAVE_MEDIA = new BoolPref("spy_save_media", true);
    public static final BoolPref SPY_SAVE_MEDIA_PRIVATE_CHATS = new BoolPref("spy_save_media_private_chats", true);
    public static final BoolPref SPY_SAVE_MEDIA_PUBLIC_CHANNELS = new BoolPref("spy_save_media_public_channels", false);
    public static final BoolPref SPY_SAVE_MEDIA_PRIVATE_CHANNELS = new BoolPref("spy_save_media_private_channels", true);
    public static final BoolPref SPY_SAVE_MEDIA_PUBLIC_GROUPS = new BoolPref("spy_save_media_public_groups", false);
    public static final BoolPref SPY_SAVE_MEDIA_PRIVATE_GROUPS = new BoolPref("spy_save_media_private_groups", true);
    public static final BoolPref SPY_SAVE_FORMATTING = new BoolPref("spy_save_formatting", true);
    public static final BoolPref SPY_SAVE_REACTIONS = new BoolPref("spy_save_reactions", true);
    public static final BoolPref SPY_SAVE_FOR_BOTS = new BoolPref("spy_save_for_bots", true);

    private static final List<BoolPref> ALL = Collections.unmodifiableList(Arrays.asList(
            SEND_READ_PACKETS,
            SEND_READ_STORIES,
            SEND_ONLINE_PACKETS,
            SEND_TYPING_PACKETS,
            SEND_UPLOAD_PROGRESS,
            AUTO_OFFLINE,
            READ_AFTER_ACTION,
            SPY_SAVE_DELETED,
            SPY_SAVE_EDITS,
            SPY_SAVE_MEDIA,
            SPY_SAVE_MEDIA_PRIVATE_CHATS,
            SPY_SAVE_MEDIA_PUBLIC_CHANNELS,
            SPY_SAVE_MEDIA_PRIVATE_CHANNELS,
            SPY_SAVE_MEDIA_PUBLIC_GROUPS,
            SPY_SAVE_MEDIA_PRIVATE_GROUPS,
            SPY_SAVE_FORMATTING,
            SPY_SAVE_REACTIONS,
            SPY_SAVE_FOR_BOTS
    ));

    /** Все булевы настройки мода. Используется для массового сброса и отладки. */
    public static List<BoolPref> all() {
        return ALL;
    }
}
