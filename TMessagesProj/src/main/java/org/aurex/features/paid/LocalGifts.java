package org.aurex.features.paid;

import static org.telegram.messenger.LocaleController.getString;

import org.aurex.core.AurexConfig;
import org.aurex.core.AurexFeatures;
import org.aurex.core.AurexNotifications;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

/**
 * Функция «Локальные подарки».
 *
 * ЧТО ЭТО. Отправка подарка выглядит и ощущается как штатная: пользователь
 * открывает обычный интерфейс Telegram, выбирает подарок, видит его цену и
 * нажимает «Отправить». Но вместо оплаты подарок создаётся только на этом
 * устройстве, а стоимость списывается с отдельного локального счётчика.
 *
 * ГЛАВНОЕ ПРАВИЛО: РЕАЛЬНЫЕ ЗВЁЗДЫ НЕ ТРОГАЕМ. Мод не читает, не изменяет, не
 * резервирует и не пересчитывает реальный баланс Telegram Stars. В коде функции
 * нет ни одного обращения к балансу StarsController — локальный баланс
 * вычисляется исключительно из своих настроек:
 *
 *     баланс = выбранное ползунком значение − локально израсходованное
 *
 * КАК УСТРОЕН ПЕРЕХВАТ. В апстриме все пути отправки подарка сходятся в один
 * метод — {@code StarsController.buyStarGift(...)}: и подтверждение в
 * SendGiftSheet, и повторная попытка после докупки звёзд. Именно там стоит
 * единственная врезка, и стоит она первой строкой — ДО формирования инвойса
 * {@code TL_inputInvoiceStarGift}, до {@code payments.getPaymentForm} и до
 * {@code payments.sendStarsForm}. То есть реальный payment flow при включённой
 * функции не начинается вообще, а не отменяется по ходу.
 *
 * ВТОРОЙ РУБЕЖ — сеть. {@link #shouldDropRequest(TLObject)} не пропускает
 * оплату подарка звёздами, даже если в будущей версии Telegram появится новый
 * путь отправки, о котором мод не знает. Это страховка, а не основной механизм:
 * в нормальной работе управление до неё не доходит.
 *
 * Пустой локальный баланс НЕ ведёт к предложению купить настоящие звёзды:
 * штатный экран докупки (StarsNeededSheet) открывается внутри
 * {@code buyStarGift}, то есть уже за нашей врезкой, и остаётся недостижимым.
 */
public final class LocalGifts {

    /** Границы ползунка «количество локальных звёзд». */
    public static final int MIN_AMOUNT = 1000;
    public static final int MAX_AMOUNT = 100000;
    /** Значение по умолчанию — ровно середина типичных цен на подарки. */
    public static final int DEFAULT_AMOUNT = 10000;

    private static final String KEY_AMOUNT = "paid_local_gifts_amount";

    private LocalGifts() {
    }

    /** Включена ли функция. */
    public static boolean isEnabled() {
        return AurexFeatures.LOCAL_GIFTS.get();
    }

    /**
     * Включает или выключает функцию.
     *
     * При выключении локальное состояние стирается целиком и по всем аккаунтам:
     * баланса больше нет, отправленные локальные подарки исчезают из чатов.
     * Реальный баланс Telegram при этом остаётся ровно таким, каким был до
     * включения — мод его не касался ни на одном шаге.
     *
     * Вызывать из главного потока: внутри рассылается уведомление ленте чата.
     */
    public static void setEnabled(boolean enabled) {
        if (isEnabled() == enabled) {
            return;
        }
        AurexFeatures.LOCAL_GIFTS.set(enabled);
        if (enabled) {
            // Включение = полный баланс: расход прошлой сессии не переносится.
            LocalGiftsStore.resetSpentAllAccounts();
        } else {
            LocalGiftsStore.clearAll();
        }
        notifyFeedChanged(0);
    }

    /** Текущее значение ползунка — оно же полный локальный баланс. */
    public static int getAmount() {
        return Utilities.clamp(AurexConfig.getInt(KEY_AMOUNT, DEFAULT_AMOUNT), MAX_AMOUNT, MIN_AMOUNT);
    }

    /**
     * Задаёт количество локальных звёзд.
     *
     * По требованию функции ползунок задаёт именно баланс, а не лимит: любое
     * изменение значения обнуляет израсходованное, поэтому потраченные звёзды
     * можно «начислить» заново, сдвинув ползунок хотя бы на единицу.
     *
     * Метод вызывается на каждое движение ползунка, поэтому одинаковое значение
     * отбрасывается сразу — иначе баланс сбрасывался бы на каждом кадре.
     */
    public static void setAmount(int amount) {
        final int value = Utilities.clamp(amount, MAX_AMOUNT, MIN_AMOUNT);
        if (value == getAmount()) {
            return;
        }
        AurexConfig.putInt(KEY_AMOUNT, value);
        LocalGiftsStore.resetSpentAllAccounts();
    }

    /** Локальный баланс аккаунта. Ноль, если функция выключена. */
    public static long getBalance(int accountId) {
        if (!isEnabled()) {
            return 0;
        }
        return Math.max(0, getAmount() - LocalGiftsStore.getSpent(accountId));
    }

    /** Стоимость подарка в звёздах — так же, как её считает штатный SendGiftSheet. */
    public static long priceOf(TL_stars.StarGift gift, boolean upgraded) {
        if (gift == null) {
            return 0;
        }
        return gift.stars + (upgraded ? gift.upgrade_stars : 0);
    }

    /**
     * Локальная отправка подарка — главная точка перехвата.
     *
     * Вызывается врезкой из {@code StarsController.buyStarGift(...)}.
     *
     * Если функция включена, метод ВСЕГДА возвращает true, в том числе при
     * внутренней ошибке мода. Это сознательное решение: единственный безопасный
     * ответ при сбое — не дать управлению уйти в реальную оплату. Хуже
     * неотправленного локального подарка может быть только списание настоящих
     * звёзд.
     *
     * @return true, если отправка обработана локально и вызывающий код обязан выйти
     */
    public static boolean send(
            int accountId,
            TL_stars.StarGift gift,
            boolean anonymous,
            boolean upgraded,
            long dialogId,
            TLRPC.TL_textWithEntities text,
            Utilities.Callback2<Boolean, String> whenDone
    ) {
        if (!isEnabled()) {
            return false;
        }
        try {
            if (gift == null || dialogId == 0 || LocalGiftsStore.ownerId(accountId) == 0) {
                finish(whenDone, false);
                return true;
            }

            final long price = priceOf(gift, upgraded);
            if (price > getBalance(accountId)) {
                // Никакого перехода к покупке настоящих звёзд: только объяснение.
                showBulletin(getString(R.string.AurexLocalGiftsNotEnough));
                finish(whenDone, false);
                return true;
            }

            final LocalGiftsStore.Entry entry = new LocalGiftsStore.Entry();
            entry.messageId = LocalGiftsStore.nextMessageId(accountId);
            entry.dialogId = dialogId;
            entry.date = ConnectionsManager.getInstance(accountId).getCurrentTime();
            entry.priceStars = price;
            entry.anonymous = anonymous;
            entry.upgraded = upgraded;
            entry.text = text != null ? text.text : null;
            entry.gift = gift;

            LocalGiftsStore.add(accountId, entry);
            LocalGiftsStore.addSpent(accountId, price);

            // Подарок появляется в открытом чате сразу, без повторного входа.
            notifyFeedChanged(dialogId);
            // Штатный UI сам закроет лист подарка и покажет привычную анимацию:
            // для него отправка завершилась успешно.
            finish(whenDone, true);
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            finish(whenDone, false);
            return true;
        }
    }

    /**
     * Сетевой бэкстоп: не даёт уйти на сервер оплате подарка звёздами.
     *
     * Глушится ровно операция списания ({@code payments.sendStarsForm}) и ровно
     * для подарочных инвойсов. Остальные платежи звёздами (платные сообщения,
     * подписки) функция не касается: она про подарки, а не про запрет оплат.
     *
     * Ничего не показывает: если управление дошло сюда, объяснение уже показано
     * выше по стеку либо отправка идёт не из интерфейса вообще.
     */
    public static boolean shouldDropRequest(TLObject request) {
        if (!(request instanceof TL_stars.TL_payments_sendStarsForm) || !isEnabled()) {
            return false;
        }
        return isGiftInvoice(((TL_stars.TL_payments_sendStarsForm) request).invoice);
    }

    /**
     * Подарочный ли это инвойс.
     *
     * Точный тип отправки подарка известен и проверяется первым. Остальные виды
     * подарочных инвойсов апстрим добавляет по мере развития функции (улучшение,
     * перепродажа, аукцион), поэтому дополнительно опознаём их по имени
     * конструктора: так новая версия Telegram не создаст дыру в бэкстопе, а мод
     * не ссылается на классы, которых может не быть.
     */
    private static boolean isGiftInvoice(Object invoice) {
        if (invoice == null) {
            return false;
        }
        if (invoice instanceof TLRPC.TL_inputInvoiceStarGift) {
            return true;
        }
        return invoice.getClass().getSimpleName().startsWith("TL_inputInvoiceStarGift");
    }

    private static void finish(Utilities.Callback2<Boolean, String> whenDone, boolean success) {
        if (whenDone == null) {
            return;
        }
        // Апстрим вызывает этот колбэк с главного потока; повторяем то же поведение,
        // иначе штатный лист подарка попытается обновиться из чужого потока.
        AndroidUtilities.runOnUIThread(() -> {
            try {
                whenDone.run(success, null);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static void notifyFeedChanged(long dialogId) {
        AndroidUtilities.runOnUIThread(() -> {
            for (int accountId = 0; accountId < UserConfig.MAX_ACCOUNT_COUNT; accountId++) {
                try {
                    if (!UserConfig.isValidAccount(accountId)) {
                        continue;
                    }
                    NotificationCenter.getInstance(accountId)
                            .postNotificationName(AurexNotifications.LOCAL_GIFTS_CHANGED, dialogId);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
    }

    /** Штатный bulletin Telegram: своей вёрстки у сообщений мода нет. */
    private static void showBulletin(CharSequence text) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
                if (fragment == null) {
                    return;
                }
                BulletinFactory.of(fragment).createErrorBulletin(text).show();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }
}
