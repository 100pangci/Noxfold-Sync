# R8/ProGuard rules for the release build.
#
# kotlinx.serialization ships its own consumer rules (META-INF/proguard in
# kotlinx-serialization-core), so no serializer keep rules are needed here.
# Compose, AndroidX and OkHttp likewise ship consumer rules.

# ZXing embedded scanning: the CaptureActivity is kept via the merged manifest,
# but the library performs reflective lookups inside its decoder classes.
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }

# AboutLibraries loads its bundled license metadata and model classes at runtime.
-keep class com.mikepenz.aboutlibraries.** { *; }
