# R8 rules for the release build (#59). The default Android rules (proguard-android-optimize.txt) and the consumer rules that
# Room, Hilt, WorkManager, OkHttp, Compose and Tink ship cover those libraries. What is below is only what R8 cannot see on
# its own: code that is reached by reflection or by name at run time. Each rule says what breaks without it, because R8 failures
# are silent: the build succeeds and the app then misbehaves on a phone. Check a new build with docs/android-release.md.

# ── Gson ──────────────────────────────────────────────────────────────────────────────────────────────────────────
# Gson maps JSON to fields by NAME using reflection and needs generic type information. Without these, R8 renames the
# fields (a, b, c...), requests go out with the wrong keys and responses come back with every field empty or null: a login
# that "succeeds" with no token, vetting records that arrive blank. No crash, no error.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Every request and response body (the network package) ...
-keep class com.example.client.network.** { <fields>; <init>(...); }
# ... and the Room entities that are used directly as response bodies (VettingRecordsPageDto.items is a list of
# FoodspaceBeneficiaryRecord) or are embedded in request bodies.
-keep class com.example.client.data.local.entity.** { <fields>; <init>(...); }

# Gson reads an enum's values through the NAMES of its constants (Field.getName), which R8 would otherwise rename.
-keepclassmembers enum com.example.client.** { <fields>; }

# ── Retrofit (R8 full mode, the default since Android Gradle Plugin 8) ────────────────────────────────────────────
# Retrofit 2.9.0 ships rules for the classic mode only. In full mode R8 strips the generic signatures of suspend functions that
# return Response<T>, and the first call fails with "Class cannot be cast to ParameterizedType". These are the rules added in
# Retrofit 2.10 for exactly that (https://github.com/square/retrofit/blob/master/retrofit/src/main/resources/META-INF/proguard/retrofit2.pro).
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ── Not needed at run time ────────────────────────────────────────────────────────────────────────────────────────
# Annotation-only references inside OkHttp/Okio/Tink that are absent on Android; R8 would otherwise stop the build on them.
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.lang.model.element.Modifier
