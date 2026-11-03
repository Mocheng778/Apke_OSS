# Enum class
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep,allowoptimization class * extends androidx.preference.PreferenceFragmentCompat
-keepclassmembers class org.apkesu.app.databinding.**  {
    public <methods>;
}
