# Врезки в код апстрима

Здесь ведётся полный список изменений в файлах официального Telegram. Если
апстрим перепишет затронутый файл целиком, этот документ позволит восстановить
мод за минуты, не разбирая diff вручную.

Главный принцип: врезок должно быть как можно меньше, каждая из них состоит из
одного вызова фасада из `org.aurex` и не содержит логики. Вся логика живёт в `org.aurex`.

Быстрая проверка, что список актуален:

```bash
git grep -n "AUREX >>>" -- TMessagesProj/src/main/java/org/telegram
```

Полный список тронутых файлов апстрима:

```bash
git diff master...dev --name-only -- TMessagesProj/src/main/java/org/telegram
```

Сводка на текущий момент:

| Файл апстрима | Врезок | Зачем |
|---|---|---|
| `ui/SettingsActivity.java` | 2 | пункт «Aurex» в настройках |
| `tgnet/ConnectionsManager.java` | 2 | режим призрака + захват апдейтов |
| `ui/ProfileActivity.java` | 6 | фон шапки профиля |
| `ui/ChatActivity.java` | 2 | режим шпиона: история правок в меню сообщения |

---

## 1. Пункт «Aurex» в настройках приложения

**Файл:** `TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java`

### 1.1 Метод `fillItems(...)`

Врезка ставится **непосредственно перед** строкой, добавляющей пункт «Аккаунт»
(`R.string.SettingsAccount`, id = 1):

```java
// AUREX >>> settings-entry
items.add(SettingCell.Factory.of(org.aurex.core.AurexHooks.SETTINGS_ITEM_ID, IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom, R.drawable.settings_power, getString(R.string.AurexSettings), getString(R.string.AurexSettingsInfo)));
// AUREX <<<
```

### 1.2 Метод `onClick(...)`

Врезка ставится внутрь `switch (item.id)`, перед `case 1:`:

```java
// AUREX >>> settings-entry
case org.aurex.core.AurexHooks.SETTINGS_ITEM_ID:
    org.aurex.core.AurexHooks.openSettings(this);
    break;
// AUREX <<<
```

**Примечания.**
- Апстрим использует id 1–23, мод занимает диапазон от 1000 — пересечений не будет.
- `R.drawable.settings_power` — временная иконка, пока не нарисована своя.

---

## 2. Фильтр исходящих запросов (режим призрака)

**Файл:** `TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java`

**Метод:** `private void sendRequestInternal(TLObject object, ...)` — самая первая строка
тела метода, до блока `if (BuildVars.LOGS_ENABLED)`.

```java
// AUREX >>> ghost-mode
if (org.aurex.core.AurexHooks.shouldDropRequest(currentAccount, object)) {
    return;
}
// AUREX <<<
```

**Почему именно здесь.** AyuGram глушит активность в десятках мест внутри
`MessagesController`, `SendMessagesHelper`, `StoriesController`. Каждая такая правка —
конфликт при обновлении Telegram. Вся активность всё равно уходит на сервер через
`ConnectionsManager`, поэтому одна врезка на выходе заменяет десятки врезок и
гарантирует, что ни один пакет не проскользнёт мимо — даже если апстрим добавит
новое место отправки прочтений.

Локальное состояние клиента не трогается: чаты по-прежнему помечаются прочитанными
в интерфейсе, просто сервер об этом не узнаёт.

Список типов запросов, которые фильтруются, см. в
`org/aurex/features/ghost/GhostRequestFilter.java`.

---

## 3. Фон шапки собственного профиля

**Файл:** `TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java`

Все шесть врезок — вызовы фасада `org.aurex.features.profilebg.ProfileBackgrounds`
и рисовалки `ProfileBackgroundDrawer`. Логики в апстриме нет.

### 3.1 Константы id пунктов меню

После `private final static int disable_no_forwards = 47;`:

```java
// AUREX >>> profile background
private final static int aurex_profile_background = 1001;
private final static int aurex_profile_background_remove = 1002;
// AUREX <<<
```

### 3.2 Поле в `TopView`

Рядом с `private Paint paint = new Paint();`:

```java
// AUREX >>> profile background
private final org.aurex.features.profilebg.ProfileBackgroundDrawer aurexProfileBackground =
    new org.aurex.features.profilebg.ProfileBackgroundDrawer(this);
// AUREX <<<
```

### 3.3 Отрисовка в `TopView.onDraw`

Сразу после блока `if (progressToGradient > 0) { ... }`:

```java
// AUREX >>> profile background
if (myProfile) {
    canvas.save();
    canvas.clipRect(0, 0, getMeasuredWidth(), y1);
    aurexProfileBackground.draw(canvas, currentAccount, getMeasuredWidth(), y1, 1f);
    canvas.restore();
}
// AUREX <<<
```

### 3.4 Пункты меню трёх точек

В конце `createActionBarMenu(boolean animated)`, после блока с `logout`:

```java
// AUREX >>> profile background
if (myProfile) {
    org.aurex.features.profilebg.ProfileBackgrounds.addMenuItems(this, otherItem, aurex_profile_background, aurex_profile_background_remove, () -> {
        if (topView != null) {
            topView.invalidate();
        }
    });
}
// AUREX <<<
```

### 3.5 Обработка клика в `onItemClick`

```java
// AUREX >>> profile background
} else if (id == aurex_profile_background || id == aurex_profile_background_remove) {
    org.aurex.features.profilebg.ProfileBackgrounds.onMenuItemClick(ProfileActivity.this, id);
// AUREX <<<
```

### 3.6 Результат системной камеры / системной галереи

В конец `onActivityResultFragment(...)`, рядом с вызовом `imageUpdater.onActivityResult(...)`:

```java
// AUREX >>> profile background
org.aurex.features.profilebg.ProfileBackgrounds.onActivityResult(this, requestCode, resultCode, data);
// AUREX <<<
```

**Почему отдельный `ImageUpdater`, а не штатный из `ProfileActivity`.** Штатный
принадлежит аватару и его delegate — сам `ProfileActivity`. Подмена delegate на время
выбора фона означала бы, что при отмене выбора следующая смена аватара ушла бы
в фон профиля. Свой экземпляр полностью изолирован и при этом даёт ровно тот же
интерфейс выбора фото.

---

## 4. Захват апдейтов (режим шпиона)

**Файл:** `TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java`

**Метод:** `onUnparsedMessageReceived(...)`, внутри блока
`if (message instanceof TLRPC.Updates) {` — до передачи апдейта в `MessagesController`.

```java
// AUREX >>> spy-mode
org.aurex.core.AurexHooks.onUpdatesReceived(currentAccount, message);
// AUREX <<<
```

**Почему именно здесь.** Сервер присылает только факт удаления/редактирования и
идентификаторы — старого содержимого в апдейте нет. Его надо вычитать из
локальной базы `messages_v2` ДО того, как `MessagesController` обработает апдейт и
затрёт данные. Поэтому врезка стоит в самой ранней возможной точке — на приёме
апдейта из сети, а не в обработчиках внутри `MessagesController`.

Побочное преимущество того же выбора: одно место перехвата покрывает сразу все
четыре типа апдейтов (`updateDeleteMessages`, `updateDeleteChannelMessages`,
`updateEditMessage`, `updateEditChannelMessage`) и будет работать для новых типов,
если апстрим их добавит.

**Важно для безопасности.** `onUnparsedMessageReceived` — горячий сетевой путь.
`AurexHooks.onUpdatesReceived` полностью обёрнут в `try/catch (Throwable)`: любая
ошибка модуля будет залогирована и проглочена, но не порвёт обработку
апдейтов Telegram.

---

## 5. Режим шпиона в чате

**Файл:** `TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java`

Самый большой файл апстрима (более 45 000 строк) и самый частый источник
конфликтов при обновлении. Поэтому здесь действует дополнительное правило:
**никакой логики, только вызовы фасадов** `org.aurex.ui.AurexSpyChat` и
`org.aurex.ui.AurexSpyDeleted`, и врезки максимально короткие.

### 5.1 Пункт «История правок» в меню сообщения

**Метод:** `createMenu(...)`, сразу после блока, добавляющего пункт `OPTION_COPY`.

```java
// AUREX >>> spy-mode
if (org.aurex.ui.AurexSpyChat.hasRevisions(currentAccount, selectedObject)) {
    items.add(LocaleController.getString(R.string.AurexSpyHistoryTitle));
    options.add(org.aurex.ui.AurexSpyChat.OPTION_SPY_HISTORY);
    icons.add(R.drawable.msg_edit);
}
// AUREX <<<
```

Пункт появляется только если для сообщения реально сохранены правки:
`hasRevisions` — это `SELECT 1 ... LIMIT 1` по индексу `idx_spy_message_lookup`,
вызов синхронный и на построение меню не влияет.

### 5.2 Обработка нажатия

**Метод:** `processSelectedOption(int option)`, сразу после проверки
`if (selectedObject == null || getParentActivity() == null) { return; }` и **до**
`switch (option)`.

```java
// AUREX >>> spy-mode
if (option == org.aurex.ui.AurexSpyChat.OPTION_SPY_HISTORY) {
    org.aurex.ui.AurexSpyChat.openHistory(this, selectedObject);
    return;
}
// AUREX <<<
```

**Почему перехват до `switch`, а не `case`.** Метка `case` требовала бы попадания
внутрь тела гигантского `switch` и завязки на compile-time-константу. Ранний
`return` перед `switch` полностью развязывает мод со структурой апстримного
метода: апстрим может как угодно переписывать `switch`, врезка останется валидной.

**Занятые id опций.** Апстрим использует значения до `OPTION_VIEW_STATISTICS = 115`,
мод занимает диапазон от 1338 (`AurexSpyChat.OPTION_SPY_HISTORY`) — пересечений нет.

### 5.3 Восстановление удалённых сообщений в ленте (планируется)

Логика уже готова и лежит в `org/aurex/features/spy/SpyChatMerger.java`, фасад —
`org/aurex/ui/AurexSpyDeleted.java`. Врезки в `ChatActivity` будут состоять из:

1. подмешивания сохранённых сообщений в загруженную порцию истории —
   `AurexSpyDeleted.merge(currentAccount, dialogId, topicId, messArr)`;
2. подписки фрагмента на события модуля —
   `AurexSpyDeleted.addObservers(...)` / `removeObservers(...)`;
3. защиты серверных действий над восстановленным сообщением —
   `AurexSpyDeleted.isRestored(messageObject)`.

Восстановленное сообщение помечается меткой `🧹` **в конце** текста: если ставить
метку в начало, сместились бы все `offset` сохранённого форматирования (жирный,
ссылки, кастомные эмодзи).
