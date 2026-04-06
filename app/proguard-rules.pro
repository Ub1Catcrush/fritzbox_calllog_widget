# Add project specific ProGuard rules here.
-keep class com.tvcs.fritzboxcallwidget.widget.** { *; }
-keep class com.tvcs.fritzboxcallwidget.prefs.** { *; }
-keep class com.tvcs.fritzboxcallwidget.model.** { *; }
-keep class com.tvcs.fritzboxcallwidget.api.** { *; }
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Simple XML (if used)
-keep class org.simpleframework.** { *; }
-dontwarn org.simpleframework.**
