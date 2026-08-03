package org.aurex.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import org.aurex.core.AurexFeatures;
import org.aurex.core.BoolPref;
import org.aurex.features.ghost.GhostMode;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Экран "Режим призрака".
 *
 * Компоненты и поведение — как в AyuGram (AyuGramPreferencesActivity), который, в свою
 * очередь, использует штатные ячейки Telegram:
 *
 *  - шапка группы = TextCheckCell2: слева название, справа настоящий switch, между ними
 *    счётчик "N/M" и стрелка раскрытия. В терминах UniversalFragment это
 *    {@link UItem#asExpandableSwitch(int, CharSequence, CharSequence)}
 *    (UniversalAdapter.VIEW_TYPE_EXPANDABLE_SWITCH -> TextCheckCell2 + setCollapseArrow).
 *    ВАЖНО: не путать с asRoundGroupCheckbox — там CheckBoxCell, у него switch'а нет;
 *    именно из-за этой ошибки главный переключатель раньше отсутствовал.
 *  - клик по самому switch'у    -> включить/выключить весь режим (item.clickCallback);
 *  - клик по строке             -> раскрыть/свернуть список (onClick);
 *  - при входе на экран список свёрнут (в AyuGram ghostModeMenuExpanded = false);
 *  - подпункты = CheckBoxCell с отступом: asRoundCheckbox(...).setPad(1).
 *
 * Настройки хранятся в положительной логике ("отправлять прочтения"), а показываются в
 * отрицательной ("Не читать сообщения") — как в AyuGram. Инверсия собрана в одном месте,
 * чтобы её нельзя было случайно применить дважды.
 */
public class AurexGhostSettingsActivity extends UniversalFragment {

    private static final int GROUP_GHOST = 1;
    private static final int BTN_READ_AFTER_ACTION = 2;
    private static final int OPTION_ID_OFFSET = 100;

    private static final class Option {
        final BoolPref pref;
        final int titleRes;
        /** true — строка показывает значение настройки наоборот ("Не читать"). */
        final boolean inverted;

        Option(BoolPref pref, int titleRes, boolean inverted) {
            this.pref = pref;
            this.titleRes = titleRes;
            this.inverted = inverted;
        }

        boolean isChecked() {
            return inverted != pref.get();
        }

        void toggle() {
            pref.set(!pref.get());
        }
    }

    private static final Option[] OPTIONS = {
            new Option(AurexFeatures.SEND_READ_PACKETS, R.string.AurexDontReadMessages, true),
            new Option(AurexFeatures.SEND_READ_STORIES, R.string.AurexDontReadStories, true),
            new Option(AurexFeatures.SEND_ONLINE_PACKETS, R.string.AurexDontSendOnline, true),
            new Option(AurexFeatures.SEND_TYPING_PACKETS, R.string.AurexDontSendTyping, true),
            new Option(AurexFeatures.SEND_UPLOAD_PROGRESS, R.string.AurexDontSendUploadProgress, true),
            new Option(AurexFeatures.AUTO_OFFLINE, R.string.AurexAutoOffline, false)
    };

    /** Как в AyuGram: при входе на экран список свёрнут. */
    private boolean collapsed = true;

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        // См. комментарий в AurexSettingsActivity и docs/UI_GUIDELINES.md:
        // фирменные скруглённые секции — dp(12) отступ, dp(16) радиус.
        listView.setSections();
        listView.adapter.setApplyBackground(false);
        actionBar.setAdaptiveBackground(listView);
        return view;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.AurexGhostMode);
    }

    private int checkedCount() {
        int count = 0;
        for (Option option : OPTIONS) {
            if (option.isChecked()) {
                count++;
            }
        }
        return count;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.AurexGhostEssentials)));
        items.add(UItem.asExpandableSwitch(
                        GROUP_GHOST,
                        getString(R.string.AurexGhostMode),
                        String.format(Locale.US, "%d/%d", checkedCount(), OPTIONS.length)
                )
                .setChecked(GhostMode.isEnabled())
                .setCollapsed(collapsed)
                // Клик по самому переключателю — включить/выключить весь режим целиком.
                .setClickCallback(v -> {
                    GhostMode.toggle();
                    listView.adapter.update(true);
                }));

        if (!collapsed) {
            for (int i = 0; i < OPTIONS.length; i++) {
                items.add(UItem.asRoundCheckbox(OPTION_ID_OFFSET + i, getString(OPTIONS[i].titleRes))
                        .setChecked(OPTIONS[i].isChecked())
                        .setPad(1));
            }
        }
        items.add(UItem.asShadow(getString(R.string.AurexGhostModeInfo)));

        items.add(UItem.asSwitch(BTN_READ_AFTER_ACTION, getString(R.string.AurexReadAfterAction))
                .setChecked(AurexFeatures.READ_AFTER_ACTION.get()));
        items.add(UItem.asShadow(getString(R.string.AurexReadAfterActionInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == GROUP_GHOST) {
            // Клик по строке — только раскрытие/сворачивание списка, как в AyuGram.
            collapsed = !collapsed;
        } else if (item.id == BTN_READ_AFTER_ACTION) {
            AurexFeatures.READ_AFTER_ACTION.toggle();
        } else if (item.id >= OPTION_ID_OFFSET && item.id < OPTION_ID_OFFSET + OPTIONS.length) {
            OPTIONS[item.id - OPTION_ID_OFFSET].toggle();
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
