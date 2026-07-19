# Snowball-стеммер вендорен как Java-исходники. Русский стеммер не использует
# диспетчеризацию по имени метода, но на будущее (release с minifyEnabled на
# этапе 5) сохраняем весь пакет, чтобы MethodHandle-путь в других языках не сломал
# R8-переименование. Подключить в buildTypes.release через proguardFiles при
# включении минификации.
-keep class org.tartarus.snowball.** { *; }

# SQLCipher (net.zetetic): JNI-слой резолвит классы/методы по имени в рантайме –
# сохраняем весь пакет, чтобы R8 не переименовал (актуально при minifyEnabled в release).
-keep class net.zetetic.database.** { *; }
-keep interface net.zetetic.database.** { *; }

# Vosk + JNA: JNA зовёт нативку через рефлексию по именам классов/полей; @aar без
# транзитивов. Актуально при включении minifyEnabled (сейчас R8 выключен для v1).
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn org.vosk.**
-dontwarn net.zetetic.**

# Room: конструктор по умолчанию генерируемого _Impl.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
