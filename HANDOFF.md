# HANDOFF – «Где лежит» (remindy)

Офлайн Android-приложение для голосовой фиксации, где лежат вещи. Целевой
пользователь – пожилой человек. Полное ТЗ: [docs/spec.md](docs/spec.md).
Модель приватности и решения: [docs/privacy.md](docs/privacy.md).

## Где мы сейчас

Этапы 1, 2, 3, 5 – **проверены на живом телефоне** (Samsung Galaxy A52, Android 14).
Этап 5 (шифрование + замок чтения) device-verified: гейт требует вход, установка кода
2-в-2 работает, база создаётся и **реально зашифрована** (файл не начинается с
`SQLite format 3`, обычный sqlite не открывает, ключ сгенерирован в Keystore), повторный
запуск снова требует вход, биометрия + запасной код работают, INTERNET отсутствует.
Этап 4 (Compose-UI) – отложен по решению пользователя (рабочий UI на голых View уже есть,
рерайт пользы отцу не даёт).

**Этап 1 – спайк Vosk:** распознавание русской речи on-device работает точно
(Samsung Galaxy A52, Android 14, arm64). Модель в assets, только RECORD_AUDIO без
INTERNET, edge-to-edge и portrait-lock в порядке.

**Этап 2 – Room + сохранение (F1/F3):** голос → разбор фразы на предмет/место →
экран подтверждения → сохранение. Список (новые сверху), свайп-влево удаление,
тап-редактирование. Перезапись → старое место в `location_history`; удаление →
каскад FK; переименование в существующий предмет сливается (не крашит). Место
хранится ВМЕСТЕ с предлогом («в верхнем ящике стола»).

**Этап 3 – поиск (F2):** голосовой или текстовый запрос → нормализация → стоп-слова
→ русский стеммер Snowball (вендорен) → покрытие токенов с добором Левенштейном →
синонимы → топ-5. Проверено на устройстве: матчинг, опечатки (Левенштейн), нулевой
поиск → полный список, самообучение синонимов (выбор из списка пишет алиас, петля
замыкается), тап → правка. Живой голос: «где мой паспорт» → нашёл «паспорт».
Общий Vosk-Model (VoskModelHolder) грузится один раз на запись и поиск.
Скоринг: точное = 1.0 выше нечёткого = 0.6; Левенштейн только на словах ≥5 с общим
первым символом (короткие «мёд/мел» не путаются); порог строго > 0.5.

Автоматически: сборка + инкрементальность, APK 60 МБ < 80, только RECORD_AUDIO без
INTERNET, схема Room экспортирована. JVM-юнит-тесты (парсер, нормализатор,
поиск, стеммер, Левенштейн). Каждый этап прошёл многоагентное adversarial-ревью,
все подтверждённые находки исправлены.

**Этап 5 – шифрование + замок чтения (код готов, device-тест предстоит):**
- Шифрование ВСЕГДА: SQLCipher (`net.zetetic:sqlcipher-android:4.17.0`). Ключ базы –
  случайные 32 байта → raw-key `x'<hex>'` (без KDF). Ключ обёрнут AES-256-GCM ключом из
  Android Keystore (StrongBox при наличии), обёртка в `filesDir/db_passphrase.bin`. Ключ
  без `setUserAuthenticationRequired` → переживает смену PIN/отпечатка. Инициализация базы
  ушла на IO-поток (`RemindyDatabase.getAsync`), т.к. Keystore нельзя на main.
- Замок на ЧТЕНИЕ: `security/UnlockActivity` – биометрия (`BIOMETRIC_STRONG`, без
  device credential) ИЛИ отдельный код приложения (не системный; `security/AppPin`,
  PBKDF2-хеш + лок от перебора). Один раз за запуск (`ReadGate`). Гейт в Search/List;
  запись (Confirmation) не гейтится. Экраны данных вне снимка «недавних»
  (`protectFromRecents`: API 33+ `setRecentsScreenshotEnabled`, ниже – `FLAG_SECURE`).
- APK 62 МБ (SQLCipher +~2 МБ), по-прежнему БЕЗ INTERNET, +USE_BIOMETRIC.
- Ревью этапа 5 (16 агентов): 4 находки исправлены (FLAG_SECURE/recents; краш на
  пустом app_pin.bin; DB-корутины без try/catch; гонка счётчика неудач) + обёртка
  крипто-исключений в PassphraseUnavailableException.

### Проверка этапа 5 на телефоне (ПРОЙДЕНА – воспроизведение)

```bash
ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
$ADB uninstall com.marlendd.remindy          # чистая шифрованная база (решение пользователя)
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell am start -n com.marlendd.remindy/.MainActivity   # не должно падать (грузится libsqlcipher)
# Тап «Список»/«Найти» → экран входа (первый раз – установка кода) → после входа виден список.
# Доказать шифрование (ключ не знаем – он в Keystore; проверяем, что файл НЕ plaintext-SQLite):
$ADB exec-out run-as com.marlendd.remindy cat databases/remindy.db 2>/dev/null | head -c 16 | xxd
#   plaintext Room начинался бы с ASCII "SQLite format 3"; у SQLCipher – случайная соль. Если
#   "SQLite format 3" НЕ видно – база зашифрована. (run-as работает на debuggable-сборке.)
$ADB shell dumpsys package com.marlendd.remindy | grep -i internet   # должно быть пусто
```

`sqlcipher` CLI (4.17.0 community) поставлен через brew – можно и им открыть pull-нутый файл,
но raw-key из Keystore недоступен, поэтому практичнее проверка «не начинается с SQLite format 3».

### Как воспроизвести проверку на телефоне

Кабель – с линиями данных (не «только зарядка»). USB-отладка: Настройки →
Сведения о телефоне → Сведения о ПО → 7 тапов по «Номер сборки» → Параметры
разработчика → «Отладка по USB» → при подключении «Разрешить». Затем команды из
раздела «Сборка и запуск» ниже. `$ADB devices` должен показать устройство (не
`unauthorized`).

## Окружение (macOS, Apple Silicon)

Всё уже установлено на машине разработки:

- **Android SDK** (CLI, без Android Studio): `/opt/homebrew/share/android-commandlinetools`
  – platform-tools 37, platforms;android-36, build-tools;36.0.0. Прописано в
  `local.properties` (gitignored).
- **JDK**: сборка на Temurin 17 (`/usr/libexec/java_home -v 17`). Демон Gradle
  пиннится через `gradle/gradle-daemon-jvm.properties` (`toolchainVersion=17`).
  На машине дефолтный JDK 25 – его надо обходить (см. риски).
- **adb** для команд ниже: `/opt/homebrew/share/android-commandlinetools/platform-tools/adb`
  (в примерах – `$ADB`).

## Сборка и запуск

```bash
cd /Users/vadim/prog/remindy/remindy
ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb

./gradlew :app:assembleDebug          # первая сборка качает модель Vosk ~45 МБ
ls -lh app/build/outputs/apk/debug/app-debug.apk   # ~59 МБ (лимит ТЗ < 80)

$ADB devices                          # телефон должен быть виден
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell am start -n com.marlendd.remindy/.MainActivity
$ADB logcat -s VoskAPI:* AndroidRuntime:E    # опционально при тесте
```

### Ручной тест (критерий этапа 1)

Запуск → разрешить микрофон → «Загрузка модели…» (первый запуск 5-30 с, потом
1-2 с) → «Готово» → «Сказать» → сказать «очки лежат на кухне на полке» →
partial-слова появляются за ~1 с, финальный текст < 2 с после конца речи →
«Стоп». Повторить с другой фразой и перезапустить приложение (второй запуск
модели быстрый – uuid совпал, распаковки нет).

## Что реализовано

- [app/build.gradle.kts](app/build.gradle.kts) – AGP-конфиг + три типизированных
  task-класса скачивания/распаковки модели Vosk, подключены через **Variant API**
  (`addGeneratedSourceDirectory`). Модель качается при сборке в gitignored `.cache/`
  и `build/`, в git не попадает. UUID детерминированный.
- [MainActivity.kt](app/src/main/java/com/marlendd/remindy/MainActivity.kt) –
  спайк: runtime-разрешение RECORD_AUDIO, `StorageService.unpack`, тумблер
  запись/стоп, `RecognitionListener` с partial/final.
- Манифест: только RECORD_AUDIO, без INTERNET; label «Где лежит»;
  `allowBackup=false` + `dataExtractionRules` (данные не уходят с устройства).

## Исправления после многоагентного ревью (уже в коде)

Верифицировано против байткода Vosk 0.3.75:

- **Крэш при занятом микрофоне:** `onError` делает полный демонтаж (stop перед
  shutdown), а не shutdown в одиночку.
- **Гонка + утечка модели при повороте/тёмной теме:** Activity не пересоздаётся
  (`screenOrientation=portrait` + `configChanges`).
- **Утечка Recognizer:** хранится в поле и закрывается каждый цикл.
- **Edge-to-edge:** window insets, чтобы навбар не перекрывал кнопку на Android 15+.
  ПРОВЕРИТЬ на устройстве с Android 15+: не задвоился ли верхний отступ.
- **Ретрай загрузки модели:** при ошибке кнопка активна, тап повторяет загрузку.

## Гочи (не наступить снова)

- **AGP 9 = встроенный Kotlin.** НЕ подключать `id("org.jetbrains.kotlin.android")`
  – это хард-ошибка. `kotlinOptions {}` мёртв.
- **AGP 9 запрещает `sourceSets.assets.srcDirs(provider)`** – только Variant API
  (`variant.sources.assets.addGeneratedSourceDirectory`).
- **`@aar` без явного JNA** → `UnsatisfiedLinkError: jnidispatch` в рантайме.
  JNA объявлен явно как `net.java.dev.jna:jna:5.18.1@aar`.
- **JDK 25 дефолтный** – bootstrap-команды (`gradle wrapper`, `sdkmanager`) гнать
  с `JAVA_HOME=$(/usr/libexec/java_home -v 17)`. Дальше держит daemon-jvm.properties.
- **Модель в assets** ждёт каталог `model-ru-small` с файлом `uuid` – совпадает с
  `StorageService.unpack(this, "model-ru-small", "model", ...)` в коде.
- **Room 2.8.4, НЕ 3.0** – 3.0 выкинул `SupportSQLiteOpenHelper`, под который на
  этапе 5 встаёт SQLCipher. KSP 2.3.10 (версия развязана с Kotlin), room-ktx влит
  в room-runtime, схема через плагин `androidx.room` в `app/schemas` (коммитить).
- **`adb input text` не умеет кириллицу** – для UI-тестов через adb вводить
  латиницей; кириллицу проверять голосом или в юнит-тестах.
- **`repeatOnLifecycle`** нет в транзитивном lifecycle из appcompat – используем
  простой `lifecycleScope.launch { flow.collect {} }`.
- **Snowball-стеммер вендорен** как Java-исходники в `app/src/main/java/org/tartarus/snowball/`
  (BSD-3, COPYING рядом). Русский стеммер не использует method-handle-диспетчеризацию,
  поэтому R8-проблем нет; proguard-keep добавлен на будущее (release не настроен).
- **`adb` не вводит кириллицу** – матчинг-логику поиска тестировать латиницей,
  русский стеммер – голосом или юнит-тестами.

## Карта кода по слоям

- `data/` – Room: сущности/DAO/база, `RecordRepository` (upsert по `name_norm`,
  слияние при переименовании, история, `learnSynonym`, `searchTargets`).
- `parse/` – `UtteranceParser` (предмет/место по предлогу, предлог остаётся в месте),
  `TextNormalizer` (lowercase, ё→е, пунктуация, пробелы).
- `search/` – `SearchEngine` (скоринг), `Levenshtein`, `StopWords`, `RussianStemmer`.
- `voice/` – `VoskModelHolder` (общая модель).
- `security/` (этап 5) – `KeystorePassphraseManager` (обёртка ключа), `DatabaseKey`
  (raw-key `x'hex'`), `AppPin` (PBKDF2-код + лок), `ReadGate` (сессионный флаг),
  `UnlockActivity` (биометрия/код), `WindowSecurity` (`protectFromRecents`).
- UI (голые View): `MainActivity`, `record/ConfirmationActivity`,
  `list/ListActivity`+`ItemAdapter`, `search/SearchActivity`, `security/UnlockActivity`.

## Дальше по ТЗ

1. **Device-verify этапа 5** (см. раздел выше) – единственное незакрытое по этапу 5.
2. **Этап 4 – Compose-UI** – отложен: рабочий UI на голых View уже есть, рерайт ради
   рерайта пользы отцу не даёт. Брать только по явной просьбе.
3. **Release-buildType** – подпись + `minifyEnabled` + `proguardFiles`; тогда заработают
   proguard-keep для Snowball и `net.zetetic` (сейчас release не настроен).
4. **На будущее (по privacy.md)** – локальный зашифрованный экспорт базы под отдельным
   паролем (страховка от потери ключа Keystore при factory reset/переустановке).

Локальные рабочие планы: `tmp/plans/` (не в git). План этапа 5:
`tmp/plans/этап-5-шифрование-и-гейт.md`.
