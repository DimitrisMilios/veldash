# ---- General: strip everything we can ----
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''

# Remove all Log calls from release DEX.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# Kotlin: drop runtime null-check intrinsics that survived -Xno-*-assertions.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNull(...);
    static void checkNotNullParameter(...);
    static void checkNotNullExpressionValue(...);
    static void checkParameterIsNotNull(...);
    static void checkExpressionValueIsNotNull(...);
    static void checkReturnedValueIsNotNull(...);
    static void checkFieldIsNotNull(...);
    static void throwUninitializedPropertyAccessException(...);
}

# ---- MapLibre Native ----
# The C++ core calls back into these Java classes via JNI, so they must keep their names.
-keep class org.maplibre.android.** { *; }
-keep interface org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
