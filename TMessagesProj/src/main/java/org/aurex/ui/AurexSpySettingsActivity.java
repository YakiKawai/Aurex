package org.aurex.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import org.aurex.core.AurexFeatures;
import org.aurex.core.BoolPref;
import org.aurex.features.spy.SpyConfig;
import org.aurex.features.spy.SpyMessage;
import org.aurex.features.spy.SpyStorage;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Экран «Режим шпиона».
 *
 * Повторяет структуру AyuGram (MessageSavingPreferencesActivity), но собран на тех же
 * штатных ячейках Telegram, что и {@link AurexGhostSettingsActivity}:
 *
 *  - простые переключатели — asSwitch;
 *  - «Сохранять медиа» — asExpandableSwitch (главный switch + счётчик N/M + стрелка),
 *    под ним — круглые чекбоксы областей чатов (asRoundCheckbox().setPad(1));
 *  - очистка базы — красная ячейка asSettingsCell().red() с подтверждением.
 *
 * Статистика читается в фоновой очереди: запросы к базе модуля блокирующие и в UI-потоке
 * им места нет (в отличие от AyuGram с allowMainThreadQueries).
 */
public class AurexSpySettingsActivity extends UniversalFragment {

    private static final int GROUP_MEDIA = 1;
    private static final int BTN_SAVE_DELETED = 2;
    private static final int BTN_SAVE_EDITS = 3;
    private static final int BTN_SAVE_FORMATTING = 4;
    private static final int BTN_SAVE_REACTIONS = 5;
    private static final int BTN_SAVE_FOR_BOTS = 6;
    private static final int BTN_CLEAR = 7;
    private static final int SCOPE_ID_OFFSET = 100;

    private static final class Scope {
        final BoolPref pref;
        final int titleRes;

        Scope(BoolPref pref, int titleRes) {
            this.pref = pref;
            this.titleRes = titleRes;
        }
    }

    private static final Scope[] MEDIA = {
            new Scope(AurexFeatures.SPY_SAVE_MEDIA_PRIVATE_CHATS, R.string.AurexSpySaveMediaPrivateChats),
            new Scope(AurexFeatures.SPY_SAVE_MEDIA_PUBLIC_CHANNELS, R.string.AurexSpySaveMediaPublicChannels),
            new Scope(AurexFeatures.SPY_SAVE_MEDIA_PRIVATE_CHANNELS, R.string.AurexSpySaveMediaPrivateChannels),
            new Scope(AurexFeatures.SPY_SAVE_MEDIA_PUBLIC_GROUPS, R.string.AurexSpySaveMediaPublicGroups),
            new Scope(AurexFeatures.SPY_SAVE_MEDIA_PRIVATE_GROUPS, R.string.AurexSpySaveMediaPrivateGroups)
    };

    /** Как в AyuGram: при входе список областей свёрнут. */
    private boolean mediaCollapsed = true;

    private int deletedCount;
    private int revisionsCount;

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        // Фирменные скруглённые секции — см. AurexSettingsActivity и docs/UI_GUIDELINES.md.
        listView.setSections();
        listView.adapter.setApplyBackground(false);
        actionBar.setAdaptiveBackground(listView);
        loadStats();
        return view;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.AurexSpyMode);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.AurexSpySaving)));
        items.add(UItem.asSwitch(BTN_SAVE_DELETED, getString(R.string.AurexSpySaveDeleted))
                .setChecked(AurexFeatures.SPY_SAVE_DELETED.get()));
        items.add(UItem.asSwitch(BTN_SAVE_EDITS, getString(R.string.AurexSpySaveEdits))
                .setChecked(AurexFeatures.SPY_SAVE_EDITS.get()));
        items.add(UItem.asShadow(getString(R.string.AurexSpySavingInfo)));

        items.add(UItem.asExpandableSwitch(
                        GROUP_MEDIA,
                        getString(R.string.AurexSpySaveMedia),
                        String.format(Locale.US, "%d/%d", SpyConfig.selectedMediaScopes(), MEDIA.length)
                )
                .setChecked(AurexFeatures.SPY_SAVE_MEDIA.get())
                .setCollapsed(mediaCollapsed)
                // Клик по самому switch'у — включить/выключить сохранение медиа целиком.
                .setClickCallback(v -> {
                    AurexFeatures.SPY_SAVE_MEDIA.toggle();
                    listView.adapter.update(true);
                }));
        if (!mediaCollapsed) {
            for (int i = 0; i < MEDIA.length; i++) {
                items.add(UItem.asRoundCheckbox(SCOPE_ID_OFFSET + i, getString(MEDIA[i].titleRes))
                        .setChecked(MEDIA[i].pref.get())
                        .setPad(1));
            }
        }
        items.add(UItem.asShadow(getString(R.string.AurexSpySaveMediaInfo)));

        items.add(UItem.asSwitch(BTN_SAVE_FORMATTING, getString(R.string.AurexSpySaveFormatting))
                .setChecked(AurexFeatures.SPY_SAVE_FORMATTING.get()));
        items.add(UItem.asSwitch(BTN_SAVE_REACTIONS, getString(R.string.AurexSpySaveReactions))
                .setChecked(AurexFeatures.SPY_SAVE_REACTIONS.get()));
        items.add(UItem.asSwitch(BTN_SAVE_FOR_BOTS, getString(R.string.AurexSpySaveForBots))
                .setChecked(AurexFeatures.SPY_SAVE_FOR_BOTS.get()));
        items.add(UItem.asShadow(getString(R.string.AurexSpyExtrasInfo)));

        items.add(UItem.asSettingsCell(BTN_CLEAR, R.drawable.msg_delete, getString(R.string.AurexSpyClear)).red());
        items.add(UItem.asShadow(statsText()));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == GROUP_MEDIA) {
            mediaCollapsed = !mediaCollapsed;
        } else if (item.id == BTN_SAVE_DELETED) {
            AurexFeatures.SPY_SAVE_DELETED.toggle();
        } else if (item.id == BTN_SAVE_EDITS) {
            AurexFeatures.SPY_SAVE_EDITS.toggle();
        } else if (item.id == BTN_SAVE_FORMATTING) {
            AurexFeatures.SPY_SAVE_FORMATTING.toggle();
        } else if (item.id == BTN_SAVE_REACTIONS) {
            AurexFeatures.SPY_SAVE_REACTIONS.toggle();
        } else if (item.id == BTN_SAVE_FOR_BOTS) {
            AurexFeatures.SPY_SAVE_FOR_BOTS.toggle();
        } else if (item.id == BTN_CLEAR) {
            confirmClear();
            return;
        } else if (item.id >= SCOPE_ID_OFFSET && item.id < SCOPE_ID_OFFSET + MEDIA.length) {
            MEDIA[item.id - SCOPE_ID_OFFSET].pref.toggle();
        } else {
            return;
        }
        listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void confirmClear() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.AurexSpyClearConfirmTitle));
        builder.setMessage(getString(R.string.AurexSpyClearConfirm));
        builder.setPositiveButton(getString(R.string.AurexSpyClear), (dialog, which) -> clear());
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        // Красная кнопка подтверждения — как у штатных деструктивных диалогов Telegram.
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof android.widget.TextView) {
            ((android.widget.TextView) button).setTextColor(
                    org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_text_RedBold));
        }
    }

    private void clear() {
        Utilities.globalQueue.postRunnable(() -> {
            SpyStorage.getInstance().clear();
            loadStats();
        });
    }

    /** Читает счётчики в фоне и обновляет экран на UI-потоке. */
    private void loadStats() {
        Utilities.globalQueue.postRunnable(() -> {
            SpyStorage storage = SpyStorage.getInstance();
            final int deleted = storage.count(SpyMessage.KIND_DELETED);
            final int revisions = storage.count(SpyMessage.KIND_REVISION);
            AndroidUtilities.runOnUIThread(() -> {
                deletedCount = deleted;
                revisionsCount = revisions;
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        });
    }

    private String statsText() {
        return String.format(Locale.getDefault(), getString(R.string.AurexSpyStorageStats), deletedCount, revisionsCount);
    }
}
