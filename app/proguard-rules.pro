-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes InnerClasses

-keep class com.idp.universalremote.data.local.entity.** { *; }
-keep class com.idp.universalremote.domain.model.** { *; }

# Retrofit / OkHttp
-keepattributes Signature
-keepattributes Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# Gson
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep public class * implements com.google.gson.JsonSerializer
-keep public class * implements com.google.gson.JsonDeserializer

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Glide
-keep public class * implements com.bumptech.glide.module.AppGlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public class * extends com.bumptech.glide.AppGlideModule

# BouncyCastle — used by AndroidTvCertStore for self-signed cert generation.
# JCA looks up providers / algorithms reflectively, so R8 must keep these.
-keep class org.bouncycastle.** { *; }
-keep interface org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# OkHttp + Conscrypt platform detection paths
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Crash reporting friendliness
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
