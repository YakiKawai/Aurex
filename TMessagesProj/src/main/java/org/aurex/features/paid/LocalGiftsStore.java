package org.aurex.features.paid;

import android.util.Base64;
import android.util.LongSparseArray;

import org.aurex.core.AurexConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Состояние функции «Локальные подарки»: сколько локальных звёзд израсходовано и
 * какие подарки были отправлены локально.
 *
 * ПОЧЕМУ КЛЮЧИ ПРИВЯЗАНЫ К ID ПОЛЬЗОВАТЕЛЯ, А НЕ К ИНДЕКСУ АККАУНТА. Индексы
 * аккаунтов (0..3) Telegram переиспользует: вышли из аккаунта, вошли в другой —
 * индекс тот же. При хранении по индексу подарки и потраченный баланс всплыли бы
 * в чужом аккаунте. Ключ вида {@code ..._<clientUserId>} делает данные одного
 * аккаунта физически недостижимыми из другого.
 *
 * ЧТО ИМЕННО ХРАНИТСЯ. Не подарок целиком, а его снимок: id, цена, флаги и один
 * документ — та самая картинка, которую рисует карточка в чате. Раньше
 * сохранялся весь TL-объект подарка, и для NFT это означало десятки вложенных
 * атрибутов с документами: запись весила в десятки раз больше, а картинку
 * карточка всё равно не находила. Документ берётся штатным
 * {@code StarGift.getDocument()} — тем же способом, каким его достаёт сам
 * Telegram, поэтому у обычного подарка это его стикер, а у NFT — модель.
 *
 * ПОЧЕМУ SharedPreferences, А НЕ СВОЯ SQLite (как в режиме шпиона). Здесь нет ни
 * потока событий, ни выборок по диапазонам: пользователь отправляет единицы
 * подарков вручную. Вдобавок общий файл настроек мода означает, что сброс
 * настроек Aurex гарантированно уносит и это состояние — отдельная база
 * потребовала бы помнить про неё в каждом сценарии очистки.
 */
public final class LocalGiftsStore {

    /**
     * Начало диапазона id локальных сообщений.
     *
     * Сервер выдаёт id последовательно и в обозримом будущем не приблизится к
     * этим значениям, поэтому локальный подарок не может столкнуться с реальным
     * сообщением: ни в ленте чата, ни в словарях сообщений Telegram.
     * Диапазон заведомо ниже {@link Integer#MAX_VALUE}, чтобы инкремент
     * счётчика никогда не переполнился.
     */
    public static final int LOCAL_MESSAGE_ID_BASE = 1500000000;

    /**
     * Предохранитель: больше этого числа подарков на аккаунт не храним.
     *
     * Тридцати достаточно с запасом — это ручные отправки, а не переписка.
     * Чем короче список, тем меньше объектов лента пересобирает при каждой
     * загрузке истории.
     */
    private static final int MAX_GIFTS_PER_ACCOUNT = 30;

    private static final String KEY_SPENT = "paid_local_gifts_spent_";
    private static final String KEY_ITEMS = "paid_local_gifts_items_";
    private static final String KEY_SEQUENCE = "paid_local_gifts_seq_";
    /** Индекс аккаунтов, у которых вообще есть данные: нужен для полной очистки. */
    private static final String KEY_OWNERS = "paid_local_gifts_owners";

    /**
     * Разобранные списки подарков по id владельца.
     *
     * Лента чата спрашивает список на каждой загрузке порции истории, поэтому
     * разбирать JSON каждый раз нельзя. Кэш сбрасывается при любой записи.
     */
    private static final LongSparseArray<List<Entry>> CACHE = new LongSparseArray<>();

    /**
     * Израсходованные звёзды по id владельца.
     *
     * Баланс спрашивают из отрисовки интерфейса подарков — то есть десятки раз в
     * секунду. Без кэша это был бы поход в настройки на каждый кадр.
     */
    private static final LongSparseArray<Long> SPENT_CACHE = new LongSparseArray<>();

    private LocalGiftsStore() {
    }

    /**
     * Одна локальная отправка подарка — ровно то, что нужно карточке в чате.
     *
     * Самого TL-объекта подарка здесь нет намеренно: карточка собирается заново
     * из этих полей, поэтому хранить нечего, кроме картинки и цены.
     */
    public static final class Entry {
        public int messageId;
        public long dialogId;
        public int date;
        public long priceStars;
        public boolean anonymous;
        public boolean upgraded;
        public String text;
        /** id подарка из каталога Telegram: нужен ячейке для кэша анимации. */
        public long giftId;
        /** Сколько звёзд вернул бы обмен подарка — карточка показывает это в подписи. */
        public long convertStars;
        /** Доплата за улучшение, если подарок отправлен с улучшением. */
        public long upgradeStars;
        /** Картинка подарка: стикер обычного или модель NFT. */
        public TLRPC.Document document;
    }

    /**
     * Картинка подарка так, как её находит сам Telegram.
     *
     * У обычного подарка это стикер, у NFT — модель из атрибутов. Штатный
     * {@code getDocument()} знает про оба случая, поэтому своей логики разбора
     * атрибутов у мода нет: именно из-за её отсутствия в прошлой версии NFT
     * приезжал в чат чёрным прямоугольником.
     */
    public static TLRPC.Document documentOf(TL_stars.StarGift gift) {
        if (gift == null) {
            return null;
        }
        try {
            final TLRPC.Document document = gift.getDocument();
            return document != null ? document : gift.sticker;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    // ~ Владелец

    /** @return id пользователя аккаунта или 0, если аккаунт не готов. */
    public static long ownerId(int accountId) {
        try {
            if (!UserConfig.isValidAccount(accountId)) {
                return 0;
            }
            final UserConfig config = UserConfig.getInstance(accountId);
            if (!config.isClientActivated()) {
                return 0;
            }
            return config.getClientUserId();
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    // ~ Израсходованные локальные звёзды

    public static long getSpent(int accountId) {
        final long owner = ownerId(accountId);
        if (owner == 0) {
            return 0;
        }
        synchronized (SPENT_CACHE) {
            final Long cached = SPENT_CACHE.get(owner);
            if (cached != null) {
                return cached;
            }
            final long value = Math.max(0, AurexConfig.getLong(KEY_SPENT + owner, 0));
            SPENT_CACHE.put(owner, value);
            return value;
        }
    }

    public static void addSpent(int accountId, long stars) {
        final long owner = ownerId(accountId);
        if (owner == 0 || stars <= 0) {
            return;
        }
        final long value = getSpent(accountId) + stars;
        AurexConfig.putLong(KEY_SPENT + owner, value);
        synchronized (SPENT_CACHE) {
            SPENT_CACHE.put(owner, value);
        }
    }

    /**
     * Обнуляет расход по всем аккаунтам.
     *
     * Вызывается при изменении ползунка: по требованию функции выбранное
     * значение сразу становится текущим локальным балансом, а не прибавкой к
     * остатку.
     */
    public static void resetSpentAllAccounts() {
        for (int accountId = 0; accountId < UserConfig.MAX_ACCOUNT_COUNT; accountId++) {
            final long owner = ownerId(accountId);
            if (owner != 0) {
                AurexConfig.remove(KEY_SPENT + owner);
            }
        }
        synchronized (SPENT_CACHE) {
            SPENT_CACHE.clear();
        }
    }

    // ~ Список подарков

    /** @return неизменяемый список локальных подарков аккаунта (никогда не null). */
    public static List<Entry> list(int accountId) {
        final long owner = ownerId(accountId);
        if (owner == 0) {
            return Collections.emptyList();
        }
        synchronized (CACHE) {
            final List<Entry> cached = CACHE.get(owner);
            if (cached != null) {
                return cached;
            }
            final List<Entry> parsed = Collections.unmodifiableList(parse(AurexConfig.getString(KEY_ITEMS + owner, null)));
            CACHE.put(owner, parsed);
            return parsed;
        }
    }

    public static void add(int accountId, Entry entry) {
        final long owner = ownerId(accountId);
        if (owner == 0 || entry == null) {
            return;
        }
        final List<Entry> updated = new ArrayList<>(list(accountId));
        updated.add(entry);
        while (updated.size() > MAX_GIFTS_PER_ACCOUNT) {
            updated.remove(0);
        }
        // Пересохраняется весь список, поэтому записи старого формата (с целым
        // TL-объектом подарка внутри) при первой же отправке превращаются в
        // компактные снимки сами, без отдельной миграции.
        save(owner, updated);
        rememberOwner(owner);
    }

    /** Следующий свободный id локального сообщения для аккаунта. */
    public static int nextMessageId(int accountId) {
        final long owner = ownerId(accountId);
        if (owner == 0) {
            return LOCAL_MESSAGE_ID_BASE;
        }
        // Счётчик, а не "максимум по списку": список обрезается по
        // MAX_GIFTS_PER_ACCOUNT, и максимум мог бы уменьшиться, выдав
        // повторный id уже использованному сообщению.
        final int next = Math.max(LOCAL_MESSAGE_ID_BASE, AurexConfig.getInt(KEY_SEQUENCE + owner, LOCAL_MESSAGE_ID_BASE));
        AurexConfig.putInt(KEY_SEQUENCE + owner, next + 1);
        return next;
    }

    /** Принадлежит ли id сообщения локальным подаркам. */
    public static boolean isLocalMessageId(int messageId) {
        return messageId >= LOCAL_MESSAGE_ID_BASE;
    }

    /**
     * Полная очистка состояния функции по всем аккаунтам.
     *
     * Вызывается при выключении функции: локальный баланс и локальные подарки
     * должны исчезнуть целиком. Реальные звёзды при этом не затрагиваются —
     * мод их вообще никогда не читает и не изменяет.
     */
    public static void clearAll() {
        final List<Long> owners = owners();
        for (int i = 0; i < owners.size(); i++) {
            final long owner = owners.get(i);
            AurexConfig.remove(KEY_SPENT + owner);
            AurexConfig.remove(KEY_ITEMS + owner);
            AurexConfig.remove(KEY_SEQUENCE + owner);
        }
        // Аккаунты, вошедшие после последней записи, в индексе могут отсутствовать.
        for (int accountId = 0; accountId < UserConfig.MAX_ACCOUNT_COUNT; accountId++) {
            final long owner = ownerId(accountId);
            if (owner != 0) {
                AurexConfig.remove(KEY_SPENT + owner);
                AurexConfig.remove(KEY_ITEMS + owner);
                AurexConfig.remove(KEY_SEQUENCE + owner);
            }
        }
        AurexConfig.remove(KEY_OWNERS);
        synchronized (CACHE) {
            CACHE.clear();
        }
        synchronized (SPENT_CACHE) {
            SPENT_CACHE.clear();
        }
    }

    // ~ Внутреннее

    private static void save(long owner, List<Entry> entries) {
        final JSONArray array = new JSONArray();
        for (int i = 0; i < entries.size(); i++) {
            final JSONObject json = encode(entries.get(i));
            if (json != null) {
                array.put(json);
            }
        }
        AurexConfig.putString(KEY_ITEMS + owner, array.toString());
        synchronized (CACHE) {
            CACHE.remove(owner);
        }
    }

    private static List<Entry> parse(String stored) {
        final List<Entry> entries = new ArrayList<>();
        if (stored == null || stored.length() == 0) {
            return entries;
        }
        try {
            final JSONArray array = new JSONArray(stored);
            for (int i = 0; i < array.length(); i++) {
                final Entry entry = decode(array.optJSONObject(i));
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (Throwable e) {
            // Битую запись молча игнорируем: потерять локальный подарок не страшно,
            // уронить клиент на открытии чата — недопустимо.
            FileLog.e(e);
        }
        return entries;
    }

    private static JSONObject encode(Entry entry) {
        try {
            final JSONObject json = new JSONObject();
            json.put("id", entry.messageId);
            json.put("dialog", entry.dialogId);
            json.put("date", entry.date);
            json.put("price", entry.priceStars);
            json.put("anon", entry.anonymous);
            json.put("upg", entry.upgraded);
            json.put("gift_id", entry.giftId);
            json.put("convert", entry.convertStars);
            json.put("upgrade", entry.upgradeStars);
            if (entry.text != null) {
                json.put("text", entry.text);
            }
            final String document = encodeDocument(entry.document);
            if (document != null) {
                json.put("doc", document);
            }
            return json;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static Entry decode(JSONObject json) {
        if (json == null) {
            return null;
        }
        try {
            final Entry entry = new Entry();
            entry.messageId = json.optInt("id", 0);
            entry.dialogId = json.optLong("dialog", 0);
            entry.date = json.optInt("date", 0);
            entry.priceStars = json.optLong("price", 0);
            entry.anonymous = json.optBoolean("anon", false);
            entry.upgraded = json.optBoolean("upg", false);
            entry.text = json.has("text") ? json.optString("text", null) : null;
            entry.giftId = json.optLong("gift_id", 0);
            entry.convertStars = json.optLong("convert", 0);
            entry.upgradeStars = json.optLong("upgrade", 0);
            entry.document = decodeDocument(json.optString("doc", null));
            if (entry.document == null && json.has("gift")) {
                // Запись предыдущей версии: подарок лежал в ней целиком.
                // Достаём из него то же, что теперь сохраняем сразу, — и такой
                // подарок наконец получает картинку вместо чёрного квадрата.
                applyLegacyGift(entry, decodeGift(json.optString("gift", null)));
            }
            return entry.messageId == 0 || entry.dialogId == 0 ? null : entry;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void applyLegacyGift(Entry entry, TL_stars.StarGift gift) {
        if (gift == null) {
            return;
        }
        entry.document = documentOf(gift);
        if (entry.giftId == 0) {
            entry.giftId = gift.id;
        }
        if (entry.priceStars == 0) {
            entry.priceStars = gift.stars;
        }
        if (entry.convertStars == 0 && !entry.upgraded) {
            entry.convertStars = gift.convert_stars;
        }
        if (entry.upgradeStars == 0 && entry.upgraded) {
            entry.upgradeStars = gift.upgrade_stars;
        }
    }

    private static String encodeDocument(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        final SerializedData data = new SerializedData();
        try {
            document.serializeToStream(data);
            return Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            data.cleanup();
        }
    }

    private static TLRPC.Document decodeDocument(String encoded) {
        if (encoded == null || encoded.length() == 0) {
            return null;
        }
        SerializedData data = null;
        try {
            data = new SerializedData(Base64.decode(encoded, Base64.NO_WRAP));
            return TLRPC.Document.TLdeserialize(data, data.readInt32(false), false);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (data != null) {
                data.cleanup();
            }
        }
    }

    /** Чтение подарка из записи предыдущей версии формата. */
    private static TL_stars.StarGift decodeGift(String encoded) {
        if (encoded == null || encoded.length() == 0) {
            return null;
        }
        SerializedData data = null;
        try {
            data = new SerializedData(Base64.decode(encoded, Base64.NO_WRAP));
            return TL_stars.StarGift.TLdeserialize(data, data.readInt32(false), false);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (data != null) {
                data.cleanup();
            }
        }
    }

    private static List<Long> owners() {
        final List<Long> owners = new ArrayList<>();
        try {
            final String stored = AurexConfig.getString(KEY_OWNERS, null);
            if (stored == null || stored.length() == 0) {
                return owners;
            }
            final JSONArray array = new JSONArray(stored);
            for (int i = 0; i < array.length(); i++) {
                final long owner = array.optLong(i, 0);
                if (owner != 0 && !owners.contains(owner)) {
                    owners.add(owner);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return owners;
    }

    private static void rememberOwner(long owner) {
        try {
            final List<Long> owners = owners();
            if (owners.contains(owner)) {
                return;
            }
            owners.add(owner);
            final JSONArray array = new JSONArray();
            for (int i = 0; i < owners.size(); i++) {
                array.put(owners.get(i).longValue());
            }
            AurexConfig.putString(KEY_OWNERS, array.toString());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
