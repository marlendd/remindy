import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    // Kotlin встроен в AGP 9 – отдельный Kotlin-плагин подключать нельзя.
    // Compose-compiler – отдельный subplugin, версия совпадает со встроенным Kotlin.
    alias(libs.plugins.compose.compiler)
}

// Схема БД экспортируется в app/schemas/ (коммитится) для тестируемых миграций
room {
    schemaDirectory("$projectDir/schemas")
}

// Секреты подписи релиза – из gitignored keystore.properties (шаблон: keystore.properties.example).
// Если файла нет (CI/чужая машина без ключа) – release собирается неподписанным, а не падает.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.marlendd.remindy"
    // Фаза 4: Compose-стек 2026 (lifecycle-runtime-compose 2.11.0) требует compileSdk 37.
    // targetSdk оставляем 36 – новые runtime-поведения API 37 не включаем.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.marlendd.remindy"
        minSdk = 26
        targetSdk = 36
        // versionCode ПОДНИМАТЬ при каждой раздаче APK: установка поверх без него не пройдёт
        versionCode = 8
        versionName = "1.3.0" // «Стереть все данные» в настройках (подтверждение отпечатком/кодом)
        // Релиз идёт APK-ом вручную на arm64-телефон; режет нативные либы в APK под одну ABI
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 для v1 выключен (решение): нативная рефлексия Vosk/JNA/SQLCipher/Snowball
            // рискованна, а APK и так < лимита. proguard-rules.pro готов на будущее.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Подписываем релизным ключом только если секреты на месте; иначе неподписанный APK
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Фаза 4: Compose. composeOptions{} НЕ нужен (мёртв с Kotlin 2.0 – компилятор
    // подключается плагином compose-compiler выше).
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    // @aar обязателен (так подключает официальный vosk-android-demo); @aar отключает
    // транзитивные зависимости, поэтому JNA объявлен явно
    implementation("com.alphacephei:vosk-android:${libs.versions.vosk.get()}@aar")
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

    // Этап 5: SQLCipher (шифрование базы) + BiometricPrompt (замок чтения)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.biometric)

    // Фаза 4: Jetpack Compose (Material 3). Версии ui/material3 – из BOM.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
}

// --- Модель Vosk: скачивание при сборке, в git не попадает -------------------
// Zip кэшируется в <rootDir>/.cache/ (переживает clean). Распакованная модель и
// файл-маркер uuid подключаются как generated assets через Variant API – AGP сам
// назначает каталоги в build/generated/ и протягивает зависимости тасков.
// StorageService на телефоне распаковывает assets/model-ru-small в файловую
// систему, сверяясь с uuid.

val voskModelVersion = "0.22"
val voskModelName = "vosk-model-small-ru-$voskModelVersion"
val voskModelUrl = "https://alphacephei.com/vosk/models/$voskModelName.zip"
// SHA-256 официального zip (пин против подмены на CDN); посчитан с локально
// скачанного архива, из которого собрана device-verified модель
val voskModelSha256 = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"
// Имя каталога модели внутри assets – его же ждёт StorageService.unpack в коде
val voskAssetDirName = "model-ru-small"

abstract class DownloadVoskModelTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        // Кэш уже скачан и цел – не гоняем 45 МБ заново (важно: смена inputs таска
        // инвалидирует up-to-date, но целый файл переживает её этой проверкой)
        if (target.exists() && fileSha256(target) == sha256.get()) return
        val part = File(target.parentFile, "${target.name}.part")
        val connection = URI(url.get()).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 300_000
        }
        connection.getInputStream().use { input ->
            part.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        val actual = fileSha256(part)
        check(actual == sha256.get()) {
            "SHA-256 модели не совпал: ожидали ${sha256.get()}, получили $actual ($part)"
        }
        if (target.exists()) target.delete()
        check(part.renameTo(target)) { "Не удалось переименовать $part в $target" }
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

abstract class UnpackVoskModelTask : DefaultTask() {
    @get:InputFile
    abstract val modelZip: RegularFileProperty

    @get:Input
    abstract val assetDirName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    protected abstract val fs: FileSystemOperations

    @get:Inject
    protected abstract val archives: ArchiveOperations

    @TaskAction
    fun unpack() {
        val dirName = assetDirName.get()
        fs.sync {
            from(archives.zipTree(modelZip)) {
                // верхняя папка zip (vosk-model-small-ru-0.22) → model-ru-small
                eachFile {
                    relativePath = RelativePath(
                        true, dirName, *relativePath.segments.drop(1).toTypedArray()
                    )
                }
                includeEmptyDirs = false
            }
            into(outputDir)
        }
    }
}

abstract class GenVoskModelUuidTask : DefaultTask() {
    @get:Input
    abstract val modelName: Property<String>

    @get:Input
    abstract val assetDirName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        // Детерминированный UUID (в отличие от random в vosk-android-demo):
        // телефон не перераспаковывает модель при каждой переустановке APK
        val uuidFile = outputDir.file("${assetDirName.get()}/uuid").get().asFile
        uuidFile.parentFile.mkdirs()
        uuidFile.writeText(UUID.nameUUIDFromBytes(modelName.get().toByteArray()).toString())
    }
}

val downloadVoskModel = tasks.register<DownloadVoskModelTask>("downloadVoskModel") {
    url = voskModelUrl
    sha256 = voskModelSha256
    outputFile = rootProject.layout.projectDirectory.file(".cache/$voskModelName.zip")
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }

        val unpack = tasks.register<UnpackVoskModelTask>("unpack${variantName}VoskModel") {
            modelZip = downloadVoskModel.flatMap { it.outputFile }
            assetDirName = voskAssetDirName
        }
        variant.sources.assets?.addGeneratedSourceDirectory(unpack, UnpackVoskModelTask::outputDir)

        val genUuid = tasks.register<GenVoskModelUuidTask>("gen${variantName}VoskModelUuid") {
            modelName = voskModelName
            assetDirName = voskAssetDirName
        }
        variant.sources.assets?.addGeneratedSourceDirectory(genUuid, GenVoskModelUuidTask::outputDir)
    }
}
