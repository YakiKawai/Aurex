# Врезки в код апстрима

Здесь ведётся полный список изменений в файлах официального Telegram. Если
апстрим перепишет затронутый файл целиком, этот документ позволит восстановить
мод за минуты, не разбирая diff вручную.

Главный принцип: врезок должно быть как можно меньше, каждая из них состоит из
одного вызова `AurexHooks` и не содержит логики. Вся логика живёт в `org.aurex`.

Быстрая проверка, что список актуален:

```bash
git grep -n "AUREX >>>" -- TMessagesProj/src/main/java/org/telegram
```

Полный список тронутых файлов апстрима:

```bash
git diff master...dev --name-only -- TMessagesProj/src/main/java/org/telegram
```

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
