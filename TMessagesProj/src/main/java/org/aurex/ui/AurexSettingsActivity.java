package org.aurex.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.view.View;

import org.aurex.core.AurexVersion;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Корневой экран настроек мода: список категорий.
 *
 * Собран из штатных компонентов апстрима (UniversalFragment + UItem) — тех же, на которых
 * построены штатные экраны Telegram. См. docs/UI_GUIDELINES.md.
 */
public class AurexSettingsActivity extends UniversalFragment {

    private static final int BTN_GHOST = 1;

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.AurexSettings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.AurexSettingsCategories)));
        items.add(UItem.asSettingsCell(BTN_GHOST, R.drawable.settings_power, getString(R.string.AurexGhostMode)));
        items.add(UItem.asShadow(AurexVersion.getFullVersion()));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BTN_GHOST) {
            presentFragment(new AurexGhostSettingsActivity());
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
