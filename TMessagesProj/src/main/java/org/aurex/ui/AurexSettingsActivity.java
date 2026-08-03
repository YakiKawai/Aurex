package org.aurex.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.view.View;

import org.aurex.core.AurexVersion;
import org.aurex.features.ghost.GhostMode;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Корневой экран настроек мода: список категорий.
 *
 * Экран построен на UniversalFragment/UItem — это штатная система списков нового
 * Telegram (на ней же собран экран "Настройки" клиента). Она сама рисует скруглённые
 * карточки, отступы между секциями, разделители и анимации, поэтому нам не нужен
 * собственный адаптер и ручная нумерация строк.
 *
 * Практическая выгода на будущее: любые изменения оформления в апстриме
 * автоматически применяются и к нашим экранам, а при обновлении форка нечему
 * конфликтовать — мы не копируем разметку, а пользуемся общей.
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
        items.add(UItem.asSettingsCell(
                BTN_GHOST,
                R.drawable.settings_power,
                getString(R.string.AurexGhostMode),
                getString(GhostMode.isEnabled() ? R.string.AurexStateOn : R.string.AurexStateOff)
        ));
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

    @Override
    public void onResume() {
        super.onResume();
        // Значение "Вкл/Выкл" могло измениться на вложенном экране.
        if (listView != null) {
            listView.adapter.update(false);
        }
    }
}
