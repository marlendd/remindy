# Snowball-стеммер вендорен как Java-исходники. Русский стеммер не использует
# диспетчеризацию по имени метода, но на будущее (release с minifyEnabled на
# этапе 5) сохраняем весь пакет, чтобы MethodHandle-путь в других языках не сломал
# R8-переименование. Подключить в buildTypes.release через proguardFiles при
# включении минификации.
-keep class org.tartarus.snowball.** { *; }
