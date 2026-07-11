# HANDOFF – «Где лежит» (remindy)

Офлайн Android-приложение для голосовой фиксации, где лежат вещи. Целевой
пользователь – пожилой человек. Полное ТЗ: [docs/spec.md](docs/spec.md).
Модель приватности и решения: [docs/privacy.md](docs/privacy.md).

## Где мы сейчас

Сделан и **проверен на живом телефоне этап 1** по разделу 9 ТЗ – спайк Vosk
(голое Activity: сказал → увидел текст). Это единственное место, где проект мог
умереть технически. Спайк прошёл – проект технически жизнеспособен, можно идти
дальше по этапам 2-5.

**Проверено на устройстве Samsung Galaxy A52 (SM-A525F), Android 14, arm64:**
- Модель Vosk распаковалась и загрузилась на первом запуске, статус «Готово».
- Второй запуск – быстрый (uuid совпал, повторной распаковки нет).
- Распознавание русской речи работает точно: непрерывная связная речь с
  микрофона транскрибируется в текст на экране (проверено на фоновом аудио –
  полные корректные предложения).
- Тумблер запись/стоп, переходы «Слушаю…»/«Готово», без крэшей за много циклов.
- Кнопка не перекрыта навбаром (edge-to-edge insets), portrait-lock держит
  ориентацию, микрофон корректно освобождается.

Автоматически проверено: сборка (дважды, инкрементальность), APK 59 МБ < 80,
только RECORD_AUDIO без INTERNET, модель + uuid в APK.

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

## Дальше по ТЗ (только после подтверждения спайка на телефоне)

Этап 2 – Room + сохранение записей (парсер «предмет/место» по предлогам).
Этап 3 – поиск (нормализация → стеммер → Левенштейн по токенам → синонимы;
самообучение синонимов через выбор из полного списка). Этап 4 – Compose-UI.
Этап 5 – SQLCipher (шифрование всегда) + биометрия/PIN-гейт **только на чтение**
(запись остаётся в одно касание) – детали в [docs/privacy.md](docs/privacy.md).

Локальный рабочий план этапа 1: `tmp/plans/stage1-vosk-spike.md` (не в git).
