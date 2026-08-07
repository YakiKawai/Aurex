package org.aurex.features.paid;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;

import org.aurex.core.AurexFeatures;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

import java.lang.ref.WeakReference;

/**
 * Функция "Заблокировать реакции за звёзды".
 *
 * КАК УСТРОЕНЫ ПЛАТНЫЕ РЕАКЦИИ В АПСТРИМЕ. Отправка всегда заканчивается
 * вызовом StarsController.sendPaidReaction(...), но дойти до него можно четырьмя путями:
 *
 *  1) нажатие на звёздную реакцию в любом пикере или по чипу под сообщением
 *     (ChatActivity.selectReaction);
 *  2) повторные тапы по уже поднятому чипу (StarReactionsOverlay.tap);
 *  3) шит со слайдером количества звёзд, в том числе в лайв-историях
 *     (StarsReactionsSheet);
 *  4) колбэк "докупить звёзды и отправить" из StarsNeededSheet.
 *
 * Поэтому блокировка двухслойная:
 *
 *  - {@link #block(Context, Theme.ResourcesProvider)} — в самой верхней точке каждого пути,
 *    ДО проверки баланса и до открытия оплаты: показывает предупреждение и говорит
 *    вызывающему коду выйти;
 *  - {@link #isBlocked()} и {@link #shouldDropRequest(TLObject)} — тихие страховки внутри
 *    StarsController и на выходе в сеть. Сюда управление доходить не должно; это
 *    гарантия на случай, если в будущей версии Telegram появится новый путь отправки.
 *
 * Предупреждение — штатный AlertDialog Telegram, без собственной вёрстки: так он
 * автоматически получает фирменные скругления, шрифты, анимацию и тему экрана,
 * включая тёмную тему просмотрщика историй.
 */
public final class PaidReactions {

    /**
     * Уже показанное предупреждение.
     *
     * Зачем: по звёздной реакции штатно жмут сериями (каждый тап — плюс звезда),
     * и без этой защиты десяток быстрых нажатий породил бы десяток диалогов стопкой.
     * WeakReference — чтобы статическое поле не держало Activity через диалог.
     */
    private static WeakReference<AlertDialog> visibleWarning;

    private PaidReactions() {
    }

    /** Включена ли блокировка платных реакций. */
    public static boolean isBlocked() {
        return AurexFeatures.BLOCK_STAR_REACTIONS.get();
    }

    /**
     * Главная точка блокировки.
     *
     * @param context           контекст места нажатия, может быть null
     * @param resourcesProvider тема экрана, может быть null
     * @return true, если действие перехвачено и вызывающий код обязан выйти
     */
    public static boolean block(Context context, Theme.ResourcesProvider resourcesProvider) {
        if (!isBlocked()) {
            return false;
        }
        showWarning(context, resourcesProvider);
        return true;
    }

    /**
     * Сетевой бэкстоп: последний рубеж перед отправкой пакета на сервер.
     *
     * Не показывает ничего: если управление сюда дошло, предупреждение либо уже показано,
     * либо отправка идёт не из UI вообще.
     */
    public static boolean shouldDropRequest(TLObject request) {
        return request instanceof TLRPC.TL_messages_sendPaidReaction && isBlocked();
    }

    private static void showWarning(Context context, Theme.ResourcesProvider resourcesProvider) {
        final AlertDialog shown = visibleWarning == null ? null : visibleWarning.get();
        if (shown != null && shown.isShowing()) {
            return;
        }
        visibleWarning = null;

        Theme.ResourcesProvider theme = resourcesProvider;
        Activity activity = context == null ? null : AndroidUtilities.findActivity(context);
        if (activity == null) {
            // Нажатие могло прийти из View без Activity в цепочке контекстов — берём текущий экран.
            final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            if (fragment != null) {
                activity = fragment.getParentActivity();
                if (theme == null) {
                    theme = fragment.getResourceProvider();
                }
            }
        }
        if (activity == null) {
            activity = LaunchActivity.instance;
        }
        if (activity == null || activity.isFinishing()) {
            // Показать негде. Блокировку это не отменяет: block() всё равно вернёт true.
            return;
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, theme);
        builder.setTitle(getString(R.string.AurexStarReactionsBlockedTitle));
        builder.setMessage(getString(R.string.AurexStarReactionsBlockedText));
        builder.setPositiveButton(getString(R.string.OK), null);
        final AlertDialog dialog = builder.create();
        dialog.show();
        visibleWarning = new WeakReference<>(dialog);
    }
}
