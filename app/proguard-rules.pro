# Kotlin serialization and Ktor ship consumer rules that retain generated
# serializers reached by the app. Keep only the metadata those rules use;
# retaining the whole application package would defeat shrinking.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod

# Optional compile-time annotations referenced by Tink's bytecode are not
# packaged at runtime and are safe for R8 to ignore.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
