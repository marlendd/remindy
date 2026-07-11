import java.net.URI
import java.util.UUID
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    // Kotlin встроен в AGP 9 – отдельный Kotlin-плагин подключать нельзя
}

// Схема БД экспортируется в app/schemas/ (коммитится) для тестируемых миграций
room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "com.marlendd.remindy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.marlendd.remindy"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Спайк ставится на один физический arm64-телефон; режет нативные библиотеки в APK
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    // @aar обязателен (так подключает официальный vosk-android-demo); @aar отключает
    // транзитивные зависимости, поэтому JNA объявлен явно
    implementation("com.alphacephei:vosk-android:${libs.versions.vosk.get()}@aar")
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

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
// Имя каталога модели внутри assets – его же ждёт StorageService.unpack в коде
val voskAssetDirName = "model-ru-small"

abstract class DownloadVoskModelTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        val connection = URI(url.get()).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 300_000
        }
        connection.getInputStream().use { input ->
            part.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        check(part.length() > 40_000_000) {
            "Скачанная модель подозрительно мала: ${part.length()} байт ($part)"
        }
        if (target.exists()) target.delete()
        check(part.renameTo(target)) { "Не удалось переименовать $part в $target" }
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
