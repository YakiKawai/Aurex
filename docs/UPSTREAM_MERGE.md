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

## Разовая настройка remote

```bash
git remote add upstream https://github.com/DrKLO/Telegram.git
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

1. Все врезки из `PATCHES.md` на месте.
2. Обновить `AurexVersion.BASE_TELEGRAM` на новую версию Telegram.
3. Обновить `versionName` в формате `<версия Telegram>-aurex.<N>`.
4. Собрать проект и вручную проверить каждую функцию мода.
5. Записать изменения в `CHANGELOG.md`.
6. Открыть pull request в `dev`.
