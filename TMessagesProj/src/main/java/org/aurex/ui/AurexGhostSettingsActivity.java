package org.aurex.ui;

import static org.telegram.messenger.LocaleController.getString;

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
 * Верхняя строка — это группа: слева круглая галочка (включает/выключает сразу весь
 * набор), справа счётчик включённых пунктов и стрелка, сворачивающая список.
 * Так экран не пугает десятком тумблеров сразу, но при этом даёт тонкую настройку.
 *
 * Настройки хранятся в положительной логике ("отправлять прочтения"), а показываются
 * в отрицательной ("Не читать сообщения"). Инверсия собрана в одном месте — в таблице
 * Option ниже, чтобы её нельзя было случайно применить дважды.
 */
public class AurexGhostSettingsActivity extends UniversalFragment {

    private static final int GROUP_GHOST = 1;
    private static final int BTN_READ_AFTER_ACTION = 2;
    private static final int OPTION_ID_OFFSET = 100;

    /** Пункт группы: настройка, её заголовок и способ отображения. */
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

    private boolean collapsed;

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
        items.add(UItem.asHeader(getString(R.string.AurexGhostMode)));
        items.add(UItem.asRoundGroupCheckbox(
                        GROUP_GHOST,
                        getString(R.string.AurexGhostMode),
                        String.format(Locale.US, "%d/%d", checkedCount(), OPTIONS.length)
                )
                .setChecked(GhostMode.isEnabled())
                .setCollapsed(collapsed)
                .setClickCallback(v -> {
                    // Стрелка сворачивает список, не трогая сами настройки.
                    collapsed = !collapsed;
                    listView.adapter.update(true);
                }));

        if (!collapsed) {
            for (int i = 0; i < OPTIONS.length; i++) {
                items.add(UItem.asRoundCheckbox(OPTION_ID_OFFSET + i, getString(OPTIONS[i].titleRes))
                        .setChecked(OPTIONS[i].isChecked())
                        .setPad(1));
            }
        }
        items.add(UItem.asShadow(getString(R.string.AurexGhostEssentialsInfo)));

        items.add(UItem.asSwitch(BTN_READ_AFTER_ACTION, getString(R.string.AurexReadAfterAction))
                .setChecked(AurexFeatures.READ_AFTER_ACTION.get()));
        items.add(UItem.asShadow(getString(R.string.AurexReadAfterActionInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == GROUP_GHOST) {
            GhostMode.toggle();
        } else if (item.id == BTN_READ_AFTER_ACTION) {
            AurexFeatures.READ_AFTER_ACTION.toggle();
        } else if (item.id >= OPTION_ID_OFFSET && item.id < OPTION_ID_OFFSET + OPTIONS.length) {
            OPTIONS[item.id - OPTION_ID_OFFSET].toggle();
        } else {
            return;
        }
        // Один источник правды: перерисовываем список из текущих значений настроек,
        // а не двигаем состояние ячеек руками.
        listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
