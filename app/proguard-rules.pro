-keepattributes *Annotation*
-keep class com.shelf.reader.data.** { *; }
-keep class androidx.compose.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn org.jetbrains.annotations.**

# libtorrent4j JNI — keep so ProGuard doesn't strip the native bridge
-keep class org.libtorrent4j.swig.libtorrent_jni { *; }
-keep class org.libtorrent4j.** { *; }
