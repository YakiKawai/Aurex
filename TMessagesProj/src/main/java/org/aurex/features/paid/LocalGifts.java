package org.aurex.features.paid;

import static org.telegram.messenger.LocaleController.getString;

import android.os.Bundle;

import org.aurex.core.AurexConfig;
import org.aurex.core.AurexFeatures;
import org.aurex.core.AurexNotifications;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
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
 * ЧТО ЭТО. Отдельный, полностью локальный кошелёк звёзд. Пользователь открывает
 * обычный интерфейс подарков Telegram, видит там СВОЙ остаток, выбирает
 * обычный подарок или NFT, нажимает «Отправить» — и подарок появляется у него
 * в чате, а стоимость списывается с локального кошелька. Ни один запрос на
 * сервер при этом не уходит.
 *
 * ГЛАВНОЕ ПРАВИЛО: РЕАЛЬНЫЕ ЗВЁЗДЫ НЕ ТРОГАЕМ. Мод не читает, не изменяет и не
 * резервирует настоящий баланс Telegram Stars:
 *
 *     баланс = значение ползунка − локально израсходованное
 *
 * АРХИТЕКТУРА ПЕРЕХВАТА. Все пять врезок стоят в одном файле апстрима —
 * StarsController.java — и каждая из них это вызов фасада AurexHooks:
 *
 *   1. getBalance(...)       — штатный интерфейс видит локальный кошелёк.
 *   2. balanceAvailable()    — кошелёк «загружен» всегда, работает офлайн.
 *   3. buyStarGift(...)      — обычный подарок: локальная отправка вместо оплаты.
 *   4. buyResellingGift(...) — NFT с витрины перепродажи: то же самое.
 *   5. buy(...)              — покупка настоящих звёзд запрещена целиком.
 *
 * Врезки 3–5 стоят ПЕРВОЙ строкой методов — до инвойса, до getPaymentForm и до
 * sendStarsForm. Реальная оплата не начинается вообще, а не прерывается по ходу.
 *
 * ПОЧЕМУ ОТПРАВКА ВЫГЛЯДИТ НАСТОЯЩЕЙ. Своих анимаций у мода нет. Он говорит
 * штатному интерфейсу «оплата прошла», и Telegram сам закрывает лист своей
 * анимацией и показывает свой bulletin. Порядок шагов тот же, что у настоящей
 * отправки: успех → карточка в чате → переход в чат → салют. Ни один шаг не
 * перебивает анимацию предыдущего.
 *
 * ПРО САЛЮТ. У NFT он появлялся и без кода мода: витрина перепродажи
 * ({@code ResaleGiftsFragment}) держит штатный {@code FireworksOverlay} и запускает
 * его по успеху оплаты. А обычные подарки апстрим отправляет через
 * {@code SendGiftSheet}, где салюта нет вообще. Поэтому мод добавляет салют
 * только там, где его нет, и делает это штатным компонентом — см.
 * {@link LocalGiftsFireworks}. Два салюта одновременно появиться не могут.
 *
 * ВТОРОЙ РУБЕЖ — сеть. Подменённый баланс делает интерфейс «щедрым», поэтому
 * {@link #shouldDropRequest(TLObject)} не выпускает наружу запросы, способные
 * списать настоящие звёзды. Чтение и управление настоящими подарками
 * проходят свободно.
 */
public final class LocalGifts {

    /** Границы ползунка «количество локальных звёзд». */
    public static final int MIN_AMOUNT = 1000;
    public static final int MAX_AMOUNT = 100000;
    /** Значение по умолчанию — середина типичных цен на подарки. */
    public static final int DEFAULT_AMOUNT = 10000;

    private static final String KEY_AMOUNT = "paid_local_gifts_amount";

    /** Столько штатный лист подарка уезжает с экрана: раньше карточку не видно. */
    private static final long SHOW_GIFT_DELAY = 350L;

    /** Переход в чат после карточки: иначе экран въезжает поверх листа. */
    private static final long OPEN_CHAT_DELAY = 550L;

    /** Салют последним: к этому моменту переход в чат уже завершён. */
    private static final long FIREWORKS_DELAY = 900L;

    /**
     * Склейка уведомлений о балансе: ползунок дёргает setAmount на каждое
     * движение пальца, а без склейки экран звёзд перестраивался бы десятки раз
     * в секунду.
     */
    private static final long BALANCE_NOTIFY_DELAY = 120L;

    /** Значение ползунка в памяти: баланс спрашивают из отрисовки. */
    private static volatile int amountCache = Integer.MIN_VALUE;

    /**
     * Готовый объект баланса на аккаунт: интерфейс спрашивает баланс на
     * каждой перерисовке, и новый объект на каждый кадр — это и мусор, и повод
     * считать, что баланс изменился.
     */
    private static final TL_stars.StarsAmount[] BALANCE_CACHE = new TL_stars.StarsAmount[UserConfig.MAX_ACCOUNT_COUNT];

    private static volatile boolean balanceNotifyScheduled;

    private LocalGifts() {
    }

    /** Включена ли функция. */
    public static boolean isEnabled() {
        return AurexFeatures.LOCAL_GIFTS.get();
    }

    /**
     * Есть ли локальный кошелёк для этого экземпляра StarsController.
     * Апстрим держит два: звёзды и TON. Подменяем только звёздный.
     */
    public static boolean hasWallet(boolean ton) {
        return !ton && isEnabled();
    }

    /**
     * Включает или выключает функцию.
     *
     * При выключении локальное состояние стирается целиком и по всем аккаунтам:
     * отправленные подарки исчезают из чатов, баланса больше нет. Реальный
     * баланс Telegram остаётся таким, каким был. Вызывать из главного потока.
     */
    public static void setEnabled(boolean enabled) {
        if (isEnabled() == enabled) {
            return;
        }
        // Список затронутых диалогов собираем ДО очистки: после неё уже не узнать,
        // какой чат надо перерисовать.
        final List<Long> affected = dialogsWithGifts();
        AurexFeatures.LOCAL_GIFTS.set(enabled);
        amountCache = Integer.MIN_VALUE;
        if (enabled) {
            // Включение = полный баланс: расход прошлой сессии не переносится.
            LocalGiftsStore.resetSpentAllAccounts();
        } else {
            LocalGiftsStore.clearAll();
        }
        notifyFeedChanged(affected);
        notifyBalanceChanged();
    }

    /** Текущее значение ползунка — оно же полный локальный баланс. */
    public static int getAmount() {
        int cached = amountCache;
        if (cached == Integer.MIN_VALUE) {
            cached = Utilities.clamp(AurexConfig.getInt(KEY_AMOUNT, DEFAULT_AMOUNT), MAX_AMOUNT, MIN_AMOUNT);
            amountCache = cached;
        }
        return cached;
    }

    /**
     * Задаёт количество локальных звёзд.
     *
     * Ползунок задаёт АБСОЛЮТНОЕ значение, а не лимит: любое изменение
     * обнуляет израсходованное, поэтому потраченные звёзды возвращаются сдвигом
     * ползунка хотя бы на единицу. Одинаковое значение отбрасывается сразу.
     */
    public static void setAmount(int amount) {
        final int value = Utilities.clamp(amount, MAX_AMOUNT, MIN_AMOUNT);
        if (value == getAmount()) {
            return;
        }
        amountCache = value;
        AurexConfig.putInt(KEY_AMOUNT, value);
        LocalGiftsStore.resetSpentAllAccounts();
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
     * Баланс в том виде, в каком его ждёт апстрим: штатный StarsAmount, та же
     * вёрстка, те же проверки «хватает / не хватает».
     */
    public static TL_stars.StarsAmount starsBalance(int accountId) {
        final long value = getBalance(accountId);
        if (accountId < 0 || accountId >= BALANCE_CACHE.length) {
            return TL_stars.StarsAmount.ofStars(value);
        }
        TL_stars.StarsAmount cached = BALANCE_CACHE[accountId];
        if (cached == null || cached.amount != value) {
            cached = TL_stars.StarsAmount.ofStars(value);
            BALANCE_CACHE[accountId] = cached;
        }
        return cached;
    }

    /** Стоимость подарка — так же, как её считает штатный SendGiftSheet. */
    public static long priceOf(TL_stars.StarGift gift, boolean upgraded) {
        if (gift == null) {
            return 0;
        }
        return gift.stars + (upgraded ? gift.upgrade_stars : 0);
    }

    /**
     * Локальная отправка обычного подарка (врезка в buyStarGift).
     *
     * Салют запрашиваем сами: штатный SendGiftSheet его не показывает.
     *
     * При включённой функции метод ВСЕГДА возвращает true, даже при внутренней
     * ошибке: единственный безопасный ответ при сбое — не дать управлению уйти
     * в реальную оплату.
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
                    text != null ? text.text : null, priceOf(gift, upgraded), true, whenDone);
        } catch (Throwable e) {
            FileLog.e(e);
            finish(whenDone, false);
            return true;
        }
    }

    /**
     * Локальная покупка NFT с витрины перепродажи (врезка в buyResellingGift).
     *
     * Салют здесь НЕ запрашивается: витрина покажет свой по успеху.
     *
     * Цена берётся из уже полученной формы оплаты — ровно та, что была на
     * кнопке. Сама форма не оплачивается.
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
            return deliver(accountId, gift, false, false, dialogId, null, resalePrice(form, gift), false, whenDone);
        } catch (Throwable e) {
            FileLog.e(e);
            finish(whenDone, false);
            return true;
        }
    }

    /**
     * Запрет покупки настоящих звёзд (врезка в buy).
     *
     * Перекрыта единственная точка входа в оплату через Google Play, а не экран
     * докупки: так закрыты все входы сразу — шторка «не хватает звёзд»,
     * магазин звёзд, ссылки.
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
     * Сетевой рубеж: наружу не уходит ничего, что может списать настоящие
     * звёзды.
     *
     * ИМЕНА НОРМАЛИЗУЮТСЯ. Схема называет запросы неоднородно: часть лежит в
     * TLRPC с префиксом ({@code TL_payments_getPaymentForm}), часть — в TL_stars без
     * него ({@code getStarGifts}, {@code upgradeStarGift}). Правило, написанное под
     * одно написание, молча не работает для второго — именно так была устроена
     * ошибка прошлых версий. Поэтому сравниваем имя без префикса.
     *
     * ПРОПУСКАЕМ всё чтение ({@code get*}): каталог подарков, витрина, форма
     * оплаты, профиль. Заглушённое чтение ничего не защищает, зато заставляет
     * интерфейс повторять запросы по кругу и заново тянуть стикеры и эмодзи —
     * так кэш и раздувался до сотен мегабайт. Также пропускаем управление
     * настоящими подарками (скрыть, закрепить, обменять): звёзд оно не тратит,
     * а ломать обычную работу Telegram мод не должен.
     *
     * БЛОКИРУЕМ оплату формы (единый шлюз всех трат), прямые траты на подарках
     * (покупка, улучшение, передача), платную реакцию и платные сообщения.
     * Опознаём по глаголу, а не по точному имени: так правило выживет
     * переименования в новых версиях схемы.
     */
    public static boolean shouldDropRequest(TLObject request) {
        if (request == null || !isEnabled()) {
            return false;
        }
        final String name = normalizedName(request);
        // Чтение пропускается всегда: ни один get* звёзд не тратит.
        if (name.startsWith("get")) {
            return false;
        }
        // Оплата формы звёздами — главный шлюз любого списания.
        if (request instanceof TL_stars.TL_payments_sendStarsForm || name.equals("sendStarsForm")) {
            return true;
        }
        // Прямые траты на подарках, минующие форму.
        if (name.contains("StarGift")
                && (name.startsWith("buy") || name.startsWith("upgrade")
                || name.startsWith("transfer") || name.startsWith("send"))) {
            return true;
        }
        // Платная реакция звёздами.
        if (name.equals("sendPaidReaction")) {
            return true;
        }
        // Платные сообщения: стоимость лежит полем в самом запросе. Смотрим только
        // отправку и пересылку, чтобы не лезть в reflection на каждый запрос.
        if (name.startsWith("send") || name.startsWith("forward")) {
            return hasPaidStars(request);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Внутреннее
    // ------------------------------------------------------------------

    /**
     * Общая часть обеих отправок: проверка кошелька, запись подарка, показ.
     *
     * Порядок тот же, что у настоящей отправки: 1) интерфейс узнаёт об успехе
     * и закрывает лист; 2) карточка появляется в чате; 3) если чат не открыт —
     * переход в него; 4) салют. Иначе каждый шаг обрывает анимацию предыдущего.
     *
     * @param fireworks нужен ли свой салют; для витрины перепродажи не нужен
     */
    private static boolean deliver(
            int accountId,
            TL_stars.StarGift gift,
            boolean anonymous,
            boolean upgraded,
            long dialogId,
            String text,
            long price,
            boolean fireworks,
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
        entry.giftId = gift.id;
        entry.convertStars = upgraded ? 0 : gift.convert_stars;
        entry.upgradeStars = upgraded ? gift.upgrade_stars : 0;
        // Картинку достаём штатным способом: у обычного подарка это стикер,
        // у NFT — модель. Сам TL-объект подарка не сохраняем.
        entry.document = LocalGiftsStore.documentOf(gift);

        LocalGiftsStore.add(accountId, entry);
        LocalGiftsStore.addSpent(accountId, price);

        // 1. Для штатного интерфейса отправка завершилась успешно.
        finish(whenDone, true);
        // 2. Подарок появляется в чате, когда лист уже ушёл с экрана.
        AndroidUtilities.runOnUIThread(() -> {
            notifyFeedChanged(dialogId);
            notifyBalanceChanged();
        }, SHOW_GIFT_DELAY);
        // 3. И, если нужный чат не открыт, Telegram переходит в него штатно.
        openChat(accountId, dialogId);
        // 4. Завершающий штрих — салют там, где апстрим его не показывает.
        if (fireworks) {
            AndroidUtilities.runOnUIThread(LocalGiftsFireworks::show, FIREWORKS_DELAY);
        }
        return true;
    }

    /**
     * Цена NFT из формы оплаты: сумма позиций — ровно то, что было на кнопке.
     * Если позиций нет, откатываемся к цене подарка, но бесплатным NFT не делаем.
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

    /**
     * Имя запроса без префикса схемы: {@code TL_payments_sendStarsForm} и
     * {@code sendStarsForm} — один и тот же запрос, просто объявленный в разных
     * файлах схемы.
     */
    private static String normalizedName(TLObject request) {
        final String name = request.getClass().getSimpleName();
        if (!name.startsWith("TL_")) {
            return name;
        }
        final int index = name.lastIndexOf('_');
        return index > 0 && index < name.length() - 1 ? name.substring(index + 1) : name;
    }

    /**
     * Есть ли в запросе оплата звёздами (платные сообщения).
     *
     * Поле {@code allow_paid_stars} есть не у всех запросов отправки и меняется от
     * версии к версии, поэтому смотрим его через reflection: иначе пришлось бы
     * перебирать все типы запросов и править мод после каждого обновления
     * Telegram.
     */
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
     * Переход в чат получателя — штатным presentFragment, то есть с родной
     * анимацией Telegram.
     *
     * Ничего не делаем, если нужный чат и так открыт (иначе в стеке было бы два
     * одинаковых экрана) и если адресат — форум: форум открывается списком тем,
     * а локальные подарки живут только в основной ленте — увести пользователя
     * в пустой список было бы хуже, чем оставить его на месте.
     */
    private static void openChat(int accountId, long dialogId) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                final BaseFragment last = LaunchActivity.getSafeLastFragment();
                if (last == null || last.getParentActivity() == null) {
                    return;
                }
                if (last instanceof ChatActivity
                        && last.getCurrentAccount() == accountId
                        && ((ChatActivity) last).getDialogId() == dialogId) {
                    return;
                }
                final Bundle args = new Bundle();
                if (dialogId >= 0) {
                    args.putLong("user_id", dialogId);
                } else {
                    final TLRPC.Chat chat = MessagesController.getInstance(accountId).getChat(-dialogId);
                    if (ChatObject.isForum(chat)) {
                        return;
                    }
                    args.putLong("chat_id", -dialogId);
                }
                last.presentFragment(new ChatActivity(args));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }, OPEN_CHAT_DELAY);
    }

    /** Ответ штатному интерфейсу в том же виде, в каком его даёт сервер. */
    private static void finish(Utilities.Callback2<Boolean, String> whenDone, boolean success) {
        if (whenDone == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> whenDone.run(success, null));
    }

    /** Диалоги, в которых есть локальные подарки, по всем аккаунтам. */
    private static List<Long> dialogsWithGifts() {
        final List<Long> dialogs = new ArrayList<>();
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!UserConfig.isValidAccount(account)) {
                continue;
            }
            final List<LocalGiftsStore.Entry> entries = LocalGiftsStore.list(account);
            for (int i = 0; i < entries.size(); i++) {
                final long dialogId = entries.get(i).dialogId;
                if (!dialogs.contains(dialogId)) {
                    dialogs.add(dialogId);
                }
            }
        }
        return dialogs;
    }

    /** Перерисовать ленту одного диалога (0 — все диалоги). */
    private static void notifyFeedChanged(long dialogId) {
        AndroidUtilities.runOnUIThread(() -> {
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (!UserConfig.isValidAccount(account)) {
                    continue;
                }
                NotificationCenter.getInstance(account)
                        .postNotificationName(AurexNotifications.LOCAL_GIFTS_CHANGED, dialogId);
            }
        });
    }

    /** То же для списка диалогов: используется при включении и выключении. */
    private static void notifyFeedChanged(List<Long> dialogIds) {
        // Один широкий сигнал вместо десятков адресных: открыт всё равно один чат.
        if (dialogIds == null || dialogIds.isEmpty()) {
            notifyFeedChanged(0L);
            return;
        }
        notifyFeedChanged(0L);
    }

    /**
     * Сообщает интерфейсу, что баланс изменился — тем же уведомлением, которым
     * апстрим сообщает о приходе звёзд. Вызовы склеиваются: ползунок дёргает их
     * десятками в секунду.
     */
    private static void notifyBalanceChanged() {
        if (balanceNotifyScheduled) {
            return;
        }
        balanceNotifyScheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            balanceNotifyScheduled = false;
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (!UserConfig.isValidAccount(account)) {
                    continue;
                }
                NotificationCenter.getInstance(account)
                        .postNotificationName(NotificationCenter.starBalanceUpdated);
            }
        }, BALANCE_NOTIFY_DELAY);
    }

    /** Штатный bulletin поверх текущего экрана. */
    private static void showBulletin(String text) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
                if (fragment == null || fragment.getParentActivity() == null) {
                    return;
                }
                BulletinFactory.of(fragment).createErrorBulletin(text).show();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }
}
