package org.aurex.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import org.aurex.core.AurexFeatures;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Экран «Платные возможности».
 *
 * Собран из тех же штатных компонентов апстрима (UniversalFragment + UItem), что и
 * остальные экраны мода и штатные настройки Telegram. См. docs/UI_GUIDELINES.md.
 *
 * Сейчас в категории одна функция, поэтому экран намеренно минимален: шапка секции,
 * один переключатель и пояснение под ним — ровно так, как выглядят одиночные настройки
 * в официальном клиенте. Новые платные функции добавляются отдельными секциями сюда же.
 */
public class AurexPaidSettingsActivity extends UniversalFragment {

    private static final int BTN_BLOCK_STAR_REACTIONS = 1;

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        // Фирменные скруглённые секции — см. AurexSettingsActivity и docs/UI_GUIDELINES.md.
        listView.setSections();
        listView.adapter.setApplyBackground(false);
        actionBar.setAdaptiveBackground(listView);
        return view;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.AurexPaidFeatures);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.AurexPaidReactions)));
        items.add(UItem.asSwitch(BTN_BLOCK_STAR_REACTIONS, getString(R.string.AurexBlockStarReactions))
                .setChecked(AurexFeatures.BLOCK_STAR_REACTIONS.get()));
        items.add(UItem.asShadow(getString(R.string.AurexBlockStarReactionsInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BTN_BLOCK_STAR_REACTIONS) {
            AurexFeatures.BLOCK_STAR_REACTIONS.toggle();
        } else {
            return;
        }
        listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
