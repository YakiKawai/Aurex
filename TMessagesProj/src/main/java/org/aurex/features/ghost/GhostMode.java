package org.aurex.features.ghost;

import org.aurex.core.AurexFeatures;
import org.aurex.core.BoolPref;

/**
 * Режим призрака.
 *
 * Режим призрака — не отдельная настройка, а состояние набора настроек
 * (так же сделано в AyuGram). Главный переключатель выставляет их все сразу,
 * а отдельные тумблеры остаются доступны для тонкой настройки.
 * Благодаря этому невозможно рассогласование вида "галочка включена, а пакеты шлются".
 */
public final class GhostMode {

    /** Настройки, которые режим призрака выключает. */
    private static final BoolPref[] SILENCED = {
            AurexFeatures.SEND_READ_PACKETS,
            AurexFeatures.SEND_READ_STORIES,
            AurexFeatures.SEND_ONLINE_PACKETS,
            AurexFeatures.SEND_TYPING_PACKETS,
            AurexFeatures.SEND_UPLOAD_PROGRESS
    };

    private GhostMode() {
    }

    /** Режим призрака активен, если всё отключено и включён автоматический офлайн. */
    public static boolean isEnabled() {
        for (BoolPref pref : SILENCED) {
            if (pref.get()) {
                return false;
            }
        }
        return AurexFeatures.AUTO_OFFLINE.get();
    }

    public static void setEnabled(boolean enabled) {
        for (BoolPref pref : SILENCED) {
            pref.set(!enabled);
        }
        AurexFeatures.AUTO_OFFLINE.set(enabled);
    }

    public static boolean toggle() {
        boolean enabled = !isEnabled();
        setEnabled(enabled);
        return enabled;
    }
}
