package org.aurex.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import org.aurex.core.AurexFeatures;
import org.aurex.features.paid.LocalPremium;
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
 * Каждая функция — отдельная секция вида «шапка — переключатель — пояснение»:
 * ровно так выглядят одиночные настройки в официальном клиенте.
 */
public class AurexPaidSettingsActivity extends UniversalFragment {

    private static final int BTN_BLOCK_STAR_REACTIONS = 1;
    private static final int BTN_LOCAL_PREMIUM = 2;

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

        items.add(UItem.asHeader(getString(R.string.AurexLocalPremiumHeader)));
        items.add(UItem.asSwitch(BTN_LOCAL_PREMIUM, getString(R.string.AurexLocalPremium))
                .setChecked(AurexFeatures.LOCAL_PREMIUM.get()));
        items.add(UItem.asShadow(getString(R.string.AurexLocalPremiumInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BTN_BLOCK_STAR_REACTIONS) {
            AurexFeatures.BLOCK_STAR_REACTIONS.toggle();
        } else if (item.id == BTN_LOCAL_PREMIUM) {
            // Не простое toggle(): вместе с флагом нужно перестроить папки, перезагрузить
            // реакции и промо и разослать уведомления — всё это делает сам модуль.
            LocalPremium.setEnabled(!LocalPremium.isEnabled());
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
