# Not applied in 0.1.0: the release build has isMinifyEnabled = false (docs/RELEASE.md §2).
# Kept for when R8 is turned on.
#
# Release builds say nothing (SECURITY-INVARIANTS.md S18): every android.util.Log
# call is removed, in Foxy's code and in its dependencies'.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

# secp256k1-kmp loads its JNI library by name and calls into it by reflection.
-keep class fr.acinq.secp256k1.** { *; }
