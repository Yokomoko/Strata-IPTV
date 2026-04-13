# Default Android proguard rules + project-specific keepers.

# kotlinx.serialization runtime needs the @Serializable companion + the
# generated .Companion / $serializer classes to survive R8.
-keep,includedescriptorclasses class com.strata.tv.**$$serializer { *; }
-keepclassmembers class com.strata.tv.** {
    *** Companion;
}
-keepclasseswithmembers class com.strata.tv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Hilt
-keep class dagger.hilt.android.internal.** { *; }

# Room — keep entity classes so Room's reflection doesn't blow up
-keep class com.strata.tv.data.db.** { *; }

# Media3 ExoPlayer keeps things itself via consumer rules.
