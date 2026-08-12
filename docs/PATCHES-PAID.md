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
| `ui/Stars/StarsController.java` | 1 | локальные подарки | 3.1 |

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
отправки подарка за звёзды без единого обращения к реальному балансу Stars.

Логика разнесена по слоям:

| Файл мода | Ответственность |
|---|---|
| `features/paid/LocalGifts.java` | фасад функции: вкл/выкл, баланс, решение «отправляем локально» |
| `features/paid/LocalGiftsStore.java` | хранилище: потраченное, список подарков, их id — отдельно на каждый аккаунт |
| `features/paid/LocalGiftsFeed.java` | сборка `TL_messageService` с `TL_messageActionStarGift` и вставка в ленту |
| `ui/AurexChatFeed.java` | сведение всего, что мод добавляет в ленту чата |
| `ui/AurexPaidSettingsActivity.java` | раскрывающаяся карточка с ползунком |

### 3.1 Перехват до payment flow — единственная врезка

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

Почему именно здесь и почему хватает одного места:

- все штатные экраны отправки подарка за звёзды (`GiftSheet` → `SendGiftSheet`,
  профиль, чат, подарки себе) сходятся в этот один метод;
- врезка стоит ДО `TL_payments_getPaymentForm`, поэтому инвойс не создаётся, форма
  не запрашивается, `sendStarsForm` не вызывается;
- реальный баланс вообще не читается: ветка с `getBalance()`, `balanceAvailable()` и
  `StarsNeededSheet` (окно покупки звёзд) остаётся ниже врезки и недостижима;
- фасад возвращает `false`, когда функция выключена, — тогда подарок уходит
  по штатному пути апстрима без изменений.

Стоимость берётся из того же объекта `gift`, что и у апстрима
(`stars + upgrade_stars`, если выбрано улучшение), чтобы цена в интерфейсе и
списание с локального баланса всегда совпадали.

### 3.2 Сетевой бэкстоп — без врезки

Второй рубеж — тот же `AurexRequestFilter` (подключён врезкой режима призрака
в `ConnectionsManager`). Пока функция включена, он глушит любой
`TL_payments_getPaymentForm` и `TL_payments_sendStarsForm` с инвойсом
`TL_inputInvoiceStarGift`. Это страховка от будущих версий Telegram: если апстрим
добавит второй путь покупки подарка, звёзды всё равно не уйдут.

### 3.3 Отображение в чате — без новых врезок

Локальный подарок показывается теми же шестью врезками в `ChatActivity`, которые
уже были сделаны для режима шпиона (см. `docs/PATCHES-SPY-CHAT.md`). Для этого
`AurexSpyDeleted` превращён в тонкий делегат над `AurexChatFeed`: имена класса и
методов сохранены дословно, поэтому ни одна строка апстрима не менялась.

Подарок — это штатное сервисное сообщение `TL_messageActionStarGift`, поэтому
его рисует штатный `ChatActionCell` с родной анимацией и ценой. Копий экранов
и своих ячеек мод не делает.

Идентификаторы локальных сообщений берутся из собственного диапазона
(`LOCAL_MESSAGE_ID_BASE = 1500000000`), чтобы никогда не столкнуться с реальными
`message.id` и не попасть в запросы к серверу.

### 3.4 Отделение от реальных звёзд

Локальный баланс — вычисляемая величина: `выбранное ползунком − потраченное`.
Собственные ключи в prefs `"aurex"`, своё значение для каждого `accountId`.
Ни одна строка функции не обращается к `StarsController.getBalance()` и не
подменяет его: реальный баланс остаётся тем, что пришло с сервера, и
продолжает показываться во всех штатных местах без изменений.

При недостатке локальных звёзд фасад всё равно возвращает `true` (то есть
апстрим останавливается), но вместо отправки показывает штатный бабл
`AurexLocalGiftsNotEnough`. Именно так выполняется требование «купить реальные
звёзды из этого сценария невозможно»: окно докупки не открывается вообще.

### 3.5 Мультиаккаунт

Всё состояние — потраченные звёзды, список подарков, счётчик id — хранится с
разделением по `accountId`, а сам подарок дополнительно помечен `ownerId` —
`getClientUserId()` аккаунта-владельца. Если аккаунт выйдет и в тот же слот
войдёт другой пользователь, чужие подарки ему не покажутся.

Индекс `paid_local_gifts_owners` помнит все аккаунты, где есть локальные данные,
чтобы выключение функции гарантированно чистило и те аккаунты, которые в
данный момент не активны.

### 3.6 Что функция сознательно не делает

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
