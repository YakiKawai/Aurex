package org.aurex.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import org.aurex.core.AurexFeatures;
import org.aurex.features.paid.LocalGifts;
import org.aurex.features.paid.LocalPremium;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.Stars.StarsIntroActivity;

import java.util.ArrayList;

/**
 * Экран «Платные возможности».
 *
 * Собран из тех же штатных компонентов апстрима (UniversalFragment + UItem), что и
 * остальные экраны мода и штатные настройки Telegram. См. docs/UI_GUIDELINES.md.
 *
 * Каждая функция — отдельная секция вида «шапка — переключатель — пояснение»:
 * ровно так выглядят одиночные настройки в официальном клиенте. Своих текстовых
 * подписей вроде «Вкл» / «Выкл» здесь нет: состояние показывает сам переключатель,
 * как во всём остальном Telegram.
 *
 * У локальных подарков есть одна настройка — количество звёзд. Она показана
 * штатным ползунком в той же секции и только пока функция включена. Отдельного
 * числа рядом с названием функции нет сознательно: два источника одного значения
 * всегда расходятся. Значение показывает сам ползунок, обновляя его на каждом
 * кадре движения.
 */
public class AurexPaidSettingsActivity extends UniversalFragment {

    private static final int BTN_BLOCK_STAR_REACTIONS = 1;
    private static final int BTN_LOCAL_PREMIUM = 2;
    private static final int BTN_LOCAL_GIFTS = 3;

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

        items.add(UItem.asHeader(getString(R.string.AurexLocalGiftsHeader)));
        items.add(UItem.asSwitch(BTN_LOCAL_GIFTS, getString(R.string.AurexLocalGifts))
                .setChecked(LocalGifts.isEnabled()));
        if (LocalGifts.isEnabled()) {
            // Штатный ползунок Telegram (SlideIntChooseView): шаг — одна звезда, поэтому
            // выбрать можно любое значение, а не только круглое. Текущее значение
            // компонент сам показывает над ползунком и обновляет его на каждом кадре —
            // именно поэтому второй подписи с числом нигде больше нет.
            items.add(UItem.asIntSlideView(
                    1,
                    LocalGifts.MIN_AMOUNT,
                    LocalGifts.getAmount(),
                    LocalGifts.MAX_AMOUNT,
                    value -> StarsIntroActivity.replaceStarsWithPlain("⭐ " + LocaleController.formatNumber(value, ','), .8f),
                    // Запись прямо в настройки мода: значение ползунка и есть локальный
                    // баланс, поэтому он применяется сразу, без кнопки подтверждения и без
                    // перезапуска функции. adapter.update() здесь вызывать нельзя: иначе
                    // ползунок пересоздаётся прямо под пальцем и теряет жест.
                    LocalGifts::setAmount
            ));
        }
        items.add(UItem.asShadow(getString(R.string.AurexLocalGiftsInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BTN_BLOCK_STAR_REACTIONS) {
            AurexFeatures.BLOCK_STAR_REACTIONS.toggle();
        } else if (item.id == BTN_LOCAL_PREMIUM) {
            // Не простое toggle(): вместе с флагом нужно перестроить папки, перезагрузить
            // реакции и промо — всё это делает сам модуль.
            LocalPremium.setEnabled(!LocalPremium.isEnabled());
        } else if (item.id == BTN_LOCAL_GIFTS) {
            // Вместе с флагом модуль сбрасывает расход, чистит локальные подарки и
            // сообщает чатам и интерфейсу подарков об изменении.
            LocalGifts.setEnabled(!LocalGifts.isEnabled());
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
