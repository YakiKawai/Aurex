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
| `tgnet/ConnectionsManager.java` | 2 | режим призрака + захват апдейтов из сокета |
| `messenger/MessagesController.java` | 1 | захват апдейтов из всех остальных каналов |
| `ui/ProfileActivity.java` | 6 | фон шапки профиля |
| `ui/ChatActivity.java` | 8 | режим шпиона: история правок и удалённые сообщения |
| `ui/Cells/ChatMessageCell.java` | 3 | иконка удалённого сообщения |

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

**Важно для безопасности.** Оба метода-фасада обёрнуты в `try/catch (Throwable)`:
сетевой путь и обработка апдейтов — самые горячие участки клиента, любая ошибка
модуля будет залогирована и проглочена, но не порвёт работу Telegram.

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

### 5.3 Восстановление удалённых сообщений в ленте

**Метод:** обработчик `messagesDidLoad`, сразу после строки
`ArrayList<MessageObject> messArr = (ArrayList<MessageObject>) args[2];`.

```java
// AUREX >>> spy-mode
if (chatMode == MODE_DEFAULT) {
    org.aurex.ui.AurexSpyDeleted.merge(currentAccount, getDialogId(), getTopicId(), messArr);
}
// AUREX <<<
```

**Почему именно здесь.** Это самая ранняя точка, где порция истории уже
получена, но ещё не разобрана. Всю дальнейшую бухгалтерию — словари сообщений,
группы медиа, разделители дат, верстку, анимации — делает штатный код Telegram,
моду достаточно дополнить список.

Ограничение `chatMode == MODE_DEFAULT` сознательное: в отложенных, избранных,
поиске и режиме предложений восстановленным сообщениям делать нечего.

**Как считается диапазон выборки.** Повторяет AyuGram
(`AyuMessagesController.getMessages` + `AyuUtils.getMinRealId`): граница только
снизу, по минимальному реальному id загруженной порции. Верхнюю границу
ставить нельзя — сообщение, удалённое последним в чате, имеет id больше всех
оставшихся и при ограничении сверху не попадёт в выборку никогда. Это была
основная причина того, что удалённые сообщения появлялись через раз.

Пустая порция трактуется как «искать с начала» (`startId = 1`): если собеседник
очистил историю, в ленте не остаётся ни одного сообщения, но факт удаления от
очистки истории не зависит и метка обязана сохраниться.

**Дедупликация.** AyuGram полагается на то, что порция истории приходит один раз.
Telegram может прислать её повторно — обновление кеша, прыжок к сообщению,
возврат к последнему прочитанному. Поэтому `SpyChatMerger` помнит подмешанные
id на время сессии чата (`openSession` / `closeSession`, см. 5.5), иначе одно и то
же восстановленное сообщение попадало в ленту несколько раз.

**Сравнение тем щадящее.** Тема на записи и тема на экране вычисляются в разных
местах Telegram и для форумов, комментариев и monoforum могут не совпасть.
Сообщение отбрасывается только если тема заведомо известна с обеих сторон и
заведомо разная.

### 5.4 Защита серверных действий над восстановленным сообщением

**Метод:** `processSelectedOption(int option)`, сразу после врезки 5.2.

```java
// AUREX >>> spy-mode
if (org.aurex.ui.AurexSpyDeleted.blockAction(this, selectedObject, option)) {
    return;
}
// AUREX <<<
```

Сообщения, восстановленные из локальной базы, на сервере не существуют. Без этой
врезки попытка ответить, переслать, закрепить или удалить такое сообщение
вернула бы ошибку сервера. Фасад разрешает только полностью локальные
действия — копирование, сохранение медиа, «Поделиться», перевод и свою
же «Историю правок», — а остальное гасит с поясняющим bulletin
(`AurexSpyDeletedActionUnavailable`).

Список разрешённых опций живёт в `AurexSpyDeleted.isLocalOption(...)` и ссылается на
публичные константы `ChatActivity.OPTION_*` — в апстриме для этого ничего
править не нужно.

### 5.5 Подписка чата на события модуля

Две симметричные врезки. В конце `onFragmentCreate()`, перед `return true;`:

```java
// AUREX >>> spy: подписка чата на события модуля
org.aurex.ui.AurexSpyDeleted.onChatOpen(currentAccount, getDialogId(), this);
// AUREX <<<
```

В `onFragmentDestroy()`, перед
`getNotificationCenter().removeObserver(this, NotificationCenter.closeChats);`:

```java
// AUREX >>> spy: снятие подписки
org.aurex.ui.AurexSpyDeleted.onChatClose(currentAccount, getDialogId(), this);
// AUREX <<<
```

Фасад делает две вещи: подписывает фрагмент на `SpyNotifications.MESSAGE_EDITED`
и `MESSAGES_DELETED` и открывает/закрывает сессию учёта подмешанных id
(см. 5.3). Учёт живёт ровно от открытия чата до его закрытия, поэтому не растёт
бесконечно.

### 5.6 Живое обновление ленты и приглушение

**Метод:** `didReceivedNotification(...)`, самое начало тела — до диспетчера
`if (id == NotificationCenter.messagesDidLoad)`.

```java
// AUREX >>> spy: мгновенное появление удалённого сообщения
if (org.aurex.ui.AurexSpyDeleted.isSpyNotification(id)) {
    if (org.aurex.ui.AurexSpyDeleted.isDeletedNotification(id) && account == currentAccount && chatMode == MODE_DEFAULT
            && args.length > 0 && args[0] instanceof Long && (Long) args[0] == getDialogId()) {
        if (org.aurex.ui.AurexSpyDeleted.merge(currentAccount, getDialogId(), getTopicId(), messages) && chatAdapter != null) {
            chatAdapter.notifyDataSetChanged(false);
        }
    }
    return;
}
// AUREX <<<
```

Перехват стоит до диспетчера сознательно: `didReceivedNotification` —
маршрутизатор, который раздаёт уведомление семи обработчикам апстрима. Наши id
(`7968`, `7969`) там не нужны, поэтому мод обрабатывает своё уведомление и
выходит, не заставляя апстрим прогонять его через все ветки.

**Метод:** `ChatActivityAdapter.onBindViewHolder(...)`, сразу после
`messageCell.setMessageObject(...)`.

```java
// AUREX >>> spy: восстановленное сообщение рисуется приглушённым
messageCell.setAlpha(org.aurex.ui.AurexSpyDeleted.alphaFor(message));
// AUREX <<<
```

Прозрачность выставляется в обе стороны (`0.6` или `1`) именно потому, что ячейки
переиспользуются: без явного возврата к `1` приглушённой оставалась бы каждая
ячейка, в которую однажды попало восстановленное сообщение.

---

## 6. Иконка удалённого сообщения

**Файл:** `TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java`

AyuGram помечает восстановленное сообщение символом `🧹` прямо в тексте.
Подход простой, но у него два минуса: текст сообщения перестаёт совпадать с
оригиналом (заметно при копировании и в истории правок), а символ выглядит
инородно. Поэтому у нас метка — штатная векторная иконка
(`res/drawable/msg_aurex_deleted.xml`), а текст не трогается вовсе.

Вся логика в `org.aurex.ui.AurexSpyMark`, в ячейке — три вызова.

### 6.1 Резервирование места, `measureTime(...)`

Сразу после блока `if (currentMessageObject.messageOwner.video_processing_pending)`,
когда `timeString` уже сформирована и до того, как считается её ширина:

```java
// AUREX >>> spy: место под иконку удалённого сообщения
if (org.aurex.ui.AurexSpyMark.isMarked(currentMessageObject)) {
    timeString = org.aurex.ui.AurexSpyMark.reserve(timeString);
}
// AUREX <<<
```

**Почему через строку времени, а не отдельным элементом.** Ширина строки времени
участвует в измерении баббла и в переносе последней строки текста. Если рисовать
иконку «поверх», она наложится на текст: в коротких сообщениях — на последнее
слово, в длинных — на хвост строки. Резервирование через `timeString` — тот же
механизм, которым Telegram отводит место под пометку «изменено», поэтому
вёрстка остаётся штатной во всех типах сообщений, включая медиа и обои.

Заполнитель — два символа U+2007 (figure space, пробел шириной цифры): его ширина
стабильна, не зависит от локали и не съедается при вёрстке. Размер иконки
выводится из фактически зарезервированной ширины, поэтому при системном
увеличении шрифта метка масштабируется вместе со временем.

### 6.2 Отрисовка на медиа, `drawTimeInternal(...)`

Сразу после `SpoilerEffect.layoutDrawMaybe(timeLayout, canvas);` в ветке времени
на медиа, до `canvas.restore()`:

```java
// AUREX >>> spy: иконка удалённого сообщения
org.aurex.ui.AurexSpyMark.draw(canvas, currentMessageObject, timeLayout.getHeight());
// AUREX <<<
```

### 6.3 Отрисовка в ба