# Сборка Aurex

## Требования

- Android Studio (актуальная стабильная версия)
- JDK 21
- Android SDK 35, Build Tools 35.0.0, NDK 27.2.12479018 (SDK Manager)
- Gradle 8.7 / AGP 8.6.1 — подтягиваются автоматически

`compileSdk 35`, `minSdk 21`, `targetSdk 35`.

## Файлы вне репозитория

Три вещи в Git не хранятся и создаются на каждой машине вручную.

### 1. `local.properties`

```properties
sdk.dir=C\:\\Android\\SDK
APP_ID=<api_id с my.telegram.org>
APP_HASH=<api_hash с my.telegram.org>
RELEASE_STORE_FILE=D\:/Aurex-keys/aurex.keystore
RELEASE_STORE_PASSWORD=<пароль хранилища>
RELEASE_KEY_ALIAS=aurex
RELEASE_KEY_PASSWORD=<пароль ключа>
```

Значения `RELEASE_*` в `gradle.properties` — это заглушки апстрима. Реальные
берутся из `local.properties` через `getProps()` в `TMessagesProj_App/build.gradle`
и перекрывают их.

### 2. `google-services.json`

Нужен в **двух** местах, файл в обоих одинаковый:

- `TMessagesProj/google-services.json`
- `TMessagesProj_App/google-services.json`

Берётся в Firebase Console → Project settings → General → приложение
`org.aurex.messenger`. Проект — `aurex-d816f`, sender ID `887227432387`.

В файле должны быть зарегистрированы **все** пакеты, которые собираются:
`org.aurex.messenger` (release), `org.aurex.messenger.beta` (debug),
`org.aurex.messenger.web` (standalone). Если пакета нет — плагин
`com.google.gms.google-services:4.3.15` валит сборку с
`No matching client found for package name ...`.

Проверка: `findstr project_id TMessagesProj\google-services.json` → `aurex-d816f`.

### 3. Keystore

`D:\Aurex-keys\aurex.keystore`, алиас `aurex`. Вне репозитория, в `.gitignore`
стоит `*.keystore` / `*.jks`.

## Что собирать

Модуль **`TMessagesProj_App`**, вариант **`afatRelease`**.

В панели Build Variants:

- `:TMessagesProj_App` → `afatRelease`
- `:TMessagesProj` → `release`, **Active ABI → `arm64-v8a`**

Остальные четыре модуля (`AppHockeyApp`, `AppHuawei`, `AppStandalone`, `AppTests`)
не используются. Android Studio подсветит «Variant selection conflicts found» —
это предупреждение, а не ошибка, собирать не мешает.

APK: `TMessagesProj_App/build/outputs/apk/afat/release/app.apk`
(имя жёстко задано через `outputFileName = "app.apk"`).

### Доступные варианты

| Вариант | applicationId | Название | Минификация |
|---|---|---|---|
| `afatDebug` | `org.aurex.messenger.beta` | Aurex Beta | нет |
| `afatStandalone` | `org.aurex.messenger.web` | Aurex | да |
| `afatRelease` | `org.aurex.messenger` | **Aurex** | да (R8) |
| `bundleAfatRelease` | `org.aurex.messenger` | Aurex | да |
| `bundleAfat_SDK23Release` | `org.aurex.messenger` | Aurex | да |

R8 настроен щадяще: в `TMessagesProj/proguard-rules.pro` в конце стоят
`-dontoptimize` и `-dontobfuscate`, поэтому дополнительные `-keep` для
`org.aurex.**` не нужны.

## Почему приложение называлось «Aurex Beta»

Имя берётся не из кода мода. В `TMessagesProj/src/main/AndroidManifest.xml`
атрибута `android:label` нет вообще — его подставляют манифесты конфигураций:

- `config/debug/AndroidManifest.xml` и `config/debug/AndroidManifest_SDK23.xml`
  → `@string/AppNameBeta` → «Aurex Beta»
- `config/release/AndroidManifest_SDK23.xml` и
  `config/release/AndroidManifest_standalone.xml` → `@string/AppName` → «Aurex»

Плюс debug-тип добавляет `applicationIdSuffix ".beta"`. То есть «Beta» — это
признак того, что собран `afatDebug`. Для нормального клиента собираем
`afatRelease`.

## Почему в настройках пишет «universal arm64-v8a»

Строка формируется в `ApplicationLoader` по остатку `versionCode % 10`:

```java
case 1: case 2: abi = "store bundled " + ...; break;
default: case 9: abi = "universal " + ...; break;
```

abiVersionCode: `bundleAfat` = 1, `bundleAfat_SDK23` = 2, `afat` = 9.
Итоговый код = `versionCode * 10 + abiVersionCode`, то есть `69919` для `afat`.
Остаток 9 → «universal». «store bundled» видно только в сборках из Google Play
(`bundleAfat*`). На работу приложения не влияет, это чисто информационная
строка. Номер в скобках в UI = `versionCode / 10` = 6991.

## Push-уведомления

Самый неочевидный узел проекта. Разбирался отдельно, повторять исследование
не нужно.

### Как это устроено

Push для стороннего клиента Telegram отправляют **серверы Telegram**, а не наш
бэкенд. Чтобы они могли это сделать, ключ доступа к нашему Firebase-проекту
должен быть загружен в настройки нашего `api_id` на my.telegram.org.
Привязка идёт **к `api_id`**, а не к имени пакета и не к Firebase-проекту.

Цепочка:

1. Приложение получает FCM-токен у своего Firebase-проекта (`aurex-d816f`).
2. Вызывает `account.registerDevice(token_type = 2, token)` при каждом старте.
   Этот запрос проходит успешно **всегда** — сервер просто запоминает строку.
3. При новом сообщении push-сервер Telegram смотрит `api_id` авторизации, берёт
   оттуда FCM-credentials и отправляет push через Google.

Если шага 3 нет, ошибок не возникает нигде: токен зарегистрирован, клиент
считает, что всё в порядке, а push просто не приходит. Именно так выглядела
проблема «уведомления приходят только при открытии приложения».

### Настройка (делается один раз)

1. Firebase Console → Project settings → **Service accounts** → Firebase Admin
   SDK → **Generate new private key** → скачивается JSON.
   Legacy Server key не подходит: Google отключил его 20.06.2024, в проекте
   `aurex-d816f` вкладка Cloud Messaging показывает
   «Cloud Messaging API (Legacy) — Disabled», а «Firebase Cloud Messaging API
   (V1) — Enabled».
2. my.telegram.org/apps → наше приложение → раздел **FCM credentials** →
   **Update** → вставить JSON → Save changes.
   После сохранения в поле «FCM service account» отображается
   `firebase-adminsdk-...@aurex-d816f.iam.gserviceaccount.com`.
3. Пересобрать `afatRelease`, поставить заново, войти в аккаунт и один раз
   запустить приложение — токен регистрируется при старте.

JSON сервисного аккаунта — **секрет**, хранится вне репозитория
(`D:\Aurex-keys\`), в Git не попадает.

### Что НЕ является каналом доставки

В настройках Telegram есть «Перезапуск при закрытии» (`pushService`) и
«Фоновое соединение» (`pushConnection`). На Android 8+ они не заменяют push:
`NotificationsService` не вызывает `startForeground()`, это обычный фоновый
сервис эпохи Android 4. Система убивает его вскоре после ухода приложения
из foreground, `START_STICKY` троттлится, а трюк «умираем → шлём broadcast
`org.telegram.start` → воскресаем» блокируется background execution limits.
Единственный рабочий канал при закрытом приложении — FCM.

### Диагностика

```
adb logcat -d | findstr /i "firebase fcm token registerDevice"
adb shell pm list packages | findstr aurex
```

Проверять так: полностью закрыть приложение свайпом, подождать ~30 секунд,
попросить написать с другого аккаунта. Если то же сообщение уже прочитано в
другой активной сессии, Telegram push не пришлёт — это штатное поведение.

## Частые ошибки

**`Duplicate resources` для `AppName`** — строка Telegram переопределена в том же
модуле, где объявлена. Переопределения строк апстрима должны лежать в
`TMessagesProj_App/src/main/res/values*/`, а не в `TMessagesProj`.

**`No matching client found for package name 'org.aurex.messenger'`** — в
`google-services.json` нет клиента под текущий `applicationId`, либо в модуле
лежит файл от чужого Firebase-проекта. См. раздел «Файлы вне репозитория».

**`INSTALL_BASELINE_PROFILE_FAILED: Package not found: <старый пакет>`** —
Android Studio держит закешированную модель проекта от прежнего `APP_PACKAGE`.
Лечится: `adb uninstall <старый пакет>`, затем
`File → Sync Project with Gradle Files`, `Build → Clean Project`, удаление папок
`TMessagesProj_App/build` и `TMessagesProj/build`, повторный Run. Как обходной
путь — установка напрямую:
`adb install -r -t TMessagesProj_App\build\intermediates\apk\afat\release\app.apk`.

**Изменения не видны после pull** — выполнить
`File → Sync Project with Gradle Files`, затем `Build → Rebuild Project`.

**Windows блокирует pack-файл при `git pull`** — сообщение
`Unlink of file '.git/objects/pack/...idx' failed. Should I try again?`
безвредно, отвечать `n`.

## Безобидные предупреждения сборки

- `source/target version 8 is obsolete`
- `Multiple substitutions specified ...` для `Gift2*`, `StarRating*`, `Suggestion*`
- `warn: removing resource ...beta:string/... without required default value`
- `android.defaults.buildfeatures.buildconfig=true is deprecated`
- предупреждения плагинов Google Services и AGConnect о манифесте
