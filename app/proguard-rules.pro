# Preserve line numbers for readable crash reports without retaining unused code.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Strip verbose debug logs only in minified release builds.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Native downloader / FFmpeg wrapper bridges (youtubedl-android & yausername).
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.youtubedl_common.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keep class org.apache.commons.compress.archivers.zip.** { *; }
-keep class org.apache.commons.io.FileUtils { *; }
-keep class org.apache.commons.io.IOUtils { *; }
-dontwarn com.yausername.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.apache.commons.io.**

# Jackson ObjectMapper uses tree/node APIs cleanly; silence optional JDK/desktop refs.
-dontwarn java.beans.**
-dontwarn javax.xml.**
-dontwarn org.w3c.dom.**
-dontwarn com.fasterxml.jackson.databind.ext.**
-keep class com.fasterxml.jackson.databind.ObjectMapper { *; }
-keep class com.fasterxml.jackson.databind.JsonNode { *; }
-keep class com.fasterxml.jackson.databind.node.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <methods>;
}

# ZXing QR generation & MLKit Code Scanner.
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
