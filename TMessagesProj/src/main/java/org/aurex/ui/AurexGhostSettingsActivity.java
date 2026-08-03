package org.aurex.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.aurex.core.AurexFeatures;
import org.aurex.core.BoolPref;
import org.aurex.features.ghost.GhostMode;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/**
 * Экран раздела "Режим призрака".
 *
 * Главный переключатель выставляет весь набор настроек сразу, отдельные тумблеры
 * позволяют настроить поведение точечно — как в AyuGram.
 *
 * Настройки хранятся в положительной логике ("отправлять прочтения"), а показываются
 * в отрицательной ("Не читать сообщения"), поэтому инверсия собрана здесь и только здесь.
 */
public class AurexGhostSettingsActivity extends BaseFragment {

    private int rowCount;
    private int ghostToggleRow;
    private int ghostToggleInfoRow;
    private int essentialsHeaderRow;
    private int dontReadMessagesRow;
    private int dontReadStoriesRow;
    private int dontSendOnlineRow;
    private int dontSendTypingRow;
    private int dontSendUploadProgressRow;
    private int autoOfflineRow;
    private int readAfterActionRow;
    private int essentialsInfoRow;

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    @Override
    public boolean onFragmentCreate() {
        updateRows();
        return super.onFragmentCreate();
    }

    private void updateRows() {
        rowCount = 0;
        ghostToggleRow = rowCount++;
        ghostToggleInfoRow = rowCount++;
        essentialsHeaderRow = rowCount++;
        dontReadMessagesRow = rowCount++;
        dontReadStoriesRow = rowCount++;
        dontSendOnlineRow = rowCount++;
        dontSendTypingRow = rowCount++;
        dontSendUploadProgressRow = rowCount++;
        autoOfflineRow = rowCount++;
        readAfterActionRow = rowCount++;
        essentialsInfoRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.AurexGhostMode));
        if (AndroidUtilities.isTablet()) {
            actionBar.setOccupyStatusBar(false);
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listAdapter = new ListAdapter(context);

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(view, position));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    private void onRowClick(View view, int position) {
        if (position == ghostToggleRow) {
            boolean enabled = GhostMode.toggle();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            // Главный переключатель меняет все остальные строки.
            if (listAdapter != null) {
                listAdapter.notifyItemRangeChanged(essentialsHeaderRow, rowCount - essentialsHeaderRow);
            }
            return;
        }

        BoolPref pref = prefForRow(position);
        if (pref == null) {
            return;
        }
        boolean value = pref.toggle();
        if (view instanceof TextCheckCell) {
            // Строки "Не ..." показывают инверсию хранимого значения.
            ((TextCheckCell) view).setChecked(isInverted(position) != value);
        }
        if (listAdapter != null) {
            listAdapter.notifyItemChanged(ghostToggleRow);
        }
    }

    private BoolPref prefForRow(int position) {
        if (position == dontReadMessagesRow) {
            return AurexFeatures.SEND_READ_PACKETS;
        }
        if (position == dontReadStoriesRow) {
            return AurexFeatures.SEND_READ_STORIES;
        }
        if (position == dontSendOnlineRow) {
            return AurexFeatures.SEND_ONLINE_PACKETS;
        }
        if (position == dontSendTypingRow) {
            return AurexFeatures.SEND_TYPING_PACKETS;
        }
        if (position == dontSendUploadProgressRow) {
            return AurexFeatures.SEND_UPLOAD_PROGRESS;
        }
        if (position == autoOfflineRow) {
            return AurexFeatures.AUTO_OFFLINE;
        }
        if (position == readAfterActionRow) {
            return AurexFeatures.READ_AFTER_ACTION;
        }
        return null;
    }

    /** Строки вида "Не читать ..." показывают значение наоборот. */
    private boolean isInverted(int position) {
        return position == dontReadMessagesRow
                || position == dontReadStoriesRow
                || position == dontSendOnlineRow
                || position == dontSendTypingRow
                || position == dontSendUploadProgressRow;
    }

    private static final int VIEW_TYPE_CHECK = 0;
    private static final int VIEW_TYPE_HEADER = 1;
    private static final int VIEW_TYPE_INFO = 2;

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        public ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_CHECK;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == essentialsHeaderRow) {
                return VIEW_TYPE_HEADER;
            }
            if (position == ghostToggleInfoRow || position == essentialsInfoRow) {
                return VIEW_TYPE_INFO;
            }
            return VIEW_TYPE_CHECK;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_HEADER) {
                view = new HeaderCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else if (viewType == VIEW_TYPE_CHECK) {
                view = new TextCheckCell(context);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int viewType = holder.getItemViewType();
            if (viewType == VIEW_TYPE_HEADER) {
                ((HeaderCell) holder.itemView).setText(LocaleController.getString(R.string.AurexGhostEssentials));
                return;
            }
            if (viewType == VIEW_TYPE_INFO) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                if (position == ghostToggleInfoRow) {
                    cell.setText(LocaleController.getString(R.string.AurexGhostModeInfo));
                } else {
                    cell.setText(LocaleController.getString(R.string.AurexGhostEssentialsInfo));
                }
                return;
            }

            TextCheckCell cell = (TextCheckCell) holder.itemView;
            if (position == ghostToggleRow) {
                cell.setTextAndCheck(LocaleController.getString(R.string.AurexGhostMode), GhostMode.isEnabled(), false);
                return;
            }

            BoolPref pref = prefForRow(position);
            if (pref == null) {
                return;
            }
            boolean checked = isInverted(position) != pref.get();
            int titleRes;
            if (position == dontReadMessagesRow) {
                titleRes = R.string.AurexDontReadMessages;
            } else if (position == dontReadStoriesRow) {
                titleRes = R.string.AurexDontReadStories;
            } else if (position == dontSendOnlineRow) {
                titleRes = R.string.AurexDontSendOnline;
            } else if (position == dontSendTypingRow) {
                titleRes = R.string.AurexDontSendTyping;
            } else if (position == dontSendUploadProgressRow) {
                titleRes = R.string.AurexDontSendUploadProgress;
            } else if (position == autoOfflineRow) {
                titleRes = R.string.AurexAutoOffline;
            } else {
                titleRes = R.string.AurexReadAfterAction;
            }
            boolean divider = position != readAfterActionRow;
            cell.setTextAndCheck(LocaleController.getString(titleRes), checked, divider);
        }
    }
}
