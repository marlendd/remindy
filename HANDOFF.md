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
Этап 4 (Compose-UI + UX) – **сделан и device-verified** на Samsung Galaxy A52 (Android 14):
все экраны на Compose, гейт (замок вкл/выкл, первичная установка кода, биометрия, неверный
код), запись голосом→подтверждение→сохранение, список, удаление **с подтверждением**, правка
(«Изменить запись»+удаление), поиск (нулевой→полный список), шифрование не регрессировало,
INTERNET нет, крашей нет, тёмная тема/insets/крупные кнопки в порядке.

**Редизайн UX (поверх Этапа 4)** – пользователь забраковал прежний вид как «убого»
(кнопки-цифры на весь экран). Сделан визуальный редизайн: **тёплая спокойная палитра**
(терракота+мёд, light/dark), **круглый компактный пад** замка, **иконки+текст** на кнопках,
**записи-карточки**, убрано «Готово» с главного. Собирается, JVM-тесты и lint зелёные,
прошёл adversarial-ревью (гейт/Vosk/шифрование не тронуты; поправлен 1 minor – фон Unlock).
**Device-verified** на Samsung Galaxy A52 (обе темы, все 6 экранов, гейт+код+биометрия,
свайп→диалог удаления, правка+danger, шифрование не регрессировало, INTERNET нет).
Плюс фикс парсера: «очки лежат на столе» → предмет «очки» (отсекаются хвостовые
глаголы-связки места; +7 тестов).

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
- UI (Фаза 4 – **Jetpack Compose**, мульти-Activity + `setContent{}`): `MainActivity`,
  `record/ConfirmationActivity`, `list/ListActivity`, `search/SearchActivity`,
  `security/UnlockActivity`, `security/SettingsActivity` (новый). Общее: `ui/theme/Theme.kt`
  (`RemindyTheme`), `ui/UiComponents.kt` (`RecordRow`). `ItemAdapter` и все XML-лейауты удалены.
- `security/LockSettings` (Фаза 4) – SharedPreferences: `lockEnabled`/`biometricEnabled`
  (дефолты true). Персистентный (в отличие от сессионного `ReadGate`).

## Фаза 4 (Compose-UI + UX) – что сделано

Порт всех 5 экранов с XML Views/AppCompat на **Jetpack Compose (Material 3)**, мульти-Activity
(каждый экран остаётся `AppCompatActivity` + `setContent{}` – обвязка этапов 1–5 не тронута:
гейт/`ActivityResultLauncher`, разрешение микрофона, жизненный цикл Vosk, биометрия,
`protectFromRecents`). Логика `data/ parse/ search/ voice/` и крипто в `security/` не менялись.

**Тулчейн (Compose под AGP 9):** `compose-compiler`-плагин `org.jetbrains.kotlin.plugin.compose`
версии `2.3.10` (= встроенный в AGP 9.2.1 Kotlin; совпадает с KSP). Compose BOM `2026.06.01`
(material3 1.4.0, ui 1.11.4), `activity-compose:1.13.0`, `lifecycle-runtime-compose:2.11.0`.
`buildFeatures { compose = true }`; `composeOptions{}` НЕ используется (мёртв с Kotlin 2.0).
**Потребовался `compileSdk = 37`** (lifecycle-runtime-compose 2.11.0 требует API 37) → доустановлена
`platforms;android-37`. `targetSdk` оставлен 36 (новые runtime-поведения не включаем). Оконная тема
→ `Theme.AppCompat.DayNight.NoActionBar` (заголовки рисуем в Compose). APK 71 МБ (было 62), < 80.

**UX-улучшения (сверх порта 1:1):**
- **Свайп-удаление с подтверждением** (`list/ListActivity`): высокий порог свайпа
  (`positionalThreshold` 0.75 – нужен осознанный жест) → диалог «Удалить запись?»
  (тот же `confirm_delete_*`, что на экране правки) → `repo.delete`. Отмена возвращает строку
  (`dismissState.reset()`). **Изначально был undo-snackbar, но на device-тесте пользователь
  нашёл свайп слишком чувствительным и попросил подтверждение** – для пожилого диалог надёжнее
  реакции за 4 c. Прежняя undo-механика (`pendingItems`/`appScope`/snackbar) и связанные с ней
  находки ревью убраны как неактуальные.
- **Экран настроек** `security/SettingsActivity` (ТЗ F4): вкл/выкл замок, «Сменить код», вкл/выкл
  биометрию. Сам под гейтом (менять безопасность – только после входа). **Замок ВКЛ по умолчанию**
  (`LockSettings`, дефолт true). `UnlockActivity.EXTRA_FORCE_SETUP` – форс установки/смены кода.
- **Флоу микрофона** (`MainActivity`): при отказе – rationale-диалог или «Открыть настройки»
  (различаем по `shouldShowRequestPermissionRationale`). В поиске голос спрашивает микрофон по тапу
  и подсказывает путь в настройки при «навсегда отклонён» (текст ищет всегда).
- **Индикаторы загрузки**: модель Vosk (`MainActivity`) и открытие зашифрованной БД
  (`ConfirmationActivity`).
- Полировка: заголовок «Изменить запись» при правке + кнопка «Удалить» с подтверждением;
  цвет ошибки входа из темы (был хардкод `#C0392B`); insets через `safeDrawing`/Scaffold.

**Проверки:** `assembleDebug` + JVM-тесты (парсер/нормализатор/поиск/стеммер/`DatabaseKey`) + lint
зелёные. **Adversarial-ревью (11 агентов, 4 измерения)**: 0 находок по гейту/безопасности/Vosk/
insets/шифрованию; 6 подтверждённых по механике undo (все «безопасного направления», без потери
данных) – исправлены (переживающий `appScope` + прунинг), кроме сознательно принятого: при быстрых
множественных свайпах snackbar-и undo идут последовательно (потери нет).

**Известное ограничение (принято):** код приложения – это read-gate, НЕ ключ шифрования. Забыл код
и нет биометрии → в настройки не войти, чтобы снять замок; практический вход – биометрия.
Data-preserving «сброс без входа» невозможен без ослабления гейта (ср. пункт 4 ниже).

### Device-verify Фазы 4 (ПРЕДСТОИТ – по «Сборка и запуск» ниже)

- Запись: «Сказать» → фраза → экран подтверждения → «Сохранено»; при первом старте виден индикатор
  загрузки модели. Отказать в микрофоне → rationale; отказать «навсегда» → «Открыть настройки».
- Список: свайп-влево (осознанный, порог 0.75) → диалог «Удалить запись?». Отмена → строка на месте;
  «Удалить» → запись удалена (переоткрыть список). Тап по строке → правка: заголовок «Изменить запись»,
  кнопка «Удалить» с подтверждением.
- Поиск: текст (клавиатурная кнопка «поиск») и голос; нулевой поиск → полный список → тап → «Запомнил».
- Настройки (кнопка на главном): выключить замок → список/поиск открываются без кода; включить →
  снова просит код; «Сменить код» → новый работает, старый нет; биометрия по переключателю.
- Замок ВКЛ по умолчанию: чистая установка → первый вход в список/поиск/настройки просит задать код.
- Регресс шифрования: файл БД не начинается с `SQLite format 3`; INTERNET отсутствует
  (`dumpsys package | grep -i internet` пусто).
- Крупные шрифты/кнопки и высокий контраст на месте; тёмная тема по системе.

### Редизайн UX (device-verified — Samsung A52, обе темы)

Визуальный редизайн поверх Фазы 4 (см. вводную «Где мы сейчас»). Файлы: `ui/theme/Color.kt`
(новый, хексы палитры), `ui/theme/Theme.kt` (light/dark ColorScheme), `ui/UiComponents.kt`
(`RecordRow` теперь Card, `IconLabel`, `RecordCardShape`), `res/drawable/ic_*.xml` (8 vector-иконок,
БЕЗ material-icons-extended), `res/values/colors.xml` + `res/values-night/colors.xml`
(`window_bg` день/ночь), `res/values/themes.xml` (`windowBackground`). Тулчейн не трогали.

Проверено на телефоне (Samsung A52, обе темы – переключением системной):
- Замок: круглый компактный пад (не растянут), точки-индикатор растут с вводом, ✓ терракотой,
  ⌫ приглушённый; пад гаснет при блокировке (неверный код × лимит); биометрия/установка кода/
  смена кода работают как раньше.
- Главный: в покое НЕТ «Готово» – по центру иконка-микрофон + «Скажите, что и где вы положили»;
  при записи – живой текст; иконки на «Сказать/Найти/Список/Настройки» по центру с текстом;
  индикатор загрузки модели виден.
- Список/Поиск: записи-карточки (тень/рамка/иерархия); свайп → красный фон с корзиной в форме
  карточки → диалог удаления.
- Подтверждение: Сохранить с ✓, Удалить – danger-outlined; поля/заголовок в новом стиле.
- Настройки: свитчи в карточке (терракота во вкл.), «Сменить код» – outlined.
- **Смена системной темы прямо на экране кода** – фон и текст перекрашиваются согласованно
  (был minor-баг рассинхрона, исправлен: `background(colorScheme.background)` на корне Unlock).
- Регресс не-визуального: гейт/Vosk/шифрование/INTERNET – как в чек-листе Фазы 4 выше.

## Дальше по ТЗ

1. **Device-verify этапа 5** (см. раздел выше) – единственное незакрытое по этапу 5.
2. **Этап 4 – Compose-UI + UX** – **сделан** (см. раздел «Фаза 4» выше). Осталось – только
   device-verify на телефоне по чек-листу.
3. **Release-buildType** – подпись + `minifyEnabled` + `proguardFiles`; тогда заработают
   proguard-keep для Snowball и `net.zetetic` (сейчас release не настроен).
4. **На будущее (по privacy.md)** – локальный зашифрованный экспорт базы под отдельным
   паролем (страховка от потери ключа Keystore при factory reset/переустановке).

Локальные рабочие планы: `tmp/plans/` (не в git). План этапа 5:
`tmp/plans/этап-5-шифрование-и-гейт.md`.
