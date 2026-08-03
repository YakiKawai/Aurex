package org.aurex.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
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
 *
 * Иконки категорий — векторные drawable в стиле апстрима, окрашиваются самой ячейкой
 * в key_windowBackgroundWhiteGrayIcon.
 */
public class AurexSettingsActivity extends UniversalFragment {

    private static final int BTN_GHOST = 1;
    private static final int BTN_SPY = 2;

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        // Фирменные скруглённые карточки-секции: dp(12) отступ по краям, dp(16) радиус.
        // Рисует сам список; UniversalFragment по умолчанию этого не делает.
        listView.setSections();
        // Фон ячеек теперь рисует секция, а не сами ячейки — иначе белый прямоугольник
        // выезжает за скругления.
        listView.adapter.setApplyBackground(false);
        // Шапка подстраивает фон под скролл, как на штатных экранах.
        actionBar.setAdaptiveBackground(listView);
        return view;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.AurexSettings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.AurexSettingsCategories)));
        items.add(UItem.asSettingsCell(BTN_GHOST, R.drawable.msg_aurex_ghost, getString(R.string.AurexGhostMode)));
        items.add(UItem.asSettingsCell(BTN_SPY, R.drawable.msg_aurex_spy, getString(R.string.AurexSpyMode)));
        items.add(UItem.asShadow(AurexVersion.getFullVersion()));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BTN_GHOST) {
            presentFragment(new AurexGhostSettingsActivity());
        } else if (item.id == BTN_SPY) {
            presentFragment(new AurexSpySettingsActivity());
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
