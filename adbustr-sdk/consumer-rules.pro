# Public surface used by host apps and by the MAX adapter via reflection-free
# direct calls — keep names stable so R8 in the host app can't rename them away.
-keep public class com.adbustr.sdk.** { public *; }

# The Google Play advertising id is looked up reflectively so the SDK carries no
# play-services dependency. Keep the class if the host app happens to bundle it.
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient { *; }
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient$Info { *; }
-dontwarn com.google.android.gms.ads.identifier.**
