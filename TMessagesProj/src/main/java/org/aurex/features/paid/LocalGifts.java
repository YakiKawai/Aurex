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
 * АРХИТЕКТУРА ПЕРЕХВАТА. Все пять врезок стоят в одном файле апстрима —
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
 * ПОЧЕМУ ОТПРАВКА ВЫГЛЯДИТ КАК НАСТОЯЩАЯ. Своих анимаций у мода нет. Он лишь
 * говорит штатному интерфейсу «оплата прошла», и дальше Telegram сам делает то
 * же, что и всегда: закрывает лист подарка своей анимацией и показывает свой
 * bulletin. Порядок шагов повторяет настоящую отправку — сначала успех, потом
 * карточка в чате, потом переход в чат, потом салют, — поэтому ни один шаг не
 * перебивает предыдущий (в прошлой версии переход стартовал слишком рано и
 * обрывал анимацию).
 *
 * ПРО САЛЮТ. У NFT он появлялся сразу и без кода мода: витрина перепродажи
 * ({@code ResaleGiftsFragment}) держит штатный {@code FireworksOverlay} и запускает
 * его по успеху оплаты. А обычные подарки апстрим отправляет через
 * {@code SendGiftSheet}, где салюта нет ни в одной строке. Поэтому мод добавляет
 * салют только там, где его нет у апстрима, и делает это его же компонентом —
 * см. {@link LocalGiftsFireworks}. Два салюта одновременно появиться не могут.
 *
 * ВТОРОЙ РУБЕЖ — сеть. Подменённый баланс делает штатный интерфейс «щедрым»:
 * он считает, что звёзды есть. Поэтому, пока функция включена,
 * {@link #shouldDropRequest(TLObject)} не выпускает наружу запросы, способные
 * списать настоящие звёзды: оплату формы, улучшение и передачу подарков,
 * платную реакцию, платное сообщение. Запросы ЧТЕНИЯ и управление уже
 * существующими настоящими подарками проходят свободно — иначе мод мешает
 * обычной работе Telegram.
 */
public final class LocalGifts {

    /** Границы ползунка «количество локальных звёзд». */
    public static final int MIN_AMOUNT = 1000;
    public static final int MAX_AMOUNT = 100000;
    /** Значение по умолчанию — середина типичных цен на подарки. */
    public static final int DEFAULT_AMOUNT = 10000;

    private static final String KEY_AMOUNT = "paid_local_gifts_amount";

    /**
     * Пауза перед появлением карточки в чате.
     *
     * Ровно столько штатный лист подарка уезжает с экрана. Подарок, возникший
     * под ещё открытым листом, пользователь просто не увидит: настоящая
     * отправка выглядит как «лист закрылся — подарок прилетел».
     */
    private static final long SHOW_GIFT_DELAY = 350L;

    /**
     * Пауза перед возвратом в чат.
     *
     * Больше предыдущей: сначала штатная анимация успеха, потом карточка, и
     * только затем переход. Иначе экран чата въезжает поверх ещё не
     * закрывшегося листа и анимация обрывается.
     */
    private static final long OPEN_CHAT_DELAY = 550L;

    /**
     * Пауза перед салютом.
     *
     * Самый последний шаг: к этому моменту переход в чат уже завершён, и
     * салют виден именно там, где появился подарок, а не на уезжающем экране.
     */
    private static final long FIREWORKS_DELAY = 900L;

    /**
     * Склейка уведомлений об изменении баланса.
     *
     * Ползунок дёргает setAmount на каждое движение пальца. Без склейки на
     * каждое такое движение уходила бы рассылка по всем аккаунтам, а любой
     * открытый экран звёзд перестраивался бы десятки раз в секунду.
     */
    private static final long BALANCE_NOTIFY_DELAY = 120L;

    /** Значение ползунка в памяти: баланс спрашивают из отрисовки интерфейса. */
    private static volatile int amountCache = Integer.MIN_VALUE;

    /**
     * Готовый объект баланса на аккаунт.
     *
     * Пересоздавать его на каждый запрос нельзя: штатный интерфейс спрашивает
     * баланс при каждой перерисовке, и новый объект на каждый кадр — это и
     * лишний мусор, и повод для интерфейса считать, что баланс изменился.
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
        amountCache = Integer.MIN_VALUE;
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
        amountCache = value;
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
     * пересчёта, те же проверки «хватает / не хватает». Объект переиспользуется,
     * пока сумма не изменилась.
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
     * Салют запрашиваем сами: штатный {@code SendGiftSheet} его не показывает.
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
                    text != null ? text.text : null, priceOf(gift, upgraded), true, whenDone);
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
     * Салют здесь НЕ запрашивается: витрина перепродажи покажет свой в ответ
     * на успех, и второй был бы лишним.
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
            return deliver(accountId, gift, false, false, dialogId, null, resalePrice(form, gift), false, whenDone);
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
     * ИМЕНА НОРМАЛИЗУЮТСЯ. Схема Telegram называет запросы неоднородно: часть
     * классов лежит в TLRPC с префиксом ({@code TL_payments_getPaymentForm}), а
     * часть — в TL_stars без него ({@code getStarGifts},
     * {@code toggleStarGiftsPinnedToTop}). Правило, написанное под одно из двух
     * написаний, молча не работает для второго — именно на этом была ошибка
     * прошлых версий. Поэтому сравниваем не «как написано», а имя без префикса.
     *
     * ЧТО ПРОПУСКАЕМ. Всё чтение ({@code get*}) — каталог подарков, витрина
     * перепродажи, форма оплаты, профиль. Заглушённое чтение ничего не
     * защищает, зато заставляет интерфейс повторять запросы по кругу и заново
     * тянуть стикеры и эмодзи — так кэш и раздувался до сотен мегабайт.
     * Также пропускается управление уже существующими настоящими подарками
     * (скрыть, закрепить, обменять на звёзды): звёзд оно не тратит, а ломать
     * обычную работу Telegram из-за включённого локального режима мод не должен.
     *
     * ЧТО БЛОКИРУЕМ. Оплату формы звёздами (единый шлюз всех трат: подарок,
     * апгрейд, перепродажа, платный контент), прямые траты на подарках
     * (покупка, улучшение, передача), платную реакцию и платные сообщения.
     * Опознаём по глаголу в имени, а не по точному совпадению: тогда правило
     * продолжит работать и после переименований в новых версиях схемы.
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
        // Прямые траты на подарках, минующие форму: покупка, улучшение, передача.
        if (name.contains("StarGift")
                && (name.startsWith("buy") || name.startsWith("upgrade")
                || name.startsWith("transfer") || name.startsWith("send"))) {
            return true;
        }
        // Платная реакция звёздами.
        if (name.equals("sendPaidReaction")) {
            return true;
        }
        // Платные сообщения: стоимость передаётся полем в самом запросе отправки.
        // Проверяем только отправку и пересылку, чтобы не лезть в reflection на
        // каждый исходящий запрос приложения.
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
     * Порядок шагов повторяет настоящую отправку Telegram:
     *   1) интерфейс узнаёт об успехе и закрывает лист своей анимацией;
     *   2) карточка подарка появляется в чате;
     *   3) если чат не открыт — выполняется переход в него;
     *   4) и только в самом конце — салют.
     * Именно в таком порядке, а не наоборот: иначе каждый следующий шаг
     * обрывает анимацию предыдущего.
     *
     * @param fireworks нужен ли свой салют; для витрины перепродажи не нужен — там
     *                  апстрим показывает его сам
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
        // Картинку достаём штатным способом Telegram: у обычного подарка это его
        // стикер, у NFT — модель. Сам TL-объект подарка не сохраняем.
        entry.document = LocalGiftsStore.documentOf(gift);

        LocalGiftsStore.add(accountId, entry);
        LocalGiftsStore.addSpent(accountId, price);

        // 1. Для штатного интерфейса отправка завершилась успешно: он сам закроет
        //    лист подарка своей анимацией и выполнит остальные шаги успеха.
        finish(whenDone, true);
        // 2. Подарок появляется в чате, когда лист уже ушёл с экрана.
        AndroidUtilities.runOnUIThread(() -> {
            notifyFeedChanged(dialogId);
            notifyBalanceChanged();
        }, SHOW_GIFT_DELAY);
        // 3. И, если нужный чат не открыт, Telegram переходит в него штатно.
        openChat(accountId, dialogId);
        // 4. Завершающий штрих — штатный салют там, где апстрим его не показывает.
        if (fireworks) {
            AndroidUtilities.runOnUIThread(LocalGiftsFireworks::show, FIREWORKS_DELAY);
        }
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

    /**
     * Имя запроса без префикса схемы.
     *
     * {@code TL_payments_sendStarsForm} и {@code sendStarsForm} — один и тот же
     * запрос, но объявленный в разных ф