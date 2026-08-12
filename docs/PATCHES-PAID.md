# Врезки: платные возможности

Врезки в файлы официального Telegram, относящиеся к разделу
**Aurex → Платные возможности**. Общие правила и остальные врезки — в
`docs/PATCHES.md`.

| Файл апстрима | Врезок | Функция | Раздел |
|---|---|---|---|
| `ui/Stars/StarsController.java` | 1 | реакции за звёзды | 1.1 |
| `ui/Stars/StarsReactionsSheet.java` | 1 | реакции за звёзды | 1.2 |
| `ui/Stars/StarReactionsOverlay.java` | 2 | реакции за звёзды | 1.3, 1.4 |
| `ui/ChatActivity.java` | 1 | реакции за звёзды | 1.5 |
| `messenger/UserConfig.java` | 1 | локальный Premium | 2.1 |
| `messenger/MessagesController.java` | 1 | локальный Premium | 2.2 |
| `ui/Stars/StarsController.java` | 5 | локальные подарки | 3.2–3.6 |

---

## 1. Блокировка реакций за звёзды

Логика целиком в `org/aurex/features/paid/PaidReactions.java`.

Требование функции: перехват должен срабатывать **до** проверки баланса и до
открытия окна покупки. Поэтому врезок несколько: у Telegram нет одной общей
точки входа для платной реакции — её можно отправить из четырёх разных мест.

Фасады: `AurexHooks.blockPaidReaction(context, resourcesProvider)` — с предупреждением,
`AurexHooks.isPaidReactionBlocked()` — тихая проверка без диалога.

### 1.1 Страховка на отправке

**Файл:** `ui/Stars/StarsController.java`
**Метод:** `sendPaidReaction(MessageObject, ChatActivity, long, boolean, boolean, Long)` —
шестипараметрическая перегрузка, первая строка тела.

```java
// AUREX >>> paid-reactions
if (org.aurex.core.AurexHooks.isPaidReactionBlocked()) {
    return null;
}
// AUREX <<<
```

Сюда сходятся все остальные перегрузки. Диалог здесь НЕ показывается: если
управление дошло сюда, предупреждение уже показано выше по стеку.

### 1.2 Шторка выбора количества звёзд

**Файл:** `ui/Stars/StarsReactionsSheet.java`
**Место:** обработчик `buttonView.setOnClickListener`, до `if (sending) return;`.

```java
// AUREX >>> paid-reactions
if (org.aurex.core.AurexHooks.blockPaidReaction(context, resourcesProvider)) {
    return;
}
// AUREX <<<
```

### 1.3 Оверлей: одиночное нажатие

**Файл:** `ui/Stars/StarReactionsOverlay.java`
**Метод:** `tap(...)` — первая строка тела.

```java
// AUREX >>> paid-reactions
if (send && org.aurex.core.AurexHooks.blockPaidReaction(getContext(), chatActivity.getResourceProvider())) {
    return;
}
// AUREX <<<
```

### 1.4 Оверлей: удержание

**Файл:** `ui/Stars/StarReactionsOverlay.java`
**Место:** начало лямбды `longPressRunnable`.

```java
// AUREX >>> paid-reactions
if (org.aurex.core.AurexHooks.blockPaidReaction(chatActivity.getContext(), chatActivity.getResourceProvider())) {
    return;
}
// AUREX <<<
```

### 1.5 Панель реакций в чате

**Файл:** `ui/ChatActivity.java`
**Метод:** `selectReaction(...)` — перегрузка на 11 параметров, первая строка тела.

```java
// AUREX >>> paid-reactions
if (visibleReaction != null && visibleReaction.isStar && org.aurex.core.AurexHooks.blockPaidReaction(getContext(), getResourceProvider())) {
    return;
}
// AUREX <<<
```

### 1.6 Сетевой бэкстоп — без врезки

Последний рубеж — `AurexRequestFilter`, который уже подключён врезкой режима
призрака в `ConnectionsManager` (см. `docs/PATCHES.md`, раздел 2). Он глушит
`TL_messages_sendPaidReaction` на выходе, если функция включена. Новые врезки для
этого не нужны: даже если апстрим добавит новую точку отправки, звёзды не уйдут.

---

## 2. Локальный Telegram Premium

Логика целиком в `org/aurex/features/paid/LocalPremium.java`.

Перенос функции `localPremium` из AyuGram. В оригинале она состоит ровно из трёх
частей: две врезки в апстрим и обработчик переключателя. Больше нигде флаг не
используется — проверено по исходникам AyuGram4A (ветка `rewrite`).

### 2.1 «Премиум ли я»

**Файл:** `messenger/UserConfig.java`
**Метод:** `isPremium()` — первая строка тела.

```java
// AUREX >>> local-premium
if (org.aurex.core.AurexHooks.isLocalPremium()) {
    return true;
}
// AUREX <<<
```

Главная точка. Через `getUserConfig().isPremium()` апстрим спрашивает про премиум
в десятках мест, в том числе в `premiumFeaturesBlocked()`, `lockFiltersInternal()`,
`getCaptionMaxLengthLimit()`, `getAboutLimit()`, `getMaxUserReactionsCount()`,
`getChatReactionsCount()`, `hasPremiumOnAccounts()`. Все они покрываются одной этой
врезкой, дополнительные не нужны.

В AyuGram метод был переписан целиком:
`return AyuConfig.localPremium || currentUser.premium;` (с отдельной веткой на
`currentUser == null`). Форма выше логически эквивалентна, но оставляет тело
апстрима нетронутым — при обновлении Telegram конфликт будет меньше.

### 2.2 «Премиум ли вот этот пользователь»

**Файл:** `messenger/MessagesController.java`
**Метод:** `isPremiumUser(TLRPC.User currentUser)` — первая строка тела.

```java
// AUREX >>> local-premium
if (org.aurex.core.AurexHooks.isLocalPremiumUser(currentAccount, currentUser)) {
    return true;
}
// AUREX <<<
```

Нужна потому, что часть экранов спрашивает не про аккаунт, а про конкретного
пользователя. Фасад возвращает `true` только для собственного профиля:
подменять статус собеседников нельзя.

**Расхождение с AyuGram.** Там было:

```java
return !premiumLocked && (currentUser.premium || currentUser.id == getUserConfig().getClientUserId() && AyuConfig.localPremium);
```

В апстриме 12.9.2 сигнатура тела другая:
`return currentUser != null && currentUser.premium && !isSupportUser(currentUser);`
— ушло `!premiumLocked`, появилась проверка на служебный аккаунт. Поэтому
врезка добавляет ветку сверху, а не переписывает выражение: поведение
апстрима сохраняется полностью.

### 2.3 Побочные эффекты переключения — без врезки

Третья часть оригинала (`toggleLocalPremium`) живёт целиком в коде мода,
в `LocalPremium.setEnabled(boolean)`. Набор действий при переключении:

1. `MessagesController.updatePremium(premium)` — блокировка / разблокировка папок.
2. `NotificationCenter.currentUserPremiumStatusChanged` — для аккаунта.
3. `NotificationCenter.premiumStatusChangedGlobal` — глобально.
4. `MediaDataController.loadPremiumPromo(false)` — промо-страница зависит от статуса.
5. `MediaDataController.loadReactions(false, 0)` — список реакций шире у премиума.
6. `getStoriesController().invalidateStoryLimit()` — лимит историй.

Пункты 1–5 — дословно из AyuGram. Пункт 6 добавлен потому, что в нашей версии
апстрима это штатная часть реакции на смену премиум-статуса — см. эталонный
`UserConfig.checkPremiumSelf(...)`.

**Важное расхождение по сигнатуре.** В AyuGram вызывался `loadReactions(false, true)`,
где второй параметр — `boolean force`. В 12.9.2 сигнатура
`loadReactions(boolean cache, Integer lastHash)`, внутри:
`req.hash = lastHash != null ? lastHash : reactionsUpdateHash;`. Точный эквивалент
`force = true` — это `loadReactions(false, 0)`: нулевой хеш заставляет сервер
прислать полный список, а не `availableReactionsNotModified`. Передавать `null`
было бы ошибкой — это версия без принудительного обновления.

**Второе расхождение — мультиаккаунт.** AyuGram применяет побочные эффекты
только к текущему аккаунту, хотя флаг общий для всего клиента. Из-за этого у
остальных аккаунтов `isPremium()` уже возвращает `true`, но папки остаются
заблокированными до перезапуска. Мы проходим по всем активированным
аккаунтам. Наблюдаемое поведение для одного аккаунта идентичное.

### 2.4 Что функция сознательно не делает

Сервер продолжает считать аккаунт обычным. Поэтому всё, что проверяется на его
стороне, работать не будет: отправка премиум-стикеров и премиум-эмодзи,
эмодзи-статус, расшифровка голосовых, увеличенные лимиты загрузки, бейдж
премиума для собеседников. Так же ведёт себя оригинал в AyuGram — там функция
даже помечена бета-суффиксом в названии.

Суффикс «β» мы не переносим: в официальном клиенте таких пометок в названиях
настроек нет, а границы функции объяснены текстом под переключателем
(`AurexLocalPremiumInfo`).

---

## 3. Локальные подарки

Собственная функция Aurex, в AyuGram аналога нет. Смысл: полная имитация
отправки подарка за звёзды без единого списания реальных Stars.

Логика разнесена по слоям:

| Файл мода | Ответственность |
|---|---|
| `features/paid/LocalGifts.java` | кошелёк и решения: вкл/выкл, баланс, отправка, запреты, возврат в чат |
| `features/paid/LocalGiftsStore.java` | хранилище: потраченное, список подарков, их id — отдельно на каждый аккаунт |
| `features/paid/LocalGiftsFeed.java` | сборка `TL_messageService` с `TL_messageActionStarGift` и вставка в ленту |
| `ui/AurexChatFeed.java` | сведение всего, что мод добавляет в ленту чата |
| `ui/AurexPaidSettingsActivity.java` | переключатель и ползунок количества звёзд |

### 3.1 Главное решение: подменяется баланс, а не экраны

Первая версия функции перехватывала только отправку и сознательно не трогала
`getBalance()`. На устройстве это дало три дефекта сразу: в шапке подарков
висело `0 ⭐`, NFT нельзя было выбрать, а кнопка отправки вела в окно докупки
звёзд. Причина одна: весь интерфейс подарков спрашивает один и тот же метод
апстрима — `StarsController.getBalance(...)`.

Поэтому архитектура изменена: мод не переделывает экраны и не добавляет
проверок в интерфейс, а подменяет единственный источник правды о балансе.
Дальше штатный код всừ делает сам: считает хватает ли звёзд, рисует остаток,
анимирует его изменение, разрешает NFT.

Плата за это решение — расширенный сетевой бэкстоп (3.7): раз весь клиент
считает, что звёзды есть, ни один запрос со списанием не должен уйти наружу.

### 3.2 Перехват до payment flow (обычные подарки)

**Файл:** `ui/Stars/StarsController.java`
**Метод:** `buyStarGift(TL_stars.StarGift gift, boolean anonymous, boolean upgraded, long dialogId, TLRPC.TL_textWithEntities text, Utilities.Callback2<Boolean, String> whenDone)`
— первая строка тела, до `final Context context = ...`.

```java
// AUREX >>> локальные подарки: перехват до payment flow
if (org.aurex.core.AurexHooks.sendLocalGift(currentAccount, gift, anonymous, upgraded, dialogId, text, whenDone)) {
    return;
}
// AUREX <<<
```

Все штатные экраны отправки обычного подарка (`GiftSheet` → `SendGiftSheet`,
профиль, чат, подарки себе) сходятся в этот один метод. Врезка стоит ДО
`TL_payments_getPaymentForm`, поэтому инвойс не создаётся и `sendStarsForm` не
вызывается. Когда функция выключена, фасад возвращает `false` и подарок уходит
по штатному пути без изменений.

Стоимость берётся из того же объекта `gift`, что и у апстрима
(`stars + upgrade_stars`, если выбрано улучшение).

### 3.3 Подмена баланса

**Файл:** `ui/Stars/StarsController.java`
**Метод:** `getBalance(boolean withMinus, Runnable loaded, boolean force)` — первая
строка тела, до `if ((!balanceLoaded || ...`.

```java
// AUREX >>> локальные подарки: баланс из локального кошелька
if (org.aurex.core.AurexHooks.hasLocalStars(currentAccount, ton)) {
    return org.aurex.core.AurexHooks.localStarsBalance(currentAccount);
}
// AUREX <<<
```

Ключевая врезка функции. Именно она даёт сразу: верный остаток в шапке
подарков, работающий выбор NFT и отсутствие окна докупки там, где локальных
звёзд хватает.

Проверка `ton` обязательна: апстрим держит два экземпляра контроллера —
звёздный и TON. Подменять TON-баланс функция не имеет права.

### 3.4 Готовность баланса (работа офлайн)

**Файл:** `ui/Stars/StarsController.java`
**Метод:** `balanceAvailable()` — первая строка тела, до `return balanceLoaded;`.

```java
// AUREX >>> локальные подарки: локальный кошелёк готов всегда
if (org.aurex.core.AurexHooks.hasLocalStars(currentAccount, ton)) {
    return true;
}
// AUREX <<<
```

Без неё интерфейс ждёт ответа сервера и показывает заглушку вместо остатка:
без сети `balanceLoaded` никогда не становится `true`. Локальный кошелёк от сети
не зависит вообще, поэтому функция полностью работоспособна в авиарежиме.

### 3.5 Локальная покупка NFT

**Файл:** `ui/Stars/StarsController.java`
**Метод:** `buyResellingGift(TLRPC.TL_payments_paymentFormStarGift form, TL_stars.StarGift gift, long dialogId, Utilities.Callback2<Boolean, String> whenDone)`
— первая строка тела, до `final Context context = ...`.

```java
// AUREX >>> локальные подарки: NFT с витрины перепродажи
if (org.aurex.core.AurexHooks.sendLocalResaleGift(currentAccount, form, gift, dialogId, whenDone)) {
    return;
}
// AUREX <<<
```

Уникальные подарки покупаются другим методом и через `buyStarGift` не проходят
вообще — без этой врезки выбранный NFT ушёл бы в настоящую оплату.

Цена берётся из уже полученной формы (`form.invoice.prices`) — ровно та сумма,
которая была на кнопке. Сама форма не оплачивается: запрос `sendStarsForm`
никогда не формируется.

### 3.6 Запрет покупки настоящих звёзд

**Файл:** `ui/Stars/StarsController.java`
**Метод:** `buy(Activity activity, TL_stars.TL_starsTopupOption option, Utilities.Callback2<Boolean, String> whenDone, ...)`
— первая строка тела.

```java
// AUREX >>> локальные подарки: никаких настоящих покупок
if (org.aurex.core.AurexHooks.blockStarsPurchase(ton, whenDone)) {
    return;
}
// AUREX <<<
```

Закрывает все входы сразу: шторку «не хватает звёзд», магазин звёзд и ссылки
на пополнение. Вместо оплаты показывается штатный бабл
`AurexLocalGiftsNotEnough` с подсказкой сдвинуть ползунок.

### 3.7 Сетевой бэкстоп — без врезки

Второй рубеж — тот же `AurexRequestFilter` (подключён врезкой режима призрака
в `ConnectionsManager`). После подмены баланса его роль стала обязательной, а не
страховочной: пока функция включена, глушатся

- `TL_payments_sendStarsForm` — любая оплата звёздами, не только подарки;
- любой `TL_payments_*StarGift*` — улучшение, передача, перепродажа;
- `TL_messages_sendPaidReaction` — платная реакция;
- любой `TL_messages_send*` с ненулевым `allow_paid_stars` — платные сообщения.

Правило формулируется одной фразой: локальный режим включён ⇒ ни одна
настоящая звезда не может уйти с устройства.

Опознание идёт по имени класса запроса, а не по ссылке на него: так фильтр
переживёт обновление схемы, в которой классы переезжают между файлами.

### 3.8 Отображение в чате и возврат — без новых врезок

Локальный подарок показывается теми же врезками в `ChatActivity`, которые уже
были сделаны для режима шпиона (см. `docs/PATCHES-SPY-CHAT.md`).

Подарок — это штатное сервисное сообщение `TL_messageActionStarGift`, поэтому
его рисует штатный `ChatActionCell` с родной анимацией и ценой. Копий экранов
и своих ячеек мод не делает.

Возврат в чат после отправки тоже штатный: `LaunchActivity.getSafeLastFragment()`
плюс `presentFragment(new ChatActivity(args))` с обычными `user_id` / `chat_id`.
Если нужный чат уже открыт, мод не делает ничего — подарок появляется в ленте
сам. Задержка перед переходом (200 мс) нужна, чтобы штатный лист подарка
успел закрыться своей анимацией.

Идентификаторы локальных сообщений берутся из собственного диапазона
(`LOCAL_MESSAGE_ID_BASE = 1500000000`), чтобы никогда не столкнуться с реальными
`message.id` и не попасть в запросы к серверу.

### 3.9 Кошелёк и мультиаккаунт

Локальный баланс — вычисляемая величина: `выбранное ползунком − потраченное`.
Ползунок задаёт абсолютное значение: любое его изменение обнуляет потраченное,
поэтому баланс мгновенно становится равен выбранному числу, без арифметики
разностей и без перезапуска функции.

Об изменении мод сообщает штатным `NotificationCenter.starBalanceUpdated` — тем же
уведомлением, которым апстрим сообщает о приходе звёзд. Поэтому открытый
интерфейс подарков пересчитывает остаток сам, штатной анимацией.

Всё состояние — потраченные звёзды, список подарков, счётчик id — хранится с
разделением по `accountId`, а сам подарок дополнительно помечен `ownerId` —
`getClientUserId()` аккаунта-владельца. Если аккаунт выйдет и в тот же слот
войдёт другой пользователь, чужие подарки ему не покажутся.

Индекс `paid_local_gifts_owners` помнит все аккаунты, где есть локальные данные,
чтобы выключение функции гарантированно чистило и те аккаунты, которые в
данный момент не активны.

### 3.10 Что функция сознательно не делает

- Получатель ничего не получает — это сама суть функции.
- Локальный подарок не попадает в профиль (вкладка «Подарки») и в общее число
  подарков: там список целиком приходит с сервера постранично, и врезка туда
  потребовала бы подмены пагинации — несоразмерно рискованно при обновлениях.
- Улучшение до уникального, передача, продажа и конвертация в звёзды для
  локального подарка недоступны: любое такое действие — это запрос к серверу по
  реальному `msg_id`, которого не существует. Вместо ошибки показывается
  `AurexLocalGiftsLocalOnly`.
- Локальные подарки не участвуют в поиске по сообщениям и не считаются
  непрочитанными: они существуют только в собранной ленте открытого чата.
- Пока режим включён, недоступны все настоящие траты звёзд: покупка звёзд,
  платные реакции, платные сообщения, операции с настоящими подарками. Это
  осознанная цена подмены баланса, а не ограничение реализации.
