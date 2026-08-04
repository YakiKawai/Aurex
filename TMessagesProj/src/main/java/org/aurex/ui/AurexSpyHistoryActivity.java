package org.aurex.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.aurex.features.spy.SpyMessage;
import org.aurex.features.spy.SpyMessageMapper;
import org.aurex.features.spy.SpyNotifications;
import org.aurex.features.spy.SpyStorage;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран «История правок»: все сохранённые версии одного сообщения, от старой к новой.
 *
 * Аналог AyuMessageHistory, но с двумя отличиями:
 *  - база читается в фоновой очереди, а не в конструкторе фрагмента в UI-потоке;
 *  - MessageObject создаётся с включённой генерацией раскладки (так апстрим делает везде,
 *    где сообщение действительно отрисовывается).
 */
public class AurexSpyHistoryActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private final MessageObject source;
    private final List<SpyMessage> revisions = new ArrayList<>();

    private RecyclerListView listView;
    private ListAdapter adapter;

    public AurexSpyHistoryActivity(MessageObject source) {
        this.source = source;
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        NotificationCenter.getInstance(currentAccount).addObserver(this, SpyNotifications.MESSAGE_EDITED);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, SpyNotifications.MESSAGE_EDITED);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.AurexSpyHistoryTitle));
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

        listView = new RecyclerListView(context);
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        });
        listView.setVerticalScrollBarEnabled(true);
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        loadRevisions();
        return fragmentView;
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        return new ArrayList<>();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != SpyNotifications.MESSAGE_EDITED) {
            return;
        }
        long dialogId = (long) args[0];
        int messageId = (int) args[1];
        if (dialogId == source.getDialogId() && messageId == source.getId()) {
            loadRevisions();
        }
    }

    /** Чтение базы модуля блокирующее, поэтому выполняется в фоновой очереди. */
    private void loadRevisions() {
        final long userId = getUserConfig().getClientUserId();
        final long dialogId = source.getDialogId();
        final int messageId = source.getId();
        Utilities.globalQueue.postRunnable(() -> {
            final List<SpyMessage> loaded = SpyStorage.getInstance().getRevisions(userId, dialogId, messageId);
            AndroidUtilities.runOnUIThread(() -> {
                revisions.clear();
                revisions.addAll(loaded);
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        });
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return revisions.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new RecyclerListView.Holder(new AurexSpyMessageCell(
                    context, currentAccount, getParentActivity(), AurexSpyHistoryActivity.this));
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            AurexSpyMessageCell cell = (AurexSpyMessageCell) holder.itemView;
            SpyMessage revision = revisions.get(position);
            cell.setRevision(revision);
            cell.setMessageObject(buildMessageObject(revision), null, false, false, false);
        }

        /**
         * Собирает из записи обычное сообщение Telegram.
         *
         * Дата подменяется на момент захвата ревизии — именно она интересна в истории
         * правок, а не исходная дата отправки.
         */
        private MessageObject buildMessageObject(SpyMessage revision) {
            TLRPC.Message msg = SpyMessageMapper.toMessage(currentAccount, revision);
            msg.date = revision.entityCreateDate;
            // Цитата берётся у живого сообщения: в базе хранятся только идентификаторы ответа.
            if (source.messageOwner != null && source.messageOwner.replyMessage != null) {
                msg.replyMessage = source.messageOwner.replyMessage;
                msg.reply_to = source.messageOwner.reply_to;
            }
            return new MessageObject(currentAccount, msg, true, true);
        }
    }
}
