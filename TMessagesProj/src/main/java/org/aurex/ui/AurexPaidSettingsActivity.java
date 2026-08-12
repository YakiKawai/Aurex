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
 * ровно так выглядят одиночные настройки в официальном клиенте.
 *
 * У локальных подарков есть своя настройка, поэтому секция собрана так же, как
 * «Режим призрака»: шапка с настоящим переключателем и стрелкой раскрытия, а внутри
 * — то, что настраивается. Своих элементов интерфейса здесь нет ни одного.
 */
public class AurexPaidSettingsActivity extends UniversalFragment {

    private static final int BTN_BLOCK_STAR_REACTIONS = 1;
    private static final int BTN_LOCAL_PREMIUM = 2;
    private static final int GROUP_LOCAL_GIFTS = 3;

    /** Как и в «Режиме призрака»: при входе на экран карточка свёрнута. */
    private boolean giftsCollapsed = true;

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
    public void onResume() {
        super.onResume();
        // Остаток локальных звёзд мог измениться, пока экран был закрыт другим
        // (пользователь ушёл в чат, отправил подарок и вернулся назад).
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    /**
     * Подпись под названием функции — текущий локальный остаток.
     *
     * Звезда рисуется штатным способом Telegram: символ в тексте заменяется на
     * фирменную иконку, своих ресурсов мод для этого не добавляет.
     *
     * Важно: здесь и ниже показывается ИСКЛЮЧИТЕЛЬНО локальное значение. Реальный
     * баланс Telegram Stars этим экраном не читается и не отображается.
     */
    private CharSequence giftsSubtitle() {
        if (!LocalGifts.isEnabled()) {
            return getString(R.string.AurexStateOff);
        }
        final String balance = LocaleController.formatNumber(LocalGifts.getBalance(currentAccount), ',');
        return StarsIntroActivity.replaceStarsWithPlain("⭐ " + balance, .8f);
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
        items.add(UItem.asExpandableSwitch(
                        GROUP_LOCAL_GIFTS,
                        getString(R.string.AurexLocalGifts),
                        giftsSubtitle()
                )
                .setChecked(LocalGifts.isEnabled())
                .setCollapsed(giftsCollapsed)
                // Клик по самому переключателю — включить/выключить функцию целиком.
                .setClickCallback(v -> {
                    final boolean enable = !LocalGifts.isEnabled();
                    LocalGifts.setEnabled(enable);
                    // Включили — сразу показываем, что можно настроить; выключили — прячем.
                    giftsCollapsed = !enable;
                    listView.adapter.update(true);
                }));
        if (!giftsCollapsed) {
            // Штатный ползунок Telegram (SlideIntChooseView): шаг — одна звезда, поэтому
            // выбрать можно любое значение, а не только круглое. Текущее значение
            // компонент сам показывает над ползунком и обновляет его на каждом кадре.
            items.add(UItem.asIntSlideView(
                    1,
                    LocalGifts.MIN_AMOUNT,
                    LocalGifts.getAmount(),
                    LocalGifts.MAX_AMOUNT,
                    value -> StarsIntroActivity.replaceStarsWithPlain("⭐ " + LocaleController.formatNumber(value, ','), .8f),
                    // Запись прямо в настройки мода: adapter.update() здесь вызывать нельзя,
                    // иначе ползунок пересоздаётся прямо под пальцем и теряет жест.
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
        } else if (item.id == GROUP_LOCAL_GIFTS) {
            // Клик по строке — только раскрытие/сворачивание, как в режиме призрака.
            giftsCollapsed = !giftsCollapsed;
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
