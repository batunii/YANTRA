# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class ie.napkin.supertasks.**$$serializer { *; }
-keepclassmembers class ie.napkin.supertasks.** {
    *** Companion;
}
-keepclasseswithmembers class ie.napkin.supertasks.** {
    kotlinx.serialization.KSerializer serializer(...);
}
