# Keep Vosk + JNA
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn org.vosk.**
-keepclassmembers class * {
  @android.webkit.JavascriptInterface <methods>;
}
