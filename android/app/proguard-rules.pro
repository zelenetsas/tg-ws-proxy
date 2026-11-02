# OkHttp / Conscrypt optional deps
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Service + proxy core are referenced from the manifest / reflection
-keep class com.flowseal.tgwsproxy.service.** { *; }
