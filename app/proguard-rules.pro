# kotlinx.serialization keeps its generated serializers via the bundled consumer rules.
# Keep @Serializable route/payload classes' companion serializers.
-keepclassmembers @kotlinx.serialization.Serializable class com.subash.atti.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
