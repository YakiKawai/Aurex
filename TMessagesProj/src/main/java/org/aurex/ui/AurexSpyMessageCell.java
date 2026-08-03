package org.aurex.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;

import org.aurex.features.spy.SpyMessage;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.BulletinFactory;

/**
 * Ячейка одной сохранённой версии сообщения.
 *
 * Это штатная {@link ChatMessageCell} Telegram без собственной отрисовки: сохранённая
 * версия выглядит ровно так же, как выглядело само сообщение. Тот же подход апстрим
 * использует в PrivacyControlActivity и ThemePreviewMessagesCell (isChat = false + setFullyDraw).
 *
 * Поведение повторяет AyuGram (AyuMessageCell): клик — копировать текст, а если есть
 * вложение — открыть его; долгое нажатие — всегда копирование.
 */
public class AurexSpyMessageCell extends ChatMessageCell {

    private SpyMessage revision;

    public AurexSpyMessageCell(Context context, Activity activity, BaseFragment fragment) {
        super(context);

        // Вне чата ячейка рисуется целиком и без аватарок группового вида.
        setFullyDraw(true);
        isChat = false;
        // Делегат по умолчанию: без него ячейка падает на нажатиях по служебным элементам.
        setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
        });

        setOnClickListener(v -> {
            if (revision == null) {
                return;
            }
            if (TextUtils.isEmpty(revision.mediaPath)) {
                copyText(fragment);
            } else {
                AndroidUtilities.openForView(getMessageObject(), activity, null, false);
            }
        });

        setOnLongClickListener(v -> {
            copyText(fragment);
            return true;
        });
    }

    public void setRevision(SpyMessage revision) {
        this.revision = revision;
    }

    private void copyText(BaseFragment fragment) {
        if (revision == null || TextUtils.isEmpty(revision.text)) {
            return;
        }
        AndroidUtilities.addToClipboard(revision.text);
        BulletinFactory.of(fragment).createCopyBulletin(getString(R.string.MessageCopied)).show();
    }
}
