# Врезки в код апстрима

Здесь ведётся полный список изменений в файлах официального Telegram. Если
апстрим перепишет затронутый файл целиком, этот документ позволит восстановить
мод за минуты, не разбирая diff вручную.

Главный принцип: врезок должно быть как можно меньше, каждая из них состоит из
одного вызова фасада из `org.aurex` и не содержит логики. Вся логика живёт в `org.aurex`.

Документ разделён на три файла:

- этот — общие врезки (настройки, сеть, профиль);
- `docs/PATCHES-SPY-CHAT.md` — врезки в `ui/ChatActivity.java` и
  `ui/Cells/ChatMessageCell.java`. Эти два файла апстрима самые большие и меняются
  чаще всех, поэтому по ним удобнее вести отдельный список;
- `docs/PATCHES-PAID.md` — врезки раздела «Платные возможности»: блокировка
  реакций за звёзды и локальный Telegram Premium.

Быстрая проверка, что список актуален:

```bash
git grep -n "AUREX >>>" -- TMessagesProj/src/main/java/org/telegram
```

Полный список тронутых файлов апстрима:

```bash
git diff master...dev --name-only -- TMessagesProj/src/main/java/org/telegram
```

Сводка на текущий момент:

| Файл апстрима | Врезок | Зачем | Где описано |
|---|---|---|---|
| `ui/SettingsActivity.java` | 2 | пункт «Aurex» в настройках | раздел 1 |
| `tgnet/ConnectionsManager.java` | 2 | режим призрака + захват апдейтов из сокета | разделы 2 и 4 |
| `messenger/MessagesController.java` | 2 | захват апдейтов; локальный Premium | раздел 4; PATCHES-PAID.md |
| `messenger/UserConfig.java` | 1 | локальный Premium | PATCHES-PAID.md |
| `ui/ProfileActivity.java` | 6 | фон шапки профиля | раздел 3 |
| `ui/ChatActivity.java` | 9 | режим шпиона (8); реакции за звёзды (1) | PATCHES-SPY-CHAT.md; PATCHES-PAID.md |
| `ui/Cells/ChatMessageCell.java` | 3 | иконка удалённого сообщения | PATCHES-SPY-CHAT.md |
| `ui/Stars/StarsController.java` | 1 | реакции за звёзды | PATCHES-PAID.md |
| `ui/Stars/StarsReactionsSheet.java` | 1 | реакции за звёзды | PATCHES-PAID.md |
| `ui/Stars/StarReactionsOverlay.java` | 2 | реакции за звёзды | PATCHES-PAID.md |

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

Сам фильтр общий для всего мода: `AurexRequestFilter` спрашивает по очереди все
модули, которые умеют глушить запросы (сейчас — режим призрака и блокировка
платных реакций). Новые функции с сетевым бэкстопом новых врезок не требуют.

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

Сервер присылает только факт удаления/редактирования и идентификаторы — старого
содержимого в апдейте нет. Его надо вычитать из локального кэша ДО того, как
`MessagesController` обработает апдейт и затрёт данные.

Апдейты доходят до клиента тремя разными путями:

| Канал | Когда работает |
|---|---|
| сокет | приложение запущено, соединение живо |
| ответ на свой запрос | например, собственное редактирование сообщения |
| getDifference / getChannelDifference | всё, что произошло, пока клиент был офлайн или выключен |

Отсюда две точки перехвата, а не одна.

### 4.1 Приём апдейтов из сети

**Файл:** `TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java`

**Метод:** `onUnparsedMessageReceived(...)`, внутри блока
`if (message instanceof TLRPC.Updates) {` — до передачи апдейта в `MessagesController`.

```java
// AUREX >>> spy-mode
org.aurex.core.AurexHooks.onUpdatesReceived(currentAccount, message);
// AUREX <<<
```

Самая ранняя из возможных точек для апдейтов из сокета.

### 4.2 Единая воронка апдейтов

**Файл:** `TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java`

**Метод:** `processUpdateArray(...)` — самая первая строка тела метода, до проверки
`if (updates.isEmpty())`.

```java
// AUREX >>> spy-mode
org.aurex.core.AurexHooks.onUpdateArray(currentAccount, updates);
// AUREX <<<
```

**Почему именно здесь.** Через `processUpdateArray` проходят все три канала из
таблицы выше, включая апдейты, собранные из push-уведомлений
(`PushListenerController` вызывает его напрямую). Врезка стоит до любой обработки,
поэтому предыдущее состояние сообщения ещё на месте.

Без этой врезки мимо модуля проходили два целых класса событий: собственные правки
(ответ на `messages.editMessage` в сокет не попадает) и всё, что произошло за время
офлайна. Именно поэтому удалённые сохранялись только при открытом клиенте, а
история правок не сохранялась почти никогда.

**Повторная обработка безопасна.** Апдейт из сокета виден обеим врезкам, но
дубль гасится на записи: удалённые — проверкой `SpyStorage.deletedExists`, ревизии —
сравнением с последней сохранённой версией. Обе точки пишут через одну и ту же
очередь хранилища, поэтому гонки между ними нет.

### 4.3 Где модуль берёт содержимое

Два штатных источника Telegram, без единой врезки в `MessagesStorage`:

1. `messages_v2` — история чатов. Всё, что клиент успел сохранить, пока работал.
2. `unread_push_messages` — очередь уведомлений. Единственное место, где лежит
   сообщение, доставленное push-уведомлением при выключенном приложении.

Порядок поиска всегда один: сначала история чата, потом — только для ненайденных
идентификаторов — очередь уведомлений. Формат BLOB в обеих таблицах одинаковый
(`TLRPC.Message.serializeToStream`), поэтому чтение общее.

Второй источник закрывает самый неприятный сценарий: приложение выгружено из
памяти, пришло уведомление, собеседник удалил сообщение до того, как клиент
запустился. В `messages_v2` такого сообщения никогда не было, а сервер его больше
не отдаст ни по одному запросу.

Границы этого источника надо знать: в очереди уведомлений лежит то, что пришло в
самом уведомлении — текст сообщения или описание вложения. Самого файла
(фото, голосового, видео) на устройстве не было никогда, и его не восстановит ни
один мод — сервер отдаёт файл только пока сообщение существует. Если в системных
настройках выключен предпросмотр содержимого уведомлений или чат замьючен,
восстанавливать тоже нечего.

**Секретные чаты сознательно не поддерживаются.** Там удаления приходят отдельным
типом апдейта и адресуются не по id сообщения, а по `random_id`. Модуль эти апдейты
не разбирает вовсе, поэтому переписка в секретных чатах никуда не сохраняется.

**Важно для безопасности.** Оба метода-фасада обёрнуты в `try/catch (Throwable)`:
сетевой путь и обработка апдейтов — самые горячие участки клиента, любая ошибка
модуля будет залогирована и проглочена, но не порвёт работу Telegram.
