# Обновление от официального Telegram

## Модель

- `master` — чистое зеркало апстрима, своих коммитов там нет.
- `dev` — разработка мода, сюда вливается апстрим.

Используется **merge**, а не rebase: rebase переписывает историю уже
опубликованной ветки и заставляет заново решать одни и те же конфликты в каждом
коммите. Merge решает конфликт один раз.

Один раз на машине включается «память конфликтов» — Git будет автоматически
применять уже принятые ранее решения:

```bash
git config --global rerere.enabled true
```

## Откуда берётся зеркало

`master` собран **не** из `DrKLO/Telegram` напрямую: коммиты в нём принадлежат
стороннему мейнтейнеру зеркала, а `TMessagesProj/jni/third_party` подключён
сабмодулями (`libvpx`, `dav1d`, `ffmpeg`) — в репозитории DrKLO эти библиотеки
лежат прямо в дереве.

Практическое следствие: указывать в `upstream` адрес `DrKLO/Telegram` нельзя.
Истории двух репозиториев не связаны — `--ff-only` завершится ошибкой, а обычный
merge принесёт конфликт в каждом файле `third_party`.

`upstream` обязан указывать на то же зеркало, из которого `master` обновлялся
раньше. Посмотреть текущее значение и автора последнего коммита зеркала:

```bash
git remote -v
git log master -1 --format='%an <%ae>  %s'
```

## Разовая настройка remote

```bash
# <URL зеркала> — репозиторий, из которого исторически обновлялся master
git remote add upstream <URL зеркала>
git remote -v
```

## Процедура обновления

```bash
# 1. Забрать свежий апстрим в зеркало
git fetch upstream
git switch master
git merge --ff-only upstream/master
git push origin master

# 2. Влить апстрим в отдельную ветку, а не сразу в dev
git switch dev
git switch -c chore/upstream-<версия>
git merge master

# 3. Подтянуть сабмодули под новую версию
git submodule update --init --recursive
```

## Разрешение конфликтов

Git помечает конфликт так:

```
<<<<<<< HEAD
наш код
=======
код апстрима
>>>>>>> master
```

Правило простое: **берём версию апстрима целиком и заново вставляем в неё нашу
врезку по `docs/PATCHES.md`.** Не пытаться «слить» две версии построчно — так
теряются изменения Telegram.

```bash
git add <файл>
git commit
```

## После мержа — обязательный чеклист

```bash
git grep -n "AUREX >>>" -- TMessagesProj/src/main/java/org/telegram
```

1. Все врезки из `PATCHES.md`, `PATCHES-SPY-CHAT.md` и `PATCHES-PAID.md` на месте.
2. Обновить `AurexVersion.BASE_TELEGRAM` на новую версию Telegram.
3. Обновить `versionName` в формате `<версия Telegram>-aurex.<N>`.
4. Собрать проект и вручную проверить каждую функцию мода.
5. Записать изменения в `CHANGELOG.md`.
6. Влить ветку обновления в `dev` и удалить её.

```bash
git switch dev
git merge --no-ff chore/upstream-<версия>
git push origin dev
git branch -d chore/upstream-<версия>
```

Pull request здесь не используется: разработчик один, ревьюить некому. Отдельная
ветка нужна не ради PR, а чтобы `dev` оставался рабочим, пока разрешаются
конфликты, — её всегда можно выбросить целиком, если мерж пошёл не так.
