package org.aurex.features.paid;

import static org.telegram.messenger.LocaleController.getString;

import android.os.Bundle;

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
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Функция «Локальные подарки».
 *
 * ЧТО ЭТО. Отдельный, полностью локальный кошелёк звёзд. Пользователь
 * открывает обычный интерфейс подарков Telegram, видит там СВОЙ локальный
 * остаток, выбирает обычный подарок или NFT, нажимает «Отправить» — и подарок
 * появляется у него в чате, а стоимость списывается с локального кошелька.
 * Ни один запрос на сервер при этом не уходит.
 *
 * ГЛАВНОЕ ПРАВИЛО: РЕАЛЬНЫЕ ЗВЁЗДЫ НЕ ТРОГАЕМ. Мод не читает, не изменяет и не
 * резервирует настоящий баланс Telegram Stars. Локальный остаток вычисляется
 * только из своих настроек:
 *
 *     баланс = выбранное ползунком значение − локально израсходованное
 *
 * АРХИТЕКТУРА ПЕРЕХВАТА. Все четыре врезки стоят в одном файле апстрима —
 * StarsController.java — и каждая из них состоит из вызова фасада AurexHooks:
 *
 *   1. getBalance(...)      — пока функция включена, штатный интерфейс видит
 *                             локальный кошелёк вместо настоящего баланса.
 *                             Именно поэтому в шапке подарков виден локальный
 *                             остаток, а NFT можно выбрать и отправить.
 *   2. balanceAvailable()   — локальный кошелёк «загружен» всегда, поэтому
 *                             интерфейс не ждёт ответа сервера и работает
 *                             офлайн.
 *   3. buyStarGift(...)     — обычный подарок: локальная отправка вместо оплаты.
 *   4. buyResellingGift(...)— NFT с витрины перепродажи: то же самое.
 *   5. buy(...)             — покупка настоящих звёзд запрещена целиком.
 *
 * Врезки 3–5 стоят ПЕРВОЙ строкой методов, то есть до формирования инвойса, до
 * payments.getPaymentForm и до payments.sendStarsForm. Реальный payment flow не
 * начинается вообще, а не прерывается по ходу.
 *
 * ВТОРОЙ РУБЕЖ — сеть. Подменённый баланс делает штатный интерфейс «щедрым»:
 * он считает, что звёзды есть. Поэтому, пока функция включена,
 * {@link #shouldDropRequest(TLObject)} не выпускает наружу НИ ОДИН запрос,
 * способный списать настоящие звёзды: ни оплату формы, ни операции с
 * подарками, ни платную реакцию, ни платное сообщение. Это не украшение, а
 * обязательная часть архитектуры: без неё подмена баланса была бы опасной.
 */
public final class LocalGifts {

    /** Границы ползунка «количество локальных звёзд». */
    public static final int MIN_AMOUNT = 1000;
    public static final int MAX_AMOUNT = 100000;
    /** Значение по умолчанию — середина типичных цен на подарки. */
    public static final int DEFAULT_AMOUNT = 10000;

    private static final String KEY_AMOUNT = "paid_local_gifts_amount";

    /**
     * Пауза перед возвратом в чат.
     *
     * Штатный лист подарка закрывается своей анимацией; переход выполняется
     * после неё, иначе экран чата въезжает под ещё открытую шторку.
     */
    private static final long OPEN_CHAT_DELAY = 200L;

    private LocalGifts() {
    }

    /** Включена ли функция. */
    public static boolean isEnabled() {
        return AurexFeatures.LOCAL_GIFTS.get();
    }

    /**
     * Есть ли локальный кошелёк для этого экземпляра StarsController.
     *
     * Апстрим держит два контроллера: звёзды и TON ({@code ton == true}).
     * Подменять можно только звёздный — к TON функция отношения не имеет.
     */
    public static boolean hasWallet(boolean ton) {
        return !ton && isEnabled();
    }

    /**
     * Включает или выключает функцию.
     *
     * При выключении локальное состояние стирается целиком и по всем аккаунтам:
     * баланса больше нет, отправленные локальные подарки исчезают из чатов.
     * Реальный баланс Telegram остаётся ровно таким, каким был до включения —
     * мод его не касался ни на одном шаге.
     *
     * Вызывать из главного потока: внутри рассылаются уведомления интерфейсу.
     */
    public static void setEnabled(boolean enabled) {
        if (isEnabled() == enabled) {
            return;
        }
        // Список затронутых диалогов собираем ДО очистки: после неё уже нельзя
        // узнать, какой именно чат надо перерисовать.
        final List<Long> affected = dialogsWithGifts();
        AurexFeatures.LOCAL_GIFTS.set(enabled);
        if (enabled) {
            // Включение = полный баланс: расход прошлой сессии не переносится.
            LocalGiftsStore.resetSpentAllAccounts();
        } else {
            LocalGiftsStore.clearAll();
        }
        notifyFeedChanged(affected);
        // Интерфейс подарков должен сразу увидеть смену кошелька: при включении —
        // локальный остаток, при выключении — снова настоящий баланс Telegram.
        notifyBalanceChanged();
    }

    /** Текущее значение ползунка — оно же полный локальный баланс. */
    public static int getAmount() {
        return Utilities.clamp(AurexConfig.getInt(KEY_AMOUNT, DEFAULT_AMOUNT), MAX_AMOUNT, MIN_AMOUNT);
    }

    /**
     * Задаёт количество локальных звёзд.
     *
     * Ползунок задаёт АБСОЛЮТНОЕ значение баланса, а не лимит и не прибавку:
     * любое изменение обнуляет израсходованное, поэтому потраченные звёзды
     * возвращаются сдвигом ползунка хотя бы на единицу.
     *
     * Метод вызывается на каждое движение ползунка, поэтому одинаковое значение
     * отбрасывается сразу — иначе баланс пересчитывался бы на каждом кадре.
     */
    public static void setAmount(int amount) {
        final int value = Utilities.clamp(amount, MAX_AMOUNT, MIN_AMOUNT);
        if (value == getAmount()) {
            return;
        }
        AurexConfig.putInt(KEY_AMOUNT, value);
        LocalGiftsStore.resetSpentAllAccounts();
        // Если интерфейс подарков открыт поверх настроек, он обновит остаток сам:
        // это то же уведомление, которым апстрим сообщает о приходе звёзд.
        notifyBalanceChanged();
    }

    /** Локальный баланс аккаунта. Ноль, если функция выключена. */
    public static long getBalance(int accountId) {
        if (!isEnabled()) {
            return 0;
        }
        return Math.max(0, getAmount() - LocalGiftsStore.getSpent(accountId));
    }

    /**
     * Локальный баланс в том виде, в каком его ждёт апстрим.
     *
     * Значение собирается штатным конструктором {@code StarsAmount.ofStars},
     * поэтому для интерфейса это обычный баланс: та же вёрстка, те же анимации
     * пересчёта, те же проверки «хватает / не хватает».
     */
    public static TL_stars.StarsAmount starsBalance(int accountId) {
        return TL_stars.StarsAmount.ofStars(getBalance(accountId));
    }

    /** Стоимость подарка в звёздах — так же, как её считает штатный SendGiftSheet. */
    public static long priceOf(TL_stars.StarGift gift, boolean upgraded) {
        if (gift == null) {
            return 0;
        }
        return gift.stars + (upgraded ? gift.upgrade_stars : 0);
    }

    /**
     * Локальная отправка обычного подарка.
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
            return deliver(accountId, gift, anonymous, upgraded, dialogId,
                    text != null ? text.text : null, priceOf(gift, upgraded), whenDone);
        } catch (Throwable e) {
            FileLog.e(e);
            finish(whenDone, false);
            return true;
        }
    }

    /**
     * Локальная покупка NFT с витрины перепродажи.
     *
     * Вызывается врезкой из {@code StarsController.buyResellingGift(...)} — это
     * отдельный путь апстрима, который не проходит через buyStarGift. Без этой
     * врезки выбранный NFT ушёл бы в настоящую оплату.
     *
     * Цена берётся из уже полученной формы оплаты: это ровно та сумма, которую
     * пользователь видел на кнопке. Форма — единственное, что мод от сервера
     * использует, и она не оплачивается.
     */
    public static boolean sendResale(
            int accountId,
            TLRPC.TL_payments_paymentFormStarGift form,
            TL_stars.StarGift gift,
            long dialogId,
            Utilities.Callback2<Boolean, String> whenDone
    ) {
        if (!isEnabled()) {
            return false;
        }
        try {
            return deliver(accountId, gift, false, false, dialogId, null, resalePrice(form, gift), whenDone);
        } catch (Throwable e) {
            FileLog.e(e);
            finish(whenDone, false);
            return true;
        }
    }

    /**
     * Запрет покупки настоящих звёзд, пока функция включена.
     *
     * Врезка в {@code StarsController.buy(...)} — единственная точка, из которой
     * апстрим запускает оплату через Google Play. Перекрыта именно она, а не
     * экран докупки: так закрыты все входы сразу (шторка «не хватает звёзд»,
     * магазин звёзд, ссылки).
     *
     * @return true, если покупку нужно отменить
     */
    public static boolean blockRealPurchase(boolean ton, Utilities.Callback2<Boolean, String> whenDone) {
        if (!hasWallet(ton)) {
            return false;
        }
        showBulletin(getString(R.string.AurexLocalGiftsNotEnough));
        finish(whenDone, false);
        return true;
    }

    /**
     * Сетевой рубеж: пока функция включена, наружу не уходит ничего, что может
     * списать настоящие звёзды.
     *
     * Список намеренно шире подарков. Причина в подменённом балансе: любой
     * другой экран Telegram тоже считает, что звёзды есть, и мог бы отправить
     * настоящую оплату. Пока включён локальный режим, звёзды не тратятся вообще
     * — это цена честной подмены баланса и одновременно её страховка.
     *
     * Ничего не показывает: объяснение уже показано выше по стеку, а сюда
     * управление в нормальной работе не доходит.
     */
    public static boolean shouldDropRequest(TLObject request) {
        if (request == null || !isEnabled()) {
            return false;
        }
        // Оплата формы звёздами — единственный способ списать звёзды за покупку.
        if (request instanceof TL_stars.TL_payments_sendStarsForm) {
            return true;
        }
        final String name = request.getClass().getSimpleName();
        // Операции с подарками, которые апстрим оплачивает звёздами напрямую:
        // улучшение, передача, перепродажа. Опознаём по имени конструктора, чтобы
        // новая версия Telegram не создала дыру и чтобы не ссылаться на классы,
        // которых в текущей схеме может не быть.
        if (name.startsWith("TL_payments_") && name.contains("StarGift")) {
            return true;
        }
        // Платная реакция звёздами.
        if (name.equals("TL_messages_sendPaidReaction")) {
            return true;
        }
        // Платные сообщения: стоимость передаётся полем в самом запросе отправки.
        return name.startsWith("TL_messages_send") && hasPaidStars(request);
    }

    // ------------------------------------------------------------------
    // Внутреннее
    // ------------------------------------------------------------------

    /**
     * Общая часть обеих отправок: проверка кошелька, запись подарка, показ.
     *
     * Порядок шагов повторяет штатный: сначала подарок появляется в чате, потом
     * интерфейсу сообщается об успехе, и только затем выполняется переход в чат.
     */
    private static boolean deliver(
            int accountId,
            TL_stars.StarGift gift,
            boolean anonymous,
            boolean upgraded,
            long dialogId,
            String text,
            long price,
            Utilities.Callback2<Boolean, String> whenDone
    ) {
        if (gift == null || dialogId == 0 || LocalGiftsStore.ownerId(accountId) == 0) {
            finish(whenDone, false);
            return true;
        }
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
        entry.text = text;
        entry.gift = gift;

        LocalGiftsStore.add(accountId, entry);
        LocalGiftsStore.addSpent(accountId, price);

        // Подарок появляется в открытом чате сразу, без повторного входа.
        notifyFeedChanged(dialogId);
        // Остаток на шапке подарков пересчитывается тем же уведомлением, которым
        // апстрим сообщает об изменении баланса.
        notifyBalanceChanged();
        // Для штатного интерфейса отправка завершилась успешно: он сам закроет
        // лист подарка своей анимацией и выполнит остальные шаги успеха.
        finish(whenDone, true);
        // И вернёт пользователя туда, где подарок видно.
        openChat(accountId, dialogId);
        return true;
    }

    /**
     * Цена NFT из формы оплаты.
     *
     * Форма считает итог сама (цена лота плюс возможные надбавки), поэтому берём
     * сумму позиций — ровно то, что было на кнопке. Если позиций почему-то нет,
     * откатываемся к цене самого подарка, но бесплатным NFT не делаем никогда.
     */
    private static long resalePrice(TLRPC.TL_payments_paymentFormStarGift form, TL_stars.StarGift gift) {
        long price = 0;
        try {
            if (form != null && form.invoice != null && form.invoice.prices != null) {
                for (int i = 0; i < form.invoice.prices.size(); i++) {
                    price += form.invoice.prices.get(i).amount;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return price > 0 ? price : priceOf(gift, false);
    }

    /** Есть ли в запросе отправки сообщения оплата звёздами. */
    private static boolean hasPaidStars(TLObject request) {
        try {
            final Field field = request.getClass().getField("allow_paid_stars");
            final Object value = field.get(request);
            return value instanceof Long && (Long) value > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Возврат в чат после отправки.
     *
     * Так ведёт себя штатный сценарий: подарок отправляют из профиля или из
     * самого чата, а увидеть его нужно в переписке. Если нужный чат уже открыт —
     * не делаем ничего, иначе открываем его штатным способом Telegram
     * (ChatActivity с обычными аргументами), без собственных экранов и анимаций.
     */
    private static void openChat(int accountId, long dialogId) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                final BaseFragment last = LaunchActivity.getSafeLastFragment();
                if (last == null) {
                    return;
                }
                if (last instanceof ChatActivity
                        && ((ChatActivity) last).getDialogId() == dialogId
                        && last.getCurrentAccount() == accountId) {
                    return;
                }
                final Bundle args = new Bundle();
                if (dialogId >= 0) {
                    args.putLong("user_id", dialogId);
                } else {
                    args.putLong("chat_id", -dialogId);
                }
                last.presentFragment(new ChatActivity(args));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }, OPEN_CHAT_DELAY);
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

    /**
     * Диалоги, в которых есть локальные подарки, по всем аккаунтам.
     *
     * Нужно именно списком: лента чата пересобирается только на уведомление про
     * свой диалог, поэтому при выключении функции надо разослать его по всем
     * затронутым чатам — иначе подарки остались бы на экране до перезахода.
     */
    private static List<Long> dialogsWithGifts() {
        final List<Long> dialogs = new ArrayList<>();
        try {
            for (int accountId = 0; accountId < UserConfig.MAX_ACCOUNT_COUNT; accountId++) {
                final List<LocalGiftsStore.Entry> entries = LocalGiftsStore.list(accountId);
                for (int i = 0; i < entries.size(); i++) {
                    final LocalGiftsStore.Entry entry = entries.get(i);
                    if (entry != null && entry.dialogId != 0 && !dialogs.contains(entry.dialogId)) {
                        dialogs.add(entry.dialogId);
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return dialogs;
    }

    private static void notifyFeedChanged(List<Long> dialogIds) {
        if (dialogIds == null || dialogIds.isEmpty()) {
            return;
        }
        for (int i = 0; i < dialogIds.size(); i++) {
            notifyFeedChanged(dialogIds.get(i));
        }
    }

    private static void notifyFeedChanged(long dialogId) {
        if (dialogId == 0) {
            return;
        }
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

    /**
     * Сообщает интерфейсу, что баланс изменился.
     *
     * Это штатное уведомление апстрима: его слушают шапка подарков, магазин
     * звёзд и кнопки отправки. Мод не перерисовывает чужие экраны сам — он
     * лишь говорит им то же, что сказал бы сервер.
     */
    private static void notifyBalanceChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int accountId = 0; accountId < UserConfig.MAX_ACCOUNT_COUNT; accountId++) {
                try {
                    if (!UserConfig.isValidAccount(accountId)) {
                        continue;
                    }
                    NotificationCenter.getInstance(accountId)
                            .postNotificationName(NotificationCenter.starBalanceUpdated);
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
