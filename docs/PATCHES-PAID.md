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
