# Jot R8 rules.
# Room, kotlinx.serialization and Navigation ship consumer rules for their own
# needs (Room entities/DAOs, @Serializable classes incl. type-safe nav routes).

# Keep route/schema serializers reachable by reflection-free lookup (belt and braces
# for navigation's serializer<T>() lookup of route classes).
-keepclassmembers @kotlinx.serialization.Serializable class net.backslasher.jot.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class net.backslasher.jot.**$$serializer { *; }

# Readable crash stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
