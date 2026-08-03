package org.aurex.core;

import org.telegram.ui.ActionBar.BaseFragment;
import org.aurex.ui.AurexSettingsActivity;

/**
 * Единственный мост между кодом официального Telegram и кодом мода.
 *
 * Правило проекта: любая врезка в файлы апстрима вызывает ТОЛЬКО методы этого
 * класса и не содержит никакой логики. Тогда врезка занимает одну–две строки,
 * а конфликты при мерже новых версий Telegram сводятся к минимуму.
 *
 * Каждая врезка в апстриме обязана быть обёрнута маркерами:
 *   // AUREX >>> причина
 *   ...
 *   // AUREX <<<
 * Полный список врезок ведётся в docs/PATCHES.md.
 */
public final class AurexHooks {

    /**
     * ID пункта "Aurex" в списке настроек приложения.
     * Апстрим использует небольшие id (1..23), поэтому мод занимает диапазон от 1000 —
     * это гарантирует отсутствие пересечений при добавлении новых пунктов Telegram.
     */
    public static final int SETTINGS_ITEM_ID = 1000;

    private AurexHooks() {
    }

    /** Открывает корневой экран настроек мода. */
    public static void openSettings(BaseFragment fragment) {
        if (fragment == null) {
            return;
        }
        fragment.presentFragment(new AurexSettingsActivity());
    }
}
