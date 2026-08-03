# Сборка Aurex

## Требования

- Android Studio (актуальная стабильная версия)
- JDK 17
- Android SDK + NDK (ставятся через SDK Manager)
- Gradle 8.7 / AGP 8.6.1 (подтягиваются автоматически)

## Секреты

Файл `local.properties` **не хранится в Git** и создаётся вручную:

```properties
sdk.dir=C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk
APP_ID=<api_id с my.telegram.org>
APP_HASH=<api_hash с my.telegram.org>
```

Keystore хранится вне репозитория (`D:\\Aurex-keys\\aurex.keystore`).

## Сборка

```bash
gradlew :TMessagesProj_App:assembleAfatDebug
```

APK: `TMessagesProj_App/build/outputs/apk/afat/debug/`.

## Частые ошибки

**`Duplicate resources` для `AppName`** — строка Telegram переопределена в том же
модуле, где объявлена. Переопределения строк апстрима должны лежать в
`TMessagesProj_App/src/main/res/values*/`, а не в `TMessagesProj`.

**Изменения не видны после pull** — выполнить
`File → Sync Project with Gradle Files`, затем `Build → Rebuild Project`.
